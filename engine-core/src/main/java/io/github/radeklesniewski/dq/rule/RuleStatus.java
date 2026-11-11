package io.github.radeklesniewski.dq.rule;

/** Lifecycle status of a rule. The engine only ever executes {@link #RELEASED} rules. */
public enum RuleStatus {
    /** Being authored; never executed. */
    DRAFT,
    /** Approved for execution. */
    RELEASED,
    /** Retired; never executed. Kept in the catalog for traceability. */
    DEPRECATED
}
