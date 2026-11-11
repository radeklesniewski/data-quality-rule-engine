# Implementation plan

Status legend: ☐ todo · ◐ in progress · ☑ done. Each step should end in one or more green Conventional Commits.
Priority order follows the brief: **core done well before extended features**.

## M0 — Project setup
- ☑ Rename branch `master` → `main`, untrack `.idea/`, initial commits.
- ☑ Multi-module Gradle: `engine-core`, `engine-json`, `example-http`. Java 25 toolchain (foojay resolver),
  convention plugins in `buildSrc` (`dq.java-conventions`, `dq.library-conventions`), version catalog `gradle/libs.versions.toml`.
- ☑ JUnit 5 + AssertJ + JaCoCo wired. IntelliJ `Main.java` placeholder removed.
- ◐ `module-info.java` for `engine-core` declared. Exports are added in M1 as the API packages gain types
  (javac rejects exporting a package that only has `package-info.java`).

## M1 — Core model (F1, F2)
- ☑ Enums `Decision`, `Severity`, `RuleStatus`. Sealed `CountryScope` (World | Country). Categories are plain strings.
- ☑ `DecisionMapping` (value → Decision, mandatory default via `builder()…orElse(…)`). Pure data, with tests.
- ☑ `DataRecord` SPI + `RecordView` for logic + internal `TrackingRecordView` for provenance (ADR-0008).
  Record id/country extraction is the engine's job (M2), via configurable field names.
- ☑ `Rule` (+ builder, defaults to DRAFT) + `RuleLogic` returning a `String` value.
- ☑ `Result` with `Provenance` (ruleId, recordId, fields/values read, computed value, default-applied flag).
- ☑ Internal `RuleEvaluator` (no isolation yet) + fixture decision table at evaluator level (`RuleEvaluatorTest`).
- ☑ `module-info` exports `dq`, `dq.record`, `dq.rule`. `dq.select` follows in M3.

## M2 — Engine + fault isolation (F1, F3, F4)
- ☐ `ValidationEngine` with streaming API: records in → sink out (results + errors). Returns `RunSummary`.
- ☐ Per-(rule × record) isolation → `RuleError`. Bad-record handling (e.g. a record without an id).
- ☐ `RunSummary`: counts of results/errors, by decision, by severity.
- ☐ `FixtureScenarioTest` (REQUIREMENTS §5) + fault-isolation variant.

## M3 — Selection (F6)
- ☐ Composable `RuleFilter` (and/or/not): status, country scope, category.
- ☐ Country scope applied per record (ADR-0007) + `emitOutOfScope` option.
- ☐ Engine enforces RELEASED-only regardless of filters. Tests for every filter and their combinations.

## M4 — Scale (F5)
- ☐ Configurable batch size in `RunOptions`. Lazy input (`Iterator`/`Stream`). No result accumulation.
- ☐ `memoryTest` Gradle task: 1M generated records under `-Xmx64m`.
- ☐ JMH benchmark: throughput per rule count and batch size.
- ☐ Execution modes per ADR-0006: `SEQUENTIAL` (default), then `PARALLEL_ORDERED` / `PARALLEL_UNORDERED`
  with `parallelism` + `maxInFlightBatches`. Test that summary counts are identical across modes.

## M5 — JSON adapters (`engine-json`)
- ☐ Streaming JSON array reader → `DataRecord` iterator (Jackson `JsonParser`, no full-tree load of the batch).
- ☐ JSON rule-catalog loader: metadata + decision mapping as data, logic referenced by id from a `RuleLogicRegistry`.
- ☐ Streaming result/error writer (NDJSON or JSON array).

## M6 — Example HTTP API (`example-http`)
- ☐ `POST /validate` (body: records JSON array; query: filters, batchSize). Streams the response.
- ☐ Integration test that starts the server on a random port.

## M7 — Deliverables
- ☐ `DESIGN.md`: public API, fault isolation, bounded memory, seams, trade-offs, next steps.
- ☐ `README.md`: build, test, run instructions, example curl.
- ☐ Final review of the commit history, plus `./gradlew build` green from a clean clone.

## Open questions / assumptions log
- ☑ Record id missing → record-level error, rule evaluations skipped (confirmed by user).
- ☑ Parallel vs ordered → ADR-0006 (confirmed by user).
- ☑ Country scope mismatch → rule skipped, no Result by default. Opt-in `emitOutOfScope` (ADR-0007, confirmed by user).
- ☑ The brief gives `vatFormat` / `ibanFormat` no default decision. Fixtures use `REVIEW` (an unexpected value goes to a human).
  The brief gives no severities, so fixtures use `ERROR`.
- ☑ JSON `null` = absent field; empty string = present (ADR-0008).
- ☑ `REQUIREMENTS.md` stays local (git-ignored). Don't commit it or quote it verbatim in committed files.
