package io.github.radeklesniewski.dq.internal;

import io.github.radeklesniewski.dq.FieldRead;
import io.github.radeklesniewski.dq.Outcome;
import io.github.radeklesniewski.dq.Provenance;
import io.github.radeklesniewski.dq.RecordError;
import io.github.radeklesniewski.dq.Result;
import io.github.radeklesniewski.dq.RuleError;
import io.github.radeklesniewski.dq.record.DataRecord;
import io.github.radeklesniewski.dq.rule.Decision;
import io.github.radeklesniewski.dq.rule.Rule;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Turns one batch item into its outcomes: every selected rule within the record's scope, each failure isolated.
 *
 * <p>Stateless and thread-safe. Parallel runs call it from several worker threads at once. Internal.
 */
final class RecordEvaluator {

    private final List<Rule> rules;
    private final RecordKeys keys;
    private final boolean emitOutOfScope;

    RecordEvaluator(List<Rule> rules, RecordKeys keys, boolean emitOutOfScope) {
        this.rules = List.copyOf(rules);
        this.keys = keys;
        this.emitOutOfScope = emitOutOfScope;
    }

    /** Evaluates a whole batch into a list. Used by parallel runs, whose workers can't call the sink directly. */
    List<Outcome> evaluateBatch(List<BatchItem> batch) {
        List<Outcome> outcomes = new ArrayList<>(batch.size() * Math.max(1, rules.size()));
        batch.forEach(item -> evaluate(item, outcomes::add));
        return outcomes;
    }

    /** Evaluates one item, passing each outcome to {@code out} in catalog order. */
    void evaluate(BatchItem item, Consumer<Outcome> out) {
        switch (item) {
            case BatchItem.Unreadable(long index, var cause) -> out.accept(RecordError.of(index, Optional.empty(), cause));
            case BatchItem.Readable(long index, DataRecord record) -> evaluate(index, record, out);
        }
    }

    private void evaluate(long index, DataRecord record, Consumer<Outcome> out) {
        String id = null;
        Optional<String> country;
        try {
            id = keys.id(record);
            country = keys.country(record);
        } catch (Exception e) {
            out.accept(RecordError.of(index, Optional.ofNullable(id), e));
            return;
        }
        for (Rule rule : rules) {
            if (rule.countryScope().appliesTo(country)) {
                out.accept(evaluateIsolated(rule, record, id, index));
            } else if (emitOutOfScope) {
                out.accept(outOfScope(rule, id, index, country));
            }
        }
    }

    /** A NOT_APPLICABLE result for a rule skipped by country scope (ADR-0007). The rule's logic doesn't run. */
    private Result outOfScope(Rule rule, String id, long index, Optional<String> country) {
        FieldRead countryRead = new FieldRead(keys.countryField(), country.map(Object.class::cast));
        return new Result(Decision.NOT_APPLICABLE, rule.severity(),
                Provenance.outOfScope(rule.id(), id, index, countryRead));
    }

    /**
     * The fault-isolation boundary for one (rule, record) pair. Ordinary exceptions, stack overflows from runaway
     * recursion and linkage errors (e.g. a rule class failing static initialisation) are contained. Other
     * {@link VirtualMachineError}s such as {@link OutOfMemoryError} are not: the JVM is no longer trustworthy.
     */
    private static Outcome evaluateIsolated(Rule rule, DataRecord record, String id, long index) {
        TrackingRecordView view = new TrackingRecordView(record);
        try {
            return RuleEvaluator.evaluate(rule, view, id, index);
        } catch (Exception | StackOverflowError | LinkageError e) {
            return RuleError.of(rule.id(), id, index, view.reads(), e);
        }
    }
}
