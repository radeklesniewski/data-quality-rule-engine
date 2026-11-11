package io.github.radeklesniewski.dq.internal;

import static io.github.radeklesniewski.dq.Fixtures.COUNTRY_BLOCKED;
import static io.github.radeklesniewski.dq.Fixtures.IBAN_FORMAT;
import static io.github.radeklesniewski.dq.Fixtures.R1;
import static io.github.radeklesniewski.dq.Fixtures.R2;
import static io.github.radeklesniewski.dq.Fixtures.R3;
import static io.github.radeklesniewski.dq.Fixtures.VAT_FORMAT;
import static io.github.radeklesniewski.dq.rule.Decision.INVALID;
import static io.github.radeklesniewski.dq.rule.Decision.NOT_APPLICABLE;
import static io.github.radeklesniewski.dq.rule.Decision.REVIEW;
import static io.github.radeklesniewski.dq.rule.Decision.VALID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.radeklesniewski.dq.FieldRead;
import io.github.radeklesniewski.dq.Result;
import io.github.radeklesniewski.dq.record.DataRecord;
import io.github.radeklesniewski.dq.record.MissingFieldException;
import io.github.radeklesniewski.dq.rule.Decision;
import io.github.radeklesniewski.dq.rule.DecisionMapping;
import io.github.radeklesniewski.dq.rule.Rule;
import io.github.radeklesniewski.dq.rule.RuleLogic;
import io.github.radeklesniewski.dq.rule.Severity;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class RuleEvaluatorTest {

    private static Result evaluate(Rule rule, DataRecord record) {
        String id = (String) record.get("id").orElseThrow();
        return RuleEvaluator.evaluate(rule, new TrackingRecordView(record), id);
    }

    static Stream<Arguments> fixtureDecisionTable() {
        return Stream.of(
                Arguments.of(R1, COUNTRY_BLOCKED, NOT_APPLICABLE),
                Arguments.of(R1, VAT_FORMAT, VALID),
                Arguments.of(R1, IBAN_FORMAT, VALID),
                Arguments.of(R2, COUNTRY_BLOCKED, NOT_APPLICABLE),
                Arguments.of(R2, VAT_FORMAT, INVALID),
                Arguments.of(R2, IBAN_FORMAT, VALID),
                Arguments.of(R3, COUNTRY_BLOCKED, INVALID),
                Arguments.of(R3, VAT_FORMAT, INVALID),
                Arguments.of(R3, IBAN_FORMAT, INVALID));
    }

    @ParameterizedTest(name = "{1} on {0} -> {2}")
    @MethodSource("fixtureDecisionTable")
    void producesTheExpectedFixtureDecision(DataRecord record, Rule rule, Decision expected) {
        assertThat(evaluate(rule, record).decision()).isEqualTo(expected);
    }

    @Test
    void resultCarriesSeverityAndProvenanceOfAbsentField() {
        Result result = evaluate(VAT_FORMAT, R3);

        assertThat(result.severity()).isEqualTo(Severity.ERROR);
        assertThat(result.ruleId()).isEqualTo("vatFormat");
        assertThat(result.recordId()).isEqualTo("r3");
        assertThat(result.provenance().fieldsRead()).containsExactly(FieldRead.absent("vatId"));
        assertThat(result.provenance().computedValue()).isEqualTo("bad");
        assertThat(result.provenance().defaultDecision()).isFalse();
    }

    @Test
    void provenanceListsEveryFieldReadWithItsValue() {
        Result result = evaluate(IBAN_FORMAT, R3);

        assertThat(result.provenance().fieldsRead())
                .containsExactly(FieldRead.present("country", "ZZ"), FieldRead.present("iban", ""));
    }

    @Test
    void unmappedValueFallsBackToDefaultAndIsFlagged() {
        Rule rule = rule(r -> "unexpected", DecisionMapping.builder().map("ok", VALID).orElse(REVIEW));

        Result result = evaluate(rule, R1);

        assertThat(result.decision()).isEqualTo(REVIEW);
        assertThat(result.provenance().defaultDecision()).isTrue();
        assertThat(result.provenance().computedValue()).isEqualTo("unexpected");
    }

    @Test
    void nullFromLogicIsRejectedWithRuleId() {
        Rule rule = rule(r -> null, DecisionMapping.builder().orElse(VALID));

        assertThatThrownBy(() -> evaluate(rule, R1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'testRule'");
    }

    @Test
    void logicFailurePropagatesAndViewKeepsFieldsReadBeforeIt() {
        Rule rule = rule(r -> {
            r.getString("country");
            return r.requireString("vatId");
        }, DecisionMapping.builder().orElse(VALID));
        TrackingRecordView view = new TrackingRecordView(R3);

        assertThatThrownBy(() -> RuleEvaluator.evaluate(rule, view, "r3"))
                .isInstanceOf(MissingFieldException.class);
        assertThat(view.reads()).containsExactly(FieldRead.present("country", "ZZ"), FieldRead.absent("vatId"));
    }

    @Test
    void ruleWithoutAnyFieldReadHasEmptyProvenanceFields() {
        Rule rule = rule(r -> "ok", DecisionMapping.builder().map("ok", VALID).orElse(INVALID));

        assertThat(evaluate(rule, DataRecord.ofMap(Map.of("id", "x"))).provenance().fieldsRead()).isEmpty();
    }

    private static Rule rule(RuleLogic logic, DecisionMapping mapping) {
        return Rule.builder("testRule").label("test").logic(logic).decisionMapping(mapping)
                .severity(Severity.INFO).build();
    }
}
