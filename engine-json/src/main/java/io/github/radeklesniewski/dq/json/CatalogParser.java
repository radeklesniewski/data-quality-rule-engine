package io.github.radeklesniewski.dq.json;

import io.github.radeklesniewski.dq.rule.CountryScope;
import io.github.radeklesniewski.dq.rule.Decision;
import io.github.radeklesniewski.dq.rule.DecisionMapping;
import io.github.radeklesniewski.dq.rule.Rule;
import io.github.radeklesniewski.dq.rule.RuleLogic;
import io.github.radeklesniewski.dq.rule.RuleLogicRegistry;
import io.github.radeklesniewski.dq.rule.RuleStatus;
import io.github.radeklesniewski.dq.rule.Severity;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;
import tools.jackson.databind.JsonNode;

/**
 * Maps a catalog's JSON tree to rules: the catalog format of {@link JsonRuleCatalog}, and nothing else. Every problem
 * is collected rather than stopping at the first. Each part of a rule comes back empty when it's missing or invalid,
 * having recorded why, and a rule is built only from parts that are all present. Single use.
 */
final class CatalogParser {

    // Sorted, because error messages list them.
    private static final List<String> ROOT_PROPERTIES = List.of("description", "rules");
    private static final List<String> RULE_PROPERTIES = List.of(
            "categories", "countryScope", "decisionMapping", "id", "label", "logic", "severity", "status");
    private static final List<String> MAPPING_PROPERTIES = List.of("default", "mappings");

    private final RuleLogicRegistry registry;
    private final CatalogProblems problems = new CatalogProblems();
    private final CatalogFields fields = new CatalogFields(problems);
    private final Set<String> seenIds = new HashSet<>();

    private CatalogParser(RuleLogicRegistry registry) {
        this.registry = registry;
    }

    /**
     * Parses a whole catalog.
     *
     * @throws CatalogLoadException listing every problem found
     */
    static List<Rule> parse(JsonNode root, RuleLogicRegistry registry) {
        return new CatalogParser(registry).catalog(root);
    }

    private List<Rule> catalog(JsonNode root) {
        JsonNode rulesNode = rulesArray(root);
        List<Rule> rules = new ArrayList<>();
        for (int i = 0; i < rulesNode.size(); i++) {
            JsonNode node = rulesNode.get(i);
            String index = "rules[" + i + "]";
            if (node.isObject()) {
                rule(node, index).ifPresent(rules::add);
            } else {
                problems.add(index, "must be an object");
            }
        }
        problems.throwIfAny();
        return rules;
    }

    /** The {@code "rules"} array. Without it there's nothing more to check, so a problem here ends the load. */
    private JsonNode rulesArray(JsonNode root) {
        if (root == null || !root.isObject()) {
            throw new CatalogLoadException(List.of("Catalog must be a JSON object with a \"rules\" array"), null);
        }
        fields.rejectUnknown(root, ROOT_PROPERTIES, "catalog");
        JsonNode rulesNode = root.get("rules");
        if (rulesNode == null || !rulesNode.isArray()) {
            problems.add("catalog", "\"rules\" must be an array");
            throw problems.failure();
        }
        return rulesNode;
    }

    /** Parses one rule. Problems are located by index, plus the id once it's known: {@code rules[1] 'vatFormat'}. */
    private Optional<Rule> rule(JsonNode node, String index) {
        Optional<String> id = fields.requiredText(node, "id", index);
        String where = id.map(value -> index + " '" + value + "'").orElse(index);
        id.ifPresent(value -> requireUnique(value, where));
        fields.rejectUnknown(node, RULE_PROPERTIES, where);

        Optional<String> label = fields.requiredText(node, "label", where);
        Optional<RuleLogic> logic = logic(node, where);
        Optional<RuleStatus> status = fields.requiredEnum(node, "status", RuleStatus.class, where);
        Optional<Severity> severity = fields.requiredEnum(node, "severity", Severity.class, where);
        Optional<CountryScope> scope = countryScope(node, where);
        Optional<List<String>> categories = categories(node, where);
        Optional<DecisionMapping> mapping = decisionMapping(node, where);
        if (Stream.of(id, label, logic, status, severity, scope, categories, mapping).anyMatch(Optional::isEmpty)) {
            return Optional.empty(); // each missing part has recorded its problem
        }
        return build(where, () -> Rule.builder(id.get())
                .label(label.get())
                .logic(logic.get())
                .status(status.get())
                .severity(severity.get())
                .countryScope(scope.get())
                .categories(categories.get())
                .decisionMapping(mapping.get())
                .build());
    }

