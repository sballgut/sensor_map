package com.example.sensorae;

import android.content.Context;
import android.util.AtomicFile;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Immutable verified release directories; an AtomicFile pointer is the activation boundary. */
public final class ReleaseInstaller {
    public static final long MAX_ASSET_BYTES = 20L * 1024 * 1024;
    private static final int MAX_MANIFEST_BYTES = 256 * 1024;
    private static final Set<String> REQUIRED = Collections.unmodifiableSet(new HashSet<>(
            Arrays.asList("map", "graph", "destinations", "model")));
    private final File root;
    private final AtomicFile pointer;
    private final Gson gson = new Gson();
    private boolean pinned;
    private String guidanceVersion;

    public ReleaseInstaller(Context context) {
        root = new File(context.getApplicationContext().getFilesDir(), "releases");
        pointer = new AtomicFile(new File(root, "active.json"));
    }

    /** Run off the UI thread. Throws rather than replacing a release while guidance is active. */
    public String install(String baseUrl) throws IOException {
        synchronized (this) { ensureUnpinned(); }
        URI base = parseBase(baseUrl);
        URI manifestUri = base.resolve("v1/releases/current");
        byte[] bytes = fetchSmall(manifestUri, MAX_MANIFEST_BYTES);
        JsonObject manifest = parseObject(bytes);
        validateManifest(manifest);
        String version = manifest.get("version").getAsString();
        synchronized (this) {
            ensureUnpinned();
            if (!root.isDirectory() && !root.mkdirs()) throw new IOException("Cannot create release directory");
        }
        File staging = new File(root, "staging-" + UUID.randomUUID());
        if (!staging.mkdir()) throw new IOException("Cannot create staging directory");
        try {
            for (JsonElement item : manifest.getAsJsonArray("assets")) {
                JsonObject asset = item.getAsJsonObject();
                URI uri = resolveAsset(base, asset.get("url").getAsString());
                File destination = new File(staging, asset.get("name").getAsString());
                download(uri, destination, asset.get("size").getAsLong(), asset.get("sha256").getAsString());
            }
            writeAndSync(new File(staging, "manifest.json"), bytes);
            synchronized (this) {
                // A guidance session may have started while the network request was in progress.
                ensureUnpinned();
                File installed = new File(root, version);
                if (installed.exists()) {
                    JsonObject oldManifest = readManifest(installed);
                    if (!oldManifest.equals(manifest)) throw new IOException("Version already exists with different manifest");
                    verifyInstalled(installed, oldManifest);
                } else if (!staging.renameTo(installed)) {
                    throw new IOException("Cannot move completed release into place");
                }
                Pointer old = readPointer();
                if (!version.equals(old.current)) writePointer(new Pointer(version, old.current));
                return version;
            }
        } finally {
            deleteStaging(staging);
        }
    }

    public synchronized String currentVersion() {
        return pinned ? guidanceVersion : readPointer().current;
    }
    public synchronized void pinForGuidance() {
        if (!pinned) { guidanceVersion = readPointer().current; pinned = true; }
    }
    public synchronized void unpin() { pinned = false; guidanceVersion = null; }
    public synchronized String pinnedVersion() { return pinned ? guidanceVersion : null; }

    public synchronized String rollback() throws IOException {
        ensureUnpinned();
        Pointer old = readPointer();
        if (!safeVersion(old.previous)) throw new IOException("No previous release available");
        File previous = new File(root, old.previous);
        JsonObject manifest = readManifest(previous);
        if (!old.previous.equals(manifest.get("version").getAsString())) throw new IOException("Release version mismatch");
        verifyInstalled(previous, manifest);
        writePointer(new Pointer(old.previous, old.current));
        return old.previous;
    }

    /** B/C/D read this directory only; they must never mutate an installed asset. */
    public synchronized File activeDirectory() {
        String version = currentVersion();
        return safeVersion(version) ? new File(root, version) : null;
    }

