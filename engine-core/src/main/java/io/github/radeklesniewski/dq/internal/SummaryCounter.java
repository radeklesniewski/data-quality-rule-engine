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
import java.util.stream.LongStream;

/** Mutable run counters. Constant memory, independent of input size. Not thread-safe. Internal. */
final class SummaryCounter {

    private final int rulesSelected;
    private long recordsRead;
    private long batches;
    private long ruleErrors;
    private long recordErrors;
    private final long[] byDecision = new long[Decision.values().length];
    private final long[] bySeverity = new long[Severity.values().length];

    SummaryCounter(int rulesSelected) {
        this.rulesSelected = rulesSelected;
    }

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
        return new RunSummary(rulesSelected, recordsRead, batches, LongStream.of(byDecision).sum(), ruleErrors,
                recordErrors, counts(Decision.class, byDecision), counts(Severity.class, bySeverity));
    }

    /** The counts indexed by ordinal, as a map with every constant of {@code type} present. */
    private static <E extends Enum<E>> Map<E, Long> counts(Class<E> type, long[] byOrdinal) {
        Map<E, Long> counts = new EnumMap<>(type);
        for (E key : type.getEnumConstants()) {
            counts.put(key, byOrdinal[key.ordinal()]);
        }
        return counts;
    }
}
