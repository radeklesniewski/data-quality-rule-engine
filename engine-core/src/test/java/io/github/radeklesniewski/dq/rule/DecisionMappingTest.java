package io.github.radeklesniewski.dq.rule;

import static io.github.radeklesniewski.dq.rule.Decision.INVALID;
import static io.github.radeklesniewski.dq.rule.Decision.NOT_APPLICABLE;
import static io.github.radeklesniewski.dq.rule.Decision.REVIEW;
import static io.github.radeklesniewski.dq.rule.Decision.VALID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DecisionMappingTest {

    private final DecisionMapping mapping =
            DecisionMapping.builder().map("ok", VALID).map("bad", INVALID).orElse(REVIEW);

    @Test
    void mapsExplicitValues() {
        assertThat(mapping.decide("ok")).isEqualTo(VALID);
        assertThat(mapping.decide("bad")).isEqualTo(INVALID);
        assertThat(mapping.isMapped("ok")).isTrue();
    }

    @Test
    void fallsBackToDefaultForUnmappedValues() {
        assertThat(mapping.decide("weird")).isEqualTo(REVIEW);
        assertThat(mapping.isMapped("weird")).isFalse();
    }

    @Test
    void matchesValuesExactly() {
        assertThat(mapping.decide("OK")).isEqualTo(REVIEW);
    }

    @Test
    void defaultOnlyMappingIsAllowed() {
        assertThat(DecisionMapping.builder().orElse(NOT_APPLICABLE).decide("anything")).isEqualTo(NOT_APPLICABLE);
    }

    @Test
    void requiresDefault() {
        assertThatThrownBy(() -> DecisionMapping.of(Map.of("ok", VALID), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("default");
    }

    @Test
    void rejectsDuplicateValueInBuilder() {
        assertThatThrownBy(() -> DecisionMapping.builder().map("ok", VALID).map("ok", INVALID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'ok'");
    }

    @Test
    void rejectsNullDecision() {
        Map<String, Decision> source = new HashMap<>();
        source.put("ok", null);

        assertThatThrownBy(() -> DecisionMapping.of(source, REVIEW))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("'ok'");
    }

    @Test
    void isImmutableAndIndependentOfSource() {
        Map<String, Decision> source = new HashMap<>(Map.of("ok", VALID));
        DecisionMapping copy = DecisionMapping.of(source, REVIEW);
        source.put("bad", INVALID);

        assertThat(copy.mappings()).containsOnlyKeys("ok");
        assertThatThrownBy(() -> copy.mappings().put("x", VALID)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void preservesDeclarationOrder() {
        DecisionMapping ordered = DecisionMapping.builder()
                .map("c", VALID).map("a", INVALID).map("b", REVIEW).orElse(REVIEW);

        assertThat(ordered.mappings().keySet()).containsExactly("c", "a", "b");
    }

    @Test
    void isValueEqual() {
        assertThat(DecisionMapping.of(Map.of("ok", VALID, "bad", INVALID), REVIEW)).isEqualTo(mapping);
    }
}
