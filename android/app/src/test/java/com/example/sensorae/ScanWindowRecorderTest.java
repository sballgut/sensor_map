package com.example.sensorae;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class ScanWindowRecorderTest {
    private static final long S = 1_000_000_000L;
    private final List<ScanWindowRecorder.Window> rows = new ArrayList<>();
    private final ScanWindowRecorder recorder = new ScanWindowRecorder("session", "device", "collection", 0, 5000, rows::add);

    @Test public void delayedTickPreservesEmptyWindowsAndBoundaryPacket() {
        recorder.packet(10*S, "sensor 07");
        recorder.packet(11*S, null);
        recorder.advance(30*S);
        assertEquals(3, rows.size());
        assertEquals(0, rows.get(0).packet_count);
        assertEquals(2, rows.get(1).packet_count);
        assertEquals(Integer.valueOf(1), rows.get(1).sensor_counts.get("sensor 07"));
        assertEquals(0, rows.get(2).packet_count);
        assertEquals(10000, rows.get(2).scan_active_ms);
        assertEquals(rows.get(0).end_elapsed_ns, rows.get(1).start_elapsed_ns);
        assertEquals(rows.get(0).end_utc_ms, rows.get(1).start_utc_ms);
        assertNotEquals(rows.get(0).window_id, rows.get(1).window_id);
    }

    @Test public void interruptionAcrossBoundaryAndPartialStop() {
        recorder.setPause(9*S, "scan_restart");
        recorder.setPause(11*S, null);
        recorder.stop(24*S, "bluetooth_off");
        assertEquals(3, rows.size());
        assertEquals(9000, rows.get(0).scan_active_ms);
        assertEquals("scan_restart", rows.get(0).interruption);
        assertEquals(9000, rows.get(1).scan_active_ms);
        assertEquals("scan_restart", rows.get(1).interruption);
        assertEquals(4000, rows.get(2).scan_active_ms);
        assertEquals("bluetooth_off", rows.get(2).interruption);
    }

    @Test public void gpsAgeUsesWindowEndAndExpiresDuringSilence() {
        recorder.fix(8*S, 7*S, 36.6, 127.4, 12);
        recorder.stop(40*S, "service_stopped");
        assertEquals(4, rows.size());
        assertEquals(Long.valueOf(3000), rows.get(0).gps_age_ms);
        assertEquals(Double.valueOf(36.6), rows.get(0).gps_lat);
        assertNull(rows.get(3).gps_lat);
        assertNull(rows.get(3).gps_age_ms);
    }
}
