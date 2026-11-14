package io.github.radeklesniewski.dq.json;

import io.github.radeklesniewski.dq.rule.CountryScope;
import io.github.radeklesniewski.dq.rule.Decision;
import io.github.radeklesniewski.dq.rule.DecisionMapping;
import io.github.radeklesniewski.dq.rule.Rule;
import io.github.radeklesniewski.dq.rule.RuleCatalog;
import io.github.radeklesniewski.dq.rule.RuleLogic;
import io.github.radeklesniewski.dq.rule.RuleLogicRegistry;
import io.github.radeklesniewski.dq.rule.RuleStatus;
import io.github.radeklesniewski.dq.rule.Severity;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import tools.jackson.core.JacksonException;
import tools.jackson.core.exc.JacksonIOException;
import tools.jackson.databind.JsonNode;

/**
 * Loads a {@link RuleCatalog} from JSON: rule metadata and decision mappings as data, with logic referenced by id
 * and resolved through a {@link RuleLogicRegistry} (ADR-0004).
 *
 * <pre>{@code
 * {
 *   "rules": [
 *     {
 *       "id": "vatFormat",
 *       "label": "VAT id has a plausible format",
 *       "logic": "vatFormat",
 *       "status": "RELEASED",
 *       "severity": "ERROR",
 *       "countryScope": "WORLD",
 *       "categories": ["tax"],
 *       "decisionMapping": { "mappings": { "ok": "VALID", "bad": "INVALID" }, "default": "REVIEW" }
 *     }
 *   ]
 * }
 * }</pre>
 *
 * <p>{@code countryScope} (default {@code "WORLD"}) and {@code categories} (default none) are optional, and so is a
 * top-level {@code "description"} for the catalog's authors. Everything else is required, including {@code status}:
 * in a data file it's better stated than defaulted.
 *
 * <p>Loading is strict and all-or-nothing. Unknown properties (usually typos), invalid enum values, unknown logic
 * ids, duplicate rule ids and invalid rules are all collected and reported together in one
 * {@link CatalogLoadException}, so a catalog author can fix every problem in one pass.
 */
public final class JsonRuleCatalog {

    // Sorted, because error messages list them.
    private static final List<String> ROOT_PROPERTIES = List.of("description", "rules");
    private static final List<String> RULE_PROPERTIES = List.of(
            "categories", "countryScope", "decisionMapping", "id", "label", "logic", "severity", "status");
    private static final List<String> MAPPING_PROPERTIES = List.of("default", "mappings");

    private JsonRuleCatalog() {
    }

    /**
     * Loads a fixed catalog from a stream.
     *
     * @param in the JSON; read fully, not closed
     * @param logic resolves the {@code logic} ids
     * @return a fixed catalog
     * @throws CatalogLoadException listing every problem found
     * @throws UncheckedIOException if the stream can't be read
     */
    public static RuleCatalog load(InputStream in, RuleLogicRegistry logic) {
        Objects.requireNonNull(in, "in");
        Objects.requireNonNull(logic, "logic");
        return load(in, logic, "rule catalog");
    }

    /**
     * Loads a fixed catalog from a file.
     *
     * @param file the JSON file
     * @param logic resolves the {@code logic} ids
     * @return a fixed catalog
     * @throws CatalogLoadException listing every problem found
     * @throws UncheckedIOException if the file can't be read
     */
    public static RuleCatalog load(Path file, RuleLogicRegistry logic) {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(logic, "logic");
        try (InputStream in = Files.newInputStream(file)) {
            return load(in, logic, "rule catalog " + file);
        } catch (IOException e) {
            throw cantRead(file, e);
        }
    }

    /**
     * Returns a catalog backed by a file that is re-read when the file changes. The engine snapshots a catalog once
     * per run, so an edit takes effect at the next run and never mid-run. An invalid edit makes the next run fail
     * fast with {@link CatalogLoadException} rather than silently running stale rules.
     *
     * @param file the JSON file; validated immediately
     * @param logic resolves the {@code logic} ids
     * @return a reloading catalog, thread-safe
     * @throws CatalogLoadException if the file is invalid now
     */
    public static RuleCatalog watching(Path file, RuleLogicRegistry logic) {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(logic, "logic");
        return new WatchingCatalog(file, logic);
    }

    private static RuleCatalog load(InputStream in, RuleLogicRegistry logic, String source) {
        JsonNode root;
        try {
            root = Json.MAPPER.readTree(in);
        } catch (JacksonIOException e) {
            throw Json.ioFailure("Can't read " + source, e);
        } catch (JacksonException e) {
            throw new CatalogLoadException(List.of("Catalog is not valid JSON: " + e.getOriginalMessage()), e);
        }
        return RuleCatalog.of(new Parser(logic).rules(root));
    }

