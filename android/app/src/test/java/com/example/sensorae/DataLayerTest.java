package com.example.sensorae;

import org.junit.Test;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import static org.junit.Assert.*;

public class DataLayerTest {
    @Test public void canonicalIdentitiesMergeAliasesWithoutMergingOtherSensors() {
        String[] aliases = {"sensor 07", "sensor 15", "sensor 31", "sensor 32", "sensor 33"};
        for (int i = 1; i <= 5; i++) assertEquals(aliases[i - 1], SensorIdentity.normalize("opensrc" + i));
        assertEquals("sensor 01", SensorIdentity.normalize("sensor1"));
        assertEquals("sensor 34", SensorIdentity.normalize("SENSOR 34"));
        assertNull(SensorIdentity.normalize("pws01"));
        assertNull(SensorIdentity.normalize("sensor 35"));
        assertNull(SensorIdentity.normalize("untrusted opensrc1 suffix"));
    }
    @Test public void laboratoryPacketIsCo2FirstAndTemperatureIsSigned() {
        byte[] raw = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putShort((short) 1000)
                .putShort((short) -250).putInt(-1).array();
        PacketParser.Parsed p = PacketParser.parse("sensor 01", raw);
        assertEquals("parsed", p.status); assertEquals(Integer.valueOf(1000), p.co2);
        assertEquals(-2.5, p.temperature, 0); assertEquals(Long.valueOf(4294967295L), p.timestamp);
        assertNull(p.humidity);
    }
    @Test public void openSourceTrailingVerificationIsRetainedNotClaimedVerified() {
        byte[] raw = ByteBuffer.allocate(21).order(ByteOrder.LITTLE_ENDIAN).putShort((short) 2534)
                .putShort((short) 4875).put((byte) 2).putShort((short) 120).putShort((short) 650)
                .putInt(123456).putLong(42L).array();
        PacketParser.Parsed p = PacketParser.parse("opensrc5", raw);
        assertEquals("sensor 33", p.sensorId); assertEquals("parsed_trailing_unverified", p.status);
        assertEquals(25.34, p.temperature, 0); assertEquals(48.75, p.humidity, 0);
        assertEquals(Integer.valueOf(650), p.co2); assertEquals(42, p.rawHex.length());
    }
    @Test public void malformedEnvironmentDoesNotEraseRawRssiOrIdentity() {
        Observation o = new Observation(); o.rssi = -77;
        PacketParser.parse("opensrc1", new byte[]{1,2,3}).applyTo(o);
        assertEquals(-77, o.rssi); assertEquals("010203", o.raw_hex);
        assertEquals("sensor 07", o.sensor_id); assertEquals("length_mismatch", o.parse_status);
        assertNull(o.temperature); assertNull(o.co2);
        assertEquals("missing_service_data", PacketParser.parse("sensor 02", null).status);
        assertEquals("unknown_name", PacketParser.parse(null, new byte[8]).status);
    }
    @Test public void allShortAndMismatchedLengthsRemainSafe() {
        for (String name : new String[]{"sensor 1", "opensrc1", "unknown", null}) {
            for (int length = 0; length <= 64; length++) assertNotNull(PacketParser.parse(name, new byte[length]).status);
        }
        assertEquals("length_mismatch", PacketParser.parse("sensor 1", new byte[13]).status);
    }
    @Test public void mapKeepsSourcesButDeduplicatesCanonicalSensors() {
        String json = "["
                + "{\"sensor\":\"sensor 07\",\"latitude\":36.1,\"longitude\":127.1,\"fresh\":false,\"mode\":\"direct\",\"time\":\"Mon Nov 16 10:00:00 2026\",\"temperature\":10},"
                + "{\"sensor\":\"opensrc1\",\"latitude\":36.1,\"longitude\":127.1,\"fresh\":true,\"mode\":\"mobile\",\"time\":\"Mon Nov 16 10:01:00 2026\",\"temperature\":11},"
                + "{\"sensor\":\"pws01\"}]";
        List<PublicApi.SensorSnapshot> values = PublicApi.parseMap(json);
        assertEquals(1, values.size()); PublicApi.SensorSnapshot p = values.get(0);
        assertEquals("sensor 07", p.sensor_id); assertEquals(2, p.variants.size());
        assertEquals("mobile", p.mode); assertEquals(11.0, p.temperature, 0);
    }
    @Test public void mapUsesLatestKstTimestampWithinSameFreshness() {
        List<PublicApi.SensorSnapshot> rows = PublicApi.parseMap("[{\"sensor\":\"sensor1\",\"fresh\":false,\"mode\":\"direct\",\"time\":\"Mon Nov 16 09:59:00 2026\"},{\"sensor\":\"sensor 01\",\"fresh\":false,\"mode\":\"mobile\",\"time\":\"Mon Nov 16 10:00:00 2026\"}]");
        assertEquals("mobile", rows.get(0).mode);
    }
    @Test public void onlyExplicitMatchingRecordAcksAreCompleted() {
        Set<String> sent = new HashSet<>(Arrays.asList("a", "b", "c"));
        Set<String> result = BatchUploader.acknowledgedIds("batch", sent,
                "{\"batch_id\":\"batch\",\"results\":[{\"record_id\":\"a\",\"status\":\"stored\"},{\"record_id\":\"b\",\"status\":\"duplicate\"},{\"record_id\":\"c\",\"status\":\"error\"},{\"record_id\":\"foreign\",\"status\":\"stored\"}]}");
        assertEquals(new HashSet<>(Arrays.asList("a", "b")), result);
    }
    @Test(expected=com.google.gson.JsonParseException.class) public void staleBatchAckIsRejected() {
        BatchUploader.acknowledgedIds("current", Collections.singleton("a"), "{\"batch_id\":\"old\",\"results\":[]}");
    }
    @Test public void contradictoryAckKeepsRecordPending() {
        assertTrue(BatchUploader.acknowledgedIds("b", Collections.singleton("a"), "{\"batch_id\":\"b\",\"results\":[{\"record_id\":\"a\",\"status\":\"stored\"},{\"record_id\":\"a\",\"status\":\"error\"}]}").isEmpty());
    }
}
