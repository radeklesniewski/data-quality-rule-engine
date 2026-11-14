package io.github.radeklesniewski.dq.json.catalog;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.radeklesniewski.dq.Fixtures;
import io.github.radeklesniewski.dq.rule.CountryScope;
import io.github.radeklesniewski.dq.rule.Decision;
import io.github.radeklesniewski.dq.rule.DecisionMapping;
import io.github.radeklesniewski.dq.rule.Rule;
import io.github.radeklesniewski.dq.rule.RuleCatalog;
import io.github.radeklesniewski.dq.rule.RuleStatus;
import io.github.radeklesniewski.dq.rule.Severity;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JsonRuleCatalogsTest {

    private static InputStream stream(String json) {
        return new ByteArrayInputStream(json.getBytes(UTF_8));
    }

    private static RuleCatalog load(String json) {
        return JsonRuleCatalogs.load(stream(json), Fixtures.LOGIC);
    }

    private static List<String> problems(String json) {
        try {
            load(json);
        } catch (CatalogLoadException e) {
            return e.problems();
        }
        throw new AssertionError("expected CatalogLoadException");
    }

    @Test
    void loadsTheFixtureCatalogEquivalentToTheJavaDefinedRules() throws IOException {
        List<Rule> rules;
        try (InputStream in = getClass().getResourceAsStream("/fixture/catalog.json")) {
            rules = JsonRuleCatalogs.load(in, Fixtures.LOGIC).rules();
        }

        assertThat(rules).extracting(Rule::id).containsExactly("countryBlocked", "vatFormat", "ibanFormat");
        for (int i = 0; i < rules.size(); i++) {
            Rule loaded = rules.get(i);
            Rule expected = Fixtures.RULES.get(i);
            assertThat(loaded.label()).isEqualTo(expected.label());
            assertThat(loaded.logic()).isSameAs(expected.logic());
            assertThat(loaded.decisionMapping()).isEqualTo(expected.decisionMapping());
            assertThat(loaded.status()).isEqualTo(RuleStatus.RELEASED);
            assertThat(loaded.severity()).isEqualTo(Severity.ERROR);
            assertThat(loaded.countryScope()).isEqualTo(CountryScope.world());
        }
        assertThat(rules.get(1).categories()).containsExactly("tax", "format");
    }

    @Test
    void optionalPropertiesAndCountryScope() {
        Rule rule = load("""
                {"rules": [{"id": "deVat", "label": "German VAT", "logic": "vatFormat", "status": "DRAFT",
                  "severity": "WARNING", "countryScope": "DE",
                  "decisionMapping": {"default": "REVIEW"}}]}""").rules().getFirst();

        assertThat(rule.countryScope()).isEqualTo(CountryScope.of("DE"));
        assertThat(rule.categories()).isEmpty();
        assertThat(rule.decisionMapping()).isEqualTo(DecisionMapping.builder().orElse(Decision.REVIEW));
        assertThat(rule.status()).isEqualTo(RuleStatus.DRAFT);
    }

    @Test
    void reportsEveryProblemAtOnceWithItsLocation() {
        List<String> problems = problems("""
                {"rules": [
                  {"id": "a", "label": "A", "logic": "noSuchLogic", "status": "RELESED", "severity": "ERROR",
                   "decisionMapping": {"mappings": {"ok": "GOOD"}, "default": "VALID"}},
                  {"id": "b", "label": "B", "logic": "vatFormat", "status": "RELEASED", "severty": "ERROR",
                   "countryScope": "Germany", "categories": ["x", 1],
                   "decisionMapping": {"mappings": {"ok": "VALID"}}},
                  {"id": "a", "label": "dup", "logic": "vatFormat", "status": "RELEASED", "severity": "INFO",
                   "decisionMapping": {"default": "VALID"}},
                  {"label": "no id"},
                  "not an object"
                ]}""");

        assertThat(problems).containsExactly(
                "rules[0] 'a': logic 'noSuchLogic' is not registered (known: [countryBlocked, ibanFormat, vatFormat])",
                "rules[0] 'a': \"status\" must be one of [DRAFT, RELEASED, DEPRECATED], got \"RELESED\"",
                "rules[0] 'a'.decisionMapping.mappings['ok']: must be one of [VALID, INVALID, REVIEW, NOT_APPLICABLE],"
                        + " got \"GOOD\"",
                "rules[1] 'b': unknown property \"severty\" (allowed: [categories, countryScope, decisionMapping, id,"
                        + " label, logic, severity, status])",
                "rules[1] 'b': \"severity\" is required",
                "rules[1] 'b': Country scope must be 'WORLD' or a two-letter upper-case country code, got: 'Germany'",
                "rules[1] 'b': categories[1] must be a string",
                "rules[1] 'b'.decisionMapping: \"default\" is required",
                "rules[2] 'a': duplicate rule id",
                "rules[3]: \"id\" is required",
                "rules[3]: \"logic\" is required",
                "rules[3]: \"status\" is required",
                "rules[3]: \"severity\" is required",
                "rules[3]: \"decisionMapping\" is required and must be an object",
                "rules[4]: must be an object");
    }

    @Test
    void ruleLevelValidationIsReportedToo() {
        assertThat(problems("""
                {"rules": [{"id": "bad id!", "label": " ", "logic": "vatFormat", "status": "RELEASED",
                  "severity": "ERROR", "decisionMapping": {"default": "VALID"}}]}"""))
                .singleElement().asString().startsWith("rules[0] 'bad id!': Rule id must match");
    }

    @Test
    void rejectsMalformedDocuments() {
        assertThat(problems("[]")).containsExactly("Catalog must be a JSON object with a \"rules\" array");
        assertThat(problems("{\"rule\": []}")).containsExactly(
                "catalog: unknown property \"rule\" (allowed: [description, rules])",
                "catalog: \"rules\" must be an array");
        assertThatThrownBy(() -> load("{\"rules\": [")).isInstanceOf(CatalogLoadException.class)
                .hasMessageContaining("not valid JSON");
    }

    @Test
    void exceptionMessageListsAllProblems() {
        assertThatThrownBy(() -> load("{\"rules\": [{}]}"))
                .hasMessageStartingWith("Invalid rule catalog (6 problems):\n  - rules[0]: \"id\" is required");
    }

    @Test
    void watchingCatalogReloadsChangedFileOnNextRun(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("catalog.json");
        String one = """
                {"rules": [{"id": "one", "label": "One", "logic": "vatFormat", "status": "RELEASED",
                  "severity": "ERROR", "decisionMapping": {"default": "VALID"}}]}""";
        Files.writeString(file, one);
        RuleCatalog catalog = JsonRuleCatalogs.watching(file, Fixtures.LOGIC);
        List<Rule> first = catalog.rules();

        assertThat(catalog.rules()).as("unchanged file is not re-parsed").isSameAs(first);

        Files.writeString(file, one.replace("\"one\"", "\"two\""));
        Files.setLastModifiedTime(file, FileTime.from(Instant.now().plusSeconds(5)));
        assertThat(catalog.rules()).extracting(Rule::id).containsExactly("two");

        Files.writeString(file, "{\"rules\": [{}]}");
        Files.setLastModifiedTime(file, FileTime.from(Instant.now().plusSeconds(10)));
        assertThatThrownBy(catalog::rules).isInstanceOf(CatalogLoadException.class);
    }

    @Test
    void watchingValidatesImmediately(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("catalog.json");
        Files.writeString(file, "{\"rules\": [{}]}");

        assertThatThrownBy(() -> JsonRuleCatalogs.watching(file, Fixtures.LOGIC))
                .isInstanceOf(CatalogLoadException.class);
    }

    @Test
    void loadExceptionKeepsItsProblemsThroughSerialization() throws Exception {
        CatalogLoadException original = new CatalogLoadException(List.of("rules[0]: \"id\" is required"), null);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(original);
        }
        CatalogLoadException copy;
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            copy = (CatalogLoadException) in.readObject();
        }

        assertThat(copy.problems()).containsExactly("rules[0]: \"id\" is required");
    }

    @Test
    void loadExceptionNeedsAtLeastOneProblem() {
        assertThatThrownBy(() -> new CatalogLoadException(List.of(), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anUnreadableStreamIsAnIoErrorNotInvalidJson() {
        InputStream failing = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("disk error");
            }
        };

        assertThatThrownBy(() -> JsonRuleCatalogs.load(failing, Fixtures.LOGIC))
                .isInstanceOf(UncheckedIOException.class)
                .hasRootCauseMessage("disk error");
    }

    @Test
    void watchingCatalogWhoseFileDisappearsFailsWithAnIoError(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("catalog.json");
        Files.writeString(file, """
                {"rules": [{"id": "one", "label": "One", "logic": "vatFormat", "status": "RELEASED",
                  "severity": "ERROR", "decisionMapping": {"default": "VALID"}}]}""");
        RuleCatalog catalog = JsonRuleCatalogs.watching(file, Fixtures.LOGIC);
        Files.delete(file);

        assertThatThrownBy(catalog::rules).isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining("catalog.json");
    }
}
