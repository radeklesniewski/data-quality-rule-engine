package io.github.radeklesniewski.dq;

import static io.github.radeklesniewski.dq.rule.Decision.INVALID;
import static io.github.radeklesniewski.dq.rule.Decision.VALID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import io.github.radeklesniewski.dq.record.DataRecord;
import io.github.radeklesniewski.dq.record.FieldTypeException;
import io.github.radeklesniewski.dq.record.MissingFieldException;
import io.github.radeklesniewski.dq.record.RecordSource;
import io.github.radeklesniewski.dq.record.RecordView;
import io.github.radeklesniewski.dq.rule.CountryScope;
import io.github.radeklesniewski.dq.rule.Decision;
import io.github.radeklesniewski.dq.rule.DecisionMapping;
import io.github.radeklesniewski.dq.rule.Rule;
import io.github.radeklesniewski.dq.rule.RuleCatalog;
import io.github.radeklesniewski.dq.rule.RuleLogic;
import io.github.radeklesniewski.dq.rule.RuleStatus;
import io.github.radeklesniewski.dq.rule.Severity;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ValidationEngineTest {

    private final ValidationEngine engine = ValidationEngine.create();

    private static Rule.Builder rule(String id, RuleLogic logic) {
        return Rule.builder(id)
                .label(id)
                .logic(logic)
                .decisionMapping(DecisionMapping.builder().map("ok", VALID).orElse(INVALID))
                .status(RuleStatus.RELEASED)
                .severity(Severity.ERROR);
    }

    private static Rule released(String id) {
        return rule(id, r -> "ok").build();
    }

    private static DataRecord record(String id, String country) {
        return DataRecord.ofMap(Map.of("id", id, "country", country));
    }

    private static List<DataRecord> records(int count) {
        return IntStream.range(0, count).mapToObj(i -> record("r" + i, "DE")).toList();
    }

    private CollectingSink run(List<DataRecord> records, Rule... rules) {
        CollectingSink sink = new CollectingSink();
        engine.run(RecordSource.of(records), RuleCatalog.of(rules), sink);
        return sink;
    }

    @Nested
    class Selection {

        @Test
        void runsOnlyReleasedRules() {
            Rule draft = rule("draft", r -> "ok").status(RuleStatus.DRAFT).build();
            Rule deprecated = rule("deprecated", r -> "ok").status(RuleStatus.DEPRECATED).build();

            CollectingSink sink = run(records(2), draft, released("released"), deprecated);

            assertThat(sink.results()).extracting(Result::ruleId).containsOnly("released").hasSize(2);
        }

        @Test
        void skipsCountryScopedRulesOutsideTheirCountryWithoutAnyOutcome() {
            Rule deOnly = rule("deOnly", r -> "ok").countryScope(CountryScope.of("DE")).build();
            Rule world = released("world");
            DataRecord noCountry = DataRecord.ofMap(Map.of("id", "none"));

            CollectingSink sink = run(List.of(record("de", "DE"), record("fr", "FR"), noCountry), deOnly, world);

            assertThat(sink.outcomes()).hasSize(4);
            assertThat(sink.results()).extracting(Result::recordId, Result::ruleId).containsExactly(
                    tuple("de", "deOnly"), tuple("de", "world"), tuple("fr", "world"), tuple("none", "world"));
        }

        @Test
        void rejectsDuplicateRuleIdsBeforeReadingAnyRecord() {
            CountingSource source = new CountingSource(records(3));

            assertThatThrownBy(() -> engine.run(source,
                    RuleCatalog.of(released("dup"), rule("dup", r -> "x").status(RuleStatus.DRAFT).build()),
                    new CollectingSink()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("'dup'");
            assertThat(source.pulls).isZero();
        }

        @Test
        void snapshotsCatalogOncePerRun() {
            int[] calls = {0};
            RuleCatalog catalog = () -> {
                calls[0]++;
                return List.of(released("a"));
            };

            engine.run(RecordSource.of(records(5)), catalog, RunOptions.builder().batchSize(1).build(),
                    new CollectingSink());

            assertThat(calls[0]).isEqualTo(1);
        }
    }

    @Nested
    class Streaming {

        @Test
        void emitsInRecordThenCatalogOrder() {
            CollectingSink sink = run(records(2), released("b"), released("a"));

            assertThat(sink.results()).extracting(Result::recordIndex, Result::ruleId).containsExactly(
                    tuple(0L, "b"), tuple(0L, "a"), tuple(1L, "b"), tuple(1L, "a"));
        }

        @Test
        void emitsTheFirstBatchBeforeReadingTheNext() {
            CountingSource source = new CountingSource(records(10));
            List<Integer> pullsAtEmit = new ArrayList<>();

            engine.run(source, RuleCatalog.of(released("a")), RunOptions.builder().batchSize(3).build(),
                    outcome -> pullsAtEmit.add(source.pulls));

            assertThat(pullsAtEmit).containsExactly(3, 3, 3, 6, 6, 6, 9, 9, 9, 11);
        }

        @ParameterizedTest
        @ValueSource(ints = {1, 2, 3, 7, 1_000})
        void batchSizeDoesNotChangeOutput(int batchSize) {
            CollectingSink sink = new CollectingSink();

            RunSummary summary = engine.run(RecordSource.of(records(7)), RuleCatalog.of(released("a"), released("b")),
                    RunOptions.builder().batchSize(batchSize).build(), sink);

            assertThat(sink.results()).extracting(Result::recordId)
                    .containsExactly("r0", "r0", "r1", "r1", "r2", "r2", "r3", "r3", "r4", "r4", "r5", "r5", "r6",
                            "r6");
            assertThat(summary.batches()).isEqualTo((7 + batchSize - 1) / batchSize);
            assertThat(summary.recordsRead()).isEqualTo(7);
        }

        @Test
        void emptyInputGivesEmptySummary() {
            RunSummary summary = engine.run(RecordSource.of(List.of()), RuleCatalog.of(released("a")),
                    new CollectingSink());

            assertThat(summary.recordsRead()).isZero();
            assertThat(summary.batches()).isZero();
            assertThat(summary.resultCount()).isZero();
            assertThat(summary.resultsByDecision()).containsOnlyKeys(Decision.values());
        }

        @Test
        void engineIsReusableAcrossRuns() {
            CollectingSink first = run(records(2), released("a"));
            CollectingSink second = run(records(2), released("a"));

            assertThat(second.results()).isEqualTo(first.results());
        }
    }

    @Nested
    class FaultIsolation {

        @Test
        void failingRuleDoesNotAffectOtherRulesOfTheSameRecord() {
            Rule failing = rule("failing", r -> r.requireString("missing")).build();

            CollectingSink sink = run(records(1), released("before"), failing, released("after"));

            assertThat(sink.outcomes()).hasSize(3);
            assertThat(sink.results()).extracting(Result::ruleId).containsExactly("before", "after");
            assertThat(sink.errors()).singleElement().isInstanceOfSatisfying(RuleError.class, e -> {
                assertThat(e.ruleId()).isEqualTo("failing");
                assertThat(e.recordId()).isEqualTo("r0");
                assertThat(e.cause()).isInstanceOf(MissingFieldException.class);
                assertThat(e.fieldsRead()).containsExactly(FieldRead.absent("missing"));
            });
        }

        @Test
        void nullValueFromLogicIsARuleError() {
            CollectingSink sink = run(records(1), rule("nullish", r -> null).build());

            assertThat(sink.errors()).singleElement()
                    .satisfies(e -> assertThat(e.errorType()).isEqualTo(IllegalStateException.class.getName()));
        }

        @Test
        void exceptionWithoutMessageUsesItsType() {
            CollectingSink sink = run(records(1), rule("npe", r -> {
                throw new NullPointerException();
            }).build());

            assertThat(sink.errors()).singleElement()
                    .satisfies(e -> assertThat(e.message()).isEqualTo(NullPointerException.class.getName()));
        }

        @Test
        void stackOverflowInRuleIsIsolated() {
            CollectingSink sink = run(records(2), rule("recursive", ValidationEngineTest::recurse).build());

            assertThat(sink.errors()).hasSize(2)
                    .allSatisfy(e -> assertThat(e.cause()).isInstanceOf(StackOverflowError.class));
        }

        @Test
        void outOfMemoryErrorIsNotSwallowed() {
            Rule oom = rule("oom", r -> {
                throw new OutOfMemoryError("simulated");
            }).build();

            assertThatThrownBy(() -> run(records(1), oom)).isInstanceOf(OutOfMemoryError.class);
        }

        @Test
        void unidentifiableRecordsAreRecordErrorsAndSkipAllRules() {
            DataRecord blankId = DataRecord.ofMap(Map.of("id", " "));
            DataRecord objectId = DataRecord.ofMap(Map.of("id", Map.of("x", "y")));
            DataRecord badCountry = DataRecord.ofMap(Map.of("id", "c", "country", new BigDecimal("49")));

            CollectingSink sink = run(List.of(blankId, objectId, badCountry, record("ok", "DE")), released("a"));

            assertThat(sink.results()).extracting(Result::recordId).containsExactly("ok");
            assertThat(sink.errors()).hasSize(3).allSatisfy(e -> assertThat(e).isInstanceOf(RecordError.class));
            assertThat(sink.errors()).extracting(ValidationError::errorType).containsExactly(
                    IllegalArgumentException.class.getName(),
                    FieldTypeException.class.getName(),
                    FieldTypeException.class.getName());
            assertThat(((RecordError) sink.errors().get(2)).recordId()).contains("c");
        }

        @Test
        void hostRecordThatThrowsIsARecordErrorWhenReadingKeys() {
            DataRecord broken = path -> {
                throw new IllegalStateException("backing row closed");
            };

            CollectingSink sink = run(List.of(broken, record("ok", "DE")), released("a"));

            assertThat(sink.errors()).singleElement().isInstanceOf(RecordError.class);
            assertThat(sink.results()).hasSize(1);
        }

        @Test
        void sinkFailureAbortsTheRunAndStopsReading() {
            CountingSource source = new CountingSource(records(10));

            assertThatThrownBy(() -> engine.run(source, RuleCatalog.of(released("a")),
                    RunOptions.builder().batchSize(2).build(), outcome -> {
                        throw new IllegalStateException("disk full");
                    }))
                    .hasMessage("disk full");
            assertThat(source.pulls).isEqualTo(2);
        }

        @Test
        void brokenSourceAbortsTheRun() {
            RecordSource broken = () -> {
                throw new IllegalStateException("connection reset");
            };

            assertThatThrownBy(() -> engine.run(broken, RuleCatalog.of(released("a")), new CollectingSink()))
                    .hasMessage("connection reset");
        }
    }

    @Nested
    class RecordKeys {

        @Test
        void usesConfiguredIdAndCountryFields() {
            ValidationEngine custom = ValidationEngine.builder().idField("meta.partnerId").countryField("addr.cc")
                    .build();
            DataRecord record = DataRecord.ofMap(Map.of(
                    "meta", Map.of("partnerId", "p-1"), "addr", Map.of("cc", "FR")));
            CollectingSink sink = new CollectingSink();

            custom.run(RecordSource.of(List.of(record)),
                    RuleCatalog.of(rule("fr", r -> "ok").countryScope(CountryScope.of("FR")).build()), sink);

            assertThat(sink.results()).singleElement().satisfies(r -> assertThat(r.recordId()).isEqualTo("p-1"));
        }

        @Test
        void acceptsNumericIds() {
            CollectingSink sink = run(List.of(
                    DataRecord.ofMap(Map.of("id", 42)),
                    DataRecord.ofMap(Map.of("id", new BigDecimal("1E+3")))), released("a"));

            assertThat(sink.results()).extracting(Result::recordId).containsExactly("42", "1000");
        }

        @Test
        void rejectsMalformedFieldPaths() {
            assertThatThrownBy(() -> ValidationEngine.builder().idField("a..b"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    private static String recurse(RecordView record) {
        return recurse(record);
    }

    /** Source over a list that counts how many times the engine pulled from it. */
    private static final class CountingSource implements RecordSource {
        private final List<DataRecord> records;
        private int pulls;

        CountingSource(List<DataRecord> records) {
            this.records = records;
        }

        @Override
        public Optional<DataRecord> next() {
            int index = pulls++;
            return index < records.size() ? Optional.of(records.get(index)) : Optional.empty();
        }
    }
}
