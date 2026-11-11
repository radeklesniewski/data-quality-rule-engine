# Design: Data-Quality Rule Engine

A Java 25 library that validates business-partner records against a catalog of data-quality rules. Hosts (an HTTP
API, batch workers, scheduled jobs) embed it. This document explains the shape of the library and the reasons
behind it. Detailed decision records are in [`docs/adr/`](docs/adr/README.md), and measurements in
[`docs/BENCHMARKS.md`](docs/BENCHMARKS.md).

**Status:** every functional requirement (F1–F6) is implemented and tested, plus an example HTTP host.
1,000,000 records stream through a 64 MB heap in about 0.4 s. A realistic 30-rule catalog runs at about 225k
records/s sequentially and about 650–800k records/s in parallel (16 threads).

---

## 1. Public API, and why it's shaped this way

The library has one verb and four nouns: **records in, a catalog, options, outcomes out**.

```java
ValidationEngine engine = ValidationEngine.create();             // immutable, thread-safe, reusable

try (RecordSource records = JsonRecordSources.array(body);         // pulled lazily
     JsonOutcomeWriter out = JsonOutcomeWriter.document(response)) {  // an OutcomeSink
    RunSummary summary = engine.run(
            records,
            catalog,                                               // RuleCatalog: snapshotted once per run
            RunOptions.builder()
                    .ruleFilter(RuleFilter.country("DE").and(RuleFilter.category("tax")))
                    .batchSize(500)
                    .build(),
            out);                                                  // every outcome, as soon as it exists
    out.finish(summary);
}
```

Why this shape:

- **The run returns a summary, not results.** Results go to a caller-supplied `OutcomeSink` as they're produced.
  Returning a `List<Result>` would make "collect 3M results" the default and bounded memory the exception. With a
  sink, accumulation is something the caller has to ask for (`CollectingSink`, documented as being for tests and
  small batches only).
- **One ordered stream of a sealed type.** `Outcome = Result | RuleError | RecordError`. Results and errors arrive
  in input order on one channel, so a host sees exactly what happened and handles it with an exhaustive `switch`.
  `OutcomeSink.of(results, errors)` splits it for hosts that want two handlers.
- **Inputs are interfaces with one method** (`RecordSource`, `DataRecord`, `RuleCatalog`, `RuleFilter`,
  `OutcomeSink`). Adapting any host's data is a lambda. The JSON module is just one set of adapters.
- **Values are immutable records with validated components.** `Rule`, `DecisionMapping`, `Result`, `Provenance`
  and `RunSummary` have no nulls in the API, and `Optional` is used only where absence means something.
- **`RunOptions` is a class with a builder, not a record.** Options were added in three milestones (filters,
  out-of-scope results, execution mode) without breaking any caller, and future ones won't either.
- **Small surface, enforced.** `engine-core`'s `module-info` exports four API packages (`dq`, `dq.record`,
  `dq.rule`, `dq.select`). The implementation in `dq.internal` isn't exported.

**Modules:** `engine-core` (the library, **zero runtime dependencies**), `engine-json` (Jackson 3 adapters),
`example-http` (the demo host, JDK `HttpServer`). Hosts that don't use JSON, or that use another JSON library,
depend on the core only (ADR-0002).

## 2. Rules and the declarative decision (F1, F2)

A `Rule` is an id, label, `RuleLogic`, `DecisionMapping` and metadata (status, severity, country scope, categories).

- **Logic computes a value and never a decision.** `RuleLogic` is `String compute(RecordView record)`. It returns an
  outcome code such as `"ok"`, `"bad"` or `"blocked"`. It's a `String` so mapping keys stay unambiguous in data files
  (`true` vs `"true"`, `1` vs `1.0`) (ADR-0008).
- **`DecisionMapping` is plain data:** value → `Decision`, plus a **mandatory default**. The builder only finishes with
  `orElse(default)`, so a mapping without a default can't be written. It's the only code path that produces a
  decision. Provenance records whether the default was used, so "unexpected value" is visible.
