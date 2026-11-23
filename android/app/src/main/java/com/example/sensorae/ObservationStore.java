package com.example.sensorae;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Immutable raw observations + durable ACK state. Call from the app's worker executor. */
public final class ObservationStore extends SQLiteOpenHelper implements BatchUploader.Queue {
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();
    public ObservationStore(Context context) { super(context.getApplicationContext(), "sensor_ae.db", null, 2); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE observations (record_id TEXT PRIMARY KEY, session_id TEXT NOT NULL, utc_ms INTEGER NOT NULL, json TEXT NOT NULL, acknowledged INTEGER NOT NULL DEFAULT 0, last_attempt_ms INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX observations_pending ON observations(acknowledged,utc_ms)");
        db.execSQL("CREATE INDEX observations_session ON observations(session_id,utc_ms)");
        createSubmissions(db);
    }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion == 1 && newVersion == 2) createSubmissions(db);
        else throw new IllegalStateException("Explicit observation migration required");
    }
    private static void createSubmissions(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE submissions (session_id TEXT PRIMARY KEY, server TEXT NOT NULL, " +
                "body BLOB NOT NULL, state TEXT NOT NULL, detail TEXT NOT NULL)");
    }
    boolean hasPending(String sessionId) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT 1 FROM observations WHERE session_id=? AND acknowledged=0 LIMIT 1", new String[]{sessionId})) {
            return c.moveToFirst();
        }
    }
    public void insert(Observation value) {
        if (value == null || value.record_id == null || value.session_id == null || value.device_id == null) throw new IllegalArgumentException("Missing record identity");
        if ("guidance".equals(value.mode) && (value.gps_lat != null || value.gps_lon != null || value.gps_accuracy != null || value.gps_age_ms != null)) throw new IllegalArgumentException("Guidance mode cannot persist GPS");
        String json = GSON.toJson(value);
        SQLiteDatabase db = getWritableDatabase();
        ContentValues row = new ContentValues();
        row.put("record_id", value.record_id); row.put("session_id", value.session_id);
        row.put("utc_ms", value.utc_ms); row.put("json", json);
        if (db.insertWithOnConflict("observations", null, row, SQLiteDatabase.CONFLICT_IGNORE) == -1) {
            try (Cursor cursor = db.query("observations", new String[]{"json"}, "record_id=?", new String[]{value.record_id}, null, null, null)) {
                if (!cursor.moveToFirst() || !cursor.getString(0).equals(json)) throw new IllegalStateException("record_id content conflict");
            }
        }
    }
    public List<Observation> pending(int limit) { return query("acknowledged=0", null, "last_attempt_ms ASC,utc_ms ASC,rowid ASC", limit); }
    public List<Observation> recent(int limit) { return query(null, null, "utc_ms DESC,rowid DESC", limit); }
    public List<Observation> session(String sessionId) { return query("session_id=?", new String[]{sessionId}, "utc_ms ASC,rowid ASC", 0); }
    List<String> consentedEvidence(String sessionId, int limit) {
        List<String> ids = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("observations", new String[]{"json"}, "session_id=?",
                new String[]{sessionId}, null, null, "utc_ms ASC,rowid ASC")) {
            while (ids.size() < limit && c.moveToNext()) {
                Observation o = GSON.fromJson(c.getString(0), Observation.class);
                if (o.candidate_consent) ids.add(o.record_id);
            }
        }
        return ids;
    }
    private List<Observation> query(String where, String[] args, String order, int limit) {
        List<Observation> result = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query("observations", new String[]{"json"}, where, args, null, null, order, limit > 0 ? Integer.toString(limit) : null)) {
            while (cursor.moveToNext()) result.add(GSON.fromJson(cursor.getString(0), Observation.class));
        }
        return result;
    }
    public void acknowledge(Set<String> ids) {
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try {
            ContentValues row = new ContentValues(); row.put("acknowledged", 1);
            for (String id : ids) db.update("observations", row, "record_id=?", new String[]{id});
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
    /** Rotate failed rows so a batch of malformed records cannot starve later valid samples. */
    public void markAttempt(Set<String> ids) {
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try {
            ContentValues row = new ContentValues(); row.put("last_attempt_ms", System.currentTimeMillis());
            for (String id : ids) db.update("observations", row, "record_id=? AND acknowledged=0", new String[]{id});
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
    public int countPending() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM observations WHERE acknowledged=0", null)) { c.moveToFirst(); return c.getInt(0); }
    }
    public void exportCsv(OutputStream stream) throws IOException {
        OutputStreamWriter writer = new OutputStreamWriter(stream, StandardCharsets.UTF_8);
        writer.write('\ufeff');
        writer.write("record_id,session_id,device_id,sensor_id,advertised_name,mac,rssi,raw_hex,utc_ms,elapsed_ns,mode,parse_status,temperature,humidity,aqi,tvoc,co2,sensor_timestamp,gps_lat,gps_lon,gps_accuracy,gps_age_ms,heading_deg,heading_accuracy,heading_source,fixed_declination_deg,candidate_consent,reference_point_id,acknowledged\r\n");
        try (Cursor c = getReadableDatabase().query("observations", new String[]{"json", "acknowledged"}, null, null, null, null, "utc_ms ASC,rowid ASC")) {
            while (c.moveToNext()) {
                Observation o = GSON.fromJson(c.getString(0), Observation.class);
                Object[] fields = {o.record_id,o.session_id,o.device_id,o.sensor_id,o.advertised_name,o.mac,o.rssi,o.raw_hex,o.utc_ms,o.elapsed_ns,o.mode,o.parse_status,o.temperature,o.humidity,o.aqi,o.tvoc,o.co2,o.sensor_timestamp,o.gps_lat,o.gps_lon,o.gps_accuracy,o.gps_age_ms,o.heading_deg,o.heading_accuracy,o.heading_source,o.fixed_declination_deg,o.candidate_consent,o.reference_point_id,c.getInt(1)};
                for (int i = 0; i < fields.length; i++) { if (i > 0) writer.write(','); writer.write(csv(fields[i])); }
                writer.write("\r\n");
            }
        }
        writer.flush();
    }
    static String csv(Object value) {
        String text = value == null ? "" : value.toString();
        if (value instanceof String && !text.isEmpty() && "=+-@\t\r".indexOf(text.charAt(0)) >= 0) text = "'" + text;
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }
}
