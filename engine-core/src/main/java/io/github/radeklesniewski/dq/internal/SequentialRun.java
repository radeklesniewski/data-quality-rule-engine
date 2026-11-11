package io.github.radeklesniewski.dq.internal;

import io.github.radeklesniewski.dq.Outcome;
import io.github.radeklesniewski.dq.OutcomeSink;
import io.github.radeklesniewski.dq.RecordError;
import io.github.radeklesniewski.dq.RuleError;
import io.github.radeklesniewski.dq.RunOptions;
import io.github.radeklesniewski.dq.RunSummary;
import io.github.radeklesniewski.dq.record.DataRecord;
import io.github.radeklesniewski.dq.record.RecordReadException;
import io.github.radeklesniewski.dq.record.RecordSource;
import io.github.radeklesniewski.dq.rule.Rule;
import io.github.radeklesniewski.dq.rule.RuleCatalog;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One sequential validation run: reads a batch, evaluates it record by record and rule by rule, emits outcomes
 * immediately, then reads the next batch. Single-use. Internal.
 *
 * <p>Working memory: one batch of record references plus constant-size counters. Results are never retained.
 */
public final class SequentialRun {

    private static final int MAX_INITIAL_BATCH_CAPACITY = 1_024;

    private final List<Rule> rules;
    private final int batchSize;
    private final RecordKeys keys;
    private final OutcomeSink sink;
    private final SummaryCounter summary = new SummaryCounter();

    /**
     * Prepares a run. Snapshots and validates the catalog, so configuration errors surface before any record is read.
     *
     * @param catalog the catalog
     * @param options run options
     * @param keys how to find record id and country
     * @param sink outcome receiver
     * @throws IllegalArgumentException if the catalog is invalid
     */
    public SequentialRun(RuleCatalog catalog, RunOptions options, RecordKeys keys, OutcomeSink sink) {
        this.rules = RuleSelection.executable(catalog);
        this.batchSize = options.batchSize();
        this.keys = Objects.requireNonNull(keys, "keys");
        this.sink = Objects.requireNonNull(sink, "sink");
    }

    /**
     * Executes the run to completion.
     *
     * @param source the records; not closed here
     * @return the run's totals
     */
    public RunSummary execute(RecordSource source) {
        List<BatchItem> batch = new ArrayList<>(Math.min(batchSize, MAX_INITIAL_BATCH_CAPACITY));
        long nextIndex = 0;
        boolean exhausted = false;
        while (!exhausted) {
            batch.clear();
            while (batch.size() < batchSize) {
                BatchItem item;
                try {
                    Optional<DataRecord> next = Objects.requireNonNull(source.next(),
                            "RecordSource.next() must not return null; return Optional.empty() at the end");
                    if (next.isEmpty()) {
                        exhausted = true;
                        break;
                    }
                    item = new BatchItem.Readable(nextIndex, next.get());
                } catch (RecordReadException e) {
                    item = new BatchItem.Unreadable(nextIndex, e);
                }
                nextIndex++;
                summary.recordRead();
                batch.add(item);
            }
            if (!batch.isEmpty()) {
                batch.forEach(this::process);
                summary.batchCompleted();
            }
        }
        return summary.snapshot();
    }

    private void process(BatchItem item) {
        switch (item) {
            case BatchItem.Unreadable(long index, RecordReadException cause) ->
                    emit(RecordError.of(index, Optional.empty(), cause));
            case BatchItem.Readable(long index, DataRecord record) -> evaluate(index, record);
        }
    }

    private void evaluate(long index, DataRecord record) {
        String id = null;
        Optional<String> country;
        try {
            id = keys.id(record);
            country = keys.country(record);
        } catch (Exception e) {
            emit(RecordError.of(index, Optional.ofNullable(id), e));
            return;
        }
        for (Rule rule : rules) {
            if (rule.countryScope().appliesTo(country)) {
                emit(evaluateIsolated(rule, record, id, index));
            }
        }
    }

    /**
     * The fault-isolation boundary for one (rule, record) pair. Ordinary exceptions, stack overflows from runaway
     * recursion and linkage errors (e.g. a rule class failing static initialisation) are contained. Other
     * {@link VirtualMachineError}s such as {@link OutOfMemoryError} are not: the JVM is no longer trustworthy.
     */
    private Outcome evaluateIsolated(Rule rule, DataRecord record, String id, long index) {
        TrackingRecordView view = new TrackingRecordView(record);
        try {
            return RuleEvaluator.evaluate(rule, view, id, index);
        } catch (Exception | StackOverflowError | LinkageError e) {
            return RuleError.of(rule.id(), id, index, view.reads(), e);
        }
    }

    private void emit(Outcome outcome) {
        summary.count(outcome);
        sink.accept(outcome);
    }

    /** A slot in the current batch: a record, or a record the source couldn't read. */
    private sealed interface BatchItem {
        record Readable(long index, DataRecord record) implements BatchItem {
        }

        record Unreadable(long index, RecordReadException cause) implements BatchItem {
        }
    }
}
