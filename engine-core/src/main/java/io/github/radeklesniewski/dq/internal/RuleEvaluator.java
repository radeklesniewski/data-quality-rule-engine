package io.github.radeklesniewski.dq.internal;

import io.github.radeklesniewski.dq.Provenance;
import io.github.radeklesniewski.dq.Result;
import io.github.radeklesniewski.dq.rule.DecisionMapping;
import io.github.radeklesniewski.dq.rule.Rule;
import java.util.Objects;

/**
 * Evaluates one rule against one record: logic computes the value, the decision mapping decides, and the
 * tracking view supplies provenance.
 *
 * <p>Failures propagate to the caller. Isolating them per (rule, record) is the engine's job. The caller owns
 * the {@link TrackingRecordView}, so it can still read the fields read before a failure.
 */
public final class RuleEvaluator {

    private RuleEvaluator() {
    }

    /**
     * Evaluates {@code rule} through a fresh tracking {@code view}.
     *
     * @param rule the rule
     * @param view a tracking view not used by any other evaluation
     * @param recordId the record's id, for provenance
     * @param recordIndex the record's position in the input, for provenance
     * @return the result
     * @throws RuntimeException whatever the rule logic throws, or {@link IllegalStateException} if it returns null
     */
    public static Result evaluate(Rule rule, TrackingRecordView view, String recordId, long recordIndex) {
        Objects.requireNonNull(rule, "rule");
        Objects.requireNonNull(view, "view");
        Objects.requireNonNull(recordId, "recordId");

        String value = rule.logic().compute(view);
        if (value == null) {
            throw new IllegalStateException(
                    "Logic of rule '" + rule.id() + "' returned null; rule logic must return a non-null value");
        }
        DecisionMapping mapping = rule.decisionMapping();
        Provenance provenance = new Provenance(
                rule.id(), recordId, recordIndex, view.reads(), value, !mapping.isMapped(value));
        return new Result(mapping.decide(value), rule.severity(), provenance);
    }
}
