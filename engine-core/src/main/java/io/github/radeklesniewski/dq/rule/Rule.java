package io.github.radeklesniewski.dq.rule;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * A named data-quality check: {@link RuleLogic} computes a value from a record, and a {@link DecisionMapping}
 * turns that value into a {@link Decision}.
 *
 * <p>Immutable and thread-safe, provided {@code logic} honours its contract. Equality is component-wise, so two
 * rules are equal only if they share the same {@code logic} instance. Catalogs identify rules by {@link #id()}.
 *
 * @param id stable identifier, unique within a catalog; letters, digits, {@code . _ -}; must start with a letter or
 *     digit
 * @param label human-readable name
 * @param logic computes the value
 * @param decisionMapping maps the value to a decision
 * @param status lifecycle status; only {@link RuleStatus#RELEASED} rules are executed
 * @param severity severity attached to every result of this rule
 * @param countryScope which records the rule applies to
 * @param categories zero or more free-form, non-blank category names (declaration order preserved)
 */
public record Rule(
        String id,
        String label,
        RuleLogic logic,
        DecisionMapping decisionMapping,
        RuleStatus status,
        Severity severity,
        CountryScope countryScope,
        Set<String> categories) {

    /** Validates all components and defensively copies {@code categories}. */
    public Rule {
        RuleIds.requireValid("Rule id", id);
        Objects.requireNonNull(label, () -> "label of rule '" + id + "'");
        if (label.isBlank()) {
            throw new IllegalArgumentException("Label of rule '" + id + "' must not be blank");
        }
        Objects.requireNonNull(logic, () -> "logic of rule '" + id + "'");
        Objects.requireNonNull(decisionMapping, () -> "decisionMapping of rule '" + id + "'");
        Objects.requireNonNull(status, () -> "status of rule '" + id + "'");
        Objects.requireNonNull(severity, () -> "severity of rule '" + id + "'");
        Objects.requireNonNull(countryScope, () -> "countryScope of rule '" + id + "'");
        categories = copyCategories(id, categories);
    }

    /**
     * Starts building a rule. Defaults: status {@link RuleStatus#DRAFT} (so a rule never runs by accident),
     * scope {@link CountryScope#world()}, no categories. Label, logic, mapping and severity are required.
     *
     * @param id the rule id
     * @return a new builder
     */
    public static Builder builder(String id) {
        return new Builder(id);
    }

    /**
     * Returns whether the rule belongs to {@code category}.
     *
     * @param category the category name
     * @return {@code true} if the rule has that category
     */
    public boolean hasCategory(String category) {
        return categories.contains(category);
    }

    private static Set<String> copyCategories(String id, Set<String> categories) {
        Objects.requireNonNull(categories, () -> "categories of rule '" + id + "'");
        Set<String> copy = new LinkedHashSet<>();
        for (String category : categories) {
            Objects.requireNonNull(category, () -> "category of rule '" + id + "'");
            if (category.isBlank()) {
                throw new IllegalArgumentException("Categories of rule '" + id + "' must not be blank");
            }
            copy.add(category);
        }
        return Collections.unmodifiableSet(copy);
    }

    /** Fluent builder for {@link Rule}. Not thread-safe. Validation happens in {@link #build()}. */
    public static final class Builder {

        private final String id;
        private String label;
        private RuleLogic logic;
        private DecisionMapping decisionMapping;
        private RuleStatus status = RuleStatus.DRAFT;
        private Severity severity;
        private CountryScope countryScope = CountryScope.world();
        private final Set<String> categories = new LinkedHashSet<>();

        private Builder(String id) {
            this.id = id;
        }

        /**
         * Sets the human label.
         *
         * @param label the label
         * @return this builder
         */
        public Builder label(String label) {
            this.label = label;
            return this;
        }

        /**
         * Sets the value-computing logic.
         *
         * @param logic the logic
         * @return this builder
         */
        public Builder logic(RuleLogic logic) {
            this.logic = logic;
            return this;
        }

        /**
         * Sets the value-to-decision mapping.
         *
         * @param decisionMapping the mapping
         * @return this builder
         */
        public Builder decisionMapping(DecisionMapping decisionMapping) {
            this.decisionMapping = decisionMapping;
            return this;
        }

        /**
         * Sets the lifecycle status (default {@link RuleStatus#DRAFT}).
         *
         * @param status the status
         * @return this builder
         */
        public Builder status(RuleStatus status) {
            this.status = status;
            return this;
        }

        /**
         * Sets the severity.
         *
         * @param severity the severity
         * @return this builder
         */
        public Builder severity(Severity severity) {
            this.severity = severity;
            return this;
        }

        /**
         * Sets the country scope (default {@link CountryScope#world()}).
         *
         * @param countryScope the scope
         * @return this builder
         */
        public Builder countryScope(CountryScope countryScope) {
            this.countryScope = countryScope;
            return this;
        }

        /**
         * Adds categories.
         *
         * @param categories category names
         * @return this builder
         */
        public Builder categories(String... categories) {
            return categories(Arrays.asList(categories));
        }

        /**
         * Adds categories.
         *
         * @param categories category names
         * @return this builder
         */
        public Builder categories(Collection<String> categories) {
            this.categories.addAll(categories);
            return this;
        }

        /**
         * Builds the rule.
         *
         * @return the immutable rule
         * @throws NullPointerException if a required component is missing
         * @throws IllegalArgumentException if a component is invalid
         */
        public Rule build() {
            return new Rule(id, label, logic, decisionMapping, status, severity, countryScope, categories);
        }
    }
}
