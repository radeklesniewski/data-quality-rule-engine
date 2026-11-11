# 0006. Execution mode: ordered sequential by default, opt-in parallel

- Status: Accepted (implemented in M4)
- Date: 2026-11-11

## Context
F5 targets ~1M records per run. Batches are independent (rules are pure functions of one record), so they can
be evaluated in parallel. Output order matters to several consumers, though: diffing runs, reproducible
reports, correlating results with the input file, and tests. Parallelism and ordering pull in opposite directions.

## Decision
`RunOptions.executionMode` picks one of three modes. The default is the simplest and safest option:

| Mode                   | Order of emitted outcomes                               | Extra memory                      |
|------------------------|---------------------------------------------------------|-----------------------------------|
| `SEQUENTIAL` (default) | Input record order, then catalog rule order             | 1 batch                           |
| `PARALLEL_ORDERED`     | Identical to `SEQUENTIAL`                               | up to `maxInFlightBatches` + 1 batches |
| `PARALLEL_UNORDERED`   | In order within a batch; batches in completion order    | up to `maxInFlightBatches` + 1 batches |

**How the parallel modes work:**
- The calling thread reads batches and submits each to a per-run pool of `parallelism` **platform** threads
  (default: available processors). Rule logic is CPU-bound, so virtual threads would add nothing. Workers only
  compute a batch's outcome list.
- A window of at most `maxInFlightBatches` (default `2 × parallelism`) gives **backpressure**. When it's full, the
  reader stops and emits a finished batch first. The reader holds the batch it just read while it waits for a slot,
  so the true bound is **(maxInFlightBatches + 1) × batchSize** records plus the outcomes of in-flight batches. This
  was measured in review: 29 records outstanding with `batchSize=10`, `maxInFlightBatches=2`. Memory stays
  O((maxInFlightBatches + 1) × batchSize × rules).
- **All emission happens on the calling thread**, so in every mode the sink is called by exactly one thread, and
  hosts never have to write thread-safe sinks. Summary counters stay single-threaded too.
- Ordered mode emits futures in submission order. Unordered uses an `ExecutorCompletionService`.
- The pool is created per run and shut down in a `finally` (`shutdownNow` on failure, then `close()`), so no
  worker thread outlives a run, even on a sink failure, a fatal error or cancellation. Workers are daemon
  threads, so a rule that never returns can't keep the JVM alive.
- Fatal errors in a worker (e.g. `OutOfMemoryError`) propagate to the caller, as in sequential mode. Ordinary
  failures are still isolated per (rule, record) inside the worker.

**Cancellation:** interrupting the calling thread stops the run at the next batch boundary, or while it waits for a
worker, with `RunCancelledException`. The exception carries a partial `RunSummary` that counts exactly what was
delivered. The interrupt flag is preserved.

## Consequences
- The default behaviour is deterministic and easy to explain. Speed is an explicit choice.
- Measured (docs/BENCHMARKS.md): ~2× for trivial rules and ~3–3.5× for a realistic 30-rule catalog on 16 threads.
  The single-threaded read/emit path and per-outcome allocation cap the speed-up. Ordered vs unordered are
  within noise when batches cost about the same.
- Every outcome carries `recordIndex`, so unordered output can be re-sorted or correlated downstream.
- Tests (`ExecutionModeTest`) verify that ordered output equals sequential exactly and that unordered output equals
  it after a stable sort by index. They also check that the sink is only called from the caller thread, that batches
  really overlap, that no threads leak, and that fatal errors and cancellation behave the same in every mode.
- Next: let hosts supply an `Executor` (e.g. a shared pool) instead of a per-run pool, and per-rule timeouts.
  A hung rule currently blocks its batch, and in ordered mode the whole run.
