"""Local reference API for SensorAE. No public sensor endpoint is called."""
import argparse
import hashlib
import hmac
import json
import math
import os
from pathlib import Path
import re
import shutil
import sqlite3
import tempfile
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit, unquote


class ApiError(Exception):
    def __init__(self, status, message):
        self.status, self.message = status, message


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False)


def text(value, field, limit=256):
    if not isinstance(value, str) or not value.strip() or len(value) > limit:
        raise ValueError(f"{field}: nonempty string up to {limit} characters required")
    return value


def validate_record(record):
    if not isinstance(record, dict):
        raise ValueError("record must be an object")
    for key in ("record_id", "session_id", "device_id", "parse_status"):
        text(record.get(key), key)
    sensor = record.get("sensor_id")
    if sensor is not None:
        text(sensor, "sensor_id")
    for key in ("utc_ms", "elapsed_ns", "rssi"):
        if isinstance(record.get(key), bool) or not isinstance(record.get(key), int):
            raise ValueError(f"{key}: integer required")
    if record["utc_ms"] < 0 or record["elapsed_ns"] < 0:
        raise ValueError("timestamps must be nonnegative")
    raw = record.get("raw_hex")
    if not isinstance(raw, str) or len(raw) > 131070 or len(raw) % 2 or not re.fullmatch(r"[0-9a-fA-F]*", raw):
        raise ValueError("raw_hex: even length hex required (empty allowed for missing payload)")
    mode = text(record.get("mode"), "mode").lower()
    if mode not in ("guidance", "collection", "evaluation"):
        raise ValueError("unknown mode")
    lat, lon = record.get("gps_lat"), record.get("gps_lon")
    if mode == "guidance" and any(value is not None for key, value in record.items() if key.startswith("gps_")):
        raise ValueError("guidance must keep all GPS fields null")
    if (lat is None) != (lon is None):
        raise ValueError("GPS latitude and longitude must both be present or both null")
    if lat is not None:
        if any(isinstance(v, bool) or not isinstance(v, (int, float)) or not math.isfinite(v) for v in (lat, lon)):
            raise ValueError("invalid GPS coordinates")
        if not -90 <= lat <= 90 or not -180 <= lon <= 180:
            raise ValueError("GPS coordinates out of range")
    canonical(record)  # reject non-finite numbers anywhere in the payload


