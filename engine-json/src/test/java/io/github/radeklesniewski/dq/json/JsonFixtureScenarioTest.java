package io.github.radeklesniewski.dq.json;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.radeklesniewski.dq.Fixtures;
import io.github.radeklesniewski.dq.RunOptions;
import io.github.radeklesniewski.dq.RunSummary;
import io.github.radeklesniewski.dq.ValidationEngine;
import io.github.radeklesniewski.dq.json.catalog.JsonRuleCatalogs;
import io.github.radeklesniewski.dq.json.internal.Json;
import io.github.radeklesniewski.dq.json.outcome.JsonOutcomeWriter;
import io.github.radeklesniewski.dq.json.record.JsonRecordSources;
import io.github.radeklesniewski.dq.record.RecordSource;
import io.github.radeklesniewski.dq.rule.Decision;
import io.github.radeklesniewski.dq.rule.RuleCatalog;
import io.github.radeklesniewski.dq.select.RuleFilter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** The brief's scenario fully as data: JSON records + JSON catalog in, JSON outcomes out. */
class JsonFixtureScenarioTest {

    private static InputStream resource(String name) {
        return JsonFixtureScenarioTest.class.getResourceAsStream("/fixture/" + name);
    }

    private static JsonNode run(InputStream records, RunOptions options) throws IOException {
        RuleCatalog catalog;
        try (InputStream in = resource("catalog.json")) {
            catalog = JsonRuleCatalogs.load(in, Fixtures.LOGIC);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (RecordSource source = JsonRecordSources.array(records);
                JsonOutcomeWriter writer = JsonOutcomeWriter.document(out)) {
            RunSummary summary = ValidationEngine.create().run(source, catalog, options, writer);
            writer.finish(summary);
        }
        return Json.MAPPER.readTree(out.toString(UTF_8));
    }

    private static Map<String, Map<String, Decision>> decisionTable(JsonNode doc) {
        Map<String, Map<String, Decision>> table = new HashMap<>();
        for (JsonNode outcome : doc.get("outcomes")) {
            if (outcome.get("type").stringValue().equals("result")) {
                table.computeIfAbsent(outcome.get("recordId").stringValue(), id -> new HashMap<>())
                        .put(outcome.get("ruleId").stringValue(),
                                Decision.valueOf(outcome.get("decision").stringValue()));
            }
        }
        return table;
    }

    @Test
    void producesTheExpectedDecisionTable() throws IOException {
        JsonNode doc = run(resource("records.json"), RunOptions.defaults());

        assertThat(decisionTable(doc)).isEqualTo(Fixtures.EXPECTED);
        assertThat(doc.at("/summary/resultCount").intValue()).isEqualTo(9);
        assertThat(doc.at("/summary/errorCount").intValue()).isZero();
        assertThat(doc.at("/summary/resultsByDecision/INVALID").intValue()).isEqualTo(4);
    }

    @Test
    void categoriesFromTheJsonCatalogDriveSelection() throws IOException {
        JsonNode doc = run(resource("records.json"),
                RunOptions.builder().ruleFilter(RuleFilter.category("bank")).build());

        assertThat(doc.at("/summary/rulesSelected").intValue()).isEqualTo(1);
        assertThat(doc.get("outcomes")).allSatisfy(o -> assertThat(o.get("ruleId").stringValue())
                .isEqualTo("ibanFormat"));
    }

    @Test
    void badElementsBecomeErrorsAndTheRestCompletes() throws IOException {
        String records = """
                [ {"id": "r1", "vatId": "DE111111111", "country": "DE", "iban": "DE1"},
                  "not a record",
                  {"vatId": "no id here"},
                  {"id": "r4", "vatId": 123456789, "country": "FR", "iban": "FR1"} ]""";

        JsonNode doc = run(new ByteArrayInputStream(records.getBytes(UTF_8)), RunOptions.defaults());

        assertThat(doc.at("/summary/recordsRead").intValue()).isEqualTo(4);
        assertThat(doc.at("/summary/recordErrorCount").intValue()).isEqualTo(2);
        assertThat(doc.at("/summary/ruleErrorCount").intValue()).as("numeric vatId").isEqualTo(1);
        assertThat(doc.at("/summary/resultCount").intValue()).isEqualTo(3 + 2);
    }
}
