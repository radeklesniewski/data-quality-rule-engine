package io.github.radeklesniewski.dq.rule;

import io.github.radeklesniewski.dq.record.RecordView;

/**
 * The computational part of a rule: it derives a value from a record's own fields.
 *
 * <p>The value is an outcome code such as {@code "ok"}, {@code "bad"} or {@code "blocked"}. The rule's
 * {@link DecisionMapping} turns it into a {@link Decision}. Logic must <em>not</em> encode decisions itself.
 *
 * <p>Contract for implementations:
 * <ul>
 *   <li>Return a non-null value. A {@code null} is reported as a rule error.</li>
 *   <li>Be a pure function of the record: no side effects and no shared mutable state. It may run concurrently.</li>
 *   <li>Read every field the value depends on through {@code record}, so that the provenance is complete.</li>
 *   <li>Throwing is allowed. The engine isolates the failure to this (rule, record) pair and reports it.</li>
 * </ul>
 */
@FunctionalInterface
public interface RuleLogic {

    /**
     * Computes the rule's value for one record.
     *
     * @param record read-only view of the record
     * @return the computed value; never {@code null}
     */
    String compute(RecordView record);
}