class Store:
    def __init__(self, db_path, release_root):
        self.lock = threading.RLock()
        self.db = sqlite3.connect(db_path, check_same_thread=False)
        self.db.row_factory = sqlite3.Row
        self.root = Path(release_root).resolve()
        self.root.mkdir(parents=True, exist_ok=True)
        self.db.executescript("""
        PRAGMA foreign_keys=ON;
        CREATE TABLE IF NOT EXISTS observations(record_id TEXT PRIMARY KEY, session_id TEXT NOT NULL,
          device_id TEXT NOT NULL, payload TEXT NOT NULL, received_ms INTEGER NOT NULL);
        CREATE TABLE IF NOT EXISTS batches(batch_id TEXT PRIMARY KEY, request_hash TEXT NOT NULL,
          response TEXT NOT NULL, received_ms INTEGER NOT NULL);
        CREATE TABLE IF NOT EXISTS candidates(candidate_id TEXT PRIMARY KEY, session_id TEXT NOT NULL,
          status TEXT NOT NULL, payload TEXT NOT NULL, created_ms INTEGER NOT NULL);
        CREATE TABLE IF NOT EXISTS reviews(id INTEGER PRIMARY KEY, candidate_id TEXT NOT NULL,
          old_status TEXT, new_status TEXT NOT NULL, reason TEXT NOT NULL, actor TEXT NOT NULL,
          at_ms INTEGER NOT NULL);
        CREATE TABLE IF NOT EXISTS releases(version TEXT PRIMARY KEY, manifest TEXT NOT NULL,
          candidate_ids TEXT NOT NULL, created_ms INTEGER NOT NULL);
        CREATE TABLE IF NOT EXISTS settings(key TEXT PRIMARY KEY, value TEXT NOT NULL);
        CREATE TABLE IF NOT EXISTS deployment_history(id INTEGER PRIMARY KEY, version TEXT NOT NULL,
          action TEXT NOT NULL, reason TEXT NOT NULL, at_ms INTEGER NOT NULL);
        """)
        self.db.commit()

    def close(self):
        self.db.close()

    def batch(self, body):
        if not isinstance(body, dict):
            raise ApiError(400, "object required")
        try:
            batch_id = text(body.get("batch_id"), "batch_id")
            request_hash = hashlib.sha256(canonical(body).encode()).hexdigest()
        except (ValueError, TypeError) as exc:
            raise ApiError(400, str(exc))
        records = body.get("records")
        if not isinstance(records, list) or not 1 <= len(records) <= 200:
            raise ApiError(400, "records must contain 1..200 items")
        with self.lock, self.db:
            prior = self.db.execute("SELECT * FROM batches WHERE batch_id=?", (batch_id,)).fetchone()
            if prior:
                if prior["request_hash"] != request_hash:
                    raise ApiError(409, "batch_id reused with different content")
                return json.loads(prior["response"])
            results = []
            for record in records:
                rid = record.get("record_id") if isinstance(record, dict) else None
                try:
                    validate_record(record)
                    payload = canonical(record)
                    existing = self.db.execute("SELECT payload FROM observations WHERE record_id=?", (rid,)).fetchone()
                    if existing and existing[0] != payload:
                        raise ValueError("record_id content conflict")
                    if existing:
                        status = "duplicate"
                    else:
                        self.db.execute("INSERT INTO observations VALUES(?,?,?,?,?)",
                                        (rid, record["session_id"], record["device_id"], payload, now()))
                        status = "stored"
                    results.append({"record_id": rid, "status": status})
                except (ValueError, TypeError) as exc:
                    results.append({"record_id": rid if isinstance(rid, str) else None,
                                    "status": "error", "error": str(exc)})
            response = {"batch_id": batch_id, "results": results}
            self.db.execute("INSERT INTO batches VALUES(?,?,?,?)", (batch_id, request_hash, canonical(response), now()))
            return response

    def candidate(self, body):
        try:
            if not isinstance(body, dict) or body.get("consent") is not True:
                raise ValueError("explicit recording consent is required")
            sid = text(body.get("session_id"), "session_id")
            text(body.get("description"), "description", 10000)
            ids = body.get("evidence_record_ids")
            if not isinstance(ids, list) or not 1 <= len(ids) <= 5000 or any(not isinstance(v, str) for v in ids):
                raise ValueError("1..5000 evidence_record_ids required")
            estimates = body.get("position_estimates", body.get("estimates", []))
            if not isinstance(estimates, list) or len(estimates) > 5000 or any(not isinstance(v, dict) for v in estimates):
                raise ValueError("position_estimates must contain at most 5000 objects")
            photos = body.get("photo_refs", [])
            if not isinstance(photos, list) or len(photos) > 50 or any(not isinstance(v, str) or len(v) > 2048 for v in photos):
                raise ValueError("photo_refs must contain at most 50 string references")
            payload = canonical(body)
        except (TypeError, ValueError) as exc:
            raise ApiError(400, str(exc))
        with self.lock, self.db:
            for rid in set(ids):
                row = self.db.execute("SELECT session_id,payload FROM observations WHERE record_id=?", (rid,)).fetchone()
                if row is None or row[0] != sid:
                    raise ApiError(400, "evidence records must exist in the same session")
                if json.loads(row[1]).get("candidate_consent") is not True:
                    raise ApiError(400, "each evidence record must have recording-time candidate consent")
            cid = str(uuid.uuid4())
            self.db.execute("INSERT INTO candidates VALUES(?,?,?,?,?)", (cid, sid, "submitted", payload, now()))
            self.db.execute("INSERT INTO reviews(candidate_id,old_status,new_status,reason,actor,at_ms) VALUES(?,?,?,?,?,?)",
                            (cid, None, "submitted", "consented submission", "app", now()))
            return {"candidate_id": cid, "status": "submitted"}

    def list_candidates(self):
        with self.lock:
            return [{"candidate_id": r[0], "session_id": r[1], "status": r[2], "created_ms": r[3]}
                    for r in self.db.execute("SELECT candidate_id,session_id,status,created_ms FROM candidates ORDER BY created_ms DESC")]

    def detail(self, cid):
        with self.lock:
            row = self.db.execute("SELECT * FROM candidates WHERE candidate_id=?", (cid,)).fetchone()
            if row is None:
                raise ApiError(404, "candidate not found")
            result = dict(row)
            result["payload"] = json.loads(result["payload"])
            result["reviews"] = [dict(r) for r in self.db.execute("SELECT * FROM reviews WHERE candidate_id=? ORDER BY id", (cid,))]
            return result

    def transition(self, cid, body):
        status, reason = body.get("status"), body.get("reason")
        try:
            text(reason, "reason", 2000)
        except ValueError as exc:
            raise ApiError(400, str(exc))
        allowed = {"submitted": {"under_review", "withdrawn"}, "under_review": {"approved", "rejected", "withdrawn"},
                   "approved": {"withdrawn"}, "rejected": {"under_review", "withdrawn"}, "published": {"withdrawn"}, "withdrawn": set()}
        with self.lock, self.db:
            current = self.detail(cid)["status"]
            if status not in allowed[current]:
                raise ApiError(409, "invalid candidate transition")
            self.db.execute("UPDATE candidates SET status=? WHERE candidate_id=?", (status, cid))
            self.db.execute("INSERT INTO reviews(candidate_id,old_status,new_status,reason,actor,at_ms) VALUES(?,?,?,?,?,?)",
                            (cid, current, status, reason, "admin", now()))
            return {"candidate_id": cid, "status": status,
                    "deployment_action_required": current == "published" and status == "withdrawn"}

    def publish(self, body):
        """Admin provides a local staging directory populated by B/C. No arbitrary URL downloads."""
        try:
            version = text(body.get("version"), "version", 64)
            if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]*", version) or ".." in version or version.endswith('.'):
                raise ValueError("invalid version")
            if body.get("compatibility") != 1:
                raise ValueError("only compatibility 1 supported")
            source = Path(text(body.get("source_dir"), "source_dir", 2048)).resolve()
            candidate_ids = body.get("candidate_ids", [])
            if not isinstance(candidate_ids, list) or len(candidate_ids) > 1000 or any(not isinstance(v, str) for v in candidate_ids):
                raise ValueError("invalid candidate_ids")
            filenames = {"map": "map.json", "graph": "graph.json", "destinations": "destinations.json", "model": "model.json"}
            for name in filenames.values():
                file = source / name
                if not file.is_file() or file.stat().st_size > 20 * 1024 * 1024:
                    raise ValueError(f"missing/oversized asset: {name}")
                json.loads(file.read_text(encoding="utf-8"))
        except (ValueError, TypeError, OSError) as exc:
            raise ApiError(400, str(exc))
        with self.lock:
            if (self.root / version).exists() or self.db.execute("SELECT 1 FROM releases WHERE version=?", (version,)).fetchone():
                raise ApiError(409, "version already exists; versions are immutable")
            for cid in candidate_ids:
                if self.detail(cid)["status"] != "approved":
                    raise ApiError(409, "all candidate_ids must be approved")
            temp = Path(tempfile.mkdtemp(prefix=".staging-", dir=self.root))
            try:
                assets = []
                for logical, name in filenames.items():
                    data = (source / name).read_bytes()
                    (temp / name).write_bytes(data)
                    assets.append({"name": logical, "url": f"/v1/assets/{version}/{name}",
                                   "sha256": hashlib.sha256(data).hexdigest(), "size": len(data)})
                manifest = {"version": version, "compatibility": 1, "assets": assets}
                (temp / "manifest.json").write_text(canonical(manifest), encoding="utf-8")
                temp.rename(self.root / version)
                with self.db:
                    self.db.execute("INSERT INTO releases VALUES(?,?,?,?)", (version, canonical(manifest), canonical(candidate_ids), now()))
                    self.db.execute("INSERT OR REPLACE INTO settings VALUES('current_release',?)", (version,))
                    self.db.execute("INSERT INTO deployment_history(version,action,reason,at_ms) VALUES(?,?,?,?)", (version, "publish", "approved release", now()))
                    for cid in candidate_ids:
                        self.db.execute("UPDATE candidates SET status='published' WHERE candidate_id=?", (cid,))
                        self.db.execute("INSERT INTO reviews(candidate_id,old_status,new_status,reason,actor,at_ms) VALUES(?,?,?,?,?,?)",
                                        (cid, "approved", "published", version, "admin", now()))
                return manifest
            finally:
                if temp.exists():
                    shutil.rmtree(temp)

    def current(self):
        with self.lock:
            row = self.db.execute("SELECT r.manifest FROM releases r JOIN settings s ON r.version=s.value WHERE s.key='current_release'").fetchone()
            if row is None:
                raise ApiError(404, "no release published")
            return json.loads(row[0])

    def rollback(self, body):
        version = body.get("version")
        try:
            text(body.get("reason"), "reason", 2000)
        except ValueError as exc:
            raise ApiError(400, str(exc))
        with self.lock, self.db:
            row = self.db.execute("SELECT manifest FROM releases WHERE version=?", (version,)).fetchone()
            if not row:
                raise ApiError(404, "release not found")
            manifest = json.loads(row[0])
            for asset in manifest["assets"]:
                path = self.root / version / (asset["name"] + ".json")
                if not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest() != asset["sha256"]:
                    raise ApiError(409, "release integrity check failed")
            self.db.execute("INSERT OR REPLACE INTO settings VALUES('current_release',?)", (version,))
            self.db.execute("INSERT INTO deployment_history(version,action,reason,at_ms) VALUES(?,?,?,?)", (version, "rollback", body["reason"], now()))
            return manifest


