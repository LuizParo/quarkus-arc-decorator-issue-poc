package org.acme;

import java.time.Instant;

/**
 * Extends {@link PayloadHandler} and turns the payload overload into a {@code default} method that
 * is deliberately unsupported, steering callers to the envelope overload.
 *
 * <p>The two-level interface hierarchy (abstract method in the super-interface, {@code default}
 * override in the sub-interface) is a common pattern. It makes the compiler emit a bridge method on
 * concrete implementations, which is part of the trigger for the Arc defect.
 */
public interface EnvelopeHandler<T> extends PayloadHandler<T> {

    @Override
    default void process(T payload, Instant timestamp) {
        throw new UnsupportedOperationException("Use process(Envelope) instead.");
    }

    void process(Envelope<T> envelope);
}
