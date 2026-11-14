package io.github.radeklesniewski.dq.rule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RuleLogicRegistryTest {

    private final RuleLogic ok = r -> "ok";

    @Test
    void findsRegisteredLogicById() {
        RuleLogicRegistry registry = RuleLogicRegistry.of(Map.of("alwaysOk", ok));

        assertThat(registry.find("alwaysOk")).containsSame(ok);
        assertThat(registry.find("unknown")).isEmpty();
        assertThat(registry.logicIds()).containsExactly("alwaysOk");
    }

    @Test
    void isACopy() {
        Map<String, RuleLogic> source = new HashMap<>(Map.of("a", ok));
        RuleLogicRegistry registry = RuleLogicRegistry.of(source);
        source.put("b", ok);

        assertThat(registry.logicIds()).containsExactly("a");
        assertThatThrownBy(() -> registry.logicIds().add("c")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsMalformedIdsAndNullLogic() {
        Map<String, RuleLogic> nullLogic = new HashMap<>();
        nullLogic.put("a", null);

        assertThatThrownBy(() -> RuleLogicRegistry.of(Map.of("has space", ok)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RuleLogicRegistry.of(nullLogic)).hasMessageContaining("'a'");
    }

    @Test
    void logicIdsFollowExactlyTheRuleIdFormat() {
        assertThatThrownBy(() -> RuleLogicRegistry.of(Map.of("has space", ok)))
                .hasMessage("Logic id must match [A-Za-z0-9][A-Za-z0-9._-]* (letters, digits, '.', '_', '-'),"
                        + " got: 'has space'");
        assertThatThrownBy(() -> Rule.builder("has space").label("x").logic(ok).severity(Severity.ERROR)
                .decisionMapping(DecisionMapping.builder().orElse(Decision.VALID)).build())
                .hasMessage("Rule id must match [A-Za-z0-9][A-Za-z0-9._-]* (letters, digits, '.', '_', '-'),"
                        + " got: 'has space'");
    }
}
