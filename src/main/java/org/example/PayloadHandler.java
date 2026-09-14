package org.example;

import java.time.Instant;

/**
 * Top-level handler contract.
 *
 * <p>Declares the payload overload as an <em>abstract</em> method, plus a generic-returning
 * descriptor method. Both matter for the reproducer: the generic return type is what makes the
 * compiler emit a bridge method on implementations, mirroring the shape that triggers the defect.
 */
public interface PayloadHandler<T> {

    void process(T payload, Instant timestamp);

    Class<T> payloadType();
}
