"""API-16 scan-window schema and validation for the batch store."""
import math


SCHEMA = """
CREATE TABLE IF NOT EXISTS scan_windows (
    window_id TEXT PRIMARY KEY, session_id TEXT NOT NULL, device_id TEXT NOT NULL,
    payload TEXT NOT NULL, received_ms INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS scan_window_batches (
    batch_id TEXT PRIMARY KEY, request_hash TEXT NOT NULL,
    response TEXT NOT NULL, received_ms INTEGER NOT NULL
);
"""
WINDOW_MS = 10_000
SENSOR_IDS = {f"sensor {number:02d}" for number in range(1, 35)}
INTERRUPTIONS = {"none", "scan_restart", "service_stopped", "bluetooth_off", "permission_denied"}


def nonnegative_integer(value, name):
    if type(value) is not int or value < 0:
        raise ValueError(f"{name}: nonnegative integer required")
    return value


def validate_window(window):
    if not isinstance(window, dict):
        raise ValueError("window must be an object")
    for key in ("window_id", "session_id", "device_id"):
        value = window.get(key)
        if not isinstance(value, str) or not value.strip() or len(value) > 256:
            raise ValueError(f"{key}: nonempty string up to 256 characters required")
    if window.get("mode") not in ("collection", "evaluation"):
        raise ValueError("scan windows require collection or evaluation mode")
    if window.get("interruption") not in INTERRUPTIONS:
        raise ValueError("unknown interruption")

    for key in ("start_utc_ms", "end_utc_ms", "start_elapsed_ns", "end_elapsed_ns",
                "scan_active_ms", "packet_count"):
        nonnegative_integer(window.get(key), key)
    if window["end_utc_ms"] <= window["start_utc_ms"]:
        raise ValueError("window end must follow start")
    if window["end_elapsed_ns"] - window["start_elapsed_ns"] != WINDOW_MS * 1_000_000:
        raise ValueError("elapsed window duration must be 10 seconds")
    if window["scan_active_ms"] > WINDOW_MS:
        raise ValueError("scan_active_ms exceeds the window duration")

    counts = window.get("sensor_counts")
    if not isinstance(counts, dict):
        raise ValueError("sensor_counts must be an object")
    for sensor, count in counts.items():
        if sensor not in SENSOR_IDS:
            raise ValueError("sensor_counts requires sensor IDs from sensor 01 to sensor 34")
        nonnegative_integer(count, "sensor count")
    # An advertisement whose sensor ID cannot be resolved still counts as a packet.
    if sum(counts.values()) > window["packet_count"]:
        raise ValueError("sensor_counts exceed packet_count")

    lat, lon = window.get("gps_lat"), window.get("gps_lon")
    accuracy, age = window.get("gps_accuracy"), window.get("gps_age_ms")
    if lat is None and lon is None:
        if accuracy is not None or age is not None:
            raise ValueError("GPS metadata requires a GPS fix")
        return
    for coordinate, bound in ((lat, 90), (lon, 180)):
        if type(coordinate) not in (int, float) or not -bound <= coordinate <= bound:
            raise ValueError("GPS coordinates missing or out of range")
    if accuracy is not None:
        if type(accuracy) not in (int, float) or accuracy < 0 or (isinstance(accuracy, float) and not math.isfinite(accuracy)):
            raise ValueError("gps_accuracy must be a finite nonnegative number")
    if age is not None:
        nonnegative_integer(age, "gps_age_ms")
