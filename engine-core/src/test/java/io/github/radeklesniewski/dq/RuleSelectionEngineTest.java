package io.github.radeklesniewski.dq;

import static io.github.radeklesniewski.dq.rule.Decision.NOT_APPLICABLE;
import static io.github.radeklesniewski.dq.rule.Decision.VALID;
import static io.github.radeklesniewski.dq.rule.RuleStatus.DEPRECATED;
import static io.github.radeklesniewski.dq.rule.RuleStatus.DRAFT;
import static io.github.radeklesniewski.dq.rule.RuleStatus.RELEASED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import io.github.radeklesniewski.dq.record.DataRecord;
import io.github.radeklesniewski.dq.record.RecordSource;
import io.github.radeklesniewski.dq.rule.CountryScope;
import io.github.radeklesniewski.dq.rule.DecisionMapping;
import io.github.radeklesniewski.dq.rule.Rule;
import io.github.radeklesniewski.dq.rule.RuleCatalog;
import io.github.radeklesniewski.dq.rule.RuleStatus;
import io.github.radeklesniewski.dq.rule.Severity;
import io.github.radeklesniewski.dq.select.RuleFilter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** F6 at engine level: caller filters compose, can only narrow, and never let non-RELEASED rules run. */
class RuleSelectionEngineTest {

    private final ValidationEngine engine = ValidationEngine.create();

    private static final AtomicInteger EXECUTIONS = new AtomicInteger();

    private static Rule rule(String id, RuleStatus status, CountryScope scope, String... categories) {
        return Rule.builder(id)
                .label(id)
                .logic(r -> {
                    EXECUTIONS.incrementAndGet();
                    return "ok";
                })
                .decisionMapping(DecisionMapping.builder().map("ok", VALID).orElse(NOT_APPLICABLE))
                .severity(Severity.WARNING)
                .status(status)
                .countryScope(scope)
                .categories(categories)
                .build();
    }

    private static final Rule WORLD_TAX = rule("worldTax", RELEASED, CountryScope.world(), "tax");
    private static final Rule DE_BANK = rule("deBank", RELEASED, CountryScope.of("DE"), "bank");
    private static final Rule FR_TAX = rule("frTax", RELEASED, CountryScope.of("FR"), "tax");
    private static final Rule DRAFT_TAX = rule("draftTax", DRAFT, CountryScope.world(), "tax");
    private static final Rule DEPRECATED_BANK = rule("deprecatedBank", DEPRECATED, CountryScope.world(), "bank");
    private static final RuleCatalog CATALOG = RuleCatalog.of(WORLD_TAX, DE_BANK, FR_TAX, DRAFT_TAX, DEPRECATED_BANK);

    private static final List<DataRecord> RECORDS = List.of(
            DataRecord.ofMap(Map.of("id", "de", "country", "DE")),
            DataRecord.ofMap(Map.of("id", "fr", "country", "FR")));

    private record Run(CollectingSink sink, RunSummary summary) {
    }

    private Run run(RunOptions options) {
        CollectingSink sink = new CollectingSink();
        RunSummary summary = engine.run(RecordSource.of(RECORDS), CATALOG, options, sink);
        return new Run(sink, summary);
    }

    private Run run(RuleFilter filter) {
        return run(RunOptions.builder().ruleFilter(filter).build());
    }

    @Nested
    class ReleasedOnly {

        @Test
        void filterSelectingOnlyDraftRunsNothing() {
            Run run = run(RuleFilter.status(DRAFT));

            assertThat(run.sink.outcomes()).isEmpty();
            assertThat(run.summary.rulesSelected()).isZero();
            assertThat(run.summary.recordsRead()).as("records are still read and counted").isEqualTo(2);
        }

        @Test
        void filterSelectingEveryStatusStillRunsOnlyReleased() {
            Run run = run(RuleFilter.status(DRAFT, RELEASED, DEPRECATED));

            assertThat(run.summary.rulesSelected()).isEqualTo(3);
            assertThat(run.sink.results()).extracting(Result::ruleId)
                    .doesNotContain("draftTax", "deprecatedBank");
        }

        @Test
        void negatedFilterCannotWidenSelection() {
            Run run = run(RuleFilter.category("nothing").negate());

            assertThat(run.sink.results()).extracting(Result::ruleId)
                    .containsOnly("worldTax", "deBank", "frTax");
        }
    }

    @Nested
    class Filters {

        @Test
        void defaultSelectsAllReleasedRulesAppliedPerRecordScope() {
            Run run = run(RunOptions.defaults());

            assertThat(run.summary.rulesSelected()).isEqualTo(3);
            assertThat(run.sink.results()).extracting(Result::recordId, Result::ruleId).containsExactly(
                    tuple("de", "worldTax"), tuple("de", "deBank"),
                    tuple("fr", "worldTax"), tuple("fr", "frTax"));
        }

