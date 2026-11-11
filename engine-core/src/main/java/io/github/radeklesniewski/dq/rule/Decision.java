package io.github.radeklesniewski.dq.rule;

/** The verdict of one rule for one record, produced by a {@link DecisionMapping}. */
public enum Decision {
    /** The record passes the check. */
    VALID,
    /** The record fails the check. */
    INVALID,
    /** The check can't decide automatically; a human should look at it. */
    REVIEW,
    /** The rule ran but decided the check doesn't apply to this record. */
    NOT_APPLICABLE
}
