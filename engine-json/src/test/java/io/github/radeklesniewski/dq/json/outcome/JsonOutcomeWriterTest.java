package io.github.radeklesniewski.dq.json.outcome;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.radeklesniewski.dq.FieldRead;
import io.github.radeklesniewski.dq.Provenance;
import io.github.radeklesniewski.dq.RecordError;
import io.github.radeklesniewski.dq.Result;
import io.github.radeklesniewski.dq.RuleError;
import io.github.radeklesniewski.dq.RunSummary;
import io.github.radeklesniewski.dq.json.internal.Json;
import io.github.radeklesniewski.dq.rule.Decision;
import io.github.radeklesniewski.dq.rule.Severity;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class JsonOutcomeWriterTest {

    private static final Result RESULT = new Result(Decision.INVALID, Severity.ERROR, Provenance.evaluated(
            "vatFormat", "r2", 1,
            List.of(FieldRead.present("vatId", "FR22"), FieldRead.present("employees", new BigDecimal("12.50")),
                    FieldRead.absent("legacyId")),
            "bad", false));
    private static final Result OUT_OF_SCOPE = new Result(Decision.NOT_APPLICABLE, Severity.INFO,
            Provenance.outOfScope("deOnly", "r2", 1, FieldRead.present("country", "FR")));
    private static final RuleError RULE_ERROR = RuleError.of("explodes", "r3", 2,
            List.of(FieldRead.absent("vatId")), new IllegalStateException("boom"));
    private static final RecordError RECORD_ERROR = RecordError.of(3, Optional.empty(),
            new IllegalArgumentException("no id"));
    private static final RunSummary SUMMARY = new RunSummary(3, 4, 1, 2, 1, 1,
            Map.of(Decision.INVALID, 1L, Decision.NOT_APPLICABLE, 1L), Map.of(Severity.ERROR, 1L, Severity.INFO, 1L));

    private static JsonNode documentOf(java.util.function.Consumer<JsonOutcomeWriter> writes) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JsonOutcomeWriter writer = JsonOutcomeWriter.document(out)) {
            writes.accept(writer);
        }
        return Json.MAPPER.readTree(out.toString(UTF_8));
    }

    @Test
    void writesEveryOutcomeTypeAndTheSummary() {
        JsonNode doc = documentOf(writer -> {
            writer.accept(RESULT);
            writer.accept(OUT_OF_SCOPE);
            writer.accept(RULE_ERROR);
            writer.accept(RECORD_ERROR);
            writer.finish(SUMMARY);
        });

        assertThat(doc.get("outcomes")).hasSize(4);
        assertThat(doc.get("outcomes").get(0)).isEqualTo(Json.MAPPER.readTree("""
                {"type": "result", "recordIndex": 1, "recordId": "r2", "ruleId": "vatFormat",
                 "decision": "INVALID", "severity": "ERROR",
                 "provenance": {"basis": "EVALUATED",
                   "fieldsRead": [{"path": "vatId", "value": "FR22"}, {"path": "employees", "value": 12.50},
                                  {"path": "legacyId", "absent": true}],
                   "computedValue": "bad", "defaultDecision": false}}"""));
        assertThat(doc.get("outcomes").get(1).get("provenance")).isEqualTo(Json.MAPPER.readTree("""
                {"basis": "OUT_OF_SCOPE", "fieldsRead": [{"path": "country", "value": "FR"}]}"""));
        assertThat(doc.get("outcomes").get(2)).isEqualTo(Json.MAPPER.readTree("""
                {"type": "ruleError", "recordIndex": 2, "recordId": "r3", "ruleId": "explodes",
                 "errorType": "java.lang.IllegalStateException", "message": "boom",
                 "fieldsRead": [{"path": "vatId", "absent": true}]}"""));
        assertThat(doc.get("outcomes").get(3)).isEqualTo(Json.MAPPER.readTree("""
                {"type": "recordError", "recordIndex": 3,
                 "errorType": "java.lang.IllegalArgumentException", "message": "no id"}"""));
        assertThat(doc.get("summary")).isEqualTo(Json.MAPPER.readTree("""
                {"rulesSelected": 3, "recordsRead": 4, "batches": 1, "resultCount": 2, "errorCount": 2,
                 "ruleErrorCount": 1, "recordErrorCount": 1,
                 "resultsByDecision": {"VALID": 0, "INVALID": 1, "REVIEW": 0, "NOT_APPLICABLE": 1},
                 "resultsBySeverity": {"ERROR": 1, "WARNING": 0, "INFO": 1}}"""));
    }

    @Test
    void closingWithoutFinishStillGivesValidJsonButNoSummary() {
        JsonNode doc = documentOf(writer -> writer.accept(RESULT));

        assertThat(doc.get("outcomes")).hasSize(1);
        assertThat(doc.has("summary")).isFalse();
    }

    @Test
    void linesFormatWritesOneOutcomePerLineThenSummary() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JsonOutcomeWriter writer = JsonOutcomeWriter.lines(out)) {
            writer.accept(RESULT);
            writer.accept(RECORD_ERROR);
            writer.finish(SUMMARY);
        }

        List<String> lines = out.toString(UTF_8).lines().toList();
        assertThat(lines).hasSize(3);
        assertThat(lines).allSatisfy(line -> assertThat(line).startsWith("{").endsWith("}"));
        assertThat(Json.MAPPER.readTree(lines.get(1)).get("type").stringValue()).isEqualTo("recordError");
        assertThat(Json.MAPPER.readTree(lines.get(2)).get("type").stringValue()).isEqualTo("summary");
        assertThat(Json.MAPPER.readTree(lines.get(2)).get("recordsRead").intValue()).isEqualTo(4);
    }

    @Test
    void abortMarksTheOutputAsFailedWithoutSummary() {
        JsonNode doc = documentOf(writer -> {
            writer.accept(RESULT);
            writer.abort("Malformed JSON at line 3");
        });
        assertThat(doc.get("outcomes")).hasSize(1);
        assertThat(doc.get("error").stringValue()).isEqualTo("Malformed JSON at line 3");
        assertThat(doc.has("summary")).isFalse();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JsonOutcomeWriter writer = JsonOutcomeWriter.lines(out)) {
            writer.accept(RESULT);
            writer.abort("broken");
            assertThatThrownBy(() -> writer.finish(SUMMARY)).isInstanceOf(IllegalStateException.class);
        }
        List<String> lines = out.toString(UTF_8).lines().toList();
        assertThat(Json.MAPPER.readTree(lines.getLast())).isEqualTo(
                Json.MAPPER.readTree("{\"type\": \"error\", \"message\": \"broken\"}"));
    }

    @Test
    void rejectsOutcomesAfterFinish() {
        JsonOutcomeWriter writer = JsonOutcomeWriter.lines(new ByteArrayOutputStream());
        writer.finish(SUMMARY);

        assertThatThrownBy(() -> writer.accept(RESULT)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> writer.finish(SUMMARY)).isInstanceOf(IllegalStateException.class);
        writer.close();
        writer.close();
    }

    @Test
    void rejectsEveryWriteAfterClose() {
        JsonOutcomeWriter writer = JsonOutcomeWriter.lines(new ByteArrayOutputStream());
        writer.close();

        assertThatThrownBy(() -> writer.accept(RESULT)).isInstanceOf(IllegalStateException.class)
                .hasMessage("Writer already closed");
        assertThatThrownBy(() -> writer.finish(SUMMARY)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> writer.abort("late")).isInstanceOf(IllegalStateException.class);
    }

    /** A host value Jackson can't serialise: the write fails after the outcome object has been opened. */
    public static final class Exploding {
        public String getValue() {
            throw new IllegalStateException("boom");
        }
    }

    @Test
    void outputStaysWellFormedWhenAnOutcomeFailsHalfWay() {
        Result unserializable = new Result(Decision.VALID, Severity.INFO, Provenance.evaluated("r", "id", 0,
                List.of(FieldRead.present("odd", new Exploding())), "ok", false));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        JsonOutcomeWriter writer = JsonOutcomeWriter.document(out);
        writer.accept(RESULT);

        assertThatThrownBy(() -> writer.accept(unserializable)).isInstanceOf(RuntimeException.class);
        writer.close();

        JsonNode doc = Json.MAPPER.readTree(out.toString(UTF_8));
        assertThat(doc.get("outcomes").get(0).get("ruleId").stringValue()).isEqualTo("vatFormat");
        assertThat(doc.has("summary")).isFalse();
    }
}
