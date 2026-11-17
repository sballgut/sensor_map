package com.example.sensorae;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.URI;
import org.junit.Test;
import static org.junit.Assert.*;

public class ReleaseInstallerTest {
    private JsonObject manifest() {
        JsonObject result = new JsonObject(); result.addProperty("version", "v1"); result.addProperty("compatibility", 1);
        JsonArray assets = new JsonArray();
        for (String name : new String[]{"map", "graph", "destinations", "model"}) {
            JsonObject asset = new JsonObject(); asset.addProperty("name", name);
            asset.addProperty("url", "/v1/assets/v1/" + name + ".json"); asset.addProperty("size", 10);
            asset.addProperty("sha256", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"); assets.add(asset);
        }
        result.add("assets", assets); return result;
    }
    @Test public void validatesCompleteRelease() throws Exception { ReleaseInstaller.validateManifest(manifest()); }
    @Test public void rejectsPathTraversalAndUnsafeVersions() {
        for (String name : new String[]{"../v1", "v1/graph", "v1..old", "v1.", "", "/x"}) assertFalse(name, ReleaseInstaller.safeVersion(name));
        assertTrue(ReleaseInstaller.safeVersion("2026-11-16_v1.0"));
    }
    @Test(expected=IOException.class) public void rejectsIncompatibleRelease() throws Exception {
        JsonObject manifest = manifest(); manifest.addProperty("compatibility", 2); ReleaseInstaller.validateManifest(manifest);
    }
    @Test(expected=IOException.class) public void rejectsMissingAsset() throws Exception {
        JsonObject manifest = manifest(); manifest.getAsJsonArray("assets").remove(0); ReleaseInstaller.validateManifest(manifest);
    }
    @Test(expected=IOException.class) public void rejectsOversizedAsset() throws Exception {
        JsonObject manifest = manifest(); manifest.getAsJsonArray("assets").get(0).getAsJsonObject().addProperty("size", ReleaseInstaller.MAX_ASSET_BYTES + 1);
        ReleaseInstaller.validateManifest(manifest);
    }
    @Test(expected=IOException.class) public void rejectsForeignOrigin() throws Exception {
        ReleaseInstaller.resolveAsset(new URI("https://example.test/"), "https://other.test/map");
    }
    @Test public void resolvesRelativeAssets() throws Exception {
        assertEquals("https://example.test/v1/assets/v1/map.json", ReleaseInstaller.resolveAsset(new URI("https://example.test/"), "/v1/assets/v1/map.json").toString());
    }
    @Test(expected=IOException.class) public void rejectsHashNotSha256() throws Exception {
        JsonObject manifest = manifest(); manifest.getAsJsonArray("assets").get(0).getAsJsonObject().addProperty("sha256", "not-a-hash");
        ReleaseInstaller.validateManifest(manifest);
    }
}
