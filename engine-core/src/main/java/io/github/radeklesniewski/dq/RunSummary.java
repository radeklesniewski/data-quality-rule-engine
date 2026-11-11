package io.github.radeklesniewski.dq;

import io.github.radeklesniewski.dq.rule.Decision;
import io.github.radeklesniewski.dq.rule.Severity;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Totals for one validation run (F4), computed incrementally from counters. Results are never retained.
 *
 * @param recordsRead records taken from the source, including unreadable ones
 * @param batches batches processed
 * @param resultCount results emitted
 * @param ruleErrorCount rule errors emitted
 * @param recordErrorCount record errors emitted
 * @param resultsByDecision result count per decision; every decision is present, possibly with 0
 * @param resultsBySeverity result count per severity; every severity is present, possibly with 0
 */
public record RunSummary(
        long recordsRead,
        long batches,
        long resultCount,
        long ruleErrorCount,
        long recordErrorCount,
        Map<Decision, Long> resultsByDecision,
        Map<Severity, Long> resultsBySeverity) {

    /** Validates components and completes and copies the breakdowns. */
    public RunSummary {
        resultsByDecision = complete(Decision.class, resultsByDecision, "resultsByDecision");
        resultsBySeverity = complete(Severity.class, resultsBySeverity, "resultsBySeverity");
    }

    /**
     * Returns the total number of errors.
     *
     * @return rule errors plus record errors
     */
    public long errorCount() {
        return ruleErrorCount + recordErrorCount;
    }

    /**
     * Returns how many results had the given decision.
     *
     * @param decision the decision
     * @return how many results had {@code decision}
     */
    public long count(Decision decision) {
        return resultsByDecision.get(Objects.requireNonNull(decision, "decision"));
    }

    /**
     * Returns how many results had the given severity.
     *
     * @param severity the severity
     * @return how many results had {@code severity}
     */
    public long count(Severity severity) {
        return resultsBySeverity.get(Objects.requireNonNull(severity, "severity"));
    }

    private static <E extends Enum<E>> Map<E, Long> complete(Class<E> type, Map<E, Long> counts, String name) {
        Objects.requireNonNull(counts, name);
        EnumMap<E, Long> copy = new EnumMap<>(type);
        for (E key : type.getEnumConstants()) {
            copy.put(key, counts.getOrDefault(key, 0L));
        }
        return Collections.unmodifiableMap(copy);
    }
}
