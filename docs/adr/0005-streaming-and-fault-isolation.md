# 0005. Streaming output and fault isolation

- Status: Accepted (implemented in M2)
- Date: 2026-11-11

## Context
F3: one failing rule or record must not sink the run, and the caller receives both results and errors.
F5: ~1M records with bounded memory, results emitted as produced, and a configurable batch size.

## Decision
**Input: `RecordSource`, pulled lazily.** One method, `Optional<DataRecord> next()`. The engine reads
`RunOptions.batchSize` records, evaluates them, emits outcomes, then reads the next batch. A source signals
"this record is unreadable, but I can go on" with `RecordReadException`. Any other exception means the source is
broken. The engine doesn't close the source; whoever opens it closes it.

**Output: one `OutcomeSink`, one sealed `Outcome` type.** `Outcome = Result | ValidationError`, and
`ValidationError = RuleError | RecordError`. Results and errors travel through the same ordered stream, so a host
sees exactly what happened, in input order, and can `switch` over it exhaustively. `OutcomeSink.of(results, errors)`
splits the stream for hosts that prefer two consumers. `CollectingSink` keeps everything in memory. It's an explicit
opt-in, documented as being for tests and small batches only.

**Summary: counters, not collections.** `RunSummary` is built from `long` counters (records read, batches, results,
rule and record errors, results by decision and by severity). It costs constant memory and is returned when the run ends.

**Fault isolation boundaries.**

| Failure | Becomes | Run continues? |
|---|---|---|
| Rule logic throws, returns `null`, misses a required field or meets a wrong type | `RuleError` (rule, record id and index, fields read so far, exception) | yes; the record's other rules still run |
| Rule logic overflows the stack, or its class fails to initialise (`LinkageError`) | `RuleError` | yes |
| Source throws `RecordReadException` | `RecordError` (index, no id) | yes |
| Record has no usable id, a non-string country, or its `DataRecord` throws while keys are read | `RecordError` (index, id if known) | yes; none of its rules run |
| Invalid catalog (duplicate ids, null rules) | `IllegalArgumentException` / `NullPointerException` **before any record is read** | no (configuration error) |
| Sink throws | propagates | no; output can't be delivered |
| Source throws anything else | propagates | no; input is broken |
| `OutOfMemoryError` and other `VirtualMachineError`s | propagates | no; the JVM is no longer trustworthy |

Every outcome carries `recordIndex`, the zero-based input position. Errors can be located even when a record has
no id, and output from future parallel modes can be re-sequenced (ADR-0006).

**API evolution.** `RunOptions` is a final class with a builder, not a record, so options (filters, execution mode,
`emitOutOfScope`) can be added without breaking callers. Outcome types are records because hosts consume them
rather than construct them.

## Consequences
- Working memory is O(batch size + catalog size), independent of total volume. Verified by the 1M-record test in M4.
- Hosts that want a list must opt in explicitly (`CollectingSink`), so the accumulation is visible.
- Record ids aren't checked for uniqueness: that would need memory proportional to input. Hosts that care can
  dedupe upstream.
- Cancellation (thread interrupt, checked between batches → `RunCancelledException` with a partial summary) was
  added in M4. See ADR-0006.
- Open: per-rule timeouts. A rule that hangs blocks the run. Timing out arbitrary code needs a separate thread
  per evaluation, or cooperative checks, and that cost wasn't justified in this time-box.
