# 0006. Execution mode: ordered sequential by default, opt-in parallel

- Status: Accepted (implementation planned in M4)
- Date: 2026-11-11

## Context
F5 targets ~1M records per run. Batches are independent (rules are pure functions of one record), so they can
be evaluated in parallel. Output order matters to several consumers, though: diffing runs, reproducible
reports, correlating results with the input file, and tests. Parallelism and ordering pull in opposite directions.

## Decision
`RunOptions` exposes an execution mode. The default is the simplest and safest option:

| Mode                 | Order of emitted outcomes                      | Throughput | Extra memory                         |
|----------------------|------------------------------------------------|------------|--------------------------------------|
| `SEQUENTIAL` (default) | Input record order, then catalog rule order  | 1 core     | 1 batch                              |
| `PARALLEL_ORDERED`   | Same as sequential (re-sequenced by batch index) | N cores, can stall on a slow batch | up to `maxInFlightBatches` batches |
| `PARALLEL_UNORDERED` | Within a batch: ordered. Across batches: completion order | N cores, no stalls | up to `maxInFlightBatches` batches |

- Parallel modes take `parallelism` (worker count) and `maxInFlightBatches`. The second value bounds memory and gives
  backpressure: the reader blocks when the window is full, so memory stays O(maxInFlightBatches × batchSize).
- Workers may be virtual threads. Rule logic is CPU-bound, so `parallelism` defaults to the number of available processors.
- The sink is always called from **one thread at a time** in every mode, so hosts never have to write thread-safe sinks.
- Every outcome carries its record's input position (and batch index), so unordered output can still be re-sorted
  or correlated downstream.

## Consequences
- The default behaviour is deterministic and easy to explain. Speed is an explicit choice.
- `PARALLEL_ORDERED` gives most of the speed-up while keeping order. Its cost is head-of-line blocking:
  one slow batch holds back the emission of later ones, within the in-flight window.
- More code paths to test. The summary must give identical counts in all modes (property-style test).
- If the time-box is tight, `SEQUENTIAL` ships first and the parallel modes are documented as next steps in DESIGN.md.
