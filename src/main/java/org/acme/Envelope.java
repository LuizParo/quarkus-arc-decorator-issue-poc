package org.acme;

import java.time.Instant;

/**
 * A simple message envelope carrying a payload plus some metadata.
 */
public interface Envelope<T> {

    T payload();

    Instant timestamp();

    String id();
}
