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
 * <p><strong>Breakdowns count results, not violations.</strong> {@link #resultsBySeverity()} counts every result
 * under its rule's severity, whatever the decision. A {@code VALID} or {@code NOT_APPLICABLE} result of an
 * {@code ERROR} rule counts as {@code ERROR}. This is the literal reading of "results by severity". A host that wants
 * "violations by severity" counts the {@code INVALID}/{@code REVIEW} results it receives in its sink.
 *
 * <p>Errors aren't results: they're counted in {@link #ruleErrorCount()} and {@link #recordErrorCount()} only.
 * Counters are incremented just before an outcome is handed to the sink. If the sink throws, the run aborts and no
 * summary is returned, so the counts and the delivered outcomes can't diverge.
 *
 * @param rulesSelected rules that took part: RELEASED and selected by the run's filter. 0 means nothing could run
 * @param recordsRead records taken from the source, including unreadable ones
 * @param batches batches processed
 * @param resultCount results emitted (including out-of-scope {@code NOT_APPLICABLE} results, if enabled)
 * @param ruleErrorCount rule errors emitted
 * @param recordErrorCount record errors emitted
 * @param resultsByDecision result count per decision; every decision is present, possibly with 0
 * @param resultsBySeverity result count per severity; every severity is present, possibly with 0
 */
public record RunSummary(
        int rulesSelected,
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
