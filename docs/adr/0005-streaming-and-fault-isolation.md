# 0005. Streaming output and fault isolation

- Status: Proposed
- Date: 2026-11-11

## Context
F3: one failing rule or record must not sink the run, and the caller receives both results and errors.
F5: ~1M records with bounded memory, results emitted as produced, and a configurable batch size.

## Decision (proposed)
- Input: a lazy `RecordSource` (an iterator-like pull source), consumed in batches of `RunOptions.batchSize`.
- Output: a caller-supplied sink receiving a sealed `Outcome` (either a `Result` or a `RuleError`) as soon as each is produced.
  An adapter is provided for "collect into a list" for small or test runs only.
- The summary is computed incrementally with counters and returned when the run ends.
- Each (rule × record) evaluation runs in its own try/catch. A `RuleError` records ruleId, recordId,
  exception type and message, and the fields read before the failure. A record-level failure (e.g. a record
  that can't be parsed or has no id) becomes a record-level error, and the run continues.
- A sink failure is *not* isolated. The caller's sink breaking means we can't deliver output, so the run aborts with a clear exception.

## Consequences
- Memory is O(batch size × rules), independent of total volume.
- Callers who want a list must opt in explicitly, so the accumulation is visible.
- Parallel batches (ordering vs. throughput) are covered in ADR-0006.
- Open: per-rule timeouts.