- **Provenance is captured automatically, not reported by the rule author.** Logic reads fields through a tracking
  `RecordView` that records every path read and the value seen (absent fields included: "no vatId" is evidence).
  Provenance therefore can't drift from what the logic actually did, and it's kept even when logic fails part-way.
- **Rules can be defined in code or as data.** `JsonRuleCatalog` loads metadata and mappings from JSON and resolves
  `"logic": "vatFormat"` through a `RuleLogicRegistry` (ADR-0004, ADR-0010). Loading is strict and reports every
  problem at once, e.g. `rules[1] 'b': "severity" is required`.

The brief's scenario passes three ways: in Java (`FixtureScenarioTest`), entirely as JSON
(`JsonFixtureScenarioTest`), and over HTTP (`ExampleHttpServerTest`). All three compare against one expected table
(`Fixtures.EXPECTED`).

## 3. Fault isolation (F3)

Each (rule × record) evaluation is its own failure domain. Failures become **data in the outcome stream**, so the
caller gets results and errors together and the run carries on.

| What fails | Becomes | Effect |
|---|---|---|
| Rule logic throws, returns `null`, misses a `require`d field, meets a wrong type, overflows the stack | `RuleError`: rule, record id and index, **fields read before the failure**, exception type and message | that record's other rules still run |
| Source can't read one record (`RecordReadException`, e.g. a JSON element that isn't an object, a broken NDJSON line) | `RecordError` with the input position | next record |
| Record has no usable id or a non-string country | `RecordError` (id if known) | its rules are skipped |
| Invalid catalog, throwing filter | exception **before any record is read** | configuration error, fail fast |
| Sink throws, source is fatally broken, `OutOfMemoryError` | propagates | records read before a source break are still validated and delivered first |

Other details:
- **Every outcome carries `recordIndex`**, the input position. It locates a failure even when the record has no id,
  and lets unordered parallel output be re-sorted.
- **Missing fields are the author's choice.** `getString("vatId")` treats absence as a normal value (it gives `"bad"`).
  `requireString("vatId")` treats it as a fault (it gives a `RuleError`).
- **No personal data in error messages.** Exceptions name paths, positions and types, never field values. The JSON
  parser is configured not to quote input. Values appear only in result provenance, which the caller controls.
- `OutOfMemoryError` and other `VirtualMachineError`s are deliberately *not* caught (`StackOverflowError` is). Once
  the JVM is in that state, "continue with the next record" is no longer a promise anyone can keep.

## 4. Bounded memory and scale (F5)

- **Pull in, push out.** The engine pulls `batchSize` records from the `RecordSource`, evaluates them, pushes each
  outcome to the sink, then pulls the next batch. Nothing outlives its batch. `RunSummary` is built from `long`
  counters. Working memory is O(batch size × rules), independent of input size. The JSON adapters keep this end
  to end: the parser reads one array element at a time, and the writer emits as it goes.
- **Evidence:** `./gradlew :engine-core:memoryTest` runs 1M records (3M results) in every execution mode under
  `-Xmx64m`, and refuses to run with a bigger heap. Keeping the results would need several hundred MB. Peak heap is
  roughly 25–45 MB, varying between runs. A test checks that batch *n+1* isn't read before batch *n* has been
  emitted. The HTTP test streams 50k records through a single request.
- **Parallelism is opt-in, and order is a choice** (ADR-0006). `SEQUENTIAL` (default), `PARALLEL_ORDERED`
  (identical output, worker pool) and `PARALLEL_UNORDERED` (batches emitted as they finish).
  `maxInFlightBatches` bounds the window and gives backpressure, so memory stays bounded in parallel too. **The
  sink is always called from the calling thread**, so hosts never write thread-safe sinks. The per-run pool is shut
  down in `finally`, and tests check that no worker thread leaks after success, sink failure, fatal error or
  cancellation.
