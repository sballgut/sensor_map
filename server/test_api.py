import copy
import io
import hashlib
import json
from pathlib import Path
import tempfile
import threading
import unittest
import urllib.request
import urllib.error
from http.server import ThreadingHTTPServer
from api import Store, Application, ApiError, make_handler


def record(rid="r1", **changes):
    value = dict(record_id=rid, session_id="s1", device_id="anon1", sensor_id="sensor 07", rssi=-63,
                 raw_hex="001122", utc_ms=1790000000000, elapsed_ns=5000000000,
                 mode="guidance", parse_status="length_mismatch", gps_lat=None, gps_lon=None)
    value.update(changes)
    return value


class ApiTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.directory = Path(self.temp.name)
        self.store = Store(self.directory / "db.sqlite", self.directory / "releases")
        self.app = Application(self.store, "application-secret", "administrator-secret")

    def tearDown(self):
        self.store.close()
        self.temp.cleanup()

    def batch(self, bid, rows):
        return self.app.route("POST", "/v1/observations/batch", "Bearer application-secret", {"batch_id": bid, "records": rows})

    def test_lost_ack_same_batch_and_new_batch_are_idempotent(self):
        first = self.batch("b1", [record()])
        self.assertEqual(first, self.batch("b1", [record()]))
        self.assertEqual("duplicate", self.batch("b2", [record()])["results"][0]["status"])
        self.assertEqual(1, self.store.db.execute("SELECT COUNT(*) FROM observations").fetchone()[0])

    def test_conflicting_id_does_not_replace_original(self):
        self.batch("b1", [record()])
        result = self.batch("b2", [record(rssi=-80)])
        self.assertEqual("error", result["results"][0]["status"])
        stored = json.loads(self.store.db.execute("SELECT payload FROM observations").fetchone()[0])
        self.assertEqual(-63, stored["rssi"])

    def test_conflicting_batch_rejected(self):
        self.batch("b1", [record()])
        with self.assertRaises(ApiError) as error:
            self.batch("b1", [record("r2")])
        self.assertEqual(409, error.exception.status)

    def test_partial_ack_and_failed_parse_rssi_are_retained(self):
        result = self.batch("b1", [record(), record("r2", elapsed_ns=-1)])
        self.assertEqual(["stored", "error"], [item["status"] for item in result["results"]])
        self.assertEqual("length_mismatch", json.loads(self.store.db.execute("SELECT payload FROM observations").fetchone()[0])["parse_status"])

    def test_guidance_gps_rejected_even_uppercase(self):
        result = self.batch("b1", [record(mode="GUIDANCE", gps_lat=36.6, gps_lon=127.4)])
        self.assertEqual("error", result["results"][0]["status"])

    def test_guidance_gps_metadata_rejected_without_coordinates(self):
        result = self.batch("b1", [record(gps_accuracy=20), record("r2", gps_age_ms=500)])
        self.assertEqual(["error", "error"], [r["status"] for r in result["results"]])

    def test_restart_preserves_observations_and_ack(self):
        first = self.batch("b1", [record()])
        self.store.close()
        self.store = Store(self.directory / "db.sqlite", self.directory / "releases")
        self.app = Application(self.store, "application-secret", "administrator-secret")
        self.assertEqual(first, self.batch("b1", [record()]))

    def candidate(self):
        self.batch("b1", [record(candidate_consent=True)])
        return self.store.candidate(dict(consent=True, session_id="s1", description="Path near library",
                                         evidence_record_ids=["r1"], estimates=[], photo_refs=[]))["candidate_id"]

    def test_candidate_requires_consent_and_same_session_evidence(self):
        with self.assertRaises(ApiError):
            self.store.candidate(dict(consent=False))
        with self.assertRaises(ApiError):
            self.store.candidate(dict(consent=True, session_id="s1", description="x", evidence_record_ids=["unknown"]))

    def test_candidate_rejects_evidence_without_recording_time_consent(self):
        self.batch("b1", [record(candidate_consent=False)])
        with self.assertRaises(ApiError) as error:
            self.store.candidate(dict(consent=True, session_id="s1", description="x", evidence_record_ids=["r1"]))
        self.assertIn("recording-time", error.exception.message)

    def test_candidate_accepts_optional_estimates_and_rejects_nonfinite_values(self):
        self.batch("b1", [record(candidate_consent=True)])
        body = dict(consent=True, session_id="s1", description="x", evidence_record_ids=["r1"],
                    position_estimates=[dict(latitude=36.62, longitude=127.45, uncertainty_m=20)], photo_refs=["photo-1"])
        cid = self.store.candidate(body)["candidate_id"]
        self.assertEqual(body, self.store.detail(cid)["payload"])
        body["position_estimates"][0]["uncertainty_m"] = float("nan")
        with self.assertRaises(ApiError):
            self.store.candidate(body)

    def test_review_requires_transition_and_keeps_history(self):
        cid = self.candidate()
        with self.assertRaises(ApiError):
            self.store.transition(cid, dict(status="approved", reason="too soon"))
        self.store.transition(cid, dict(status="under_review", reason="evidence checked"))
        self.store.transition(cid, dict(status="rejected", reason="building crossing"))
        self.assertEqual(3, len(self.store.detail(cid)["reviews"]))

    def test_admin_token_cannot_be_replaced_with_app_token(self):
        with self.assertRaises(ApiError) as error:
            self.app.route("GET", "/v1/admin/candidates", "Bearer application-secret")
        self.assertEqual(401, error.exception.status)
        self.assertEqual([], self.app.route("GET", "/v1/admin/candidates", "Bearer administrator-secret")["candidates"])
        with self.assertRaises(ValueError):
            Application(self.store, "same", "same")

    def stage(self, value=1):
        source = self.directory / "input"
        source.mkdir(exist_ok=True)
        for name in ("map", "graph", "destinations", "model"):
            (source / (name + ".json")).write_text(json.dumps({"test_fixture": value}))
        return str(source)

    def test_publish_requires_approval_and_assets(self):
        cid = self.candidate()
        source = self.stage()
        with self.assertRaises(ApiError):
            self.store.publish(dict(version="v1", compatibility=1, source_dir=source, candidate_ids=[cid]))
        self.store.transition(cid, dict(status="under_review", reason="review"))
        self.store.transition(cid, dict(status="approved", reason="approved"))
        manifest = self.store.publish(dict(version="v1", compatibility=1, source_dir=source, candidate_ids=[cid]))
        self.assertEqual("published", self.store.detail(cid)["status"])
        self.assertEqual({"map", "graph", "destinations", "model"}, {v["name"] for v in manifest["assets"]})
        for asset in manifest["assets"]:
            data = (self.directory / "releases" / "v1" / (asset["name"] + ".json")).read_bytes()
            self.assertEqual(hashlib.sha256(data).hexdigest(), asset["sha256"])
            self.assertEqual(len(data), asset["size"])
        self.assertEqual(manifest, self.store.current())
        with self.assertRaises(ApiError):
            self.store.publish(dict(version="v1", compatibility=1, source_dir=source))

    def test_failed_publish_preserves_current_release_and_rollback_checks_hash(self):
        source = self.stage()
        self.store.publish(dict(version="v1", compatibility=1, source_dir=source))
        with self.assertRaises(ApiError):
            self.store.publish(dict(version="v2", compatibility=2, source_dir=source))
        self.assertEqual("v1", self.store.current()["version"])
        self.store.publish(dict(version="v2", compatibility=1, source_dir=self.stage(2)))
        self.assertEqual("v1", self.store.rollback(dict(version="v1", reason="test rollback"))["version"])
        (self.directory / "releases" / "v2" / "graph.json").write_text("corrupted")
        with self.assertRaises(ApiError):
            self.store.rollback(dict(version="v2", reason="invalid"))
        self.assertEqual("v1", self.store.current()["version"])

    def test_withdrawal_retains_history_and_requires_deployment_change(self):
        cid = self.candidate()
        self.store.transition(cid, dict(status="under_review", reason="review"))
        self.store.transition(cid, dict(status="approved", reason="approved"))
        self.store.publish(dict(version="v1", compatibility=1, source_dir=self.stage(), candidate_ids=[cid]))
        result = self.store.transition(cid, dict(status="withdrawn", reason="incorrect registration"))
        self.assertTrue(result["deployment_action_required"])
        self.assertEqual("withdrawn", self.store.detail(cid)["status"])

    def test_local_http_handler_end_to_end(self):
        # Bind only to localhost and an ephemeral port, using this test's temporary DB.
        server = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(self.app))
        thread = threading.Thread(target=server.serve_forever, kwargs={"poll_interval": 0.05}, daemon=True)
        thread.start()
        base = "http://127.0.0.1:" + str(server.server_address[1])
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

        def call(path, body=None, token=None):
            headers = {"Content-Type": "application/json"}
            if token:
                headers["Authorization"] = "Bearer " + token
            request = urllib.request.Request(base + path, data=None if body is None else json.dumps(body).encode(), headers=headers)
            try:
                with opener.open(request, timeout=3) as response:
                    return response.status, response.headers.get_content_type(), response.read()
            except urllib.error.HTTPError as error:
                return error.code, error.headers.get_content_type(), error.read()

        try:
            try:
                status, content_type, payload = call("/health")
            except urllib.error.URLError as error:
                if isinstance(error.reason, PermissionError):
                    self.skipTest("Localhost socket access is denied by this environment: " + str(error.reason))
                raise
            self.assertEqual((200, "application/json", {"status": "ok"}), (status, content_type, json.loads(payload)))
            status, content_type, payload = call("/admin")
            self.assertEqual((200, "text/html"), (status, content_type))
            self.assertIn("후보 검토와 버전 배포", payload.decode("utf-8"))
            self.assertNotIn("administrator-secret", payload.decode("utf-8"))
            self.assertEqual(401, call("/v1/admin/candidates", token="application-secret")[0])
            self.assertEqual(200, call("/v1/admin/candidates", token="administrator-secret")[0])
            batch = {"batch_id": "http-b1", "records": [record(candidate_consent=True)]}
            self.assertEqual(401, call("/v1/observations/batch", batch)[0])
            first = call("/v1/observations/batch", batch, "application-secret")
            self.assertEqual(200, first[0])
            self.assertEqual("stored", json.loads(first[2])["results"][0]["status"])
            self.assertEqual(first[2], call("/v1/observations/batch", batch, "application-secret")[2])
            batch["batch_id"] = "http-b2"
            self.assertEqual("duplicate", json.loads(call("/v1/observations/batch", batch, "application-secret")[2])["results"][0]["status"])
            candidate = dict(consent=True, session_id="s1", description="HTTP evidence test", evidence_record_ids=["r1"])
            status, _, payload = call("/v1/candidates", candidate, "application-secret")
            self.assertEqual(200, status)
            cid = json.loads(payload)["candidate_id"]
            status, _, payload = call("/v1/admin/candidates/" + cid, token="administrator-secret")
            self.assertEqual(200, status)
            self.assertEqual("submitted", json.loads(payload)["status"])
            self.assertEqual(1, self.store.db.execute("SELECT COUNT(*) FROM observations").fetchone()[0])
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=3)

    def test_http_handler_parses_real_http_frames_without_network(self):
        # Exercise BaseHTTPRequestHandler serialization/parsing using byte streams.
        # This test complements, rather than substitutes for, the loopback test.
        class MemoryConnection:
            def __init__(self, data):
                self.input = io.BytesIO(data)
                self.output = bytearray()
            def makefile(self, *_):
                return self.input
            def sendall(self, data):
                self.output.extend(data)

        def request(method, path, body=None, token=None):
            payload = b"" if body is None else json.dumps(body).encode("utf-8")
            headers = [f"{method} {path} HTTP/1.0", "Host: localhost", "Content-Type: application/json",
                       f"Content-Length: {len(payload)}"]
            if token:
                headers.append("Authorization: Bearer " + token)
            connection = MemoryConnection(("\r\n".join(headers) + "\r\n\r\n").encode() + payload)
            make_handler(self.app)(connection, ("127.0.0.1", 12345), None)
            header, body = bytes(connection.output).split(b"\r\n\r\n", 1)
            return int(header.split(b" ")[1]), header, body

        self.assertEqual(200, request("GET", "/health")[0])
        status, headers, body = request("GET", "/admin")
        self.assertEqual(200, status)
        self.assertIn(b"text/html", headers)
        self.assertIn("후보 검토와 버전 배포", body.decode())
        batch = dict(batch_id="memory-http-b1", records=[record(candidate_consent=True)])
        self.assertEqual(401, request("POST", "/v1/observations/batch", batch)[0])
        status, _, body = request("POST", "/v1/observations/batch", batch, "application-secret")
        self.assertEqual(200, status)
        self.assertEqual("stored", json.loads(body)["results"][0]["status"])
        self.assertEqual(body, request("POST", "/v1/observations/batch", batch, "application-secret")[2])
        batch["batch_id"] = "memory-http-b2"
        self.assertEqual("duplicate", json.loads(request("POST", "/v1/observations/batch", batch, "application-secret")[2])["results"][0]["status"])
        self.assertEqual(401, request("GET", "/v1/admin/candidates", token="application-secret")[0])
        candidate = dict(consent=True, session_id="s1", description="HTTP parser test", evidence_record_ids=["r1"])
        status, _, body = request("POST", "/v1/candidates", candidate, "application-secret")
        self.assertEqual(200, status)
        cid = json.loads(body)["candidate_id"]
        self.assertEqual(200, request("GET", "/v1/admin/candidates/" + cid, token="administrator-secret")[0])


if __name__ == "__main__":
    unittest.main()
