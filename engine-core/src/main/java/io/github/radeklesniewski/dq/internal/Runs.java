package io.github.radeklesniewski.dq.internal;

import io.github.radeklesniewski.dq.ExecutionMode;
import io.github.radeklesniewski.dq.OutcomeSink;
import io.github.radeklesniewski.dq.RunOptions;
import io.github.radeklesniewski.dq.RunSummary;
import io.github.radeklesniewski.dq.record.RecordSource;
import io.github.radeklesniewski.dq.rule.Rule;
import io.github.radeklesniewski.dq.rule.RuleCatalog;
import java.util.List;

/** Wires one validation run together and picks the execution strategy. Internal. */
public final class Runs {

    private Runs() {
    }

    /**
     * Executes one run. The catalog and filter are validated before the source is touched.
     *
     * @param source the records; not closed here
     * @param catalog the rules
     * @param options run options
     * @param keys how to find record id and country
     * @param sink outcome receiver
     * @return the run's totals
     */
    public static RunSummary execute(RecordSource source, RuleCatalog catalog, RunOptions options, RecordKeys keys,
            OutcomeSink sink) {
        List<Rule> rules = RuleSelection.executable(catalog, options.ruleFilter());
        RecordEvaluator evaluator = new RecordEvaluator(rules, keys, options.emitOutOfScope());
        SummaryCounter summary = new SummaryCounter(rules.size());
        BatchReader reader = new BatchReader(source, options.batchSize(), summary);
        Emitter emitter = new Emitter(sink, summary);
        return switch (options.executionMode()) {
            case SEQUENTIAL -> new SequentialRun(reader, evaluator, emitter).execute();
            case PARALLEL_ORDERED, PARALLEL_UNORDERED -> new ParallelRun(reader, evaluator, emitter,
                    options.parallelism(), options.maxInFlightBatches(),
                    options.executionMode() == ExecutionMode.PARALLEL_ORDERED)
                    .execute();
        };
    }
}