- **Cancellation:** interrupting the calling thread stops the run at the next batch boundary with
  `RunCancelledException`. The exception carries a partial summary that counts exactly what was delivered.

## 5. Rule selection (F6)

`RuleFilter` composes with `and` / `or` / `negate`, and has `status(…)`, `country(code)`, `category(…)` and
`of(description, predicate)`. The built-in filters are value objects with readable `toString()`s, e.g.
`((country DE (incl. WORLD) and category any of [tax]) and not (status in [DRAFT]))`, so a run's selection can be logged.

- **Filters can only narrow.** The engine applies the caller's filter, *then* `status == RELEASED`. No filter,
  including a negation, can make a DRAFT or DEPRECATED rule run. Tests cover `status(DRAFT)`, every status, and
  negated filters.
- **Country works at two levels.** `RuleFilter.country("DE")` selects the rules relevant to German records (WORLD +
  DE). Independently, the engine checks each rule's scope against each record's country. A mismatch produces
  **no outcome** by default (ADR-0007). With `emitOutOfScope`, it produces a `NOT_APPLICABLE` result whose provenance
  says `OUT_OF_SCOPE`, and the logic still doesn't run.
- `RunSummary.rulesSelected` makes an empty selection visible. It's not an error.

## 6. Seams for host-supplied inputs

| Seam | Contract | Provided implementations |
|---|---|---|
| `DataRecord` | `Optional<Object> get(path)` | `DataRecord.ofMap`, `JsonDataRecord` |
| `RecordSource` | lazy `next()`; `RecordReadException` skips one record; `AutoCloseable` | `RecordSource.of(Iterable/Iterator/Stream)`, `JsonRecordSources.array/lines` |
| `RuleCatalog` | `List<Rule> rules()`, snapshotted once per run | `RuleCatalog.of(...)`, `JsonRuleCatalog.load/watching` (reloads between runs) |
| `RuleLogicRegistry` | logic id → `RuleLogic`, for catalogs defined as data | `RuleLogicRegistry.of(Map)` |
| `RuleFilter` | `matches(Rule)` | status / country / category / custom, composable |
| `OutcomeSink` | `accept(Outcome)`, called from one thread | `CollectingSink`, `OutcomeSink.of(...)`, `JsonOutcomeWriter.document/lines` |
| Record keys | where the id and country live | `ValidationEngine.builder().idField("meta.partnerId").countryField(...)` |

## 7. What else a library should provide

The brief left these open on purpose. What I did about each:

- **Dependency hygiene:** the core has zero runtime dependencies. Jackson is confined to `engine-json`, declared as
  `api` / `requires transitive` because its types appear in that module's API.
- **Encapsulation:** JPMS modules, the internal package isn't exported, small public surface.
- **Concurrency contract:** documented on every type. The engine is immutable and shared. A run is single-use. Sinks
  never need to be thread-safe. No thread outlives a run.
- **Determinism:** fixed output order (records, then catalog order) except in the explicitly unordered mode. The
  catalog is snapshotted per run.
- **Fail-fast configuration:** invalid rules, mappings without defaults, duplicate ids and unknown logic ids are
  rejected at build or load time, never mid-run.
- **Operability:** cancellation, a run summary, safe-to-log errors, `toString()`s on options and filters for logs.
- **Evolvability:** builders for anything that will grow. Sealed types give exhaustive handling.
- **Documentation and tests:** Javadoc on all public API (doclint enabled). 213 tests (plus the memory test), including
  equivalence across execution modes, concurrency tests that use barriers rather than timing, an HTTP integration
  test, a memory test and JMH benchmarks. Shared test fixtures mean the brief's scenario is defined once.

## 8. Assumptions

