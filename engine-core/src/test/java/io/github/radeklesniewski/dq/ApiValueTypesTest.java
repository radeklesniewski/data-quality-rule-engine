package io.github.radeklesniewski.dq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.radeklesniewski.dq.rule.Decision;
import io.github.radeklesniewski.dq.rule.Severity;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ApiValueTypesTest {

    private static Result result() {
        return new Result(Decision.VALID, Severity.INFO,
                new Provenance("rule", "rec", 0, List.of(), "ok", false));
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
        RunSummary summary = new RunSummary(1, 1, 1, 2, 3, Map.of(Decision.VALID, 1L), Map.of());

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
        assertThatThrownBy(() -> new Provenance("r", "x", -1, List.of(), "v", false))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
