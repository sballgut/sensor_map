import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import fetch_history as history


class HistoryTests(unittest.TestCase):
    def test_kst_date_and_identity(self):
        self.assertEqual(history.timestamp("2026-11-01"), history.timestamp("2026-10-31T15:00:00Z"))
        self.assertEqual(history.sensor_name("sensor7"), "sensor 07")
        self.assertEqual(history.retry_delay("120"), 120)
        self.assertEqual(history.retry_delay("invalid"), 5)

    def test_cursor_preserves_filters(self):
        with tempfile.TemporaryDirectory() as directory:
            client = history.HistoryClient("test-token", Path(directory))
            calls = []
            def page(query):
                calls.append(dict(query))
                if "cursor" not in query:
                    return {"rows": [{"timestamp": 1}], "next_cursor": "page2"}
                return {"rows": [{"timestamp": 2}], "next_cursor": None}
            client.page = page
            rows = list(client.rows("sensor 07", 1, 3, "mobile", 1000))
            self.assertEqual(len(rows), 2)
            self.assertEqual(calls[1], dict(calls[0], cursor="page2"))

    def test_success_page_cached_without_key(self):
        with tempfile.TemporaryDirectory() as directory:
            client = history.HistoryClient("secret-test-value", Path(directory))
            body = {"sensor": "sensor 01", "rows": [], "next_cursor": None}
            query = {"sensor": "sensor 01", "start": 1, "end": 3, "limit": 1000}
            with patch.object(client.opener, "open", return_value=io.BytesIO(json.dumps(body).encode())) as opened:
                self.assertEqual(client.page(query), body)
                self.assertEqual(client.page(query), body)
                self.assertEqual(opened.call_count, 1)
                self.assertTrue(opened.call_args.args[0].full_url.startswith(history.URL + "?"))
            self.assertNotIn("secret-test-value", next(Path(directory).glob("*.json")).read_text())

    def test_looping_cursor_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            client = history.HistoryClient("test", Path(directory))
            client.page = lambda query: {"rows": [], "next_cursor": "repeat"}
            with self.assertRaises(RuntimeError):
                list(client.rows("sensor 01", 1, 3, None, 1000))

    def test_bad_sensor_reply_rejected(self):
        with self.assertRaises(ValueError):
            history.HistoryClient.validate({"sensor": "sensor 02", "rows": [], "next_cursor": None}, "sensor 01")


if __name__ == "__main__":
    unittest.main()
