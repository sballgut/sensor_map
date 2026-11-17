#!/usr/bin/env python3
"""Read-only public API v4 history export. Never calls /sensor/sensing/."""
from __future__ import annotations
import argparse
import datetime as dt
import email.utils
import hashlib
import json
import math
import os
from pathlib import Path
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

URL = "http://203.255.81.72:10021/sensor/api/history"
KST = dt.timezone(dt.timedelta(hours=9))
MAX_RESPONSE_BYTES = 16 * 1024 * 1024


def timestamp(value: str) -> float:
    try:
        result = float(value)
    except ValueError:
        try:
            parsed = dt.datetime.fromisoformat(value.replace("Z", "+00:00"))
        except ValueError as exc:
            raise argparse.ArgumentTypeError("Use epoch seconds or ISO date/time (unqualified = KST)") from exc
        result = parsed.replace(tzinfo=KST).timestamp() if parsed.tzinfo is None else parsed.timestamp()
    if not 0 <= result < 1e12:
        raise argparse.ArgumentTypeError("Invalid timestamp")
    return result


def sensor_name(value: str) -> str:
    match = re.fullmatch(r"(?:sensor\s*)?(\d{1,2})", value.strip(), re.I)
    if not match or not 1 <= int(match.group(1)) <= 34:
        raise argparse.ArgumentTypeError("Sensor must be 1..34, e.g. 'sensor 07'")
    return f"sensor {int(match.group(1)):02d}"


def retry_delay(header: str | None, now: float | None = None) -> float:
    if not header:
        return 5.0
    try:
        seconds = float(header)
        return max(0, seconds) if math.isfinite(seconds) else 5.0
    except ValueError:
        try:
            return max(0, email.utils.parsedate_to_datetime(header).timestamp() - (time.time() if now is None else now))
        except (ValueError, TypeError, OverflowError):
            return 5.0


def atomic_json(path: Path, value: dict) -> None:
    temp = path.with_suffix(path.suffix + ".tmp")
    with temp.open("w", encoding="utf-8") as stream:
        json.dump(value, stream, ensure_ascii=False, allow_nan=False)
        stream.flush()
        os.fsync(stream.fileno())
    os.replace(temp, path)


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        # Prevent the API key from being forwarded to an unexpected endpoint.
        return None


