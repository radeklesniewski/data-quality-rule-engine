package io.github.radeklesniewski.dq.rule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CountryScopeTest {

    @Test
    void worldAppliesToEveryRecordIncludingOnesWithoutCountry() {
        assertThat(CountryScope.world().appliesTo(Optional.of("DE"))).isTrue();
        assertThat(CountryScope.world().appliesTo(Optional.empty())).isTrue();
    }

    @Test
    void countryAppliesOnlyToTheSameCountry() {
        CountryScope de = CountryScope.of("DE");

        assertThat(de.appliesTo(Optional.of("DE"))).isTrue();
        assertThat(de.appliesTo(Optional.of("FR"))).isFalse();
        assertThat(de.appliesTo(Optional.of("de"))).isFalse();
        assertThat(de.appliesTo(Optional.empty())).isFalse();
    }

    @Test
    void parsesWorldKeyword() {
        assertThat(CountryScope.of("WORLD")).isSameAs(CountryScope.world());
        assertThat(CountryScope.of("ZZ")).isEqualTo(new CountryScope.Country("ZZ"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "de", "DEU", "D", "world", "D1"})
    void rejectsInvalidCodes(String code) {
        assertThatThrownBy(() -> CountryScope.of(code))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'" + code + "'");
    }

    @Test
    void toStringIsTheCode() {
        assertThat(CountryScope.world()).hasToString("WORLD");
        assertThat(CountryScope.of("FR")).hasToString("FR");
    }
}
