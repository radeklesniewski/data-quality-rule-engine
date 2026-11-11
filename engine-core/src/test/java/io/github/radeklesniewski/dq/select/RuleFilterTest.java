package io.github.radeklesniewski.dq.select;

import static io.github.radeklesniewski.dq.rule.RuleStatus.DEPRECATED;
import static io.github.radeklesniewski.dq.rule.RuleStatus.DRAFT;
import static io.github.radeklesniewski.dq.rule.RuleStatus.RELEASED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.radeklesniewski.dq.rule.CountryScope;
import io.github.radeklesniewski.dq.rule.Decision;
import io.github.radeklesniewski.dq.rule.DecisionMapping;
import io.github.radeklesniewski.dq.rule.Rule;
import io.github.radeklesniewski.dq.rule.RuleStatus;
import io.github.radeklesniewski.dq.rule.Severity;
import java.util.List;
import org.junit.jupiter.api.Test;

class RuleFilterTest {

    private static Rule rule(String id, RuleStatus status, CountryScope scope, String... categories) {
        return Rule.builder(id)
                .label(id)
                .logic(r -> "ok")
                .decisionMapping(DecisionMapping.builder().orElse(Decision.VALID))
                .severity(Severity.INFO)
                .status(status)
                .countryScope(scope)
                .categories(categories)
                .build();
    }

    private final Rule worldTax = rule("worldTax", RELEASED, CountryScope.world(), "tax");
    private final Rule deBank = rule("deBank", RELEASED, CountryScope.of("DE"), "bank", "format");
    private final Rule frDraft = rule("frDraft", DRAFT, CountryScope.of("FR"), "tax");
    private final Rule worldPlain = rule("worldPlain", DEPRECATED, CountryScope.world());
    private final List<Rule> catalog = List.of(worldTax, deBank, frDraft, worldPlain);

    @Test
    void allSelectsEverything() {
        assertThat(RuleFilter.all().select(catalog)).isEqualTo(catalog);
    }

    @Test
    void statusSelectsAnyOfTheGivenStatuses() {
        assertThat(RuleFilter.status(DRAFT, DEPRECATED).select(catalog)).containsExactly(frDraft, worldPlain);
    }

    @Test
    void countrySelectsWorldAndThatCountry() {
        assertThat(RuleFilter.country("DE").select(catalog)).containsExactly(worldTax, deBank, worldPlain);
        assertThat(RuleFilter.country("FR").select(catalog)).containsExactly(worldTax, frDraft, worldPlain);
        assertThat(RuleFilter.country("PL").select(catalog)).containsExactly(worldTax, worldPlain);
    }

    @Test
    void categorySelectsAnyOfTheGivenCategoriesAndNeverUncategorisedRules() {
        assertThat(RuleFilter.category("tax").select(catalog)).containsExactly(worldTax, frDraft);
        assertThat(RuleFilter.category("bank", "tax").select(catalog)).containsExactly(worldTax, deBank, frDraft);
        assertThat(RuleFilter.category("unknown").select(catalog)).isEmpty();
    }

    @Test
    void filtersCompose() {
        RuleFilter taxOrBankInDe =
                RuleFilter.country("DE").and(RuleFilter.category("tax").or(RuleFilter.category("bank")));
        RuleFilter allCategoriesOfDeBank = RuleFilter.category("bank").and(RuleFilter.category("format"));
        RuleFilter notDraft = RuleFilter.status(DRAFT).negate();

        assertThat(taxOrBankInDe.select(catalog)).containsExactly(worldTax, deBank);
        assertThat(allCategoriesOfDeBank.select(catalog)).containsExactly(deBank);
        assertThat(notDraft.select(catalog)).containsExactly(worldTax, deBank, worldPlain);
        assertThat(RuleFilter.allOf(RuleFilter.category("tax"), notDraft).select(catalog)).containsExactly(worldTax);
        assertThat(RuleFilter.anyOf(RuleFilter.country("PL"), RuleFilter.status(DRAFT)).select(catalog))
                .containsExactly(worldTax, frDraft, worldPlain);
    }

    @Test
    void doubleNegationIsTheOriginal() {
        RuleFilter tax = RuleFilter.category("tax");

        assertThat(tax.negate().negate()).isEqualTo(tax);
    }

    @Test
    void customFiltersCompose() {
        RuleFilter byId = RuleFilter.of("id in [deBank]", rule -> rule.id().equals("deBank"));

        assertThat(byId.or(RuleFilter.status(DRAFT)).select(catalog)).containsExactly(deBank, frDraft);
        assertThat(byId).hasToString("id in [deBank]");
    }

    @Test
    void builtInFiltersAreValuesWithReadableDescriptions() {
        RuleFilter filter = RuleFilter.country("DE")
                .and(RuleFilter.category("tax", "bank"))
                .and(RuleFilter.status(DRAFT).negate());

        assertThat(filter).isEqualTo(RuleFilter.country("DE")
                .and(RuleFilter.category("tax", "bank"))
                .and(RuleFilter.status(DRAFT).negate()));
        assertThat(filter).hasToString(
                "((country DE (incl. WORLD) and category any of [tax, bank]) and not (status in [DRAFT]))");
        assertThat(RuleFilter.all()).hasToString("all");
    }

    @Test
    void rejectsEmptyOrInvalidArguments() {
        assertThatThrownBy(RuleFilter::status).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(RuleFilter::category).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(RuleFilter::allOf).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RuleFilter.country("de")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RuleFilter.country("WORLD")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RuleFilter.category("tax", null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> RuleFilter.all().and(null)).isInstanceOf(NullPointerException.class);
    }
}
