# Data-Quality Rule Engine

A reusable Java library that validates business-partner records (company names, addresses, tax ids, bank
accounts, …) against a catalog of data-quality rules. It streams, so memory stays bounded at any volume. Every
decision comes from a declarative mapping. Faults are isolated per rule and record, and every result explains itself.

- **[DESIGN.md](DESIGN.md)**: the public API and why it looks this way, fault isolation, bounded memory, extension
  points, trade-offs and next steps. Start here.
- [docs/adr/](docs/adr/README.md): one record per design decision.
- [docs/BENCHMARKS.md](docs/BENCHMARKS.md): memory and throughput measurements.

## Requirements

JDK 25. The Gradle wrapper is included. If JDK 25 isn't installed, Gradle downloads it automatically
(foojay toolchain resolver). If `java` isn't on your `PATH`, point `JAVA_HOME` at any JDK (17+) to run Gradle itself.

## Build and test

```bash
./gradlew build                      # compile, Javadoc, all tests, coverage (≈10 s)
./gradlew test                       # tests only
./gradlew :engine-core:memoryTest    # 1,000,000 records in every execution mode under a 64 MB heap
./gradlew :engine-core:jmh           # JMH throughput benchmarks (≈2 min)
```

Reports: `*/build/reports/tests/test/index.html` (tests), `*/build/reports/jacoco/test/html/index.html` (coverage).

| Module | What | Depends on |
|---|---|---|
| `engine-core` | The library: model, engine, extension points | nothing |
| `engine-json` | Streaming JSON record sources, JSON rule catalog, streaming JSON output | core, Jackson 3 |
| `example-http` | Example host: HTTP API over the library | core, json |

## Using the library

```java
// 1. Rules: logic computes a value; a declarative mapping turns it into a decision.
Rule vatFormat = Rule.builder("vatFormat")
        .label("VAT id has a plausible format")
        .logic(r -> r.getString("vatId").filter(v -> v.length() >= 9).isPresent() ? "ok" : "bad")
        .decisionMapping(DecisionMapping.builder()
                .map("ok", Decision.VALID)
                .map("bad", Decision.INVALID)
                .orElse(Decision.REVIEW))          // a default is mandatory
        .status(RuleStatus.RELEASED)               // only RELEASED rules ever run
        .severity(Severity.ERROR)
        .categories("tax")
        .build();

// 2. Run: records in (pulled lazily), outcomes out (pushed as produced), summary returned.
RunSummary summary = ValidationEngine.create().run(
        RecordSource.of(records),                  // or JsonRecordSources.array(inputStream)
        RuleCatalog.of(vatFormat),                 // or JsonRuleCatalog.load(path, logicRegistry)
        RunOptions.builder()
                .ruleFilter(RuleFilter.country("DE").and(RuleFilter.category("tax")))
                .batchSize(1_000)
                .executionMode(ExecutionMode.SEQUENTIAL)  // or PARALLEL_ORDERED / PARALLEL_UNORDERED
                .build(),
        outcome -> {
            switch (outcome) {
                case Result r      -> store(r);    // decision, severity, provenance (fields and values read)
                case RuleError e   -> log(e);      // one rule failed on one record; the run went on
                case RecordError e -> park(e);     // record unreadable or without an id
            }
        });
```

Rules can also be defined as data. See `example-http/src/main/resources/example-catalog.json` and
`JsonRuleCatalog`.

## Running the example HTTP API

```bash
./gradlew :example-http:run          # http://localhost:8080 (bundled example catalog)
# or: ./gradlew :example-http:run --args="--port 9090 --catalog /path/to/catalog.json"   (reloads on change)
```

```bash
# The brief's three records → outcomes + summary
curl -s -X POST localhost:8080/validate -H 'Content-Type: application/json' \
     --data-binary @example-http/samples/brief-records.json

# Select rules and execution with query parameters (all optional, combined with AND)
curl -s -X POST 'localhost:8080/validate?country=DE&category=tax&mode=PARALLEL_ORDERED&batchSize=500' \
     -H 'Content-Type: application/json' --data-binary @example-http/samples/brief-records.json

# NDJSON in and out. The sample has a wrongly typed vatId and a broken line, which show up as errors.
curl -s -X POST localhost:8080/validate -H 'Content-Type: application/x-ndjson' \
     -H 'Accept: application/x-ndjson' --data-binary @example-http/samples/records.ndjson

curl -s 'localhost:8080/rules?status=DRAFT'   # list the catalog, with the same filters
```

| Query parameter | Meaning |
|---|---|
| `status=RELEASED,DRAFT` | rules with any of these statuses (only RELEASED ever *run*) |
| `country=DE` | rules that apply to DE records (WORLD + DE) |
| `category=tax,bank` | rules with any of these categories |
| `mode=PARALLEL_ORDERED` | `SEQUENTIAL` (default), `PARALLEL_ORDERED`, `PARALLEL_UNORDERED` |
| `batchSize=500` | records per batch (1–100,000; default 1,000) |
| `emitOutOfScope=true` | `NOT_APPLICABLE` results for rules outside a record's country |
| `format=ndjson` | NDJSON response (same as `Accept: application/x-ndjson`) |

The response streams. The status `200` is sent before the body has been fully read, so **a response without a
`summary` is incomplete**. If the body turns out to be malformed partway through, the response ends with an `error`
instead.

## Repository conventions

Conventional Commits. Each commit builds on its own. AI-assistant guidance is in [AGENTS.md](AGENTS.md) and
[CLAUDE.md](CLAUDE.md). The milestone plan and assumptions log are in [docs/PLAN.md](docs/PLAN.md).
