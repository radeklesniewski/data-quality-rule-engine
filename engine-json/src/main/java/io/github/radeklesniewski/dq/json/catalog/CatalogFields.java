package io.github.radeklesniewski.dq.json.catalog;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

/**
 * Typed, validated access to the properties of a catalog's JSON objects. Each method returns empty when a value is
 * missing or has the wrong type, after recording why in the shared {@link CatalogProblems}. Knows nothing about
 * rules.
 */
final class CatalogFields {

    private final CatalogProblems problems;

    CatalogFields(CatalogProblems problems) {
        this.problems = problems;
    }

    /** The property, or empty if it's absent or JSON {@code null}. Absence isn't a problem here. */
    static Optional<JsonNode> optional(JsonNode node, String name) {
        JsonNode value = node.get(name);
        return value == null || value.isNull() ? Optional.empty() : Optional.of(value);
    }

    /** Like {@link #optional}, but an absent property is a problem. */
    Optional<JsonNode> required(JsonNode node, String name, String where) {
        Optional<JsonNode> value = optional(node, name);
        if (value.isEmpty()) {
            problems.add(where, quoted(name) + " is required");
        }
        return value;
    }

    Optional<String> requiredText(JsonNode node, String name, String where) {
        return required(node, name, where).flatMap(value -> text(value, name, where));
    }

    <E extends Enum<E>> Optional<E> requiredEnum(JsonNode node, String name, Class<E> type, String where) {
        return required(node, name, where).flatMap(value -> {
            Optional<E> parsed = parseEnum(value, type);
            if (parsed.isEmpty()) {
                problems.add(where, quoted(name) + " " + notOneOf(value, type));
            }
            return parsed;
        });
    }

    /** The value as a string; anything else is a problem. */
    Optional<String> text(JsonNode value, String name, String where) {
        if (!value.isString()) {
            problems.add(where, quoted(name) + " must be a string");
            return Optional.empty();
        }
        return Optional.of(value.stringValue());
    }

    /** Records a problem for every property that isn't in {@code allowed}: usually a typo. */
    void rejectUnknown(JsonNode node, List<String> allowed, String where) {
        for (Map.Entry<String, JsonNode> property : node.properties()) {
            if (!allowed.contains(property.getKey())) {
                problems.add(where, "unknown property " + quoted(property.getKey()) + " (allowed: " + allowed + ")");
            }
        }
    }

    /** The constant whose name is exactly the string {@code value}; empty for anything else. */
    static <E extends Enum<E>> Optional<E> parseEnum(JsonNode value, Class<E> type) {
        if (!value.isString()) {
            return Optional.empty();
        }
        return Arrays.stream(type.getEnumConstants()).filter(e -> e.name().equals(value.stringValue())).findFirst();
    }

    /** The message for a value that isn't one of {@code type}'s constants. */
    static String notOneOf(JsonNode value, Class<? extends Enum<?>> type) {
        return "must be one of " + Arrays.toString(type.getEnumConstants()) + ", got " + value;
    }

    private static String quoted(String name) {
        return "\"" + name + "\"";
    }
}
