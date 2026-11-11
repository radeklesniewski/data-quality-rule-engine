package io.github.radeklesniewski.dq;

import java.util.List;
import java.util.Objects;

/**
 * Explains how a {@link Result} came about: which rule judged which record, from which field values, and how the
 * computed value was mapped to the decision.
 *
 * @param ruleId the rule that produced the result
 * @param recordId the record it judged
 * @param fieldsRead fields the rule read, in first-read order, captured automatically by the engine
 * @param computedValue the value the rule logic computed
 * @param defaultDecision {@code true} if {@code computedValue} wasn't explicitly mapped, so the default decision applied
 */
public record Provenance(
        String ruleId,
        String recordId,
        List<FieldRead> fieldsRead,
        String computedValue,
        boolean defaultDecision) {

    /** Validates components and copies {@code fieldsRead}. */
    public Provenance {
        Objects.requireNonNull(ruleId, "ruleId");
        Objects.requireNonNull(recordId, "recordId");
        fieldsRead = List.copyOf(Objects.requireNonNull(fieldsRead, "fieldsRead"));
        Objects.requireNonNull(computedValue, "computedValue");
    }
}
