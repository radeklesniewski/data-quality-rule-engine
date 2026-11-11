package io.github.radeklesniewski.dq;

import static io.github.radeklesniewski.dq.rule.Decision.INVALID;
import static io.github.radeklesniewski.dq.rule.Decision.NOT_APPLICABLE;
import static io.github.radeklesniewski.dq.rule.Decision.REVIEW;
import static io.github.radeklesniewski.dq.rule.Decision.VALID;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toMap;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.radeklesniewski.dq.record.DataRecord;
import io.github.radeklesniewski.dq.record.FieldTypeException;
import io.github.radeklesniewski.dq.record.MissingFieldException;
import io.github.radeklesniewski.dq.record.RecordReadException;
import io.github.radeklesniewski.dq.record.RecordSource;
import io.github.radeklesniewski.dq.rule.Decision;
import io.github.radeklesniewski.dq.rule.DecisionMapping;
import io.github.radeklesniewski.dq.rule.Rule;
import io.github.radeklesniewski.dq.rule.RuleCatalog;
import io.github.radeklesniewski.dq.rule.RuleStatus;
import io.github.radeklesniewski.dq.rule.Severity;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The acceptance scenario of the task brief (§5), end to end through the engine. */
class FixtureScenarioTest {

    private final ValidationEngine engine = ValidationEngine.create();

    @Test
    void producesTheExpectedDecisionTableAndSummary() {
        CollectingSink sink = new CollectingSink();

        RunSummary summary = engine.run(RecordSource.of(Fixtures.RECORDS), RuleCatalog.of(Fixtures.RULES), sink);

        assertThat(decisionTable(sink.results())).isEqualTo(Map.of(
                "r1", Map.of("countryBlocked", NOT_APPLICABLE, "vatFormat", VALID, "ibanFormat", VALID),
                "r2", Map.of("countryBlocked", NOT_APPLICABLE, "vatFormat", INVALID, "ibanFormat", VALID),
                "r3", Map.of("countryBlocked", INVALID, "vatFormat", INVALID, "ibanFormat", INVALID)));
        assertThat(sink.errors()).isEmpty();

        assertThat(summary.resultCount()).isEqualTo(9);
        assertThat(summary.errorCount()).isZero();
        assertThat(summary.resultsByDecision())
                .containsEntry(VALID, 3L).containsEntry(INVALID, 4L)
                .containsEntry(NOT_APPLICABLE, 2L).containsEntry(REVIEW, 0L);
        assertThat(summary.count(Severity.ERROR)).isEqualTo(9);
        assertThat(summary.count(Severity.WARNING)).isZero();
    }

    @Test
    void resultsExplainThemselves() {
        CollectingSink sink = new CollectingSink();
        engine.run(RecordSource.of(Fixtures.RECORDS), RuleCatalog.of(Fixtures.RULES), sink);

        Result r2Vat = sink.results().stream()
                .filter(r -> r.recordId().equals("r2") && r.ruleId().equals("vatFormat"))
                .findFirst().orElseThrow();

        assertThat(r2Vat.decision()).isEqualTo(INVALID);
        assertThat(r2Vat.severity()).isEqualTo(Severity.ERROR);
        assertThat(r2Vat.recordIndex()).isEqualTo(1);
        assertThat(r2Vat.provenance().fieldsRead()).containsExactly(FieldRead.present("vatId", "FR22"));
        assertThat(r2Vat.provenance().computedValue()).contains("bad");
    }

