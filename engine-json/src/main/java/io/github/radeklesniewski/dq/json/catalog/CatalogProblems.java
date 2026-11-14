package io.github.radeklesniewski.dq.json.catalog;

import java.util.ArrayList;
import java.util.List;

/**
 * Collects every problem found while loading a catalog, each prefixed with its location, e.g.
 * {@code rules[1] 'vatFormat': "severity" is required}. Loading reports them all at once instead of stopping at the
 * first. Single use, not thread-safe.
 */
final class CatalogProblems {

    private final List<String> problems = new ArrayList<>();

    /** Records {@code what} is wrong at {@code where}. */
    void add(String where, String what) {
        problems.add(where + ": " + what);
    }

    /** Throws one exception listing everything recorded so far, if anything was. */
    void throwIfAny() {
        if (!problems.isEmpty()) {
            throw failure();
        }
    }

    /** The exception for everything recorded so far; at least one problem must have been recorded. */
    CatalogLoadException failure() {
        return new CatalogLoadException(problems, null);
    }
}
