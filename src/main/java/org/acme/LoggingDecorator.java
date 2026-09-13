package org.acme;

import jakarta.annotation.Priority;
import jakarta.decorator.Decorator;
import jakarta.decorator.Delegate;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Any;
import jakarta.inject.Inject;
import java.time.Instant;

/**
 * Decorator that overrides <em>both</em> interface methods, including the payload overload
 * {@code process(T, Instant)} that {@link AbstractEnvelopeHandler} self-invokes.
 */
@Decorator
@Priority(1)
@Dependent
public class LoggingDecorator<T> implements EnvelopeHandler<T> {

    private final EnvelopeHandler<T> delegate;

    @Inject
    public LoggingDecorator(@Delegate @Any EnvelopeHandler<T> delegate) {
        this.delegate = delegate;
    }

    @Override
    public Class<T> payloadType() {
        return delegate.payloadType();
    }

    @Override
    public void process(Envelope<T> envelope) {
        delegate.process(envelope);
    }

    @Override
    public void process(T payload, Instant timestamp) {
        delegate.process(payload, timestamp);
    }
}
