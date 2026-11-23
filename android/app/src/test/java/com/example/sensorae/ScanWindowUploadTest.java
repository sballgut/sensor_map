package com.example.sensorae;

import static org.junit.Assert.assertEquals;
import com.google.gson.JsonParseException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

public class ScanWindowUploadTest {
    @Test public void onlyUnambiguousWindowAcksFromThisBatchAreApplied() {
        Set<String> sent = new HashSet<>(Arrays.asList("a", "b", "c", "d"));
        String reply = "{\"batch_id\":\"batch\",\"results\":[" +
                "{\"window_id\":\"a\",\"status\":\"duplicate\"}," +
                "{\"window_id\":\"b\",\"status\":\"stored\"}," +
                "{\"window_id\":\"b\",\"status\":\"error\"}," +
                "{\"record_id\":\"c\",\"status\":\"stored\"}," +
                "{\"window_id\":\"d\",\"status\":\"error\"}," +
                "{\"window_id\":\"another\",\"status\":\"stored\"}]}";
        assertEquals(Collections.singleton("a"),
                BatchUploader.acknowledgedIds("batch", sent, reply, BatchUploader.Endpoint.WINDOWS));
    }

    @Test(expected = JsonParseException.class)
    public void staleWindowAckCannotClearPendingFlags() {
        BatchUploader.acknowledgedIds("new", Collections.singleton("w1"),
                "{\"batch_id\":\"old\",\"results\":[]}", BatchUploader.Endpoint.WINDOWS);
    }
}
