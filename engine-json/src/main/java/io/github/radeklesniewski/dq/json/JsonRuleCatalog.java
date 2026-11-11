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
import tools.jackson.core.JacksonException;
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
 * <p>{@code countryScope} (default {@code "WORLD"}) and {@code categories} (default none) are optional. Everything
 * else is required, including {@code status}: in a data file it's better stated than defaulted.
 *
 * <p>Loading is strict and all-or-nothing. Unknown properties (usually typos), invalid enum values, unknown logic
 * ids, duplicate rule ids and invalid rules are all collected and reported together in one
 * {@link CatalogLoadException}, so a catalog author can fix every problem in one pass.
 */
public final class JsonRuleCatalog {

    private static final Set<String> ROOT_PROPERTIES = Set.of("rules", "description");
    private static final Set<String> RULE_PROPERTIES = Set.of(
            "id", "label", "logic", "status", "severity", "countryScope", "categories", "decisionMapping");
    private static final Set<String> MAPPING_PROPERTIES = Set.of("mappings", "default");

    private JsonRuleCatalog() {
    }

    /**
     * Loads a fixed catalog from a stream.
     *
     * @param in the JSON; read fully, not closed
     * @param logic resolves the {@code logic} ids
     * @return a fixed catalog
     * @throws CatalogLoadException listing every problem found
     */
    public static RuleCatalog load(InputStream in, RuleLogicRegistry logic) {
        Objects.requireNonNull(in, "in");
        Objects.requireNonNull(logic, "logic");
        JsonNode root;
        try {
            root = Json.MAPPER.readTree(in);
        } catch (JacksonException e) {
            throw new CatalogLoadException(List.of("Catalog is not valid JSON: " + e.getOriginalMessage()), e);
        }
        return RuleCatalog.of(parse(root, logic));
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
        try (InputStream in = Files.newInputStream(file)) {
            return load(in, logic);
        } catch (IOException e) {
            throw new UncheckedIOException("Can't read rule catalog " + file, e);
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
                throw new UncheckedIOException("Can't read rule catalog " + file, e);
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

    // ------------------------------------------------------------------ parsing

    private static List<Rule> parse(JsonNode root, RuleLogicRegistry logic) {
        List<String> problems = new ArrayList<>();
        if (root == null || !root.isObject()) {
            throw new CatalogLoadException(List.of("Catalog must be a JSON object with a \"rules\" array"), null);
        }
        unknownProperties(root, ROOT_PROPERTIES, "catalog", problems);
        JsonNode rulesNode = root.get("rules");
        if (rulesNode == null || !rulesNode.isArray()) {
            problems.add("catalog: \"rules\" must be an array");
            throw new CatalogLoadException(problems, null);
        }

        List<Rule> rules = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < rulesNode.size(); i++) {
            JsonNode node = rulesNode.get(i);
            String where = "rules[" + i + "]";
            if (!node.isObject()) {
                problems.add(where + ": must be an object");
                continue;
            }
            String id = text(node, "id", where, problems, true).orElse(null);
            if (id != null) {
                where += " '" + id + "'";
                if (!ids.add(id)) {
                    problems.add(where + ": duplicate rule id");
                }
            }
            parseRule(node, id, where, logic, problems).ifPresent(rules::add);
        }
        if (!problems.isEmpty()) {
            throw new CatalogLoadException(problems, null);
        }
        return rules;
    }

    private static Optional<Rule> parseRule(JsonNode node, String id, String where, RuleLogicRegistry logic,
            List<String> problems) {
        int before = problems.size();
        unknownProperties(node, RULE_PROPERTIES, where, problems);
        Optional<String> label = text(node, "label", where, problems, true);
        Optional<RuleLogic> function = text(node, "logic", where, problems, true).flatMap(logicId -> {
            Optional<RuleLogic> found = logic.find(logicId);
            if (found.isEmpty()) {
                problems.add(where + ": logic '" + logicId + "' is not registered (known: "
                        + logic.logicIds().stream().sorted().toList() + ")");
            }
            return found;
        });
        Optional<RuleStatus> status = enumValue(node, "status", RuleStatus.class, where, problems, true);
        Optional<Severity> severity = enumValue(node, "severity", Severity.class, where, problems, true);
        Optional<CountryScope> scope = countryScope(node, where, problems);
        List<String> categories = categories(node, where, problems);
        Optional<DecisionMapping> mapping = decisionMapping(node.get("decisionMapping"), where, problems);

        if (problems.size() > before || id == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(Rule.builder(id)
                    .label(label.orElseThrow())
                    .logic(function.orElseThrow())
                    .status(status.orElseThrow())
                    .severity(severity.orElseThrow())
                    .countryScope(scope.orElse(CountryScope.world()))
                    .categories(categories)
                    .decisionMapping(mapping.orElseThrow())
                    .build());
        } catch (IllegalArgumentException | NullPointerException e) {
            problems.add(where + ": " + e.getMessage());
            return Optional.empty();
        }
    }

    private static Optional<DecisionMapping> decisionMapping(JsonNode node, String where, List<String> problems) {
        String at = where + ".decisionMapping";
        if (node == null || !node.isObject()) {
            problems.add(where + ": \"decisionMapping\" is required and must be an object");
            return Optional.empty();
        }
        int before = problems.size();
        unknownProperties(node, MAPPING_PROPERTIES, at, problems);
        Optional<Decision> fallback = enumValue(node, "default", Decision.class, at, problems, true);
        Map<String, Decision> mappings = new LinkedHashMap<>();
        JsonNode entries = node.get("mappings");
        if (entries != null && !entries.isObject()) {
            problems.add(at + ": \"mappings\" must be an object of value → decision");
        } else if (entries != null) {
            for (Map.Entry<String, JsonNode> entry : entries.properties()) {
                parseEnum(entry.getValue(), Decision.class).ifPresentOrElse(
                        decision -> mappings.put(entry.getKey(), decision),
                        () -> problems.add(at + ".mappings['" + entry.getKey() + "']: "
                                + invalidEnum(entry.getValue(), Decision.class)));
            }
        }
        if (problems.size() > before) {
            return Optional.empty();
        }
        return Optional.of(DecisionMapping.of(mappings, fallback.orElseThrow()));
    }

    private static Optional<CountryScope> countryScope(JsonNode node, String where, List<String> problems) {
        return text(node, "countryScope", where, problems, false).flatMap(code -> {
            try {
                return Optional.of(CountryScope.of(code));
            } catch (IllegalArgumentException e) {
                problems.add(where + ": " + e.getMessage());
                return Optional.empty();
            }
        });
    }

    private static List<String> categories(JsonNode node, String where, List<String> problems) {
        JsonNode categories = node.get("categories");
        if (categories == null || categories.isNull()) {
            return List.of();
        }
        if (!categories.isArray()) {
            problems.add(where + ": \"categories\" must be an array of strings");
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (int i = 0; i < categories.size(); i++) {
            JsonNode category = categories.get(i);
            if (category.isString()) {
                result.add(category.stringValue());
            } else {
                problems.add(where + ": categories[" + i + "] must be a string");
            }
        }
        return result;
    }

    private static Optional<String> text(JsonNode node, String property, String where, List<String> problems,
            boolean required) {
        JsonNode value = node.get(property);
        if (value == null || value.isNull()) {
            if (required) {
                problems.add(where + ": \"" + property + "\" is required");
            }
            return Optional.empty();
        }
        if (!value.isString()) {
            problems.add(where + ": \"" + property + "\" must be a string");
            return Optional.empty();
        }
        return Optional.of(value.stringValue());
    }

    private static <E extends Enum<E>> Optional<E> enumValue(JsonNode node, String property, Class<E> type,
            String where, List<String> problems, boolean required) {
        JsonNode value = node.get(property);
        if (value == null || value.isNull()) {
            if (required) {
                problems.add(where + ": \"" + property + "\" is required");
            }
            return Optional.empty();
        }
        Optional<E> parsed = parseEnum(value, type);
        if (parsed.isEmpty()) {
            problems.add(where + ": \"" + property + "\" " + invalidEnum(value, type));
        }
        return parsed;
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

    private static void unknownProperties(JsonNode node, Set<String> allowed, String where, List<String> problems) {
        for (Map.Entry<String, JsonNode> property : node.properties()) {
            if (!allowed.contains(property.getKey())) {
                problems.add(where + ": unknown property \"" + property.getKey() + "\" (allowed: "
                        + allowed.stream().sorted().toList() + ")");
            }
        }
    }
}
