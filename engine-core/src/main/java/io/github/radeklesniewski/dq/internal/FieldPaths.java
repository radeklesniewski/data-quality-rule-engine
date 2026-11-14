package io.github.radeklesniewski.dq.internal;

import java.util.Objects;

/** Validation and parsing of dot-separated field paths. Internal. */
public final class FieldPaths {

    private FieldPaths() {
    }

    /**
     * Checks that {@code path} is well-formed: non-blank, dot-separated, no empty segments.
     *
     * @param path the path to check
     * @return {@code path}, for chaining
     * @throws IllegalArgumentException if malformed
     */
    public static String requireValid(String path) {
        Objects.requireNonNull(path, "path");
        if (path.isBlank() || path.startsWith(".") || path.endsWith(".") || path.contains("..")) {
            throw new IllegalArgumentException("Invalid field path '" + path
                    + "': expected non-empty dot-separated segments, e.g. 'address.city'");
        }
        return path;
    }

    /**
     * Splits a valid path into its segments.
     *
     * @param path a path accepted by {@link #requireValid(String)}
     * @return the segments, in order
     */
    static String[] segments(String path) {
        return requireValid(path).split("\\.");
    }
}
