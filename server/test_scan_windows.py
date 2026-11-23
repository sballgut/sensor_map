import json
from pathlib import Path
import tempfile
import unittest

from api import ApiError, Application, Store


def window(window_id="w1", **changes):
    sample = dict(window_id=window_id, session_id="session-1", device_id="device-1",
                  mode="collection", start_utc_ms=1795400010000, end_utc_ms=1795400020000,
                  start_elapsed_ns=5133000000000, end_elapsed_ns=5143000000000,
                  scan_active_ms=10000, packet_count=0, sensor_counts={}, interruption="none")
    sample.update(changes)
    return sample


class ScanWindowTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        self.open_application()
        self.addCleanup(lambda: self.store.close())

    def open_application(self):
        self.store = Store(self.directory / "test.sqlite", self.directory / "packages")
        self.app = Application(self.store, "app-secret", "admin-secret")

    def post(self, batch_id, windows, token="Bearer app-secret"):
        return self.app.route("POST", "/v1/scan-windows/batch", token,
                              {"batch_id": batch_id, "windows": windows})

    def test_empty_window_and_restart_remain_distinct_from_observations(self):
        empty = window(gps_lat=36.6301, gps_lon=127.4572, gps_accuracy=9, gps_age_ms=300)
        restarted = window("w2", mode="evaluation", start_elapsed_ns=5143000000000,
                           end_elapsed_ns=5153000000000, start_utc_ms=1795400020000,
                           end_utc_ms=1795400030000, scan_active_ms=9000,
                           interruption="scan_restart", packet_count=3, sensor_counts={"sensor 07": 2})
        ack = self.post("b", [empty, restarted])
        self.assertEqual([{"window_id": v["window_id"], "status": "stored"}
                          for v in [empty, restarted]], ack["results"])
        rows = self.store.db.execute("SELECT payload FROM scan_windows ORDER BY window_id").fetchall()
        self.assertEqual([empty, restarted], [json.loads(row[0]) for row in rows])
        self.assertEqual(0, self.store.db.execute("SELECT count(*) FROM observations").fetchone()[0])

    def test_replay_after_restart_and_conflicts(self):
        original = window()
        first = self.post("a", [original])
        self.store.close()
        self.open_application()
        self.assertEqual(first, self.post("a", [original]))
        self.assertEqual("duplicate", self.post("b", [original])["results"][0]["status"])
        changed = window(packet_count=1, sensor_counts={"sensor 01": 1})
        self.assertEqual("error", self.post("c", [changed])["results"][0]["status"])
        with self.assertRaises(ApiError) as error:
            self.post("a", [changed])
        self.assertEqual(409, error.exception.status)
        row = self.store.db.execute("SELECT payload FROM scan_windows").fetchone()
        self.assertEqual(original, json.loads(row[0]))

    def test_invalid_rows_do_not_prevent_valid_rows_storing(self):
        invalid = [
            {"mode": "guidance"}, {"mode": "guidance", "gps_lat": 36, "gps_lon": 127},
            {"end_elapsed_ns": 5142000000000}, {"end_utc_ms": 0},
            {"scan_active_ms": 10001}, {"packet_count": True}, {"interruption": "unknown"},
            {"sensor_counts": {"sensor 01": 1}}, {"sensor_counts": {"sensor 35": 0}},
            {"sensor_counts": {"sensor 01": -1}}, {"gps_lat": 36},
            {"gps_lat": 91, "gps_lon": 127}, {"gps_accuracy": 5},
            {"gps_lat": 36, "gps_lon": 127, "gps_age_ms": -1},
        ]
        rows = [window(f"bad-{idx}", **fields) for idx, fields in enumerate(invalid)]
        result = self.post("mixed", rows + [window()])
        for ack, row in zip(result["results"], rows):
            self.assertEqual(row["window_id"], ack["window_id"])
            self.assertEqual("error", ack["status"], row)
            self.assertTrue(ack["error"])
        self.assertEqual({"window_id": "w1", "status": "stored"}, result["results"][-1])

    def test_requires_app_token_and_nonempty_batch(self):
        for token in (None, "Bearer admin-secret"):
            with self.assertRaises(ApiError) as error:
                self.post("a", [window()], token)
            self.assertEqual(401, error.exception.status)
        with self.assertRaises(ApiError) as error:
            self.post("a", [])
        self.assertEqual(400, error.exception.status)
