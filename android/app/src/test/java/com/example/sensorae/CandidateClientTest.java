package com.example.sensorae;

import java.io.IOException;
import org.junit.Test;
import static org.junit.Assert.*;

public class CandidateClientTest {
    @Test public void healthRequiresExplicitBooleanBeforeRetryingAStoredRequest() {
        assertFalse(CandidateClient.supportsIdempotency("{\"status\":\"ok\"}"));
        assertFalse(CandidateClient.supportsIdempotency("{\"candidate_submission_idempotency\":\"true\"}"));
        assertTrue(CandidateClient.supportsIdempotency("{\"candidate_submission_idempotency\":true}"));
    }
    @Test public void replayOfReviewedCandidateIsAnAcknowledgement() throws Exception {
        assertEquals("c-1", CandidateClient.confirmedCandidate("{\"candidate_id\":\"c-1\",\"status\":\"approved\"}"));
    }
    @Test public void ambiguousResponsesNeverAcknowledge() {
        for (String invalid : new String[]{"{}", "{\"candidate_id\":true,\"status\":\"submitted\"}",
                "{\"candidate_id\":\"c-1\"}", "{\"candidate_id\":\"c-1\",\"status\":\"error\"}"}) {
            assertThrows(IOException.class, () -> CandidateClient.confirmedCandidate(invalid));
        }
    }
}
