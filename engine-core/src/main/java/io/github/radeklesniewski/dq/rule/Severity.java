package io.github.radeklesniewski.dq.rule;

/** How serious a failing check of this rule is. Carried on every result of the rule. */
public enum Severity {
    /** Data is wrong and must be fixed. */
    ERROR,
    /** Data is suspicious and should be fixed. */
    WARNING,
    /** For information only. */
    INFO
}
