package org.acme;

import java.time.Instant;

/**
 * Small helper to build test envelopes.
 */
public final class TestEnvelope {

    private TestEnvelope() {}

    public static Envelope<String> of(String payload) {
        return new Envelope<>() {
            @Override
            public String payload() {
                return payload;
            }

            @Override
            public Instant timestamp() {
                return Instant.EPOCH;
            }

            @Override
            public String id() {
                return "id-1";
            }
        };
    }
}
