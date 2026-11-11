# AGENTS.md — Data-Quality Rule Engine

Guide for AI coding assistants (Claude Code, Copilot, Codex, Junie, …) working in this repo.
The source of truth for scope is `REQUIREMENTS.md`. It is **local-only and git-ignored**: never commit it or copy it
verbatim into committed files. Design rationale lives in `DESIGN.md` and `docs/adr/`.
The work plan and current status are in `docs/PLAN.md`; read it before you start and update it when you finish a step.

## What this project is

A **reusable Java library** that validates business-partner records (JSON objects) against a catalog of
data-quality rules. Several hosts embed it: an HTTP API, batch workers and scheduled jobs. It is a take-home
task, so it is judged on **design quality, correctness, fault isolation, streaming / bounded memory,
extensibility and written reasoning**, not on how many features it has.
A smaller slice done well is better than a broad, shaky one.

## Tech stack (decided, see ADRs)

| Concern        | Choice                                                            |
|----------------|-------------------------------------------------------------------|
| Language       | Java 25 (Gradle toolchain), ADR-0001                              |
| Build          | Gradle 9.x wrapper, Groovy DSL, multi-module, ADR-0002            |
| JSON           | Jackson (streaming `JsonParser` for input), **only outside core** |
| HTTP example   | JDK `com.sun.net.httpserver.HttpServer`, no web framework         |
| Tests          | JUnit 5 + AssertJ                                                 |
| Coverage       | JaCoCo                                                            |
| Performance    | JMH benchmarks + a bounded-heap 1M-record test                    |
| Base package   | `io.github.radeklesniewski.dq`                                    |

## Module layout (target)

```
engine-core/    # The library. ZERO runtime dependencies. Public API + internal implementation.
engine-json/    # Jackson adapters: streaming record reader, JSON rule-catalog loader, result writer.
example-http/   # Demo host: JDK HttpServer exposing POST /validate. Depends on core + json.
```

Packages in `engine-core`:
- `io.github.radeklesniewski.dq` — public API: `ValidationEngine`, `RunOptions`, `RunSummary`, `OutcomeSink`,
  sealed `Outcome` (`Result` | `RuleError` | `RecordError`), `Provenance`, `FieldRead`, `CollectingSink`.
- `io.github.radeklesniewski.dq.record` — `DataRecord` and `RecordSource` (host SPIs), `RecordView` (what rule logic
  reads), field exceptions, `RecordReadException`.
- `io.github.radeklesniewski.dq.rule` — rule model: `Rule`, `RuleLogic`, `DecisionMapping`, `CountryScope`,
  `RuleCatalog` (SPI), metadata enums.
- `io.github.radeklesniewski.dq.select` — `RuleFilter`: composable rule selection (status, country, category, custom).
- `io.github.radeklesniewski.dq.internal` — implementation. **Not API**: `module-info.java` does not export it.

## Commands

```bash
./gradlew build                       # compile + test + checks, all modules
./gradlew test                        # unit tests
./gradlew :engine-core:test --tests '*FixtureScenarioTest'   # a single test class
./gradlew jacocoTestReport            # coverage → */build/reports/jacoco/
./gradlew :engine-core:memoryTest     # 1M records, every execution mode, under -Xmx64m (tag 'memory', not part of `test`)
./gradlew :engine-core:jmh            # JMH throughput benchmarks, ~2 min (see docs/BENCHMARKS.md)
./gradlew :example-http:run           # [from M6] start the demo API (port 8080)
```

The build needs JDK 25. If `java` is not on PATH, set `JAVA_HOME` (locally `~/.jdks/openjdk-25.0.1`).

Build layout: shared config is in the `buildSrc` convention plugins (`dq.java-conventions` for every module,
`dq.library-conventions` for published libraries). All versions live in `gradle/libs.versions.toml`, so don't
hard-code versions in module build files. Configuration cache and build cache are on (`gradle.properties`).

## Architectural invariants (do not break these)

1. **Decisions are data.** Rule logic computes a *value* (e.g. `"ok"`, `"bad"`, `"blocked"`). A
   `DecisionMapping` (value → `Decision`, plus a mandatory default) turns that value into a decision.
   Rule logic must never return or construct a `Decision`, and must never branch to pick one.
2. **Fault isolation.** Each (rule × record) evaluation is isolated. An exception becomes a `RuleError`
   carrying ruleId, recordId, the error type and message, and the fields read so far. It never aborts the
   batch or the run. Catch `Exception` (and `LinkageError`/`StackOverflowError` where justified) but let
   `VirtualMachineError` such as OOM propagate. Errors are first-class outputs, delivered alongside results.
