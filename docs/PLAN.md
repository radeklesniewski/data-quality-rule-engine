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
- ☑ `ValidationEngine.run(RecordSource, RuleCatalog, RunOptions, OutcomeSink) → RunSummary`. Sealed
  `Outcome = Result | RuleError | RecordError`. `RuleCatalog` SPI with a once-per-run snapshot and duplicate-id check.
- ☑ Per-(rule × record) isolation → `RuleError`. Unreadable or unidentifiable records → `RecordError` (ADR-0005).
- ☑ `RunSummary`: records read, batches, results, rule and record errors, by decision, by severity.
- ☑ RELEASED-only and per-record country scope enforced by the engine (caller filters follow in M3).
- ☑ `FixtureScenarioTest`: decision table, summary, and a fault-isolation variant (throwing rule, wrongly typed
  field, record without an id, unreadable record).

## M3 — Selection (F6)
- ☑ Composable `RuleFilter` (and/or/negate, allOf/anyOf): status, country, category, custom (ADR-0009).
- ☑ Country scope applied per record (ADR-0007) + `emitOutOfScope` option (`Provenance.Basis.OUT_OF_SCOPE`).
- ☑ Engine enforces RELEASED-only after the caller's filter. Tested with filters that select DRAFT, every status,
  and negations.
- ☑ Tests for every filter and combination (`RuleFilterTest`, `RuleSelectionEngineTest`). `RunSummary.rulesSelected`.

## M4 — Scale (F5)
- ☑ Configurable batch size in `RunOptions`. Lazy input (`RecordSource`). No result accumulation (done in M2).
- ☐ Cancellation between batches (thread interrupt) and optionally per-rule timeouts (ADR-0005 open points).
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
- ☑ Record id: a non-blank string or a number. Configurable field (default `id`). Uniqueness isn't checked (would
  need unbounded memory). A non-string country → `RecordError`.
- ☑ The engine doesn't close the `RecordSource`; whoever opens it closes it.
- ☑ `REQUIREMENTS.md` stays local (git-ignored). Don't commit it or quote it verbatim in committed files.
