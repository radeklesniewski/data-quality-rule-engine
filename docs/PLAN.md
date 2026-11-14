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
- ☑ Cancellation via thread interrupt → `RunCancelledException` with a partial summary. Per-rule timeouts are
  deliberately deferred (ADR-0005).
- ☑ `memoryTest` Gradle task: 1M generated records, every mode, under `-Xmx64m` (docs/BENCHMARKS.md).
- ☑ JMH benchmark (`:engine-core:jmh`): mode × catalog (3 vs 30 rules) × batch size (docs/BENCHMARKS.md).
- ☑ Execution modes per ADR-0006 with `parallelism` + `maxInFlightBatches`. `ExecutionModeTest` covers equivalence,
  single-threaded sink, real overlap, no thread leaks, fatal errors and cancellation.
- ☐ (Next) Host-supplied `Executor`; reduce per-outcome allocation (benchmarks show the parallel path is GC-bound).

## M5 — JSON adapters (`engine-json`)
- ☑ `JsonRecordSources.array` (streaming, non-object elements → `RecordError`, syntax error → abort) and
  `JsonRecordSources.lines` (NDJSON, a bad line → `RecordError`). `JsonDataRecord` reads Jackson nodes without copying.
- ☑ `JsonRuleCatalogs`: strict format, every problem reported at once, `watching(path)` reloads between runs.
  `RuleLogicRegistry` added to core.
- ☑ `JsonOutcomeWriter`: streaming `document` (`{"outcomes":[...],"summary":{...}}`) and `lines` (NDJSON) formats.
- ☑ `Fixtures` moved to `engine-core` test fixtures. `JsonFixtureScenarioTest` runs the brief entirely as JSON.
- ☐ (Next) Parameterised logic in catalogs; a JSON Schema for catalog files (ADR-0010).

## M6 — Example HTTP API (`example-http`)
- ☑ `POST /validate` (JSON array or NDJSON in; document or NDJSON out, streamed), `GET /rules`, `GET /health`.
  Query parameters: status, country, category, mode, batchSize, emitOutOfScope, format (ADR-0003).
- ☑ `ExampleHttpServerTest`: the brief over real HTTP, all modes, NDJSON, filters, 50k-record streaming, bad
  records, malformed body (error, no summary), 400/405/415.
- ☑ Engine fix found here: on a fatal source error, records read before the break are validated and emitted
  first, whatever the batch size or mode (ADR-0005).
- ☑ `JsonOutcomeWriter.abort(reason)` for output that was already partly sent.

## M7 — Deliverables
- ☑ `DESIGN.md`: public API, rules and decisions, fault isolation, bounded memory, selection, seams, library
  qualities, assumptions, trade-offs, next steps, and a requirement → evidence table.
- ☑ `README.md`: requirements, build/test/benchmark commands, library usage, HTTP example with curl (verified against
  a running server), sample requests in `example-http/samples/`.
- ☑ Clean clone with an empty Gradle home: `./gradlew build :engine-core:memoryTest` passes (≈25 s).
- ☐ Push to GitHub (needs a remote); optionally CI (GitHub Actions running `build` + `memoryTest`).

## M8 — Review follow-up
- ☑ D1: the HTTP example spools the request body to a temp file (size limit → 413) before validating. This fixes a
  deadlock with half-duplex clients (regression test with 200k records over `HttpURLConnection`).
- ☑ D2: `JsonRecordSources.array` reports content after the closing `]` instead of silently dropping it.
- ☑ Docs match measured behaviour: parallel bound `(maxInFlightBatches + 1) × batchSize`, severity breakdown
  semantics, unbounded single-record size, Java 21 compatibility, build-cache caveat.
- ☐ Known limitations and future work: DESIGN.md §10 and §11.
- ☑ HTTP review: server-side failures before streaming (catalog unreadable or invalid, temp file not writable) are a
  `500` on `/validate` and `/rules`, not a dropped connection; aborting a malformed-body response is quiet. Review
  notes added to ADR-0003 (action endpoint, deadlock mechanism, temp-file safety, early `200`).
- ☐ HTTP review, next: split `validate` into smaller steps; formats in `RunRequest`; `Accept` lists; exact path
  matching; syntax pre-pass for a `400` on malformed JSON.
- ☑ JSON review (ADR-0010 review notes): NDJSON read as bytes (invalid UTF-8 is a bad line, not altered values; BOM
  accepted); stream failures are I/O errors, not "malformed JSON"; record `toString()` without values; writer rejects
  writes after close and stays well-formed after a failed write; `CatalogLoadException` serializable and never empty.
  Refactors: catalog `Parser`, `ArraySource` state enum, writer `Layout` enum.
- ☑ JSON single-responsibility refactor: entry points delegate to package-private helpers (table in ADR-0010).
  Public API unchanged; `ByteLineReaderTest` added.
- ☑ `engine-json` split into feature packages `record`, `catalog`, `outcome` (+ unexported `internal`), ADR-0011.
- ☑ Core review, step 1 (bugs): an exception whose `getMessage()` throws no longer aborts the run (`Failures`);
  `RunCancelledException` keeps its summary through serialization; numeric ids in plain notation; no `int` overflow
  sizing a parallel batch's outcomes; `toBuilder()` keeps derived parallel defaults derived; every record read goes
  through `RecordFields.read`, so `RecordView.of` enforces the `DataRecord` contract too.
- ☐ Core review, next: step 2 `RecordFields.as` + `RuleIds`; step 3 `ParallelRun`/`BatchWindow`/`Cancellation` and
  method-level refactors; step 4 visibility (`RuleEvaluator`, `TrackingRecordView` package-private).
- ☐ JSON review, next (need a decision): package-private exception constructors; one name for the failure field in
  both writer layouts; strict duplicate-key detection for catalogs.

## Open questions / assumptions log
- ☑ Record id missing → record-level error, rule evaluations skipped (confirmed by user).
- ☑ Parallel vs ordered → ADR-0006 (confirmed by user).
- ☑ Country scope mismatch → rule skipped, no Result by default. Opt-in `emitOutOfScope` (ADR-0007, confirmed by user).
- ☑ The brief gives `vatFormat` / `ibanFormat` no default decision. Fixtures use `REVIEW` (an unexpected value goes to a human).
  The brief gives no severities, so fixtures use `ERROR`.
- ☑ JSON `null` = absent field; empty string = present (ADR-0008).
- ☑ Record id: a non-blank string or a finite number, rendered in plain notation whatever its Java type
  (`1.2345678E7` → `"12345678"`; NaN → `RecordError`). Configurable field (default `id`). Uniqueness isn't checked
  (would need unbounded memory). A non-string country → `RecordError`.
- ☑ The engine doesn't close the `RecordSource`; whoever opens it closes it.
- ☑ `REQUIREMENTS.md` stays local (git-ignored). Don't commit it or quote it verbatim in committed files.
