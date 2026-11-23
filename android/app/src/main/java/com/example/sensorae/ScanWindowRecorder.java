package com.example.sensorae;

import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Consumer;

/** One collection/evaluation session. All events run on the collection thread. */
final class ScanWindowRecorder {
    static final long WINDOW_NS = 10_000_000_000L;

    static final class Window {
        String window_id, session_id, device_id, mode, interruption;
        long start_utc_ms, end_utc_ms, start_elapsed_ns, end_elapsed_ns, scan_active_ms;
        int packet_count;
        Map<String, Integer> sensor_counts = new TreeMap<>();
        Double gps_lat, gps_lon, gps_accuracy;
        Long gps_age_ms;
    }

    private final String session, device, mode;
    private final long originNs, originUtc;
    private final Consumer<Window> sink;
    private Window current;
    private long lastNs, activeNs;
    private String pause;
    private long fixNs = -1;
    private double latitude, longitude, accuracy;

    ScanWindowRecorder(String session, String device, String mode, long ns, long utc, Consumer<Window> sink) {
        this.session = session;
        this.device = device;
        this.mode = mode;
        this.originNs = ns;
        this.originUtc = utc;
        this.sink = sink;
        lastNs = ns;
        current = newWindow(ns);
    }

    private Window newWindow(long start) {
        Window w = new Window();
        w.window_id = UUID.randomUUID().toString();
        w.session_id = session;
        w.device_id = device;
        w.mode = mode;
        w.start_elapsed_ns = start;
        w.end_elapsed_ns = start + WINDOW_NS;
        w.start_utc_ms = originUtc + (start - originNs) / 1_000_000L;
        w.end_utc_ms = w.start_utc_ms + 10_000;
        w.interruption = "none";
        return w;
    }

    private void account(long until) {
        if (until <= lastNs) return;
        if (pause == null) activeNs += until - lastNs;
        else current.interruption = pause;
        lastNs = until;
    }

    void advance(long ns) {
        while (ns >= current.end_elapsed_ns) {
            long end = current.end_elapsed_ns;
            account(end);
            emit(end);
            current = newWindow(end);
            activeNs = 0;
        }
        account(ns);
    }

    void packet(long ns, String sensorId) {
        advance(ns);
        current.packet_count++;
        if (sensorId != null) current.sensor_counts.merge(sensorId, 1, Integer::sum);
    }

    void fix(long nowNs, long measuredNs, double lat, double lon, double metres) {
        advance(nowNs);
        if (measuredNs < originNs || measuredNs < fixNs || measuredNs > nowNs || !Double.isFinite(metres)
                || metres < 0 || metres > 100 || !Double.isFinite(lat) || Math.abs(lat) > 90
                || !Double.isFinite(lon) || Math.abs(lon) > 180) return;
        fixNs = measuredNs;
        latitude = lat;
        longitude = lon;
        accuracy = metres;
    }

    void setPause(long ns, String reason) {
        advance(ns);
        pause = reason;
    }

    void stop(long ns, String reason) {
        advance(ns);
        if (ns == current.start_elapsed_ns) return;
        current.interruption = reason;
        emit(current.end_elapsed_ns);
    }

    private void emit(long endNs) {
        current.scan_active_ms = activeNs / 1_000_000L;
        if (fixNs >= 0 && endNs - fixNs <= 30_000_000_000L) {
            current.gps_age_ms = (endNs - fixNs) / 1_000_000L;
            current.gps_lat = latitude;
            current.gps_lon = longitude;
            current.gps_accuracy = accuracy;
        }
        sink.accept(current);
    }
}