3. **Streaming and bounded memory.** The engine never collects all results in a collection. Input is pulled
   lazily (iterator/stream/reader) in caller-configurable batches. Output is pushed to a caller-supplied
   sink as it is produced. The summary uses counters (`EnumMap`/`long`), never stored results. Memory per
   run is O(batch size + rule count), independent of total volume.
4. **RELEASED only.** Caller filters (status, country scope, category) compose freely, but the engine always
   intersects them with `status == RELEASED`. Tests must cover the case where a caller asks for DRAFT.
5. **Provenance is automatic.** Rule logic reads fields through an accessor that records each field path
   and value it read, so `Result` provenance (rule, record, fields/values read) cannot drift from the logic.
6. **Core has no dependencies.** No Jackson, no logging framework, no DI container in `engine-core`.
   Use `System.Logger` if logging is needed. Hosts supply records, catalogs and sinks through SPI interfaces.
7. **Host seams are interfaces**: record source, rule catalog source, rule-logic registry, result/error sink,
   and run listener/metrics. Keep them small, functional-interface-friendly and documented.

## Library-quality checklist (apply to every public type)

- Small public surface. Prefer `record`s and sealed interfaces. Everything else is package-private or `internal`.
- Immutable value types. Defensive copies (`List.copyOf`, `Map.copyOf`). Validate in compact constructors.
- Explicit null contract: no nulls in the API. Use `Optional` for return values and for record components that
  model absence (e.g. `FieldRead.value`), never for parameters.
- Exception messages name paths, ids and types but **never field values** (PII). Values belong only in provenance. Use `Objects.requireNonNull` with a message.
- Document thread safety in Javadoc. The engine instance is thread-safe and reusable, and a run is single-use.
- Javadoc on every public type and method, stating the contract rather than the implementation.
- Deterministic output order for a sequential run (record order, then catalog order).
- No checked exceptions leak from rule logic. Config errors (e.g. a missing default mapping, an unknown logic id)
  fail fast at catalog load time with a clear message, not mid-run.

## Domain cheat-sheet

- `Decision`: VALID, INVALID, REVIEW, NOT_APPLICABLE.
- `Severity`: ERROR, WARNING, INFO. `RuleStatus`: DRAFT, RELEASED, DEPRECATED.
- Country scope: `WORLD` or an ISO country code. A rule applies to a record when its scope is WORLD or equals
  the record's `country`. On a mismatch the rule is skipped and emits **no Result** (ADR-0007), unless the
  `emitOutOfScope` option is on.
- Missing field semantics: logic chooses. `record.get(path)` returns an Optional (absence is a normal value,
  e.g. a missing vatId gives `"bad"`). `record.require(path)` throws, which turns into a `RuleError`.

## Acceptance scenario (must always pass)

The fixture in `REQUIREMENTS.md` §5 (records r1–r3; rules countryBlocked / vatFormat / ibanFormat) is encoded
as `FixtureScenarioTest` and must produce exactly the expected decision table. A fault-isolation variant adds
one failing rule or record and asserts that the rest of the batch completes and the failure is reported.

## Testing conventions

- JUnit 5 + AssertJ. Test names describe behaviour: `releasedOnlyEvenWhenCallerSelectsDraft()`.
- Unit-test each invariant above directly. Use the fixture as the end-to-end acceptance test.
- The 1M-record test (`BoundedMemoryTest`, `@Tag("memory")`) generates records lazily (never in a list) and runs
  only in the `memoryTest` task with `-Xmx64m`. Tag other slow tests and keep them out of `test`.
- Concurrency tests must be deterministic: prove overlap with barriers, not timing. Check for leaked
  `dq-engine-run*` threads after parallel runs, and clear the interrupt flag after cancellation tests.
- Don't mock what you own. Use real small rules and in-memory sinks.

## Git workflow

- Branch `main`. **Conventional Commits**: `feat(core): …`, `fix(json): …`, `test(core): …`,
  `docs: …`, `build: …`, `refactor(http): …`. Scopes: `core`, `json`, `http`, `build`, `docs`.
- Small, focused commits that each build and pass tests. The history is part of the deliverable.
- Only commit or push when the user asks. Never rewrite pushed history.

## Documentation duties

- When a design decision is made or changed, add or update an ADR in `docs/adr/` (template: `docs/adr/README.md`).
- Keep `DESIGN.md` in sync. It is a graded deliverable: public API rationale, fault isolation, bounded memory,
  host seams, trade-offs and "what I'd do next".
- State assumptions explicitly (in the ADR or DESIGN.md) rather than silently picking one.
- Update `docs/PLAN.md` status when a milestone step is done.
