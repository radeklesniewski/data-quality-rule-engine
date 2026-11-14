package io.github.radeklesniewski.dq.json;

import io.github.radeklesniewski.dq.rule.RuleCatalog;
import io.github.radeklesniewski.dq.rule.RuleLogicRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
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
        return RuleCatalog.of(CatalogParser.parse(readTree(in, "rule catalog"), logic));
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
            return RuleCatalog.of(CatalogParser.parse(readTree(in, "rule catalog " + file), logic));
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

    /** Reads the JSON tree, telling a stream failure apart from invalid JSON. */
    private static JsonNode readTree(InputStream in, String source) {
        try {
            return Json.MAPPER.readTree(in);
        } catch (JacksonIOException e) {
            throw Json.ioFailure("Can't read " + source, e);
        } catch (JacksonException e) {
            throw new CatalogLoadException(List.of("Catalog is not valid JSON: " + e.getOriginalMessage()), e);
        }
    }

    static UncheckedIOException cantRead(Path file, IOException e) {
        return new UncheckedIOException("Can't read rule catalog " + file, e);
    }
}
