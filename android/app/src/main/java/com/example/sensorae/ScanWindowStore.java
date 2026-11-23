package com.example.sensorae;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/** Durable window samples, including intervals with no received advertisements. */
public final class ScanWindowStore extends SQLiteOpenHelper implements BatchUploader.Queue {
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();
    private static final String TABLE = "scan_windows";
    private static final String[] CSV_FIELDS = {
            "window_id", "session_id", "device_id", "mode", "start_utc_ms", "end_utc_ms",
            "start_elapsed_ns", "end_elapsed_ns", "scan_active_ms", "packet_count",
            "sensor_counts", "gps_lat", "gps_lon", "gps_accuracy", "gps_age_ms", "interruption"
    };

    public ScanWindowStore(Context context) {
        super(context.getApplicationContext(), "scan_windows.db", null, 1);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE scan_windows (window_id TEXT PRIMARY KEY, json TEXT NOT NULL, " +
                "acknowledged INTEGER NOT NULL DEFAULT 0, last_attempt_ms INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX scan_windows_pending ON scan_windows(acknowledged,last_attempt_ms)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        throw new IllegalStateException("Explicit scan window migration required");
    }

    public void insert(ScanWindowRecorder.Window window) {
        ContentValues row = new ContentValues();
        row.put("window_id", window.window_id);
        row.put("json", GSON.toJson(window));
        getWritableDatabase().insertOrThrow(TABLE, null, row);
    }

    @Override public int countPending() {
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM scan_windows WHERE acknowledged=0", null)) {
            cursor.moveToFirst();
            return cursor.getInt(0);
        }
    }

    public BatchUploader.Result uploadOnce(String base, String key) {
        JsonArray rows = new JsonArray();
        try (Cursor cursor = getReadableDatabase().query(TABLE, new String[]{"json"}, "acknowledged=0",
                null, null, null, "last_attempt_ms,rowid", "100")) {
            while (cursor.moveToNext()) rows.add(JsonParser.parseString(cursor.getString(0)));
        }
        return BatchUploader.upload(base, key, BatchUploader.Endpoint.WINDOWS, rows, this);
    }

    @Override public void markAttempt(Set<String> ids) {
        ContentValues values = new ContentValues();
        values.put("last_attempt_ms", System.currentTimeMillis());
        updatePending(ids, values);
    }

    @Override public void acknowledge(Set<String> ids) {
        ContentValues values = new ContentValues();
        values.put("acknowledged", 1);
        updatePending(ids, values);
    }

    private void updatePending(Set<String> ids, ContentValues values) {
        if (ids.isEmpty()) return;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (String id : ids) db.update(TABLE, values, "window_id=? AND acknowledged=0", new String[]{id});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public void exportCsv(OutputStream stream) throws IOException {
        OutputStreamWriter writer = new OutputStreamWriter(stream, StandardCharsets.UTF_8);
        writer.write('\ufeff');
        writer.write(String.join(",", CSV_FIELDS) + ",acknowledged\r\n");
        try (Cursor cursor = getReadableDatabase().query(TABLE, new String[]{"json", "acknowledged"},
                null, null, null, null, "rowid")) {
            while (cursor.moveToNext()) {
                JsonObject row = JsonParser.parseString(cursor.getString(0)).getAsJsonObject();
                for (String field : CSV_FIELDS) {
                    writer.write(ObservationStore.csv(csvValue(row.get(field))));
                    writer.write(',');
                }
                writer.write(Integer.toString(cursor.getInt(1)));
                writer.write("\r\n");
            }
        }
        writer.flush();
    }

    private static Object csvValue(JsonElement value) {
        if (value == null || value.isJsonNull()) return null;
        if (value.isJsonObject()) return value.toString();
        if (value.getAsJsonPrimitive().isNumber()) return value.getAsNumber();
        return value.getAsString();
    }
}
