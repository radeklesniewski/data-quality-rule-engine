package io.github.radeklesniewski.dq.internal;

import io.github.radeklesniewski.dq.rule.Rule;
import io.github.radeklesniewski.dq.rule.RuleCatalog;
import io.github.radeklesniewski.dq.rule.RuleStatus;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Snapshots and validates a catalog, then selects the rules a run may execute. Internal. */
final class RuleSelection {

    private RuleSelection() {
    }

    /**
     * Returns the RELEASED rules of {@code catalog}, in catalog order.
     *
     * @throws IllegalArgumentException if the catalog contains duplicate rule ids
     * @throws NullPointerException if the catalog returns null or contains null rules
     */
    static List<Rule> executable(RuleCatalog catalog) {
        List<Rule> all = Objects.requireNonNull(catalog.rules(), "RuleCatalog.rules() returned null");
        Set<String> ids = new HashSet<>();
        for (Rule rule : all) {
            Objects.requireNonNull(rule, "RuleCatalog.rules() contains a null rule");
            if (!ids.add(rule.id())) {
                throw new IllegalArgumentException("Duplicate rule id in catalog: '" + rule.id() + "'");
            }
        }
        return all.stream().filter(rule -> rule.status() == RuleStatus.RELEASED).toList();
    }
}