    private void requireUnique(String id, String where) {
        if (!seenIds.add(id)) {
            problems.add(where, "duplicate rule id");
        }
    }

    /** Runs the rule's own validation (e.g. a malformed id) and records its message as a problem. */
    private Optional<Rule> build(String where, Supplier<Rule> builder) {
        try {
            return Optional.of(builder.get());
        } catch (IllegalArgumentException e) {
            problems.add(where, e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<RuleLogic> logic(JsonNode node, String where) {
        return fields.requiredText(node, "logic", where).flatMap(logicId -> {
            Optional<RuleLogic> found = registry.find(logicId);
            if (found.isEmpty()) {
                problems.add(where, "logic '" + logicId + "' is not registered (known: "
                        + registry.logicIds().stream().sorted().toList() + ")");
            }
            return found;
        });
    }

    /** {@code WORLD} when absent; empty only when present but invalid. */
    private Optional<CountryScope> countryScope(JsonNode node, String where) {
        Optional<JsonNode> value = CatalogFields.optional(node, "countryScope");
        if (value.isEmpty()) {
            return Optional.of(CountryScope.world());
        }
        return fields.text(value.get(), "countryScope", where).flatMap(code -> {
            try {
                return Optional.of(CountryScope.of(code));
            } catch (IllegalArgumentException e) {
                problems.add(where, e.getMessage());
                return Optional.empty();
            }
        });
    }

    /** No categories when absent; empty only when present but invalid. */
    private Optional<List<String>> categories(JsonNode node, String where) {
        Optional<JsonNode> value = CatalogFields.optional(node, "categories");
        if (value.isEmpty()) {
            return Optional.of(List.of());
        }
        JsonNode categories = value.get();
        if (!categories.isArray()) {
            problems.add(where, "\"categories\" must be an array of strings");
            return Optional.empty();
        }
        List<String> result = new ArrayList<>();
        boolean valid = true;
        for (int i = 0; i < categories.size(); i++) {
            JsonNode category = categories.get(i);
            if (category.isString()) {
                result.add(category.stringValue());
            } else {
                problems.add(where, "categories[" + i + "] must be a string");
                valid = false;
            }
        }
        return valid ? Optional.of(result) : Optional.empty();
    }

    private Optional<DecisionMapping> decisionMapping(JsonNode node, String where) {
        JsonNode mapping = node.get("decisionMapping");
        if (mapping == null || !mapping.isObject()) {
            problems.add(where, "\"decisionMapping\" is required and must be an object");
            return Optional.empty();
        }
        String at = where + ".decisionMapping";
        fields.rejectUnknown(mapping, MAPPING_PROPERTIES, at);
        Optional<Decision> fallback = fields.requiredEnum(mapping, "default", Decision.class, at);
        Optional<Map<String, Decision>> mappings = mappings(mapping.get("mappings"), at);
        if (mappings.isEmpty() || fallback.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(DecisionMapping.of(mappings.get(), fallback.get()));
    }

    /** No mappings when absent (everything gets the default); empty only when present but invalid. */
    private Optional<Map<String, Decision>> mappings(JsonNode entries, String at) {
        if (entries == null) {
            return Optional.of(Map.of());
        }
        if (!entries.isObject()) {
            problems.add(at, "\"mappings\" must be an object of value → decision");
            return Optional.empty();
        }
        Map<String, Decision> result = new LinkedHashMap<>();
        boolean valid = true;
        for (Map.Entry<String, JsonNode> entry : entries.properties()) {
            Optional<Decision> decision = CatalogFields.parseEnum(entry.getValue(), Decision.class);
            if (decision.isPresent()) {
                result.put(entry.getKey(), decision.get());
            } else {
                problems.add(at + ".mappings['" + entry.getKey() + "']",
                        CatalogFields.notOneOf(entry.getValue(), Decision.class));
                valid = false;
            }
        }
        return valid ? Optional.of(result) : Optional.empty();
    }
}