        @Test
        void categoryFilter() {
            Run run = run(RuleFilter.category("bank"));

            assertThat(run.summary.rulesSelected()).isEqualTo(1);
            assertThat(run.sink.results()).extracting(Result::recordId, Result::ruleId)
                    .containsExactly(tuple("de", "deBank"));
        }

        @Test
        void countryFilterNarrowsRulesAndPerRecordScopeStillApplies() {
            Run run = run(RuleFilter.country("FR"));

            assertThat(run.summary.rulesSelected()).isEqualTo(2);
            assertThat(run.sink.results()).extracting(Result::recordId, Result::ruleId).containsExactly(
                    tuple("de", "worldTax"),
                    tuple("fr", "worldTax"), tuple("fr", "frTax"));
        }

        @Test
        void composedFilters() {
            Run run = run(RuleFilter.category("tax").and(RuleFilter.country("FR").negate()));

            assertThat(run.sink.results()).extracting(Result::ruleId).isEmpty();
            assertThat(run(RuleFilter.category("tax").and(RuleFilter.country("DE"))).sink.results())
                    .extracting(Result::ruleId).containsExactly("worldTax", "worldTax");
        }

        @Test
        void throwingFilterFailsBeforeAnyRecordIsRead() {
            RuleFilter broken = RuleFilter.of("broken", rule -> {
                throw new IllegalStateException("bad filter");
            });
            int[] pulls = {0};
            RecordSource source = () -> {
                pulls[0]++;
                return Optional.empty();
            };

            assertThatThrownBy(() -> engine.run(source, CATALOG, RunOptions.builder().ruleFilter(broken).build(),
                    new CollectingSink())).hasMessage("bad filter");
            assertThat(pulls[0]).isZero();
        }
    }

    @Nested
    class OutOfScope {

        @Test
        void emitsNotApplicableWithoutExecutingTheRule() {
            EXECUTIONS.set(0);

            Run run = run(RunOptions.builder().emitOutOfScope(true).build());

            assertThat(run.sink.results()).extracting(Result::recordId, Result::ruleId, Result::decision)
                    .containsExactly(
                            tuple("de", "worldTax", VALID), tuple("de", "deBank", VALID),
                            tuple("de", "frTax", NOT_APPLICABLE),
                            tuple("fr", "worldTax", VALID), tuple("fr", "deBank", NOT_APPLICABLE),
                            tuple("fr", "frTax", VALID));
            assertThat(EXECUTIONS).as("only in-scope pairs execute logic").hasValue(4);
        }

        @Test
        void outOfScopeProvenanceExplainsTheSkip() {
            Run run = run(RunOptions.builder().emitOutOfScope(true).build());

            Result skipped = run.sink.results().stream()
                    .filter(r -> r.provenance().basis() == Provenance.Basis.OUT_OF_SCOPE)
                    .filter(r -> r.recordId().equals("fr"))
                    .findFirst().orElseThrow();

            assertThat(skipped.ruleId()).isEqualTo("deBank");
            assertThat(skipped.severity()).isEqualTo(Severity.WARNING);
            assertThat(skipped.provenance().fieldsRead()).containsExactly(FieldRead.present("country", "FR"));
            assertThat(skipped.provenance().computedValue()).isEmpty();
            assertThat(skipped.provenance().defaultDecision()).isFalse();
        }

        @Test
        void recordWithoutCountryIsOutOfScopeForCountryRules() {
            CollectingSink sink = new CollectingSink();
            engine.run(RecordSource.of(List.of(DataRecord.ofMap(Map.of("id", "none")))), RuleCatalog.of(DE_BANK),
                    RunOptions.builder().emitOutOfScope(true).build(), sink);

            assertThat(sink.results()).singleElement().satisfies(r -> {
                assertThat(r.decision()).isEqualTo(NOT_APPLICABLE);
                assertThat(r.provenance().fieldsRead()).containsExactly(FieldRead.absent("country"));
            });
        }

        @Test
        void outOfScopeResultsAreCountedAsNotApplicable() {
            RunSummary summary = run(RunOptions.builder().emitOutOfScope(true).build()).summary;

            assertThat(summary.resultCount()).isEqualTo(6);
            assertThat(summary.count(NOT_APPLICABLE)).isEqualTo(2);
            assertThat(summary.count(VALID)).isEqualTo(4);
        }

        @Test
        void filteredOutRulesProduceNoOutOfScopeResults() {
            Run run = run(RunOptions.builder().emitOutOfScope(true).ruleFilter(RuleFilter.category("bank")).build());

            assertThat(run.sink.results()).extracting(Result::recordId, Result::decision)
                    .containsExactly(tuple("de", VALID), tuple("fr", NOT_APPLICABLE));
        }
    }
}
