package org.acme;

import java.time.Instant;

/**
 * Base class implementing the envelope overload. It unwraps the envelope and self-invokes the
 * payload overload that concrete subclasses override.
 *
 * <p>The context-aware overload is <em>not</em> declared on any interface, so it is not part of the
 * decorated type; only the interface-declared methods are decoration points.
 */
public abstract class AbstractEnvelopeHandler<T> implements EnvelopeHandler<T> {

    /**
     * Intermediate overload carrying a correlation id. Not declared on the interface.
     */
    public void process(T payload, Instant timestamp, String correlationId) {
        process(payload, timestamp);
    }

    @Override
    public void process(Envelope<T> envelope) {
        process(envelope.payload(), envelope.timestamp(), envelope.id());
    }
}
