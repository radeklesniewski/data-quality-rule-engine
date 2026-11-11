package io.github.radeklesniewski.dq.rule;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Supplies the rules for a run. This is the seam for catalog sources: in code, a JSON file, a database, a remote
 * service and so on.
 *
 * <p>The engine calls {@link #rules()} once at the start of each run and works on that snapshot, so a catalog that
 * changes over time never changes mid-run. The returned list may contain rules of any status. The engine executes
 * only {@link RuleStatus#RELEASED} ones. Rule ids must be unique, or the run fails before reading any record.
 */
@FunctionalInterface
public interface RuleCatalog {

    /**
     * Returns the current rules, in evaluation order.
     *
     * @return the current rules, in evaluation order; no {@code null} elements
     */
    List<Rule> rules();

    /**
     * Returns a fixed catalog.
     *
     * @param rules the rules, in evaluation order
     * @return a fixed catalog of {@code rules}
     */
    static RuleCatalog of(Rule... rules) {
        return of(Arrays.asList(rules));
    }

    /**
     * Returns a fixed catalog, copying {@code rules}.
     *
     * @param rules the rules, in evaluation order
     * @return a fixed catalog of {@code rules}
     */
    static RuleCatalog of(Collection<Rule> rules) {
        List<Rule> copy = List.copyOf(Objects.requireNonNull(rules, "rules"));
        return () -> copy;
    }
}