- `vatFormat` and `ibanFormat` get the default decision `REVIEW` (the brief gives none, and every mapping needs one).
  An unexpected value goes to a human. All fixture rules use severity `ERROR` (the brief gives none).
- A JSON `null` and a missing field are the same thing (absent). An empty string is present (so `iban: ""` → `"bad"`).
- A record id is a non-blank string or a number. Uniqueness isn't checked, because that would need memory
  proportional to the input.
- Country matching is exact and case-sensitive. Normalising data is the host's job.
- Field paths are dot-separated (`address.city`). Array indexing isn't supported.
- Whoever opens a `RecordSource` closes it. The engine doesn't.

## 9. Trade-offs under the time-box

- **Per-rule timeouts are not implemented.** A rule that never returns blocks its batch (and, in ordered mode, the
  run). Timing out arbitrary code means one thread per evaluation, or cooperative checks. Rules are expected to be
  cheap, pure functions. Cancellation does work between batches.
- **Parallel speed-up is about 3×, not 16×.** Reading, emitting and counting are single-threaded by design (the sink
  contract), and every outcome allocates. I chose the simpler single-emitter contract over raw throughput.
- **The JSON array format can't recover from syntax errors**, because the parser loses its place. Records before the
  break are validated, and then the run fails clearly. NDJSON is recommended for untrusted bulk input, since a bad
  line costs only that line.
- **The HTTP 200 is sent before the body has been fully read** (streaming). A body that turns out to be malformed
  produces `"error"` and no `"summary"`. Buffering to get a proper 400 would give up bounded memory.
- **Rule logic is Java code referenced by id**, not an expression language. It's type-safe, testable and debuggable,
  but a new *kind* of check needs a deploy.
- **Provenance tracking costs one small object per (rule, record)**, which the benchmarks include. I judged explanations
  that can't be wrong to be worth it.

## 10. What I'd do next

1. **Parameterised logic in catalogs** (`{"logic": "minLength", "params": {"field": "vatId", "min": 9}}`), so new
   rules of an existing kind are pure data. Then a JSON Schema for catalog files.
2. **A host-supplied `Executor`** for the parallel modes (a shared pool across HTTP requests), and **per-rule timeouts**
   on top of it.
3. **Reduce per-outcome allocation**, guided by an allocation profile. The parallel path is GC-bound.
4. **Metrics hook** (`RunListener`: per-batch timing, per-rule error rates) for operations dashboards.
5. **Rule dependencies** (e.g. skip `ibanChecksum` when `ibanFormat` is INVALID), which the current model
   deliberately leaves out.
6. **Publishing:** `maven-publish`, semantic versioning, an API-compatibility check (e.g. japicmp) in CI, and
   GitHub Actions running `build` and `memoryTest`.

## Requirement → evidence

| Requirement | Where | Proven by |
|---|---|---|
| F1 one Result per rule with provenance | `ValidationEngine`, `Result`, `Provenance`, `TrackingRecordView` | `FixtureScenarioTest`, `RuleEvaluatorTest` |
| F2 decisions as data | `DecisionMapping`, `RuleLogic` (values only) | `DecisionMappingTest`, `JsonRuleCatalogTest` |
| F3 fault isolation | `RecordEvaluator`, `RuleError`, `RecordError` | `FixtureScenarioTest#faultsAreIsolated…`, `ValidationEngineTest$FaultIsolation` |
| F4 summary | `RunSummary` | `FixtureScenarioTest`, `ApiValueTypesTest` |
| F5 1M records, bounded memory, configurable batches | `BatchReader`, `OutcomeSink`, `ParallelRun` | `BoundedMemoryTest` (`memoryTest`), `ValidationEngineTest$Streaming`, `EngineBenchmark` |
| F6 composable filters, RELEASED only | `RuleFilter`, `RuleSelection` | `RuleFilterTest`, `RuleSelectionEngineTest` |
| Example HTTP API | `ExampleHttpServer` | `ExampleHttpServerTest` |
