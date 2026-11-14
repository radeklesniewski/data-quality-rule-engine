package io.github.radeklesniewski.dq.json.catalog;

import java.io.Serial;
import java.util.List;
import java.util.Objects;

/** A rule catalog couldn't be loaded. Lists <em>every</em> problem found, each prefixed with its location. */
public final class CatalogLoadException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    // Not transient: problems() must survive serialization. List.copyOf returns a serializable list, which the
    // declared type can't express, hence the suppression.
    @SuppressWarnings("serial")
    private final List<String> problems;

    /**
     * Creates the exception.
     *
     * @param problems one message per problem, e.g. {@code rules[2] 'vatFormat': "severity" is required}; at least one
     * @param cause the underlying failure, or {@code null}
     * @throws IllegalArgumentException if {@code problems} is empty
     */
    public CatalogLoadException(List<String> problems, Throwable cause) {
        super(message(problems), cause);
        this.problems = List.copyOf(problems);
    }

    private static String message(List<String> problems) {
        Objects.requireNonNull(problems, "problems");
        if (problems.isEmpty()) {
            throw new IllegalArgumentException("A catalog load failure needs at least one problem");
        }
        return "Invalid rule catalog (" + problems.size() + " problem" + (problems.size() == 1 ? "" : "s") + "):\n  - "
                + String.join("\n  - ", problems);
    }

    /**
     * Returns every problem found, in document order.
     *
     * @return every problem found, in document order; never empty
     */
    public List<String> problems() {
        return problems;
    }
}