    private void ensureUnpinned() throws IOException {
        if (pinned) throw new IOException("안내 중에는 버전을 교체할 수 없습니다. 안내 종료 후 다시 시도하세요.");
    }
    public static boolean safeVersion(String value) {
        return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
                && !value.contains("..") && !value.endsWith(".");
    }
    public static void validateManifest(JsonObject manifest) throws IOException {
        try {
            if (!safeVersion(manifest.get("version").getAsString())) throw new IOException("Unsafe release version");
            if (!"1".equals(manifest.get("compatibility").getAsString())) throw new IOException("Incompatible release");
            JsonArray assets = manifest.getAsJsonArray("assets");
            if (assets == null || assets.size() != REQUIRED.size()) throw new IOException("Four assets are required");
            Set<String> names = new HashSet<>(); long total = 0;
            for (JsonElement item : assets) {
                JsonObject asset = item.getAsJsonObject(); String name = asset.get("name").getAsString();
                if (!REQUIRED.contains(name) || !names.add(name)) throw new IOException("Unknown or duplicate asset");
                String sizeText = asset.get("size").getAsString();
                if (!sizeText.matches("[0-9]+")) throw new IOException("Invalid asset size");
                long size = Long.parseLong(sizeText);
                if (size <= 0 || size > MAX_ASSET_BYTES) throw new IOException("Asset exceeds size limit");
                total += size;
                if (!asset.get("sha256").getAsString().matches("[A-Fa-f0-9]{64}")) throw new IOException("Invalid SHA-256");
                if (asset.get("url").getAsString().isEmpty()) throw new IOException("Missing asset URL");
            }
            if (total > 4 * MAX_ASSET_BYTES || !names.equals(REQUIRED)) throw new IOException("Incomplete release");
        } catch (RuntimeException error) {
            throw new IOException("Invalid release manifest", error);
        }
    }
    private static URI parseBase(String value) throws IOException {
        try {
            URI uri = new URI(value.endsWith("/") ? value : value + "/");
            if (!Arrays.asList("http", "https").contains(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IOException("Invalid server address");
            }
            return uri;
        } catch (URISyntaxException | NullPointerException error) { throw new IOException("Invalid server address", error); }
    }
    public static URI resolveAsset(URI base, String value) throws IOException {
        try {
            URI uri = base.resolve(new URI(value));
            if (!base.getScheme().equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || !base.getHost().equalsIgnoreCase(uri.getHost()) || port(base) != port(uri)
                    || uri.getUserInfo() != null || uri.getFragment() != null) {
                throw new IOException("Asset URL must use the configured server origin");
            }
            return uri;
        } catch (IllegalArgumentException | URISyntaxException error) { throw new IOException("Invalid asset URL", error); }
    }
    private static int port(URI uri) { return uri.getPort() >= 0 ? uri.getPort() : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80; }
    private static HttpURLConnection connect(URI uri) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setConnectTimeout(15_000); connection.setReadTimeout(30_000);
        connection.setInstanceFollowRedirects(false); connection.setRequestMethod("GET");
        if (connection.getResponseCode() != 200) { connection.disconnect(); throw new IOException("Release download failed"); }
        return connection;
    }
    private static byte[] fetchSmall(URI uri, int limit) throws IOException {
        HttpURLConnection connection = connect(uri);
        try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > limit) throw new IOException("Manifest exceeds size limit");
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        } finally { connection.disconnect(); }
    }
    private static void download(URI uri, File file, long size, String expected) throws IOException {
        HttpURLConnection connection = connect(uri); MessageDigest digest = sha256(); long total = 0;
        try (InputStream input = connection.getInputStream(); FileOutputStream output = new FileOutputStream(file)) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > size || total > MAX_ASSET_BYTES) throw new IOException("Asset is larger than manifest");
                digest.update(buffer, 0, count); output.write(buffer, 0, count);
            }
            if (total != size || !hex(digest.digest()).equalsIgnoreCase(expected)) throw new IOException("Asset size/hash verification failed");
            output.getFD().sync();
        } finally { connection.disconnect(); }
    }
    private static void verifyInstalled(File directory, JsonObject manifest) throws IOException {
        validateManifest(manifest);
        for (JsonElement element : manifest.getAsJsonArray("assets")) {
            JsonObject asset = element.getAsJsonObject(); File file = new File(directory, asset.get("name").getAsString());
            if (!file.isFile() || file.length() != asset.get("size").getAsLong()) throw new IOException("Installed asset size mismatch");
            MessageDigest digest = sha256();
            try (InputStream input = new FileInputStream(file)) {
                byte[] buffer = new byte[8192]; int count;
                while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
            }
            if (!hex(digest.digest()).equalsIgnoreCase(asset.get("sha256").getAsString())) throw new IOException("Installed asset hash mismatch");
        }
    }
    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder(); for (byte value : bytes) text.append(String.format(Locale.US, "%02x", value & 255)); return text.toString();
    }
    private static JsonObject parseObject(byte[] bytes) throws IOException {
        try { return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject(); }
        catch (RuntimeException error) { throw new IOException("Invalid manifest JSON", error); }
    }
    private static JsonObject readManifest(File directory) throws IOException {
        File file = new File(directory, "manifest.json");
        if (!file.isFile() || file.length() > MAX_MANIFEST_BYTES) throw new IOException("Missing installed manifest");
        try (InputStream input = new FileInputStream(file); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return parseObject(output.toByteArray());
        }
    }
    private synchronized Pointer readPointer() {
        try (InputStream input = pointer.openRead(); InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            Pointer state = gson.fromJson(reader, Pointer.class);
            if (state == null || !safeVersion(state.current)) return new Pointer(null, null);
            if (state.previous != null && !safeVersion(state.previous)) state.previous = null;
            return state;
        } catch (IOException | RuntimeException error) { return new Pointer(null, null); }
    }
    private void writePointer(Pointer state) throws IOException {
        FileOutputStream output = null;
        try {
            output = pointer.startWrite(); output.write(gson.toJson(state).getBytes(StandardCharsets.UTF_8));
            pointer.finishWrite(output);
        } catch (IOException | RuntimeException error) {
            if (output != null) pointer.failWrite(output);
            throw new IOException("Could not activate verified release", error);
        }
    }
    private static void writeAndSync(File file, byte[] data) throws IOException {
        try (FileOutputStream output = new FileOutputStream(file)) { output.write(data); output.getFD().sync(); }
    }
    private static void deleteStaging(File staging) {
        // Called only for our UUID staging directory. Installed directories are never deleted here.
        File[] files = staging.listFiles();
        if (files != null) for (File file : files) if (file.isFile()) file.delete();
        staging.delete();
    }
    private static final class Pointer {
        String current, previous;
        Pointer(String current, String previous) { this.current = current; this.previous = previous; }
    }
}
