package com.example.sensorae;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** All operations run on the process's single network executor. */
final class CandidateClient {
    private final ObservationStore store;
    private final File positionFile;
    static final String QUEUED = "queued", UNKNOWN = "unknown", SUBMITTED = "submitted", REJECTED = "rejected";

    static final class Entry {
        final String session, server, state, detail;
        Entry(Cursor cursor) {
            session = cursor.getString(0);
            server = cursor.getString(1);
            state = cursor.getString(2);
            detail = cursor.getString(3);
        }
        String label() {
            String stateLabel = QUEUED.equals(state) ? "제출 대기" : UNKNOWN.equals(state) ? "결과 모름"
                    : SUBMITTED.equals(state) ? "제출됨" : "거부됨";
            return stateLabel + " · " + session + (detail.isEmpty() ? "" : "\n" + detail);
        }
    }
    CandidateClient(ObservationStore store, File filesDir) {
        this.store = store;
        this.positionFile = new File(filesDir, "candidate_positions.jsonl");
    }
    List<Entry> entries(String where, String... args) {
        List<Entry> rows = new ArrayList<>();
        try (Cursor c = store.getReadableDatabase().query("submissions",
                new String[]{"session_id","server","state","detail"}, where, args, null, null, "rowid DESC")) {
            while (c.moveToNext()) rows.add(new Entry(c));
        }
        return rows;
    }
    private Entry get(String session) { return entries("session_id=?", session).get(0); }
    static String baseUrl(String url) throws IOException {
        String base = url.trim().replaceAll("/+$", "");
        URL parsed = new URL(base);
        if (!("http".equals(parsed.getProtocol()) || "https".equals(parsed.getProtocol())) ||
                parsed.getHost().isEmpty() || parsed.getUserInfo() != null || parsed.getQuery() != null ||
                parsed.getRef() != null) throw new IOException("HTTP(S) 서버 주소를 확인하세요.");
        return base;
    }
    Entry prepare(String session, String server, String description) throws IOException {
        List<Entry> prior = entries("session_id=?", session);
        if (!prior.isEmpty()) return prior.get(0);
        if (description.isEmpty() || description.length() > 10000)
            throw new IOException("후보 설명을 1~10,000자로 입력하세요.");
        JsonObject body = new JsonObject();
        body.addProperty("consent", true);
        body.addProperty("session_id", session);
        body.addProperty("client_submission_id", UUID.randomUUID().toString());
        body.addProperty("description", description);
        JsonArray ids = new JsonArray();
        for (String id : store.consentedEvidence(session, 5000)) ids.add(id);
        if (ids.size() == 0) throw new IOException("기록에 동의한 관측이 없습니다.");
        body.add("evidence_record_ids", ids);
        JsonArray estimates = new JsonArray();
        if (positionFile.isFile()) {
            try (BufferedReader reader = Files.newBufferedReader(positionFile.toPath(), StandardCharsets.UTF_8)) {
                String line;
                while (estimates.size() < 5000 && (line = reader.readLine()) != null) {
                    JsonObject point = JsonParser.parseString(line).getAsJsonObject();
                    if (point.has("session_id") && session.equals(point.get("session_id").getAsString()))
                        estimates.add(point);
                }
            }
        }
        body.add("position_estimates", estimates);
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 4000000) throw new IOException("후보 본문이 서버 크기 제한을 초과했습니다.");
        ContentValues row = new ContentValues();
        row.put("session_id", session);
        row.put("server", server);
        row.put("body", bytes);
        row.put("state", QUEUED);
        row.put("detail", "");
        store.getWritableDatabase().insertOrThrow("submissions", null, row);
        return get(session);
    }
    private Entry state(String session, String state, String detail) {
        ContentValues changes = new ContentValues();
        changes.put("state", state);
        changes.put("detail", detail);
        store.getWritableDatabase().update("submissions", changes,
                "session_id=? AND (state<>? OR detail<>?)", new String[]{session, state, detail});
        return get(session);
    }
    private HttpURLConnection connection(String base, String path) throws IOException {
        HttpURLConnection c = (HttpURLConnection)new URL(base + path).openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(10000);
        c.setInstanceFollowRedirects(false);
        c.setUseCaches(false);
        return c;
    }
    private boolean supportsRetry(String base) throws IOException {
        HttpURLConnection c = connection(base, "/health");
        try {
            if (c.getResponseCode() != 200) throw new IOException("팀 서버 상태 확인 실패");
            try (InputStream in = c.getInputStream()) {
                return supportsIdempotency(PublicApi.readLimited(in, 32768));
            }
        } finally { c.disconnect(); }
    }
    static boolean supportsIdempotency(String response) {
        JsonObject health = JsonParser.parseString(response).getAsJsonObject();
        JsonElement feature = health.get("candidate_submission_idempotency");
        return feature != null && feature.isJsonPrimitive() &&
                feature.getAsJsonPrimitive().isBoolean() && feature.getAsBoolean();
    }
    static String confirmedCandidate(String response) throws IOException {
        JsonObject result = JsonParser.parseString(response).getAsJsonObject();
        JsonElement id = result.get("candidate_id");
        if (id == null || !id.isJsonPrimitive() || !id.getAsJsonPrimitive().isString() ||
                id.getAsString().trim().isEmpty()) throw new IOException("서버 제출 ID 누락");
        JsonElement status = result.get("status");
        if (status == null || !status.isJsonPrimitive() || !Arrays.asList("submitted", "under_review",
                "approved", "rejected", "published", "withdrawn").contains(status.getAsString()))
            throw new IOException("서버 제출 상태를 확인할 수 없습니다.");
        return id.getAsString();
    }
    // A request may exceed the per-row size of Android's SQLite CursorWindow.
    private void writeBody(String session, OutputStream output) throws IOException {
        SQLiteDatabase db = store.getReadableDatabase();
        int offset = 1;
        int chunk = 64 * 1024;
        while (true) {
            try (Cursor c = db.rawQuery("SELECT substr(body,?,65536) FROM submissions WHERE session_id=?",
                    new String[]{Integer.toString(offset), session})) {
                if (!c.moveToFirst()) throw new IOException("저장된 제출을 찾을 수 없습니다.");
                byte[] bytes = c.getBlob(0);
                if (bytes == null) return; // Some SQLite versions represent an empty slice as null.
                output.write(bytes);
                if (bytes.length < chunk) return;
                offset += bytes.length;
            }
        }
    }
    Entry send(Entry entry, String server, String key, boolean manual) throws IOException {
        Entry current = get(entry.session);
        if (SUBMITTED.equals(current.state) || (!manual && !QUEUED.equals(current.state))) return current;
        if (!current.server.equals(server)) throw new IOException("최초 제출 때 저장한 서버 주소를 사용하세요.");
        if (key.isEmpty()) throw new IOException("서버 APP_TOKEN을 입력하세요.");
        if (store.hasPending(current.session))
            return state(current.session, current.state, "이 세션의 대기 관측을 먼저 전송하세요.");
        if (!supportsRetry(server))
            return state(current.session, current.state, "서버의 후보 중복 방지 업데이트가 필요합니다.");
        HttpURLConnection c = connection(server, "/v1/candidates");
        try {
            c.setRequestMethod("POST");
            c.setRequestProperty("Authorization", "Bearer " + key);
            c.setRequestProperty("Content-Type", "application/json");
            c.setDoOutput(true);
            // A crash or timeout after this write requires an explicit retry with the saved body.
            state(current.session, UNKNOWN, "응답 미확인 · 저장한 후보에서 재전송할 수 있습니다.");
            try (OutputStream out = c.getOutputStream()) { writeBody(current.session, out); }
            int status = c.getResponseCode();
            if (status == 200) {
                try (InputStream in = c.getInputStream()) {
                    String id = confirmedCandidate(PublicApi.readLimited(in, 32768));
                    return state(current.session, SUBMITTED, "서버 제출 ID · " + id);
                }
            }
            if (status >= 400 && status < 500) {
                try (InputStream in = c.getErrorStream()) {
                    String reason = in == null ? "" : PublicApi.readLimited(in, 8192);
                    return state(current.session, REJECTED, "HTTP " + status + " · " + reason);
                }
            }
            return state(current.session, UNKNOWN, "서버 응답 HTTP " + status);
        } finally { c.disconnect(); }
    }
}