    private static UncheckedIOException cantRead(Path file, IOException e) {
        return new UncheckedIOException("Can't read rule catalog " + file, e);
    }

    private static final class WatchingCatalog implements RuleCatalog {
        private final Path file;
        private final RuleLogicRegistry logic;
        private FileTime loadedVersion;
        private List<Rule> rules;

        WatchingCatalog(Path file, RuleLogicRegistry logic) {
            this.file = file;
            this.logic = logic;
            rules();
        }

        @Override
        public synchronized List<Rule> rules() {
            FileTime version;
            try {
                version = Files.getLastModifiedTime(file);
            } catch (IOException e) {
                throw cantRead(file, e);
            }
            if (!version.equals(loadedVersion)) {
                rules = load(file, logic).rules();
                loadedVersion = version;
            }
            return rules;
        }

        @Override
        public String toString() {
            return "JsonRuleCatalog[watching " + file + "]";
        }
    }

    /**
     * Turns a catalog tree into rules, collecting every problem rather than stopping at the first. Each method
     * returns empty when its part is missing or invalid, having recorded why; a rule is built only from parts that
     * are all present. Single use.
     */
    private static final class Parser {
        private final RuleLogicRegistry registry;
        private final List<String> problems = new ArrayList<>();

        Parser(RuleLogicRegistry registry) {
            this.registry = registry;
        }

        List<Rule> rules(JsonNode root) {
            if (root == null || !root.isObject()) {
                throw new CatalogLoadException(List.of("Catalog must be a JSON object with a \"rules\" array"), null);
            }
            unknownProperties(root, ROOT_PROPERTIES, "catalog");
            JsonNode rulesNode = root.get("rules");
            if (rulesNode == null || !rulesNode.isArray()) {
                problems.add("catalog: \"rules\" must be an array");
                throw new CatalogLoadException(problems, null);
            }

            List<Rule> rules = new ArrayList<>();
            Set<String> ids = new HashSet<>();
            for (int i = 0; i < rulesNode.size(); i++) {
                JsonNode node = rulesNode.get(i);
                if (node.isObject()) {
                    rule(node, "rules[" + i + "]", ids).ifPresent(rules::add);
                } else {
                    problems.add("rules[" + i + "]: must be an object");
                }
            }
            if (!problems.isEmpty()) {
                throw new CatalogLoadException(problems, null);
            }
            return rules;
        }

        /** Parses one rule. Problems are located by index, plus the id once it's known: {@code rules[1] 'vatFormat'}. */
        private Optional<Rule> rule(JsonNode node, String index, Set<String> seenIds) {
            Optional<String> id = requiredText(node, "id", index);
            String where = id.map(value -> index + " '" + value + "'").orElse(index);
            if (id.isPresent() && !seenIds.add(id.get())) {
                problems.add(where + ": duplicate rule id");
            }
            unknownProperties(node, RULE_PROPERTIES, where);
            Optional<String> label = requiredText(node, "label", where);
            Optional<RuleLogic> logic = logic(node, where);
            Optional<RuleStatus> status = requiredEnum(node, "status", RuleStatus.class, where);
            Optional<Severity> severity = requiredEnum(node, "severity", Severity.class, where);
            Optional<CountryScope> scope = countryScope(node, where);
            Optional<List<String>> categories = categories(node, where);
            Optional<DecisionMapping> mapping = decisionMapping(node, where);
            if (Stream.of(id, label, logic, status, severity, scope, categories, mapping).anyMatch(Optional::isEmpty)) {
                return Optional.empty(); // each missing part has recorded its problem
            }
            try {
                return Optional.of(Rule.builder(id.get())
                        .label(label.get())
                        .logic(logic.get())
                        .status(status.get())
                        .severity(severity.get())
                        .countryScope(scope.get())
                        .categories(categories.get())
                        .decisionMapping(mapping.get())
                        .build());
            } catch (IllegalArgumentException e) { // the rule's own validation, e.g. a malformed id
                problems.add(where + ": " + e.getMessage());
                return Optional.empty();
            }
        }

        private Optional<RuleLogic> logic(JsonNode node, String where) {
            return requiredText(node, "logic", where).flatMap(logicId -> {
                Optional<RuleLogic> found = registry.find(logicId);
                if (found.isEmpty()) {
                    problems.add(where + ": logic '" + logicId + "' is not registered (known: "
                            + registry.logicIds().stream().sorted().toList() + ")");
                }
                return found;
            });
        }

