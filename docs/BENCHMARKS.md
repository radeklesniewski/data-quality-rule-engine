# Performance and memory evidence (F5)

Two complementary measurements. Both can be reproduced with one command.

## 1. Bounded memory: 1,000,000 records under a 64 MB heap

```bash
./gradlew :engine-core:memoryTest
```

`BoundedMemoryTest` generates 1M records lazily (the three fixture shapes, each with a unique id) and validates
them with the fixture catalog, which gives 3M results. The sink only counts. The task runs with `-Xmx64m`, and the
test refuses to run with a larger heap. Keeping 3M `Result`s (each with provenance, field reads and strings) would
need several hundred MB, so completing at all shows nothing accumulates. The test also asserts exact decision
counts, and input order for the ordered modes.

| Mode               | Time    | Peak heap (incl. garbage) |
|--------------------|---------|---------------------------|
| SEQUENTIAL         | ~0.4 s  | 26 MB of 64 MB            |
| PARALLEL_ORDERED   | ~0.7 s  | 41 MB of 64 MB            |
| PARALLEL_UNORDERED | ~0.7 s  | 37 MB of 64 MB            |

Settings: batch size 500, 8 in-flight batches. Here the run is dominated by generating records on the reader
thread. The rules are trivial, so the parallel modes only add hand-off cost. See section 2 for where parallelism pays off.

## 2. Throughput: JMH

```bash
./gradlew :engine-core:jmh     # results: engine-core/build/results/jmh/results.json
```

`EngineBenchmark` validates 100,000 pre-built records per invocation. The score is **records per second**. The
input isn't parsed, so this measures the engine itself: selection, isolation, provenance tracking, mapping and emission.

- `fixture`: the 3 rules from the brief.
- `realistic`: 30 rules. These are the 3 fixture rules, an IBAN mod-97 checksum (BigInteger), and 26 regex rules
  over 5 fields, including a nested one.

Machine: AMD Ryzen 7 9800X3D (8 cores / 16 threads), OpenJDK 25.0.1, default JMH heap, parallelism = 16,
in-flight batches = 32. 2×2 s warm-up, 3×2 s measurement, 1 fork. Numbers are rounded.

| Catalog   | Batch | SEQUENTIAL | PARALLEL_ORDERED | PARALLEL_UNORDERED |
|-----------|------:|-----------:|-----------------:|-------------------:|
| fixture   |   100 |    4.2 M/s |          8.8 M/s |      6.9 M/s (±noisy) |
| fixture   |  1000 |    4.2 M/s |          7.7 M/s |            8.3 M/s |
| realistic |   100 |    225 k/s |          770 k/s |            820 k/s |
| realistic |  1000 |    223 k/s |          650 k/s |            640 k/s |

### Reading the numbers
- **1M records with 30 rules takes ~4.5 s sequentially and ~1.3 s in parallel**, on top of input parsing.
  The brief's target volume is comfortably in reach on one machine.
- **Parallel speed-up is ~2× for cheap rules and ~3–3.5× for realistic ones, not 16×.** Reading, emission
  and summary counting are single-threaded by design (the sink is never called concurrently). Every outcome
  allocates (result, provenance, field reads), so the run is partly allocation- and GC-bound. Profiling
  allocation per outcome is the next optimisation step.
- **Ordered vs unordered** are within noise of each other here, because batches take similar time. Unordered only
  pays off when batch costs vary a lot (head-of-line blocking).
- **Batch size** hardly matters between 100 and 1000 for throughput. It's mainly a memory and latency knob.
