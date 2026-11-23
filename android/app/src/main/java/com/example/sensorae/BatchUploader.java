package com.example.sensorae;

import com.google.gson.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Uploads to the team's E server, never the public GET /sensor/sensing/ write endpoint. */
public final class BatchUploader {
    private final String baseUrl, token;
    private final ObservationStore store;
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();

    interface Queue {
        void markAttempt(Set<String> ids);
        void acknowledge(Set<String> ids);
        int countPending();
    }

    enum Endpoint {
        OBSERVATIONS("/v1/observations/batch", "records", "record_id"),
        WINDOWS("/v1/scan-windows/batch", "windows", "window_id");

        final String path, arrayField, idField;
        Endpoint(String path, String arrayField, String idField) {
            this.path = path;
            this.arrayField = arrayField;
            this.idField = idField;
        }
    }
    public BatchUploader(String baseUrl, String token, ObservationStore store) {
        this.baseUrl = baseUrl;
        this.token = token;
        this.store = store;
    }
    public static final class Result {
        public final int acknowledged, pending;
        public final String message;
        Result(int acknowledged, int pending, String message) { this.acknowledged = acknowledged; this.pending = pending; this.message = message; }
    }
    public Result uploadOnce() {
        JsonArray rows = GSON.toJsonTree(store.pending(100)).getAsJsonArray();
        return upload(baseUrl, token, Endpoint.OBSERVATIONS, rows, store);
    }

    static Result upload(String baseUrl, String token, Endpoint endpoint, JsonArray rows, Queue queue) {
        if (rows.size() == 0) return new Result(0, 0, "전송 대기 데이터 없음");
        String address = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
        String secret = token == null ? "" : token.trim();
        if (address.isEmpty() || secret.isEmpty()) return new Result(0, queue.countPending(), "팀 서버 주소와 전송 토큰을 설정하세요.");
        HttpURLConnection connection = null;
        try {
            URI base = URI.create(address);
            if (!("http".equals(base.getScheme()) || "https".equals(base.getScheme())) || base.getHost() == null || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null) throw new IOException("Invalid team server URL");
            if ("203.255.81.72".equals(base.getHost())) throw new IOException("공용 서버에는 팀 배치 API가 없습니다. 별도 E 서버를 설정하세요.");
            String batch = UUID.randomUUID().toString(); Set<String> sent = new HashSet<>();
            for (JsonElement row : rows) sent.add(row.getAsJsonObject().get(endpoint.idField).getAsString());
            queue.markAttempt(sent);
            JsonObject body = new JsonObject(); body.addProperty("batch_id", batch); body.add(endpoint.arrayField, rows);
            connection = (HttpURLConnection) new URL(address + endpoint.path).openConnection();
            connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(15_000); connection.setReadTimeout(20_000);
            connection.setRequestMethod("POST"); connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8"); connection.setRequestProperty("Authorization", "Bearer " + secret);
            byte[] encoded = GSON.toJson(body).getBytes(StandardCharsets.UTF_8); connection.setFixedLengthStreamingMode(encoded.length);
            try (OutputStream out = connection.getOutputStream()) { out.write(encoded); }
            int status = connection.getResponseCode();
            if (status != 200) return new Result(0, queue.countPending(), "팀 서버 HTTP " + status + " · 대기열 보존");
            String reply;
            try (InputStream input = connection.getInputStream()) { reply = PublicApi.readLimited(input, 2 * 1024 * 1024); }
            Set<String> accepted = acknowledgedIds(batch, sent, reply, endpoint);
            queue.acknowledge(accepted);
            return new Result(accepted.size(), queue.countPending(), "ACK 확인 " + accepted.size() + "/" + rows.size());
        } catch (IOException | RuntimeException error) {
            return new Result(0, queue.countPending(), "전송 미확인 · " + error.getClass().getSimpleName() + " · 대기열 보존");
        } finally { if (connection != null) connection.disconnect(); }
    }
    public static Set<String> acknowledgedIds(String batchId, Set<String> sent, String reply) {
        return acknowledgedIds(batchId, sent, reply, Endpoint.OBSERVATIONS);
    }
    static Set<String> acknowledgedIds(String batchId, Set<String> sent, String reply, Endpoint endpoint) {
        JsonObject body = JsonParser.parseString(reply).getAsJsonObject();
        if (!body.has("batch_id") || !batchId.equals(body.get("batch_id").getAsString()) || !body.has("results") || !body.get("results").isJsonArray()) throw new JsonParseException("ACK batch mismatch");
        Set<String> accepted = new HashSet<>(), seen = new HashSet<>(), contradictory = new HashSet<>();
        for (JsonElement item : body.getAsJsonArray("results")) {
            if (!item.isJsonObject()) continue;
            JsonObject row = item.getAsJsonObject();
            if (!row.has(endpoint.idField) || row.get(endpoint.idField).isJsonNull() || !row.has("status")) continue;
            String id = row.get(endpoint.idField).getAsString(); if (!sent.contains(id)) continue;
            if (!seen.add(id)) contradictory.add(id);
            String status = row.get("status").getAsString();
            if ("stored".equals(status) || "duplicate".equals(status)) accepted.add(id);
        }
        accepted.removeAll(contradictory); return accepted;
    }
}
