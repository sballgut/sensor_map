package com.example.sensorae;

import android.content.Context;
import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.*;

/** Read-only public map API. These are installed-sensor snapshots, NOT local RSSI observations. */
public final class PublicApi {
    public static final String MAP_URL = "http://203.255.81.72:10021/sensor/api/map";
    private final File cache;
    private final Gson gson = new GsonBuilder().serializeNulls().create();
    public PublicApi(Context context) { cache = new File(context.getFilesDir(), "public_map.json"); }
    public static final class SensorVariant {
        public String mode, time;
        public boolean fresh;
        public Double latitude, longitude, temperature;
        public Integer co2;
    }
    public static final class SensorSnapshot {
        public String sensor_id, mode, time;
        public Double latitude, longitude, temperature;
        public Integer co2;
        public boolean fresh;
        public List<SensorVariant> variants = new ArrayList<>();
    }
    public List<SensorSnapshot> refreshMap() throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(MAP_URL).openConnection();
        connection.setConnectTimeout(15_000); connection.setReadTimeout(20_000);
        connection.setInstanceFollowRedirects(false); connection.setRequestMethod("GET");
        try {
            if (connection.getResponseCode() != 200) throw new IOException("Public map HTTP " + connection.getResponseCode());
            List<SensorSnapshot> values;
            try (InputStream stream = connection.getInputStream()) { values = parseMap(readLimited(stream, 4 * 1024 * 1024)); }
            if (values.isEmpty()) throw new IOException("No recognized map sensors; previous cache retained");
            File temp = new File(cache.getParentFile(), cache.getName() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(temp)) { out.write(gson.toJson(values).getBytes(StandardCharsets.UTF_8)); out.getFD().sync(); }
            Files.move(temp.toPath(), cache.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return values;
        } catch (JsonParseException | IllegalStateException error) { throw new IOException("Invalid public map response", error); }
        finally { connection.disconnect(); }
    }
    public List<SensorSnapshot> cachedMap() {
        if (!cache.isFile()) return Collections.emptyList();
        try (FileInputStream input = new FileInputStream(cache)) {
            List<SensorSnapshot> result = gson.fromJson(readLimited(input, 4 * 1024 * 1024), new TypeToken<List<SensorSnapshot>>(){}.getType());
            return result == null ? Collections.emptyList() : result;
        } catch (IOException | RuntimeException error) { return Collections.emptyList(); }
    }
    public static List<SensorSnapshot> parseMap(String json) {
        JsonElement parsed = JsonParser.parseString(json);
        if (!parsed.isJsonArray()) throw new JsonParseException("Expected map array");
        Map<String, SensorSnapshot> grouped = new TreeMap<>();
        for (JsonElement item : parsed.getAsJsonArray()) {
            if (!item.isJsonObject()) continue;
            JsonObject row = item.getAsJsonObject(); String id = SensorIdentity.normalize(string(row, "sensor"));
            if (id == null) continue;
            SensorVariant variant = new SensorVariant();
            variant.mode = string(row, "mode"); variant.time = string(row, "time");
            variant.fresh = row.has("fresh") && row.get("fresh").isJsonPrimitive() && row.get("fresh").getAsJsonPrimitive().isBoolean() && row.get("fresh").getAsBoolean();
            variant.latitude = number(row, "latitude"); variant.longitude = number(row, "longitude");
            variant.temperature = number(row, "temperature"); Double co2 = number(row, "co2");
            if (co2 != null && co2 >= 0 && co2 <= Integer.MAX_VALUE && co2 == Math.floor(co2)) variant.co2 = co2.intValue();
            if (variant.latitude == null || variant.longitude == null || Math.abs(variant.latitude) > 90 || Math.abs(variant.longitude) > 180) { variant.latitude = null; variant.longitude = null; }
            SensorSnapshot current = grouped.get(id);
            if (current == null) { current = new SensorSnapshot(); current.sensor_id = id; grouped.put(id, current); }
            boolean preferred = current.variants.isEmpty() || (variant.fresh && !current.fresh)
                    || variant.fresh == current.fresh && parseTime(variant.time) > parseTime(current.time);
            current.variants.add(variant);
            if (preferred) {
                current.mode = variant.mode; current.time = variant.time; current.fresh = variant.fresh;
                current.temperature = variant.temperature; current.co2 = variant.co2;
                if (variant.latitude != null) { current.latitude = variant.latitude; current.longitude = variant.longitude; }
            }
            if (current.latitude == null && variant.latitude != null) { current.latitude = variant.latitude; current.longitude = variant.longitude; }
        }
        return new ArrayList<>(grouped.values());
    }
    private static String string(JsonObject row, String key) {
        JsonElement value = row.get(key); return value == null || !value.isJsonPrimitive() ? null : value.getAsString();
    }
    private static Double number(JsonObject row, String key) {
        try { JsonElement value = row.get(key); if (value == null || value.isJsonNull()) return null; double number = value.getAsDouble(); return Double.isFinite(number) ? number : null; }
        catch (RuntimeException error) { return null; }
    }
    private static long parseTime(String text) {
        if (text == null) return Long.MIN_VALUE;
        for (String pattern : new String[]{"EEE MMM dd HH:mm:ss yyyy", "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd'T'HH:mm:ssXXX"}) {
            SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.US); format.setTimeZone(TimeZone.getTimeZone("Asia/Seoul")); format.setLenient(false);
            ParsePosition position = new ParsePosition(0); Date date = format.parse(text, position);
            if (date != null && position.getIndex() == text.length()) return date.getTime();
        }
        return Long.MIN_VALUE;
    }
    static String readLimited(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int count;
        while ((count = input.read(buffer)) != -1) { if (output.size() + count > limit) throw new IOException("Response too large"); output.write(buffer, 0, count); }
        return output.toString(StandardCharsets.UTF_8.name());
    }
}