        /** {@code WORLD} when absent; empty only when present but invalid. */
        private Optional<CountryScope> countryScope(JsonNode node, String where) {
            Optional<JsonNode> value = optional(node, "countryScope");
            if (value.isEmpty()) {
                return Optional.of(CountryScope.world());
            }
            return text(value.get(), "countryScope", where).flatMap(code -> {
                try {
                    return Optional.of(CountryScope.of(code));
                } catch (IllegalArgumentException e) {
                    problems.add(where + ": " + e.getMessage());
                    return Optional.empty();
                }
            });
        }

        /** No categories when absent; empty only when present but invalid. */
        private Optional<List<String>> categories(JsonNode node, String where) {
            Optional<JsonNode> value = optional(node, "categories");
            if (value.isEmpty()) {
                return Optional.of(List.of());
            }
            JsonNode categories = value.get();
            if (!categories.isArray()) {
                problems.add(where + ": \"categories\" must be an array of strings");
                return Optional.empty();
            }
            List<String> result = new ArrayList<>();
            boolean valid = true;
            for (int i = 0; i < categories.size(); i++) {
                JsonNode category = categories.get(i);
                if (category.isString()) {
                    result.add(category.stringValue());
                } else {
                    problems.add(where + ": categories[" + i + "] must be a string");
                    valid = false;
                }
            }
            return valid ? Optional.of(result) : Optional.empty();
        }

        private Optional<DecisionMapping> decisionMapping(JsonNode node, String where) {
            JsonNode mapping = node.get("decisionMapping");
            if (mapping == null || !mapping.isObject()) {
                problems.add(where + ": \"decisionMapping\" is required and must be an object");
                return Optional.empty();
            }
            String at = where + ".decisionMapping";
            unknownProperties(mapping, MAPPING_PROPERTIES, at);
            Optional<Decision> fallback = requiredEnum(mapping, "default", Decision.class, at);
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
                problems.add(at + ": \"mappings\" must be an object of value → decision");
                return Optional.empty();
            }
            Map<String, Decision> result = new LinkedHashMap<>();
            boolean valid = true;
            for (Map.Entry<String, JsonNode> entry : entries.properties()) {
                Optional<Decision> decision = parseEnum(entry.getValue(), Decision.class);
                if (decision.isPresent()) {
                    result.put(entry.getKey(), decision.get());
                } else {
                    problems.add(at + ".mappings['" + entry.getKey() + "']: "
                            + invalidEnum(entry.getValue(), Decision.class));
                    valid = false;
                }
            }
            return valid ? Optional.of(result) : Optional.empty();
        }

        // -------------------------------------------------------------- property access

        /** The property, or empty if it's absent or JSON {@code null}. */
        private static Optional<JsonNode> optional(JsonNode node, String name) {
            JsonNode value = node.get(name);
            return value == null || value.isNull() ? Optional.empty() : Optional.of(value);
        }

        /** Like {@link #optional}, but an absent property is a problem. */
        private Optional<JsonNode> required(JsonNode node, String name, String where) {
            Optional<JsonNode> value = optional(node, name);
            if (value.isEmpty()) {
                problems.add(where + ": \"" + name + "\" is required");
            }
            return value;
        }

        private Optional<String> requiredText(JsonNode node, String name, String where) {
            return required(node, name, where).flatMap(value -> text(value, name, where));
        }

        private <E extends Enum<E>> Optional<E> requiredEnum(JsonNode node, String name, Class<E> type,
                String where) {
            return required(node, name, where).flatMap(value -> {
                Optional<E> parsed = parseEnum(value, type);
                if (parsed.isEmpty()) {
                    problems.add(where + ": \"" + name + "\" " + invalidEnum(value, type));
                }
                return parsed;
            });
        }

        private Optional<String> text(JsonNode value, String name, String where) {
            if (!value.isString()) {
                problems.add(where + ": \"" + name + "\" must be a string");
                return Optional.empty();
            }
            return Optional.of(value.stringValue());
        }

        private static <E extends Enum<E>> Optional<E> parseEnum(JsonNode value, Class<E> type) {
            if (!value.isString()) {
                return Optional.empty();
            }
            return Arrays.stream(type.getEnumConstants()).filter(e -> e.name().equals(value.stringValue())).findFirst();
        }

        private static String invalidEnum(JsonNode value, Class<? extends Enum<?>> type) {
            return "must be one of " + Arrays.toString(type.getEnumConstants()) + ", got " + value;
        }

        private void unknownProperties(JsonNode node, List<String> allowed, String where) {
            for (Map.Entry<String, JsonNode> property : node.properties()) {
                if (!allowed.contains(property.getKey())) {
                    problems.add(where + ": unknown property \"" + property.getKey() + "\" (allowed: " + allowed + ")");
                }
            }
        }
    }
}
