# Quarkus Arc `@Decorator` misroutes self-invocation of a decorated interface method

Minimal, self-contained reproduction of a Quarkus Arc CDI regression involving `@Decorator`
and a bean whose base class **self-invokes** an interface method that is part of the decorated
type.

> **Upstream issue:** [quarkusio/quarkus#56608](https://github.com/quarkusio/quarkus/issues/56608)
> — this repository is the reference reproducer for it.

- **Environment:** Java 25, Maven.
- **Regression range (measured):** last good **3.34.7**, first bad **3.35.0**, still broken in
  **3.39.3** (latest 3.39). See [Version matrix](#version-matrix).
- **Two package variants:** the reproducer ships **`org.acme`** and **`org.example`**, the two
  opposite sides of the package-name sensitivity described below. See
  [the package-dependence section](#an-important-observation-the-result-depends-on-the-package-name).
- **No external dependencies** — only `quarkus-arc`, `quarkus-junit5`, `quarkus-junit5-internal`
  and `quarkus-arc-deployment` (test scope).

---

## TL;DR

When a bean is decorated by a CDI `@Decorator`, Arc must **not** apply the decorator to
self-invocations made from inside the bean. Here, a base class self-invokes
`process(T, Instant)` — a method declared on the decorated interface — and Arc routes that call
back through the decorator instead of to the concrete override:

- If the decorator **overrides** `process(T, Instant)` → infinite decorator recursion
  (`StackOverflowError`).
- If the decorator **does not override** it → the self-call lands on the interface `default`
  method (which intentionally throws) instead of the concrete override
  (`UnsupportedOperationException`).

Either way the real handler logic is unreachable through the normal entry point.

---

## The shape being reproduced

```java
// A generic handler interface. The payload overload is abstract here...
interface PayloadHandler<T> {
    void process(T payload, Instant timestamp);
    Class<T> payloadType();
}

// ...and overridden to a throwing default in the sub-interface.
interface EnvelopeHandler<T> extends PayloadHandler<T> {
    @Override
    default void process(T payload, Instant timestamp) {
        throw new UnsupportedOperationException("Use process(Envelope) instead.");
    }
    void process(Envelope<T> envelope);
}

// Base class implements the envelope overload and SELF-INVOKES the payload overload.
abstract class AbstractEnvelopeHandler<T> implements EnvelopeHandler<T> {
    public void process(T payload, Instant timestamp, String correlationId) {
        process(payload, timestamp);                 // <- self-invocation
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

Calling `handler.process(Envelope)` should run `GreetingHandler.process(payload, ts)` exactly once.

The pattern is not contrived: it is the natural way to offer a modern envelope-based entry point
while keeping the old, simpler payload signature working — and to funnel callers away from the old
one with a throwing `default`.

### Why this shape triggers it

- `process(T, Instant)` is declared on the `EnvelopeHandler` interface, so for a decorated bean it
  is part of the **decorated type** and becomes a decoration point.
- The two-level interface hierarchy (abstract in the super-interface, `default` override in the
  sub-interface) makes the compiler emit a **bridge method** on the concrete implementation.
- The base-class self-invocation of that method is then intercepted/re-routed by Arc's generated
  subclass, which is spec-incorrect: **self-invocations must not be decorated**.

---

## Two faces of the defect

### Face (a) — decorator overrides the payload method → `StackOverflowError`

`LoggingDecoratorTest` (decorator overrides `process(T, Instant)`):

```
java.lang.StackOverflowError
    at org.acme.LoggingDecorator.process(LoggingDecorator.java:39)
    at org.acme.GreetingHandler_Subclass.process(Unknown Source)
    at org.acme.GreetingHandler.process(GreetingHandler.java:9)          // generic bridge
    at org.acme.LoggingDecorator_..._Delegate_Subclass.process(Unknown Source)
    at org.acme.LoggingDecorator.process(LoggingDecorator.java:39)       // <- loop
    ...
```

### Face (b) — decorator does not override it → `UnsupportedOperationException`

`PassThroughDecoratorTest` (decorator only overrides `process(Envelope)`):

```
java.lang.UnsupportedOperationException: Use process(Envelope) instead.
    at org.acme.EnvelopeHandler.process(EnvelopeHandler.java:17)         // interface DEFAULT (throws)
    at org.acme.GreetingHandler_Subclass.process(Unknown Source)
    at org.acme.GreetingHandler.process(GreetingHandler.java:9)          // generic bridge
    at org.acme.AbstractEnvelopeHandler.process(AbstractEnvelopeHandler.java:18)
    at org.acme.AbstractEnvelopeHandler.process(AbstractEnvelopeHandler.java:23)
    at org.acme.GreetingHandler_Subclass.process$$superforward(Unknown Source)
    at org.acme.PassThroughDecorator_..._Delegate_Subclass.process(Unknown Source)
    at org.acme.PassThroughDecorator.process(PassThroughDecorator.java:35)
```

Note: face (b) is observable on **all** tested versions including 3.34.7, so it is not itself the
version regression. Only **face (a)** is the version regression (passes ≤3.34.7, fails ≥3.35.0).
Both are reported because they are two manifestations of the same routing mistake; face (b) shows
that Arc prefers a throwing interface `default` over the concrete override for the self-call.

---

## Version matrix

`LoggingDecoratorTest` (face a) across every stable Quarkus release in the range, run via
`-Dquarkus.platform.version=<v>`. Because the outcome flips with the package name, the table below
reports both variants — **`org.acme`** and **`org.example`** — so a single row shows the flip:

| Quarkus Version | org.acme | org.example |
|---|---|---|
| 3.33.0 | PASS | FAIL-StackOverflow |
| 3.33.1 | PASS | FAIL-StackOverflow |
| 3.33.2 | PASS | FAIL-StackOverflow |
| 3.33.3 | PASS | FAIL-StackOverflow |
| 3.34.0 | PASS | FAIL-StackOverflow |
| 3.34.1 | PASS | FAIL-StackOverflow |
| 3.34.2 | PASS | FAIL-StackOverflow |
| 3.34.3 | PASS | FAIL-StackOverflow |
| 3.34.4 | PASS | FAIL-StackOverflow |
| 3.34.5 | PASS | FAIL-StackOverflow |
| 3.34.6 | PASS | FAIL-StackOverflow |
| 3.34.7 | PASS | FAIL-StackOverflow |
| **3.35.0** | **FAIL-StackOverflow** | PASS |
| 3.35.1 | FAIL-StackOverflow | PASS |
| 3.35.2 | FAIL-StackOverflow | PASS |
| 3.35.3 | FAIL-StackOverflow | PASS |
| 3.35.4 | FAIL-StackOverflow | PASS |
| 3.36.0 | FAIL-StackOverflow | PASS |
| 3.36.1 | FAIL-StackOverflow | PASS |
| 3.36.2 | FAIL-StackOverflow | PASS |
| 3.36.3 | FAIL-StackOverflow | PASS |
| 3.37.0 | FAIL-StackOverflow | PASS |
| 3.37.1 | FAIL-StackOverflow | PASS |
| 3.37.2 | FAIL-StackOverflow | PASS |
| 3.37.3 | FAIL-StackOverflow | PASS |
| 3.37.4 | FAIL-StackOverflow | PASS |
| 3.38.0 | FAIL-StackOverflow | PASS |
| 3.38.1 | FAIL-StackOverflow | PASS |
| 3.38.2 | FAIL-StackOverflow | PASS |
| 3.38.3 | FAIL-StackOverflow | PASS |
| 3.39.0 | FAIL-StackOverflow | PASS |
| 3.39.1 | FAIL-StackOverflow | PASS |
| 3.39.2 | FAIL-StackOverflow | PASS |
| 3.39.3 | FAIL-StackOverflow | PASS |

Raw machine-generated results live in [`results/version-matrix.md`](results/version-matrix.md),
produced by [`run-matrix.sh`](run-matrix.sh).

**Last good: 3.34.7. First bad: 3.35.0. Broken through 3.39.3.**

For `org.acme` — the package the issue was originally reported with — the regression is clean:
last good 3.34.7, first bad 3.35.0. `org.example` is the mirror image (already broken at 3.34.7,
already fixed at 3.35.0). The two columns together prove the result is order/hash-driven and
narrow the cause to the 3.34.x → 3.35.0 window (Arc/CDI changes).

---

## An important observation: the result depends on the package name

While simplifying the classes, the outcome was found to **flip depending only on the package
name** — with byte-for-byte identical source apart from the `package` declaration. Examples
(all at 3.34.7 / 3.39.3, face (a)):

| Package | 3.34.7 | 3.39.3 |
|---|---|---|
| `org.acme` | PASS | StackOverflow |
| `io.example.decorator` | PASS | StackOverflow |
| `example.app` | PASS | StackOverflow |
| `org.example` | StackOverflow | PASS |
| `decorator` | StackOverflow | PASS |
| `com.example.arcdecorator` | StackOverflow | PASS |

This is deterministic (repeated runs give the same result per package), but it shows Arc's
generated dispatch is sensitive to something derived from the class/package names — most likely
the ordering or hashing of generated method/bean identifiers. This project uses **`org.acme`**,
which reproduces the clean regression above.

To make the flip directly observable in the repository, the whole reproducer is **duplicated under
`org.example`** (the opposite side of the flip). Running both variants side by side shows that the
two packages disagree on the same Quarkus version, which is exactly the naming sensitivity:

| Build | `org.acme` (face a) | `org.example` (face a) |
|---|---|---|
| 3.34.7 (last good for `org.acme`) | PASS | FAIL-StackOverflow |
| 3.39.3 (broken for `org.acme`) | FAIL-StackOverflow | PASS |

So the reproducer no longer depends on a lucky package name: it contains one package that fails and
one that passes on any given version, on both sides of the regression window.

For a maintainer this is a strong hint: the bug is in how Arc selects which implementation a
self-invocation should resolve to, and that selection can be perturbed by naming. It also means
any reduction/fix must be validated across several package names, not just one.

---

## Project layout

```
src/main/java/org/acme/          # side A of the package flip (PASS on 3.34.7, FAIL on 3.39.3)
  Envelope.java                  // generic message envelope (payload + metadata)
  PayloadHandler.java            // super-interface: abstract process(T, Instant) + payloadType()
  EnvelopeHandler.java           // sub-interface: throwing default process(T, Instant) + process(Envelope)
  AbstractEnvelopeHandler.java   // base class: self-invokes process(T, Instant)
  GreetingHandler.java           // @ApplicationScoped concrete handler (real logic)
  LoggingDecorator.java          // @Decorator that overrides BOTH interface methods   -> face (a)
  PassThroughDecorator.java      // @Decorator that overrides ONLY process(Envelope)  -> face (b)

src/main/java/org/example/       # side B: identical sources, opposite flip (FAIL on 3.34.7, PASS on 3.39.3)

src/test/java/org/acme/
  TestEnvelope.java
  LoggingDecoratorTest.java      // expects the self-invocation to reach GreetingHandler
  PassThroughDecoratorTest.java  // same expectation, different decorator shape

src/test/java/org/example/       # same two tests under the second package

run-matrix.sh                    // runs both packages across the version range -> results/version-matrix.md
results/version-matrix.md        // generated Quarkus Version x package data table
```

Each test runs in its own `QuarkusUnitTest` archive so the two decorators do not interfere.

## Running

```bash
# Regression version for org.acme (face a fails with StackOverflowError)
mvn clean -Dquarkus.platform.version=3.39.3 -Dtest='org.acme.LoggingDecoratorTest' test

# Last known-good version for org.acme (passes)
mvn clean -Dquarkus.platform.version=3.34.7 -Dtest='org.acme.LoggingDecoratorTest' test

# Both faces, both packages
mvn clean -Dquarkus.platform.version=3.39.3 -Dtest='org.acme.*Test,org.example.*Test' test

# Full version x package matrix -> results/version-matrix.md
./run-matrix.sh
```

Java 25. `quarkus-junit5-internal` provides `QuarkusUnitTest`; `quarkus-arc-deployment` is needed
as a test dependency for Arc to process the test archive.

`run-matrix.sh` accepts an optional inclusive version range, e.g.
`./run-matrix.sh 3.34.7 3.39.3`, and writes a markdown data table with one column per package.

## Reporting upstream

- Target: `github.com/quarkusio/quarkus`, "Bug report" template, label `area/arc`.
- Suggested title:
  `Arc: @Decorator misroutes self-invocation of decorated interface method (StackOverflowError; regression since 3.35.0)`
- Search for duplicates first: `area/arc decorator delegate self-invocation`,
  `decorator generic bridge default method`.
- Include: this project, both stack traces, the version table (last good 3.34.7 / first bad 3.35.0),
  and the package-name-sensitivity observation (both `org.acme` and `org.example` variants, with the
  `results/version-matrix.md` table).
- Note the CDI requirement: **a decorator must not be applied to self-invocations**.
