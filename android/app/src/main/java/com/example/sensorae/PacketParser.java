package com.example.sensorae;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** API v4: laboratory payload is CO2 FIRST, not temperature first. */
public final class PacketParser {
    private PacketParser() { }
    public static final class Parsed {
        public String status, sensorId, rawHex;
        public Double temperature, humidity;
        public Integer aqi, tvoc, co2;
        public Long timestamp;
        public void applyTo(Observation observation) {
            observation.sensor_id = sensorId; observation.raw_hex = rawHex;
            observation.parse_status = status; observation.temperature = temperature;
            observation.humidity = humidity; observation.aqi = aqi; observation.tvoc = tvoc;
            observation.co2 = co2; observation.sensor_timestamp = timestamp;
        }
    }
    public static Parsed parse(String advertisedName, byte[] raw) {
        Parsed result = new Parsed();
        result.sensorId = SensorIdentity.normalize(advertisedName);
        result.rawHex = hex(raw);
        SensorIdentity.Kind kind = SensorIdentity.kind(advertisedName);
        if (kind == SensorIdentity.Kind.UNKNOWN) { result.status = "unknown_name"; return result; }
        if (raw == null) { result.status = "missing_service_data"; return result; }
        if (kind == SensorIdentity.Kind.LAB && raw.length != 8
                || kind == SensorIdentity.Kind.OPEN_SOURCE && raw.length < 13) {
            result.status = "length_mismatch"; return result;
        }
        ByteBuffer buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        if (kind == SensorIdentity.Kind.LAB) {
            result.co2 = buffer.getShort() & 0xffff;
            result.temperature = buffer.getShort() / 100.0;
        } else {
            result.temperature = buffer.getShort() / 100.0;
            result.humidity = (buffer.getShort() & 0xffff) / 100.0;
            result.aqi = buffer.get() & 0xff;
            result.tvoc = buffer.getShort() & 0xffff;
            result.co2 = buffer.getShort() & 0xffff;
        }
        result.timestamp = buffer.getInt() & 0xffffffffL;
        // Trailing verification bytes are preserved but have NOT been authenticated.
        result.status = kind == SensorIdentity.Kind.OPEN_SOURCE && raw.length > 13
                ? "parsed_trailing_unverified" : "parsed";
        return result;
    }
    public static String hex(byte[] bytes) {
        if (bytes == null) return "";
        char[] digits = "0123456789abcdef".toCharArray();
        char[] output = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            output[i * 2] = digits[(bytes[i] & 0xff) >>> 4]; output[i * 2 + 1] = digits[bytes[i] & 15];
        }
        return new String(output);
    }
}
