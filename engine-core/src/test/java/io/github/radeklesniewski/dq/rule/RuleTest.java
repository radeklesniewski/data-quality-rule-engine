package io.github.radeklesniewski.dq.rule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RuleTest {

    private static Rule.Builder valid(String id) {
        return Rule.builder(id)
                .label("A rule")
                .logic(r -> "ok")
                .decisionMapping(DecisionMapping.builder().map("ok", Decision.VALID).orElse(Decision.INVALID))
                .severity(Severity.WARNING);
    }

    @Test
    void defaultsAreSafe() {
        Rule rule = valid("r").build();

        assertThat(rule.status()).as("unreleased unless stated").isEqualTo(RuleStatus.DRAFT);
        assertThat(rule.countryScope()).isEqualTo(CountryScope.world());
        assertThat(rule.categories()).isEmpty();
    }

    @Test
    void keepsAllComponents() {
        Rule rule = valid("vat.format-v2_1")
                .status(RuleStatus.RELEASED)
                .countryScope(CountryScope.of("DE"))
                .categories("tax", "format")
                .build();

        assertThat(rule.id()).isEqualTo("vat.format-v2_1");
        assertThat(rule.status()).isEqualTo(RuleStatus.RELEASED);
        assertThat(rule.severity()).isEqualTo(Severity.WARNING);
        assertThat(rule.countryScope()).isEqualTo(CountryScope.of("DE"));
        assertThat(rule.categories()).containsExactly("tax", "format");
        assertThat(rule.hasCategory("tax")).isTrue();
        assertThat(rule.hasCategory("bank")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "-leading", "has space", "ümlaut", "a/b"})
    void rejectsInvalidIds(String id) {
        assertThatThrownBy(() -> valid(id).build()).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requiresSeverityAndNamesTheRule() {
        assertThatThrownBy(() -> valid("vatFormat").severity(null).build())
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("severity")
                .hasMessageContaining("vatFormat");
    }

    @Test
    void requiresLogicAndMapping() {
        assertThatThrownBy(() -> valid("r").logic(null).build()).hasMessageContaining("logic");
        assertThatThrownBy(() -> valid("r").decisionMapping(null).build()).hasMessageContaining("decisionMapping");
    }

    @Test
    void rejectsBlankLabelAndCategory() {
        assertThatThrownBy(() -> valid("r").label(" ").build()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> valid("r").categories("ok", " ").build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void categoriesAreCopiedAndImmutable() {
        List<String> source = new ArrayList<>(List.of("tax"));
        Rule.Builder builder = valid("r").categories(source);
        Rule rule = builder.build();
        source.add("bank");
        builder.categories("later");

        assertThat(rule.categories()).containsExactly("tax");
        assertThatThrownBy(() -> rule.categories().add("x")).isInstanceOf(UnsupportedOperationException.class);
    }
}
