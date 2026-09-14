package org.example;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.QuarkusUnitTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Face (a): the decorator overrides the payload overload {@code process(T, Instant)}, which is part
 * of the decorated type and is self-invoked by {@link AbstractEnvelopeHandler#process(Envelope)}.
 *
 * <p>Expected (correct CDI): the self-invocation reaches {@link GreetingHandler#process}.
 * <p>Observed: the result flips with the package name (see README). This {@code org.example}
 * variant is the opposite of {@code org.acme}: it fails with {@code StackOverflowError} on 3.34.7
 * and earlier and already passes from 3.35.0 on. Kept next to {@code org.acme} so a single project
 * covers both a failing and a passing package.
 */
public class LoggingDecoratorTest {

    @RegisterExtension
    static final QuarkusUnitTest unitTest = new QuarkusUnitTest()
            .withApplicationRoot(jar -> jar.addClasses(GreetingHandler.class, LoggingDecorator.class, TestEnvelope.class));

    @Inject
    EnvelopeHandler<String> handler;

    @BeforeEach
    void reset() {
        GreetingHandler.invoked = false;
    }

    @Test
    void selfInvocationReachesConcreteHandler() {
        Throwable thrown = null;
        try {
            handler.process(TestEnvelope.of("hello"));
        } catch (Throwable t) {
            thrown = t;
            t.printStackTrace(System.out);
        }
        assertTrue(
                thrown == null && GreetingHandler.invoked,
                "self-invocation of process(T, Instant) was misrouted (thrown=" + thrown + ", invoked="
                        + GreetingHandler.invoked + ")");
    }
}
