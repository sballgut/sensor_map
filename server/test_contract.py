"""Replay the November 23 API-01 and API-04–07 examples against a temporary database."""
from pathlib import Path
import tempfile
import unittest

from api import ApiError, Application, Store


class ContractTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        root = Path(directory.name)
        self.store = Store(root / "contract.sqlite", root / "releases")
        self.addCleanup(self.store.close)
        self.api = Application(self.store, "contract-app", "contract-admin")

    def test_observation_submission_and_review(self):
        general = {
            "record_id": "1710cf53-27ac-435a-ba97-c643656412a9",
            "session_id": "8ca59966-66ce-4b36-8512-bd1311072231",
            "device_id": "fd724452-ccea-41ff-8a14-876aeaff1a09",
            "sensor_id": "sensor 07", "advertised_name": "opensrc1",
            "mac": "AA:BB:CC:DD:EE:FF", "rssi": -72, "raw_hex": "6608b40f010c00a40140a1036b",
            "utc_ms": 1795400000000, "elapsed_ns": 5123000000000, "sensor_timestamp": 1795400000,
            "mode": "collection", "parse_status": "parsed",
            "temperature": 21.5, "humidity": 40.2, "aqi": 1, "tvoc": 12, "co2": 420,
            "gps_lat": 36.6290, "gps_lon": 127.4560, "gps_accuracy": 8.0, "gps_age_ms": 1200,
            "heading_deg": None, "heading_accuracy": None, "heading_source": None,
            "fixed_declination_deg": None, "candidate_consent": False, "reference_point_id": None,
        }
        guidance = dict(general,
                        record_id="43000de0-1b2e-440e-93ad-dccb2c33be0a",
                        session_id="06905269-ed6f-4b09-b165-c8ce36e2f24b",
                        mode="guidance", utc_ms=1795486400000, elapsed_ns=912000000000, rssi=-68,
                        gps_lat=None, gps_lon=None, gps_accuracy=None, gps_age_ms=None,
                        candidate_consent=True)
        app_auth = "Bearer contract-app"
        admin_auth = "Bearer contract-admin"
        for record, batch_id, expected in [
            (general, "b8a1abcd-1a69-46c7-8da4-f9fc3c6da5d7", "stored"),
            (general, "8534f457-38d0-48ec-8f10-99c6c3e1b258", "duplicate"),
            (guidance, "c79d6793-46d4-4c7a-9c39-02b38963dc6e", "stored"),
        ]:
            with self.subTest(batch_id=batch_id):
                ack = self.api.route("POST", "/v1/observations/batch", app_auth,
                                     {"batch_id": batch_id, "records": [record]})
                self.assertEqual(ack, {"batch_id": batch_id, "results": [
                    {"record_id": record["record_id"], "status": expected}]})

        submission = {
            "client_submission_id": "a9ec5b29-f96b-4fbc-9446-986368dba36f",
            "session_id": guidance["session_id"], "consent": True,
            "description": "도서관 뒤 샛길: 도서관 뒤에서 학생회관으로 바로 가는 길",
            "evidence_record_ids": [guidance["record_id"]],
            "position_estimates": [
                {"utc_ms": utc_ms, "latitude": latitude, "longitude": longitude,
                 "uncertainty_m": uncertainty, "status": status, "source": "ble",
                 "segment": segment, "model_version": "team-release-001"}
                for utc_ms, latitude, longitude, uncertainty, status, segment in [
                    (1795486401000, 36.62901, 127.45602, 18.0, "valid", 0),
                    (1795486403000, 36.62907, 127.45611, 22.0, "valid", 0),
                    (1795486420000, 36.62931, 127.45660, 20.0, "uncertain", 1),
                ]
            ],
        }
        first = self.api.route("POST", "/v1/candidates", app_auth, submission)
        cid = first["candidate_id"]
        self.assertTrue(cid)
        self.assertEqual(first, {"candidate_id": cid, "status": "submitted"})
        admin_path = "/v1/admin/candidates/" + cid
        for status, reason in [("under_review", "검토 시작"), ("approved", "현장 확인 완료")]:
            response = self.api.route("POST", admin_path, admin_auth, {"status": status, "reason": reason})
            self.assertEqual(response, {"candidate_id": cid, "status": status,
                                        "deployment_action_required": False})

        self.assertEqual(self.api.route("POST", "/v1/candidates", app_auth, submission),
                         {"candidate_id": cid, "status": "approved"})
        refused = dict(submission, client_submission_id="c3af132a-7a11-4098-9d51-f1186000c934",
                       session_id=general["session_id"], evidence_record_ids=[general["record_id"]])
        with self.assertRaises(ApiError) as error:
            self.api.route("POST", "/v1/candidates", app_auth, refused)
        self.assertEqual(error.exception.status, 400)
        self.assertIn("recording-time candidate consent", error.exception.message)

        rows = self.api.route("GET", "/v1/admin/candidates", admin_auth)["candidates"]
        self.assertEqual([(row["candidate_id"], row["status"]) for row in rows], [(cid, "approved")])
        detail = self.api.route("GET", admin_path, admin_auth)
        self.assertEqual(detail["payload"], submission)
        self.assertEqual([review["new_status"] for review in detail["reviews"]],
                         ["submitted", "under_review", "approved"])
        self.assertEqual(self.store.db.execute("SELECT COUNT(*) FROM observations").fetchone()[0], 2)