def now():
    return int(time.time() * 1000)


class Application:
    def __init__(self, store, app_token, admin_token):
        if not app_token or not admin_token or app_token == admin_token:
            raise ValueError("different nonempty APP_TOKEN and ADMIN_TOKEN are required")
        self.store, self.app_token, self.admin_token = store, app_token, admin_token

    def authorize(self, token, admin=False):
        expected = self.admin_token if admin else self.app_token
        if not hmac.compare_digest(token or "", "Bearer " + expected):
            raise ApiError(401, "unauthorized")

    def route(self, method, path, token=None, body=None):
        if method == "GET" and path == "/health":
            return {"status": "ok"}
        if method == "GET" and path == "/v1/releases/current":
            return self.store.current()
        if path.startswith("/v1/admin/"):
            self.authorize(token, True)
            if method == "GET" and path == "/v1/admin/candidates":
                return {"candidates": self.store.list_candidates()}
            if path.startswith("/v1/admin/candidates/"):
                cid = path.removeprefix("/v1/admin/candidates/")
                if method == "GET":
                    return self.store.detail(cid)
                if method == "POST":
                    return self.store.transition(cid, body or {})
            if method == "POST" and path == "/v1/admin/releases":
                return self.store.publish(body or {})
            if method == "POST" and path == "/v1/admin/releases/rollback":
                return self.store.rollback(body or {})
        elif method == "POST":
            self.authorize(token)
            if path == "/v1/observations/batch":
                return self.store.batch(body)
            if path == "/v1/candidates":
                return self.store.candidate(body)
        raise ApiError(404, "not found")


