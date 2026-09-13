# Arc: @Decorator is applied to a self-invocation of a decorated interface method (StackOverflowError regression since 3.35.0)

A bean that extends an abstract base class which **self-invokes** a method declared on a decorated
interface is incorrectly re-decorated by Arc when a CDI `@Decorator` is applied. Per the CDI spec a
decorator intercepts every external method invocation on the decorated bean, but a self-invocation
made from inside the bean must not be decorated. Here the self-invocation is routed back through the
decorator instead of reaching the concrete override, so the real handler logic is unreachable through
the normal entry point.

Reproducer project: https://github.com/LuizParo/quarkus-arc-decorator-issue-poc (package `org.acme`, Java 25).

Regression range: worked through **3.34.7**, first broken in **3.35.0**, still broken in **3.39.3**.

---

## Describe the bug

Using a common handler pattern — an interface hierarchy where the payload method is abstract on a
super-interface and overridden as a throwing `default` on the sub-interface (to steer callers to an
envelope-based overload), plus an abstract base class that unwraps the envelope and self-invokes the
payload method — applying a `@Decorator` breaks the self-invocation.

```java
interface PayloadHandler<T> {
    void process(T payload, Instant timestamp);
    Class<T> payloadType();
}

interface EnvelopeHandler<T> extends PayloadHandler<T> {
    @Override
    default void process(T payload, Instant timestamp) {
        throw new UnsupportedOperationException("Use process(Envelope) instead.");
    }
    void process(Envelope<T> envelope);
}

abstract class AbstractEnvelopeHandler<T> implements EnvelopeHandler<T> {
    public void process(T payload, Instant timestamp, String correlationId) {
        process(payload, timestamp);                 // self-invocation of a decorated-type method
    }
    @Override
    public void process(Envelope<T> envelope) {
        process(envelope.payload(), envelope.timestamp(), envelope.id());
    }
}

@ApplicationScoped
class GreetingHandler extends AbstractEnvelopeHandler<String> {
    @Override public Class<String> payloadType() { return String.class; }
    @Override public void process(String payload, Instant timestamp) { /* real logic */ }
}

@Decorator @Priority(1) @Dependent
class LoggingDecorator<T> implements EnvelopeHandler<T> {
    @Inject LoggingDecorator(@Delegate @Any EnvelopeHandler<T> delegate) { ... }
    @Override public void process(Envelope<T> envelope) { delegate.process(envelope); }
    @Override public void process(T payload, Instant timestamp) { delegate.process(payload, timestamp); }
}
```

## Expected behavior

Calling `handler.process(envelope)` runs the concrete override `GreetingHandler.process(payload, ts)`
exactly once. The decorator is applied to the external call; the self-invocation inside
`AbstractEnvelopeHandler` reaches the concrete override directly (a self-invocation does not go
through the client proxy and must not be decorated).

## Actual behavior

Two faces, depending on whether the decorator overrides the payload method:

- **Decorator overrides `process(T, Instant)`** → the self-invocation is routed back through the
  decorator, producing infinite recursion and `StackOverflowError` (`LoggingDecoratorTest`).
- **Decorator does not override `process(T, Instant)`** → the self-invocation is routed to the
  sub-interface `default` method (which throws) instead of the concrete override, producing
  `UnsupportedOperationException` (`PassThroughDecoratorTest`).

### Face (a) — `LoggingDecoratorTest`, Quarkus 3.39.3

```
java.lang.StackOverflowError
    at org.acme.LoggingDecorator.process(LoggingDecorator.java:39)
    at org.acme.GreetingHandler_Subclass.process(Unknown Source)
    at org.acme.GreetingHandler.process(GreetingHandler.java:9)          // generic bridge
    at org.acme.LoggingDecorator_ipwFIfCfVBowV30JoNQCim6M8EI_Delegate_Subclass.process(Unknown Source)
    at org.acme.LoggingDecorator.process(LoggingDecorator.java:39)       // <- loop
    ...
```

### Face (b) — `PassThroughDecoratorTest`, Quarkus 3.39.3

