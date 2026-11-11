package io.github.radeklesniewski.dq.rule;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Which records a rule applies to, by country: every record ({@link World}) or one country ({@link Country}).
 *
 * <p>Matching is exact and case-sensitive against the record's country code. Normalising input data is the host's
 * job. A record without a country matches only {@link World} (ADR-0007).
 */
public sealed interface CountryScope permits CountryScope.World, CountryScope.Country {

    /** The keyword accepted by {@link #of(String)} for {@link World}. */
    String WORLD_KEYWORD = "WORLD";

    /**
     * Returns the scope that applies to every record.
     *
     * @return the scope that applies to every record
     */
    static CountryScope world() {
        return World.INSTANCE;
    }

    /**
     * Parses a scope: {@code "WORLD"} or a two-letter upper-case country code such as {@code "DE"}.
     *
     * @param code the scope code
     * @return the scope
     * @throws IllegalArgumentException if {@code code} is neither
     */
    static CountryScope of(String code) {
        Objects.requireNonNull(code, "code");
        return WORLD_KEYWORD.equals(code) ? world() : new Country(code);
    }

    /**
     * Returns whether a rule with this scope applies to a record with the given country.
     *
     * @param recordCountry the record's country code, or empty if the record has none
     * @return {@code true} if the rule applies
     */
    boolean appliesTo(Optional<String> recordCountry);

    /** Applies to every record, with or without a country. */
    enum World implements CountryScope {
        /** The single instance. */
        INSTANCE;

        @Override
        public boolean appliesTo(Optional<String> recordCountry) {
            return true;
        }

        @Override
        public String toString() {
            return WORLD_KEYWORD;
        }
    }

    /**
     * Applies only to records whose country equals {@code code}.
     *
     * @param code a two-letter upper-case country code (ISO 3166-1 alpha-2 format; user-assigned codes such as
     *     {@code ZZ} are accepted)
     */
    record Country(String code) implements CountryScope {

        private static final Pattern CODE = Pattern.compile("[A-Z]{2}");

        /** Validates the code. */
        public Country {
            Objects.requireNonNull(code, "code");
            if (!CODE.matcher(code).matches()) {
                throw new IllegalArgumentException(
                        "Country scope must be 'WORLD' or a two-letter upper-case country code, got: '" + code + "'");
            }
        }

        @Override
        public boolean appliesTo(Optional<String> recordCountry) {
            return recordCountry.filter(code::equals).isPresent();
        }

        @Override
        public String toString() {
            return code;
        }
    }
}
