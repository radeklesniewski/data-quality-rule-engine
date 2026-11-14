package io.github.radeklesniewski.dq.select;

import io.github.radeklesniewski.dq.rule.CountryScope;
import io.github.radeklesniewski.dq.rule.Rule;
import io.github.radeklesniewski.dq.rule.RuleStatus;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Selects which catalog rules take part in a run (F6). Filters compose with {@link #and}, {@link #or} and
 * {@link #negate}, or with {@link #allOf} and {@link #anyOf}:
 *
 * <pre>{@code
 * RuleFilter filter = RuleFilter.country("DE")
 *         .and(RuleFilter.category("tax", "bank"))
 *         .and(RuleFilter.status(RuleStatus.DRAFT).negate());
 * }</pre>
 *
 * <p><strong>A filter can only narrow.</strong> Whatever a filter selects, the engine runs only
 * {@link RuleStatus#RELEASED} rules, and runs each one only on records within its country scope. A filter that
 * selects DRAFT rules therefore runs nothing.
 *
 * <p>The built-in filters are immutable values with structural equality and a readable {@code toString()}, so a
 * run's selection can be logged and compared. Hosts may implement their own filters. Implementations must be
 * pure and thread-safe. The engine applies the filter once per run, before reading any record.
 */
@FunctionalInterface
public interface RuleFilter {

    /**
     * Returns whether {@code rule} is selected.
     *
     * @param rule a catalog rule
     * @return {@code true} if {@code rule} is selected
     */
    boolean matches(Rule rule);

    /**
     * Returns a filter that selects only rules this filter <em>and</em> {@code other} select.
     *
     * @param other the other filter
     * @return the conjunction
     */
    default RuleFilter and(RuleFilter other) {
        return allOf(this, other);
    }

    /**
     * Returns a filter that selects rules this filter <em>or</em> {@code other} selects.
     *
     * @param other the other filter
     * @return the disjunction
     */
    default RuleFilter or(RuleFilter other) {
        return anyOf(this, other);
    }

    /**
     * Returns a filter that selects exactly the rules this filter rejects.
     *
     * @return the negation
     */
    default RuleFilter negate() {
        return new RuleFilters.Not(this);
    }

    /**
     * Returns the filter that selects every rule. This is the default for a run.
     *
     * @return the filter that selects every rule
     */
    static RuleFilter all() {
        return RuleFilters.All.INSTANCE;
    }

    /**
     * Selects rules whose status is any of {@code statuses}.
     *
     * @param statuses one or more statuses
     * @return the filter
     */
    static RuleFilter status(RuleStatus... statuses) {
        return new RuleFilters.StatusIn(RuleFilters.nonEmptySet("status", Arrays.asList(statuses)));
    }

    /**
     * Selects rules that can apply to records of {@code countryCode}: rules scoped to {@link CountryScope#world()}
     * and rules scoped to exactly that country.
     *
     * @param countryCode a two-letter upper-case country code, e.g. {@code "DE"}
     * @return the filter
     * @throws IllegalArgumentException if {@code countryCode} isn't a valid code
     */
    static RuleFilter country(String countryCode) {
        return new RuleFilters.ApplicableTo(new CountryScope.Country(countryCode));
    }

    /**
     * Selects rules that have at least one of {@code categories}. Rules without categories never match.
     * Combine with {@link #and} to require several categories.
     *
     * @param categories one or more category names
     * @return the filter
     */
    static RuleFilter category(String... categories) {
        return new RuleFilters.CategoryIn(RuleFilters.nonEmptySet("category", Arrays.asList(categories)));
    }

    /**
     * Selects rules that every one of {@code filters} selects.
     *
     * @param filters one or more filters
     * @return the conjunction
     */
    static RuleFilter allOf(RuleFilter... filters) {
        return new RuleFilters.AllOf(RuleFilters.nonEmptyList("filters", filters));
    }

    /**
     * Selects rules that at least one of {@code filters} selects.
     *
     * @param filters one or more filters
     * @return the disjunction
     */
    static RuleFilter anyOf(RuleFilter... filters) {
        return new RuleFilters.AnyOf(RuleFilters.nonEmptyList("filters", filters));
    }

    /**
     * Wraps an arbitrary predicate with a description for logs, e.g. to select rules by id.
     *
     * @param description shown by {@code toString()}
     * @param predicate the selection logic; must be pure and thread-safe
     * @return the filter
     */
    static RuleFilter of(String description, Predicate<Rule> predicate) {
        return new RuleFilters.Described(Objects.requireNonNull(description, "description"),
                Objects.requireNonNull(predicate, "predicate"));
    }

    /**
     * Returns the rules of {@code rules} that this filter selects, in order. A convenience for hosts listing rules.
     *
     * @param rules rules to filter
     * @return the selected rules
     */
    default List<Rule> select(List<Rule> rules) {
        return rules.stream().filter(this::matches).toList();
    }
}