    /**
     * Fault isolation: a rule that always throws, a record with a wrongly typed field, a record without an id and
     * an unreadable record are all reported. Every other (rule, record) pair still produces its normal result.
     */
    @Test
    void faultsAreIsolatedAndReportedWhileTheBatchCompletes() {
        Rule exploding = Rule.builder("exploding")
                .label("Always fails")
                .logic(r -> {
                    r.getString("legalName");
                    throw new IllegalStateException("simulated bug in rule logic");
                })
                .decisionMapping(DecisionMapping.builder().orElse(VALID))
                .status(RuleStatus.RELEASED)
                .severity(Severity.WARNING)
                .build();
        DataRecord numericVat = DataRecord.ofMap(Map.of(
                "id", "r4", "vatId", new BigDecimal("123456789"), "country", "DE", "iban", "DE1"));
        DataRecord withoutId = DataRecord.ofMap(Map.of("country", "DE"));

        List<Object> input = new ArrayList<>(Fixtures.RECORDS);
        input.add(1, new RecordReadException("element is not a JSON object", null)); // index 1
        input.add(numericVat);                                                        // index 4
        input.add(withoutId);                                                         // index 5

        List<Rule> rules = new ArrayList<>(Fixtures.RULES);
        rules.add(1, exploding);
        CollectingSink sink = new CollectingSink();

        RunSummary summary = engine.run(
                sourceOf(input), RuleCatalog.of(rules), RunOptions.builder().batchSize(2).build(), sink);

        // The original scenario is untouched by the faults around it.
        Map<String, Map<String, Decision>> table = decisionTable(sink.results());
        assertThat(table.get("r1")).containsExactlyInAnyOrderEntriesOf(
                Map.of("countryBlocked", NOT_APPLICABLE, "vatFormat", VALID, "ibanFormat", VALID));
        assertThat(table.get("r2")).containsExactlyInAnyOrderEntriesOf(
                Map.of("countryBlocked", NOT_APPLICABLE, "vatFormat", INVALID, "ibanFormat", VALID));
        assertThat(table.get("r3")).containsExactlyInAnyOrderEntriesOf(
                Map.of("countryBlocked", INVALID, "vatFormat", INVALID, "ibanFormat", INVALID));
        // r4's other rules ran despite its bad vatId.
        assertThat(table.get("r4")).containsExactlyInAnyOrderEntriesOf(
                Map.of("countryBlocked", NOT_APPLICABLE, "ibanFormat", VALID));

        // Errors in emission order, each locatable.
        List<ValidationError> errors = sink.errors();
        assertThat(errors).hasSize(7);
        assertThat(errors.get(0)).isInstanceOfSatisfying(RuleError.class, e -> {
            assertThat(e.ruleId()).isEqualTo("exploding");
            assertThat(e.recordId()).isEqualTo("r1");
            assertThat(e.recordIndex()).isZero();
            assertThat(e.errorType()).isEqualTo(IllegalStateException.class.getName());
            assertThat(e.message()).isEqualTo("simulated bug in rule logic");
            assertThat(e.fieldsRead()).containsExactly(FieldRead.present("legalName", "ACME GmbH"));
        });
        assertThat(errors.get(1)).isInstanceOfSatisfying(RecordError.class, e -> {
            assertThat(e.recordIndex()).isEqualTo(1);
            assertThat(e.recordId()).isEmpty();
            assertThat(e.errorType()).isEqualTo(RecordReadException.class.getName());
        });
        assertThat(errors.subList(2, 4)).allSatisfy(e -> assertThat(e).isInstanceOf(RuleError.class));
        assertThat(errors.get(5)).isInstanceOfSatisfying(RuleError.class, e -> {
            assertThat(e.ruleId()).isEqualTo("vatFormat");
            assertThat(e.recordId()).isEqualTo("r4");
            assertThat(e.cause()).isInstanceOf(FieldTypeException.class);
            assertThat(e.fieldsRead()).extracting(FieldRead::path).containsExactly("vatId");
        });
        assertThat(errors.get(6)).isInstanceOfSatisfying(RecordError.class, e -> {
            assertThat(e.recordIndex()).isEqualTo(5);
            assertThat(e.cause()).isInstanceOf(MissingFieldException.class);
            assertThat(e.message()).isEqualTo("Required field 'id' is missing");
        });

        assertThat(summary.recordsRead()).isEqualTo(6);
        assertThat(summary.batches()).isEqualTo(3);
        assertThat(summary.resultCount()).isEqualTo(11);
        assertThat(summary.ruleErrorCount()).isEqualTo(5);
        assertThat(summary.recordErrorCount()).isEqualTo(2);
        assertThat(summary.errorCount()).isEqualTo(sink.errors().size());
    }

    /** Builds the decision table "record → rule → decision" from results. */
    static Map<String, Map<String, Decision>> decisionTable(List<Result> results) {
        return results.stream().collect(groupingBy(Result::recordId, LinkedHashMap::new,
                toMap(Result::ruleId, Result::decision)));
    }

    /** A source yielding records, or throwing the given RecordReadExceptions in their place. */
    private static RecordSource sourceOf(List<Object> items) {
        Iterator<Object> it = items.iterator();
        return () -> {
            if (!it.hasNext()) {
                return Optional.empty();
            }
            Object item = it.next();
            if (item instanceof RecordReadException e) {
                throw e;
            }
            return Optional.of((DataRecord) item);
        };
    }
}