```
java.lang.UnsupportedOperationException: Use process(Envelope) instead.
    at org.acme.EnvelopeHandler.process(EnvelopeHandler.java:17)         // interface default (throws)
    at org.acme.GreetingHandler_Subclass.process(Unknown Source)
    at org.acme.GreetingHandler.process(GreetingHandler.java:9)          // generic bridge
    at org.acme.AbstractEnvelopeHandler.process(AbstractEnvelopeHandler.java:18)
    at org.acme.AbstractEnvelopeHandler.process(AbstractEnvelopeHandler.java:23)
    at org.acme.GreetingHandler_Subclass.process$$superforward(Unknown Source)
    at org.acme.PassThroughDecorator_ipwFIfCfVBowV30JoNQCim6M8EI_Delegate_Subclass.process(Unknown Source)
    at org.acme.PassThroughDecorator.process(PassThroughDecorator.java:35)
```

Note: face (b) is present on every version tested, including 3.34.7, so it is not itself the version
regression. Only face (a) is the regression (passes ≤ 3.34.7, fails ≥ 3.35.0). Both are shown
because they are two manifestations of the same routing mistake: Arc treats the self-invocation as
if it were an external call.

## How to Reproduce?

Self-contained Maven project, Java 25. Dependencies are only `quarkus-arc` plus the test artifacts
`quarkus-junit5`, `quarkus-junit5-internal` and `quarkus-arc-deployment`. There is no application
code beyond the classes above. Clone it and run the commands below.

```bash
# Regression version — fails with StackOverflowError
mvn clean -Dquarkus.platform.version=3.39.3 -Dtest='LoggingDecoratorTest' test

# Last known-good version — passes
mvn clean -Dquarkus.platform.version=3.34.7 -Dtest='LoggingDecoratorTest' test

# Both faces
mvn clean -Dquarkus.platform.version=3.39.3 -Dtest='LoggingDecoratorTest,PassThroughDecoratorTest' test
```

Steps:
1. Clone the reproducer repository.
2. Run `mvn clean -Dquarkus.platform.version=3.39.3 -Dtest='LoggingDecoratorTest' test` → fails with `StackOverflowError`.
3. Run the same with `-Dquarkus.platform.version=3.34.7` → passes.

Two tests are included: `LoggingDecoratorTest` (face a) and `PassThroughDecoratorTest` (face b).
Each runs in its own `QuarkusUnitTest` archive so the two decorators do not interfere.

## Environment

- Output of `uname -a`: `Darwin CY24R1WNCY 25.6.0 Darwin Kernel Version 25.6.0 ... RELEASE_ARM64_T6030 arm64` (macOS)
- Output of `java -version`: `openjdk version "25.0.2" 2026-01-20` (GraalVM CE 25.0.2+10.1)
- Quarkus version: **3.39.3** (also broken on every release from 3.35.0 through 3.39.3)
- Build tool: Apache Maven `3.9.16` (`mvnw` is included in the reproducer)

## Additional information

### Regression range

Tested with `LoggingDecoratorTest` across every stable Quarkus release in the range:

| Quarkus | Result |
|---|---|
| 3.33.0 – 3.33.3 | pass |
| 3.34.0 – 3.34.7 | pass |
| **3.35.0** | **fail — StackOverflowError (first bad)** |
| 3.35.0 – 3.39.3 | fail (all releases tested) |

Last known good: **3.34.7**. First bad: **3.35.0**.

### The reproducer's outcome depends on the package name

With byte-for-byte identical source except the `package` declaration, I get opposite results on the
same Quarkus version. The result is deterministic across repeated runs for a given package:

| package | 3.34.7 | 3.39.3 |
|---|---|---|
| `org.acme` | pass | StackOverflow |
| `io.example.decorator` | pass | StackOverflow |
| `example.app` | pass | StackOverflow |
| `org.example` | StackOverflow | pass |
| `decorator` | StackOverflow | pass |
| `com.example.arcdecorator` | StackOverflow | pass |

The reproducer uses `org.acme`. This naming sensitivity suggests the defect is related to how Arc
selects or orders the generated implementation classes/methods (possibly tied to the ArC
reproducibility changes around 3.35). I am flagging it because it may point directly at the cause,
and any fix should be validated with more than one package name.

### Notes

- The two-level interface hierarchy (abstract method in the super-interface, `default` override in
  the sub-interface) is what produces the bridge method visible in the stack traces; I could not
  reproduce the defect without that shape.
- The intermediate overload `process(T, Instant, String)` is not declared on any interface and so is
  not part of the decorated type; the misroute happens on its self-invocation of the interface-
  declared `process(T, Instant)`.
