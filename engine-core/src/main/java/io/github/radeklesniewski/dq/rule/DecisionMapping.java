package io.github.radeklesniewski.dq.rule;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Declarative, data-only mapping from a rule's computed value to a {@link Decision}, with a mandatory default
 * for any value that isn't mapped.
 *
 * <p>This is the only place a decision is made (F2). Rule logic computes a value and never chooses a decision.
 * Keeping the mapping as plain data means a catalog can store and change it without touching code.
 *
 * <p>Values match exactly (case-sensitive). Iteration order of {@link #mappings()} is the declaration order.
 *
 * <pre>{@code
 * DecisionMapping.builder()
 *         .map("ok", Decision.VALID)
 *         .map("bad", Decision.INVALID)
 *         .orElse(Decision.REVIEW);
 * }</pre>
 *
 * @param mappings explicit value to decision entries; neither keys nor values may be {@code null}
 * @param defaultDecision the decision for any value not in {@code mappings}
 */
public record DecisionMapping(Map<String, Decision> mappings, Decision defaultDecision) {

    /** Validates and defensively copies the mapping. */
    public DecisionMapping {
        Objects.requireNonNull(mappings, "mappings");
        Objects.requireNonNull(defaultDecision, "defaultDecision (every mapping needs a default)");
        Map<String, Decision> copy = new LinkedHashMap<>();
        mappings.forEach((value, decision) -> copy.put(
                Objects.requireNonNull(value, "mapped value"),
                Objects.requireNonNull(decision, () -> "decision for value '" + value + "'")));
        mappings = Collections.unmodifiableMap(copy);
    }

    /**
     * Creates a mapping from existing data, e.g. a loaded catalog.
     *
     * @param mappings explicit value to decision entries
     * @param defaultDecision the fallback decision
     * @return the mapping
     */
    public static DecisionMapping of(Map<String, Decision> mappings, Decision defaultDecision) {
        return new DecisionMapping(mappings, defaultDecision);
    }

    /**
     * Starts a fluent mapping. It is completed by {@link Builder#orElse(Decision)}, so a default can't be forgotten.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the decision for a computed value.
     *
     * @param value the value computed by rule logic
     * @return the mapped decision, or {@link #defaultDecision()} if unmapped
     */
    public Decision decide(String value) {
        Objects.requireNonNull(value, "value");
        return mappings.getOrDefault(value, defaultDecision);
    }

    /**
     * Returns whether {@code value} has an explicit entry, i.e. the default does not apply.
     *
     * @param value the value computed by rule logic
     * @return {@code true} if explicitly mapped
     */
    public boolean isMapped(String value) {
        return mappings.containsKey(Objects.requireNonNull(value, "value"));
    }

    /** Fluent builder for {@link DecisionMapping}. Not thread-safe. */
    public static final class Builder {

        private final Map<String, Decision> mappings = new LinkedHashMap<>();

        private Builder() {
        }

        /**
         * Maps a computed value to a decision.
         *
         * @param value the computed value
         * @param decision the decision
         * @return this builder
         * @throws IllegalArgumentException if {@code value} is already mapped
         */
        public Builder map(String value, Decision decision) {
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(decision, "decision");
            if (mappings.putIfAbsent(value, decision) != null) {
                throw new IllegalArgumentException("Value '" + value + "' is already mapped to " + mappings.get(value));
            }
            return this;
        }

        /**
         * Sets the default decision and builds the mapping.
         *
         * @param defaultDecision the decision for any unmapped value
         * @return the immutable mapping
         */
        public DecisionMapping orElse(Decision defaultDecision) {
            return new DecisionMapping(mappings, defaultDecision);
        }
    }
}
