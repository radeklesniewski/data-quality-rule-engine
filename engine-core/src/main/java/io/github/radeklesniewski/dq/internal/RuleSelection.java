package io.github.radeklesniewski.dq.internal;

import io.github.radeklesniewski.dq.rule.Rule;
import io.github.radeklesniewski.dq.rule.RuleCatalog;
import io.github.radeklesniewski.dq.rule.RuleStatus;
import io.github.radeklesniewski.dq.select.RuleFilter;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Snapshots and validates a catalog, then selects the rules a run may execute. Internal. */
final class RuleSelection {

    private RuleSelection() {
    }

    /**
     * Returns the rules of {@code catalog} that are RELEASED <em>and</em> selected by {@code filter}, in catalog order.
     * The RELEASED requirement is applied here, after the caller's filter, so no filter can widen it.
     *
     * @throws IllegalArgumentException if the catalog contains duplicate rule ids
     * @throws NullPointerException if the catalog returns null or contains null rules
     */
    static List<Rule> executable(RuleCatalog catalog, RuleFilter filter) {
        Objects.requireNonNull(filter, "filter");
        List<Rule> all = Objects.requireNonNull(catalog.rules(), "RuleCatalog.rules() returned null");
        requireUniqueIds(all);
        return all.stream()
                .filter(filter::matches)
                .filter(rule -> rule.status() == RuleStatus.RELEASED)
                .toList();
    }

    /** The catalog's integrity check: no null rules, no duplicate ids. Runs before any filter or record. */
    private static void requireUniqueIds(List<Rule> rules) {
        Set<String> ids = new HashSet<>();
        for (Rule rule : rules) {
            Objects.requireNonNull(rule, "RuleCatalog.rules() contains a null rule");
            if (!ids.add(rule.id())) {
                throw new IllegalArgumentException("Duplicate rule id in catalog: '" + rule.id() + "'");
            }
        }
    }
}
