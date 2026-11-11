package io.github.radeklesniewski.dq;

import io.github.radeklesniewski.dq.rule.Decision;
import io.github.radeklesniewski.dq.rule.Severity;
import java.util.Objects;

/**
 * What one rule produced for one record: the decision, the rule's severity and the provenance explaining it.
 *
 * @param decision the decision derived through the rule's decision mapping
 * @param severity the rule's severity
 * @param provenance which rule, which record, which fields and values, and how the value was mapped
 */
public record Result(Decision decision, Severity severity, Provenance provenance) {

    /** Validates components. */
    public Result {
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(provenance, "provenance");
    }

    /**
     * Returns the id of the rule that produced this result.
     *
     * @return the rule id, from the provenance
     */
    public String ruleId() {
        return provenance.ruleId();
    }

    /**
     * Returns the id of the record this result is about.
     *
     * @return the record id, from the provenance
     */
    public String recordId() {
        return provenance.recordId();
    }
}
