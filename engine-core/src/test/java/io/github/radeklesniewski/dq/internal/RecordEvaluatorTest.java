package io.github.radeklesniewski.dq.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.radeklesniewski.dq.RecordError;
import io.github.radeklesniewski.dq.record.RecordReadException;
import io.github.radeklesniewski.dq.rule.Decision;
import io.github.radeklesniewski.dq.rule.DecisionMapping;
import io.github.radeklesniewski.dq.rule.Rule;
import io.github.radeklesniewski.dq.rule.Severity;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class RecordEvaluatorTest {

    @Test
    void batchTimesRulesBeyondIntRangeDoesNotBreakTheBatch() {
        Rule rule = Rule.builder("r").label("R").logic(view -> "ok").severity(Severity.ERROR)
                .decisionMapping(DecisionMapping.builder().orElse(Decision.VALID)).build();
        RecordEvaluator evaluator = new RecordEvaluator(
                Collections.nCopies(50_000, rule), new RecordKeys("id", "country"), false);
        // 50,000 × 50,000 = 2.5 billion: more than an int holds. Unreadable items keep the test fast: each gives
        // exactly one RecordError without evaluating any rule.
        List<BatchItem> batch = Collections.nCopies(50_000,
                new BatchItem.Unreadable(0, new RecordReadException("unreadable", null)));

        assertThat(evaluator.evaluateBatch(batch)).hasSize(50_000).allMatch(RecordError.class::isInstance);
    }
}
