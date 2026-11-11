package io.github.radeklesniewski.dq.internal;

import io.github.radeklesniewski.dq.Outcome;
import io.github.radeklesniewski.dq.RecordError;
import io.github.radeklesniewski.dq.Result;
import io.github.radeklesniewski.dq.RuleError;
import io.github.radeklesniewski.dq.RunSummary;
import io.github.radeklesniewski.dq.rule.Decision;
import io.github.radeklesniewski.dq.rule.Severity;
import java.util.EnumMap;
import java.util.Map;

/** Mutable run counters. Constant memory, independent of input size. Not thread-safe. Internal. */
final class SummaryCounter {

    private long recordsRead;
    private long batches;
    private long ruleErrors;
    private long recordErrors;
    private final long[] byDecision = new long[Decision.values().length];
    private final long[] bySeverity = new long[Severity.values().length];

    void recordRead() {
        recordsRead++;
    }

    void batchCompleted() {
        batches++;
    }

    void count(Outcome outcome) {
        switch (outcome) {
            case Result result -> {
                byDecision[result.decision().ordinal()]++;
                bySeverity[result.severity().ordinal()]++;
            }
            case RuleError ignored -> ruleErrors++;
            case RecordError ignored -> recordErrors++;
        }
    }

    RunSummary snapshot() {
        Map<Decision, Long> decisions = new EnumMap<>(Decision.class);
        long results = 0;
        for (Decision decision : Decision.values()) {
            decisions.put(decision, byDecision[decision.ordinal()]);
            results += byDecision[decision.ordinal()];
        }
        Map<Severity, Long> severities = new EnumMap<>(Severity.class);
        for (Severity severity : Severity.values()) {
            severities.put(severity, bySeverity[severity.ordinal()]);
        }
        return new RunSummary(recordsRead, batches, results, ruleErrors, recordErrors, decisions, severities);
    }
}
