package org.example;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.QuarkusUnitTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Face (b): the decorator does <em>not</em> override the payload overload {@code process(T, Instant)}.
 *
 * <p>Expected (correct CDI): the self-invocation reaches {@link GreetingHandler#process}.
 * <p>Observed: Arc routes the self-invocation to the interface {@code default}, which throws
 * {@link UnsupportedOperationException} instead of reaching the concrete override.
 */
public class PassThroughDecoratorTest {

    @RegisterExtension
    static final QuarkusUnitTest unitTest = new QuarkusUnitTest()
            .withApplicationRoot(jar -> jar.addClasses(GreetingHandler.class, PassThroughDecorator.class, TestEnvelope.class));

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
