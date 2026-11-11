package io.github.radeklesniewski.dq.internal;

import io.github.radeklesniewski.dq.Outcome;
import io.github.radeklesniewski.dq.OutcomeSink;
import io.github.radeklesniewski.dq.RunCancelledException;
import io.github.radeklesniewski.dq.RunSummary;
import java.util.function.Consumer;

/**
 * Delivers outcomes to the caller's sink and counts them. Used only from the run's calling thread, which is how the
 * engine guarantees that sinks are never called concurrently. Internal.
 */
final class Emitter implements Consumer<Outcome> {

    private final OutcomeSink sink;
    private final SummaryCounter summary;

    Emitter(OutcomeSink sink, SummaryCounter summary) {
        this.sink = sink;
        this.summary = summary;
    }

    @Override
    public void accept(Outcome outcome) {
        summary.count(outcome);
        sink.accept(outcome);
    }

    void batchCompleted() {
        summary.batchCompleted();
    }

    RunSummary summary() {
        return summary.snapshot();
    }

    /**
     * Cancellation point, checked between batches. The interrupt flag is left set for the caller to see.
     *
     * @throws RunCancelledException if the calling thread has been interrupted
     */
    void throwIfCancelled() {
        if (Thread.currentThread().isInterrupted()) {
            throw cancelled(null);
        }
    }

    RunCancelledException cancelled(Throwable cause) {
        return new RunCancelledException(summary.snapshot(), cause);
    }
}
