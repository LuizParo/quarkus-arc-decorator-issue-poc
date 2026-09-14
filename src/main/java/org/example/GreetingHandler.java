package org.example;

import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;

/**
 * Concrete handler with the real (dummy) logic. It only overrides the payload overload.
 */
@ApplicationScoped
public class GreetingHandler extends AbstractEnvelopeHandler<String> {

    public static volatile boolean invoked;

    @Override
    public Class<String> payloadType() {
        return String.class;
    }

    @Override
    public void process(String payload, Instant timestamp) {
        invoked = true;
    }
}
