package io.github.radeklesniewski.dq.json;

import java.io.Serial;
import java.util.List;

/** A rule catalog couldn't be loaded. Lists <em>every</em> problem found, each prefixed with its location. */
public final class CatalogLoadException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final transient List<String> problems;

    /**
     * Creates the exception.
     *
     * @param problems one message per problem, e.g. {@code rules[2] 'vatFormat': "severity" is required}
     * @param cause the underlying failure, or {@code null}
     */
    public CatalogLoadException(List<String> problems, Throwable cause) {
        super("Invalid rule catalog (" + problems.size() + " problem" + (problems.size() == 1 ? "" : "s") + "):\n  - "
                + String.join("\n  - ", problems), cause);
        this.problems = List.copyOf(problems);
    }

    /**
     * Returns every problem found, in document order.
     *
     * @return every problem found, in document order
     */
    public List<String> problems() {
        return problems;
    }
}
