package io.github.radeklesniewski.dq;

import java.util.Objects;
import java.util.Optional;

/**
 * One field a rule read while computing its value, and the value it saw.
 *
 * @param path the dot-separated field path
 * @param value the value seen, or empty if the field was absent (absence is evidence too: "no vatId" explains "bad")
 */
public record FieldRead(String path, Optional<Object> value) {

    /** Validates components. */
    public FieldRead {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(value, "value (use Optional.empty() for an absent field)");
    }

    /**
     * Returns a read of a present field.
     *
     * @param path the field path
     * @param value the value seen
     * @return the read
     */
    public static FieldRead present(String path, Object value) {
        return new FieldRead(path, Optional.of(value));
    }

    /**
     * Returns a read of an absent field.
     *
     * @param path the field path
     * @return the read
     */
    public static FieldRead absent(String path) {
        return new FieldRead(path, Optional.empty());
    }
}
