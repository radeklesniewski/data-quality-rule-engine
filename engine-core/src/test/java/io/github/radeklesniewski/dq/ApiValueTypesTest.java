package io.github.radeklesniewski.dq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.radeklesniewski.dq.rule.Decision;
import io.github.radeklesniewski.dq.rule.Severity;
import io.github.radeklesniewski.dq.select.RuleFilter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ApiValueTypesTest {

    private static Result result() {
        return new Result(Decision.VALID, Severity.INFO,
                Provenance.evaluated("rule", "rec", 0, List.of(), "ok", false));
    }

    @Test
    void runOptionsValidateAndRoundTrip() {
        assertThat(RunOptions.defaults().batchSize()).isEqualTo(RunOptions.DEFAULT_BATCH_SIZE);
        assertThat(RunOptions.builder().batchSize(5).build().toBuilder().build())
                .isEqualTo(RunOptions.builder().batchSize(5).build());
        assertThatThrownBy(() -> RunOptions.builder().batchSize(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void summaryFillsMissingKeysWithZeroAndIsImmutable() {
        RunSummary summary = new RunSummary(2, 1, 1, 1, 2, 3, Map.of(Decision.VALID, 1L), Map.of());

        assertThat(summary.count(Decision.REVIEW)).isZero();
        assertThat(summary.count(Severity.ERROR)).isZero();
        assertThat(summary.errorCount()).isEqualTo(5);
        assertThatThrownBy(() -> summary.resultsByDecision().put(Decision.VALID, 9L))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void sinkRoutesResultsAndErrors() {
        List<Result> results = new ArrayList<>();
        List<ValidationError> errors = new ArrayList<>();
        OutcomeSink sink = OutcomeSink.of(results::add, errors::add);
        RecordError error = RecordError.of(3, Optional.empty(), new IllegalStateException("x"));

        sink.accept(result());
        sink.accept(error);

        assertThat(results).containsExactly(result());
        assertThat(errors).containsExactly(error);
    }

    @Test
    void collectingSinkSplitsOutcomesAndExposesReadOnlyViews() {
        CollectingSink sink = new CollectingSink();
        RecordError error = RecordError.of(1, Optional.of("r"), new IllegalStateException());

        sink.accept(result());
        sink.accept(error);

        assertThat(sink.outcomes()).containsExactly(result(), error);
        assertThat(sink.results()).containsExactly(result());
        assertThat(sink.errors()).containsExactly(error);
        assertThatThrownBy(() -> sink.results().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void provenanceRejectsNegativeIndex() {
        assertThatThrownBy(() -> Provenance.evaluated("r", "x", -1, List.of(), "v", false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void provenanceKeepsBasisAndComputedValueConsistent() {
        assertThatThrownBy(() -> new Provenance("r", "x", 0, Provenance.Basis.OUT_OF_SCOPE, List.of(),
                Optional.of("v"), false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Provenance("r", "x", 0, Provenance.Basis.EVALUATED, List.of(),
                Optional.empty(), false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Provenance("r", "x", 0, Provenance.Basis.OUT_OF_SCOPE, List.of(),
                Optional.empty(), true)).isInstanceOf(IllegalArgumentException.class);

        Provenance outOfScope = Provenance.outOfScope("r", "x", 0, FieldRead.present("country", "FR"));
        assertThat(outOfScope.computedValue()).isEmpty();
        assertThat(outOfScope.fieldsRead()).containsExactly(FieldRead.present("country", "FR"));
    }

    @Test
    void runOptionsCarryFilterAndOutOfScopeFlag() {
        RunOptions options = RunOptions.builder()
                .ruleFilter(RuleFilter.category("tax"))
                .emitOutOfScope(true)
                .executionMode(ExecutionMode.PARALLEL_ORDERED)
                .parallelism(3)
                .maxInFlightBatches(5)
                .build();

        assertThat(RunOptions.defaults().ruleFilter()).isEqualTo(RuleFilter.all());
        assertThat(RunOptions.defaults().emitOutOfScope()).isFalse();
        assertThat(options.toBuilder().build()).isEqualTo(options).hasSameHashCodeAs(options);
        assertThat(options).hasToString("RunOptions[batchSize=1000, ruleFilter=category any of [tax], "
                + "emitOutOfScope=true, executionMode=PARALLEL_ORDERED, parallelism=3, maxInFlightBatches=5]");
        assertThatThrownBy(() -> RunOptions.builder().ruleFilter(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void executionDefaults() {
        RunOptions defaults = RunOptions.defaults();

        assertThat(defaults.executionMode()).isEqualTo(ExecutionMode.SEQUENTIAL);
        assertThat(defaults.parallelism()).isEqualTo(Runtime.getRuntime().availableProcessors());
        assertThat(defaults.maxInFlightBatches()).isEqualTo(2 * defaults.parallelism());
        assertThat(RunOptions.builder().parallelism(3).build().maxInFlightBatches()).isEqualTo(6);
        assertThatThrownBy(() -> RunOptions.builder().parallelism(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RunOptions.builder().maxInFlightBatches(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RunOptions.builder().executionMode(null)).isInstanceOf(NullPointerException.class);
    }
}
