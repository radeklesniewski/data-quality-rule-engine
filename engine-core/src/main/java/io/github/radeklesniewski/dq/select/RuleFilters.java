package io.github.radeklesniewski.dq.select;

import io.github.radeklesniewski.dq.rule.Rule;
import io.github.radeklesniewski.dq.rule.RuleStatus;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/** Built-in {@link RuleFilter} implementations: immutable values. Create them through {@link RuleFilter}'s factories. */
final class RuleFilters {

    private RuleFilters() {
    }

    enum All implements RuleFilter {
        INSTANCE;

        @Override
        public boolean matches(Rule rule) {
            return true;
        }

        @Override
        public String toString() {
            return "all";
        }
    }

    record StatusIn(Set<RuleStatus> statuses) implements RuleFilter {
        @Override
        public boolean matches(Rule rule) {
            return statuses.contains(rule.status());
        }

        @Override
        public String toString() {
            return "status in " + statuses;
        }
    }

    record ApplicableTo(String countryCode) implements RuleFilter {
        @Override
        public boolean matches(Rule rule) {
            return rule.countryScope().appliesTo(Optional.of(countryCode));
        }

        @Override
        public String toString() {
            return "country " + countryCode + " (incl. WORLD)";
        }
    }

    record CategoryIn(Set<String> categories) implements RuleFilter {
        @Override
        public boolean matches(Rule rule) {
            return categories.stream().anyMatch(rule::hasCategory);
        }

        @Override
        public String toString() {
            return "category any of " + categories;
        }
    }

    record AllOf(List<RuleFilter> filters) implements RuleFilter {
        @Override
        public boolean matches(Rule rule) {
            return filters.stream().allMatch(filter -> filter.matches(rule));
        }

        @Override
        public String toString() {
            return join(filters, " and ");
        }
    }

    record AnyOf(List<RuleFilter> filters) implements RuleFilter {
        @Override
        public boolean matches(Rule rule) {
            return filters.stream().anyMatch(filter -> filter.matches(rule));
        }

        @Override
        public String toString() {
            return join(filters, " or ");
        }
    }

    record Not(RuleFilter filter) implements RuleFilter {
        Not {
            Objects.requireNonNull(filter, "filter");
        }

        @Override
        public boolean matches(Rule rule) {
            return !filter.matches(rule);
        }

        @Override
        public RuleFilter negate() {
            return filter;
        }

        @Override
        public String toString() {
            return "not (" + filter + ")";
        }
    }

    record Described(String description, Predicate<Rule> predicate) implements RuleFilter {
        @Override
        public boolean matches(Rule rule) {
            return predicate.test(rule);
        }

        @Override
        public String toString() {
            return description;
        }
    }

    static <T> Set<T> nonEmptySet(String name, Collection<T> values) {
        if (values.isEmpty()) {
            throw new IllegalArgumentException("At least one " + name + " is required");
        }
        Set<T> copy = new LinkedHashSet<>();
        for (T value : values) {
            copy.add(Objects.requireNonNull(value, name));
        }
        return Collections.unmodifiableSet(copy);
    }

    static List<RuleFilter> nonEmptyList(String name, RuleFilter... filters) {
        if (filters.length == 0) {
            throw new IllegalArgumentException("At least one of " + name + " is required");
        }
        return List.of(filters); // rejects nulls
    }

    private static String join(List<RuleFilter> filters, String operator) {
        return filters.stream().map(Object::toString).collect(Collectors.joining(operator, "(", ")"));
    }
}
