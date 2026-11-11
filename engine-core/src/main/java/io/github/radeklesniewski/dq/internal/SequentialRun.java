package io.github.radeklesniewski.dq.internal;

import io.github.radeklesniewski.dq.RunSummary;
import java.util.List;

/**
 * {@code SEQUENTIAL} execution: everything on the calling thread. It reads a batch, evaluates it record by record
 * and rule by rule, emits each outcome immediately, then reads the next batch. Internal.
 *
 * <p>Working memory: one batch of record references plus constant-size counters.
 */
final class SequentialRun {

    private final BatchReader reader;
    private final RecordEvaluator evaluator;
    private final Emitter emitter;

    SequentialRun(BatchReader reader, RecordEvaluator evaluator, Emitter emitter) {
        this.reader = reader;
        this.evaluator = evaluator;
        this.emitter = emitter;
    }

    RunSummary execute() {
        while (true) {
            emitter.throwIfCancelled();
            List<BatchItem> batch = reader.next();
            if (batch.isEmpty()) {
                return emitter.summary();
            }
            batch.forEach(item -> evaluator.evaluate(item, emitter));
            emitter.batchCompleted();
        }
    }
}
