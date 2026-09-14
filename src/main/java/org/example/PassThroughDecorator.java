package org.example;

import jakarta.annotation.Priority;
import jakarta.decorator.Decorator;
import jakarta.decorator.Delegate;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Any;
import jakarta.inject.Inject;

/**
 * Decorator that only overrides the envelope overload and leaves the payload overload untouched.
 *
 * <p>Used by {@link PassThroughDecoratorTest}. On current Quarkus the self-invocation is routed to
 * the interface {@code default} (which throws), rather than to the concrete override.
 */
@Decorator
@Priority(1)
@Dependent
public class PassThroughDecorator<T> implements EnvelopeHandler<T> {

    private final EnvelopeHandler<T> delegate;

    @Inject
    public PassThroughDecorator(@Delegate @Any EnvelopeHandler<T> delegate) {
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
}
