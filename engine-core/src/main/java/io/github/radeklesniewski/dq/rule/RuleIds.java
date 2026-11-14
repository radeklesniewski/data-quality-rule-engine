package io.github.radeklesniewski.dq.rule;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The one format for identifiers that catalogs store and look up: rule ids and logic ids. Letters, digits,
 * {@code .}, {@code _} and {@code -}, starting with a letter or digit. Keeping it in one place means a logic id
 * accepted by a registry can never be rejected as a rule id, or the other way round.
 */
final class RuleIds {

    private static final Pattern FORMAT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    private RuleIds() {
    }

    /**
     * Checks {@code id} against the format.
     *
     * @param kind what the id identifies, for messages, e.g. {@code "Rule id"}
     * @return {@code id}, for chaining
     * @throws NullPointerException if {@code id} is null
     * @throws IllegalArgumentException if {@code id} doesn't match the format
     */
    static String requireValid(String kind, String id) {
        Objects.requireNonNull(id, kind);
        if (!FORMAT.matcher(id).matches()) {
            throw new IllegalArgumentException(
                    kind + " must match " + FORMAT.pattern() + " (letters, digits, '.', '_', '-'), got: '" + id + "'");
        }
        return id;
    }
}