def make_handler(app):
    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            self.handle_request()

        def do_POST(self):
            self.handle_request()

        def handle_request(self):
            try:
                path = unquote(urlsplit(self.path).path)
                if self.command == "GET" and path == "/admin":
                    self.respond(200, Path(__file__).with_name("admin.html").read_bytes(), "text/html; charset=utf-8")
                    return
                if self.command == "GET" and path.startswith("/v1/assets/"):
                    parts = path.split("/")
                    if len(parts) != 5 or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]*", parts[3]) or parts[4] not in {"map.json", "graph.json", "destinations.json", "model.json"}:
                        raise ApiError(404, "asset not found")
                    with app.store.lock:
                        row = app.store.db.execute("SELECT manifest FROM releases WHERE version=?", (parts[3],)).fetchone()
                    if not row:
                        raise ApiError(404, "release not found")
                    data = (app.store.root / parts[3] / parts[4]).read_bytes()
                    self.respond(200, data, "application/json")
                    return
                body = None
                if self.command == "POST":
                    size = int(self.headers.get("Content-Length", "0"))
                    if not 0 < size <= 4_000_000:
                        raise ApiError(413, "body must be 1..4000000 bytes")
                    body = json.loads(self.rfile.read(size))
                    if not isinstance(body, dict):
                        raise ApiError(400, "JSON object required")
                result = app.route(self.command, path, self.headers.get("Authorization"), body)
                self.respond(200, canonical(result).encode())
            except ApiError as exc:
                self.respond(exc.status, canonical({"error": exc.message}).encode())
            except (ValueError, TypeError, json.JSONDecodeError):
                self.respond(400, b'{"error":"invalid request"}')
            except Exception:
                self.respond(500, b'{"error":"internal error; inspect server environment"}')

        def respond(self, status, data, content_type="application/json; charset=utf-8"):
            self.send_response(status)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def log_message(self, *_):
            pass  # do not log bearer tokens or raw candidate records
    return Handler


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8080)
    parser.add_argument("--data", default="data")
    args = parser.parse_args()
    directory = Path(args.data)
    directory.mkdir(parents=True, exist_ok=True)
    store = Store(directory / "sensor.db", directory / "releases")
    app = Application(store, os.environ.get("APP_TOKEN"), os.environ.get("ADMIN_TOKEN"))
    server = ThreadingHTTPServer((args.host, args.port), make_handler(app))
    print(f"SensorAE reference server: http://{args.host}:{args.port}")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
        store.close()