class HistoryClient:
    def __init__(self, key: str, cache: Path, retries: int = 4):
        if not key.strip():
            raise ValueError("SENSOR_API_KEY is empty")
        self.key = key
        self.cache = cache
        cache.mkdir(parents=True, exist_ok=True)
        self.retries = retries
        self.last_request = None
        self.opener = urllib.request.build_opener(NoRedirect())

    @staticmethod
    def validate(body: dict, expected_sensor: str) -> None:
        if not isinstance(body, dict) or body.get("sensor") != expected_sensor or not isinstance(body.get("rows"), list):
            raise ValueError("Unexpected history response shape or sensor")
        cursor = body.get("next_cursor")
        if cursor is not None and (not isinstance(cursor, str) or not cursor):
            raise ValueError("Invalid next_cursor")
        if any(not isinstance(row, dict) for row in body["rows"]):
            raise ValueError("Invalid history row")

    def page(self, query: dict) -> dict:
        encoded = urllib.parse.urlencode(query)
        cache_key = hashlib.sha256(encoded.encode()).hexdigest()
        path = self.cache / (cache_key + ".json")
        if path.is_file():
            try:
                cached = json.loads(path.read_text(encoding="utf-8"))
                if cached["query"] == query:
                    self.validate(cached["response"], query["sensor"])
                    return cached["response"]
            except (OSError, ValueError, KeyError, TypeError):
                pass
        for attempt in range(self.retries + 1):
            if self.last_request is not None:
                time.sleep(max(0, 2.1 - (time.monotonic() - self.last_request)))
            self.last_request = time.monotonic()
            request = urllib.request.Request(URL + "?" + encoded, headers={"X-API-Key": self.key, "Accept": "application/json"}, method="GET")
            try:
                with self.opener.open(request, timeout=30) as response:
                    raw = response.read(MAX_RESPONSE_BYTES + 1)
                if len(raw) > MAX_RESPONSE_BYTES:
                    raise ValueError("History page too large")
                body = json.loads(raw.decode("utf-8"))
                self.validate(body, query["sensor"])
                atomic_json(path, {"source": "public_history", "query": query, "fetched_at_utc_ms": int(time.time() * 1000), "response": body})
                return body
            except urllib.error.HTTPError as error:
                code = error.code
                delay = retry_delay(error.headers.get("Retry-After"))
                error.close()
                if code not in (429, 503) or attempt == self.retries:
                    raise RuntimeError(f"History HTTP {code} for {query['sensor']}; completed pages remain cached") from None
                print(f"HTTP {code}: waiting {delay:.1f}s before retry {attempt+1}/{self.retries}", file=sys.stderr)
                time.sleep(delay)
            except (urllib.error.URLError, TimeoutError):
                if attempt == self.retries:
                    raise RuntimeError(f"History network failure for {query['sensor']}; completed pages remain cached") from None
                time.sleep(min(30, 2 ** attempt))
        raise AssertionError("unreachable")

    def rows(self, sensor: str, start: float, end: float, mode: str | None, limit: int):
        query = {"sensor": sensor, "start": start, "end": end, "limit": limit}
        if mode:
            query["mode"] = mode
        cursors = set()
        while True:
            body = self.page(query)
            yield from body["rows"]
            cursor = body.get("next_cursor")
            if cursor is None:
                return
            if cursor in cursors:
                raise RuntimeError("Repeated history cursor; stopping to avoid a loop")
            cursors.add(cursor)
            query = dict(query, cursor=cursor)


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    selected = parser.add_mutually_exclusive_group(required=True)
    selected.add_argument("--sensor", type=sensor_name, help="one sensor, e.g. 7 or 'sensor 07'")
    selected.add_argument("--all", action="store_true", help="sequentially query all 34 sensors (explicit opt-in)")
    parser.add_argument("--start", required=True, type=timestamp)
    parser.add_argument("--end", required=True, type=timestamp, help="exclusive end; interval must be <=7 days and in the past")
    parser.add_argument("--mode", choices=("direct", "mobile"))
    parser.add_argument("--limit", type=int, default=1000)
    parser.add_argument("--retries", type=int, default=4)
    parser.add_argument("--output", type=Path, default=Path("history_export"))
    args = parser.parse_args(argv)
    if not 0 < args.end - args.start <= 7 * 86400:
        parser.error("start/end interval must be positive and <=7 days; split longer periods")
    if args.end > time.time():
        parser.error("end must not be in the future; cache only completed intervals")
    if not 1 <= args.limit <= 5000 or not 0 <= args.retries <= 10:
        parser.error("limit must be 1..5000; retries must be 0..10")
    key = os.environ.get("SENSOR_API_KEY", "").strip()
    if not key:
        parser.error("Set SENSOR_API_KEY to your assigned team API key")
    args.output.mkdir(parents=True, exist_ok=True)
    client = HistoryClient(key, args.output / "pages", args.retries)
    sensors = [f"sensor {i:02d}" for i in range(1, 35)] if args.all else [args.sensor]
    temp = args.output / "history_records.jsonl.tmp"
    total = 0
    try:
        with temp.open("w", encoding="utf-8") as stream:
            for sensor in sensors:
                count = 0
                for row in client.rows(sensor, args.start, args.end, args.mode, args.limit):
                    # Never present API values as a new phone BLE observation.
                    stream.write(json.dumps({"source": "public_history", "sensor": sensor, "record": row}, ensure_ascii=False, allow_nan=False) + "\n")
                    count += 1
                print(f"{sensor}: {count} records")
                total += count
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temp, args.output / "history_records.jsonl")
        atomic_json(args.output / "export_manifest.json", {"source": "public_history", "sensors": sensors, "start": args.start, "end": args.end, "mode": args.mode, "records": total})
        print(f"Completed: {total} records -> {args.output / 'history_records.jsonl'}")
        return 0
    except (OSError, ValueError, RuntimeError) as error:
        print(f"Stopped: {error}. Rerun the same command to reuse completed pages.", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
