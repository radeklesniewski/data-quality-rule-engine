package io.github.radeklesniewski.dq.internal;

import io.github.radeklesniewski.dq.Outcome;
import io.github.radeklesniewski.dq.OutcomeSink;
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
}
