package io.github.radeklesniewski.dq;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Explains how a {@link Result} came about: which rule judged which record, from which field values, and how the
 * decision was reached.
 *
 * @param ruleId the rule that produced the result
 * @param recordId the record it judged
 * @param recordIndex zero-based position of the record in the run's input, for locating it even without the id
 * @param basis how the decision was reached
 * @param fieldsRead fields read to reach the decision, in first-read order. For {@link Basis#EVALUATED} these are
 *     the fields the rule logic read, captured automatically. For {@link Basis#OUT_OF_SCOPE} it's the record's country.
 * @param computedValue the value the rule logic computed; present if and only if {@code basis} is
 *     {@link Basis#EVALUATED}
 * @param defaultDecision {@code true} if {@code computedValue} wasn't explicitly mapped, so the default decision applied
 */
public record Provenance(
        String ruleId,
        String recordId,
        long recordIndex,
        Basis basis,
        List<FieldRead> fieldsRead,
        Optional<String> computedValue,
        boolean defaultDecision) {

    /** How a result's decision was reached. */
    public enum Basis {
        /** The rule's logic computed a value and its decision mapping turned it into the decision. */
        EVALUATED,
        /**
         * The rule wasn't executed because the record is outside its country scope. The decision is
         * {@code NOT_APPLICABLE}. Only emitted when {@link RunOptions#emitOutOfScope()} is on.
         */
        OUT_OF_SCOPE
    }

    /** Validates components and copies {@code fieldsRead}. */
    public Provenance {
        Objects.requireNonNull(ruleId, "ruleId");
        Objects.requireNonNull(recordId, "recordId");
        if (recordIndex < 0) {
            throw new IllegalArgumentException("recordIndex must be >= 0, got " + recordIndex);
        }
        Objects.requireNonNull(basis, "basis");
        fieldsRead = List.copyOf(Objects.requireNonNull(fieldsRead, "fieldsRead"));
        Objects.requireNonNull(computedValue, "computedValue");
        if (computedValue.isPresent() != (basis == Basis.EVALUATED)) {
            throw new IllegalArgumentException("computedValue must be present exactly when basis is EVALUATED");
        }
        if (defaultDecision && basis != Basis.EVALUATED) {
            throw new IllegalArgumentException("defaultDecision applies only when basis is EVALUATED");
        }
    }

    /**
     * Provenance of a result computed by rule logic and mapped to a decision.
     *
     * @param ruleId the rule
     * @param recordId the record id
     * @param recordIndex the record's position in the input
     * @param fieldsRead fields the logic read
     * @param computedValue the computed value
     * @param defaultDecision whether the mapping's default applied
     * @return the provenance
     */
    public static Provenance evaluated(String ruleId, String recordId, long recordIndex, List<FieldRead> fieldsRead,
            String computedValue, boolean defaultDecision) {
        return new Provenance(ruleId, recordId, recordIndex, Basis.EVALUATED, fieldsRead,
                Optional.of(computedValue), defaultDecision);
    }

    /**
     * Provenance of a {@code NOT_APPLICABLE} result for a rule skipped because of its country scope.
     *
     * @param ruleId the rule
     * @param recordId the record id
     * @param recordIndex the record's position in the input
     * @param country the record's country field as read by the engine
     * @return the provenance
     */
    public static Provenance outOfScope(String ruleId, String recordId, long recordIndex, FieldRead country) {
        return new Provenance(ruleId, recordId, recordIndex, Basis.OUT_OF_SCOPE, List.of(country),
                Optional.empty(), false);
    }
}
