package com.example.sensorae;

/** One raw BLE observation. Installation coordinates never populate gps_* fields. */
public final class Observation {
    public String record_id, session_id, device_id, sensor_id, advertised_name, mac;
    public int rssi;
    public String raw_hex, mode, parse_status;
    public long utc_ms, elapsed_ns;
    public Double temperature, humidity;
    public Integer aqi, tvoc, co2;
    public Long sensor_timestamp;
    public Double gps_lat, gps_lon, gps_accuracy;
    public Long gps_age_ms;
    public Double heading_deg;
    public Integer heading_accuracy;
    public String heading_source;
    public Double fixed_declination_deg;
    public boolean candidate_consent;
    public String reference_point_id;
}
