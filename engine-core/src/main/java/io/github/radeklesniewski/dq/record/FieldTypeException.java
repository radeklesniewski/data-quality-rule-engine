package io.github.radeklesniewski.dq.record;

import java.io.Serial;
import java.util.Objects;

/** A field is present but its value has a different type than the rule logic expected. */
public final class FieldTypeException extends FieldAccessException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final String expectedType;
    private final String actualType;

    /**
     * Creates an exception for a type mismatch.
     *
     * @param path the field path
     * @param expected the type the rule expected
     * @param actual the type actually found
     */
    public FieldTypeException(String path, Class<?> expected, Class<?> actual) {
        super(path, "Field '" + path + "' expected " + expected.getSimpleName() + " but was " + actual.getSimpleName());
        this.expectedType = Objects.requireNonNull(expected, "expected").getName();
        this.actualType = Objects.requireNonNull(actual, "actual").getName();
    }

    /**
     * Returns the fully qualified name of the expected type.
     *
     * @return the fully qualified name of the expected type
     */
    public String expectedType() {
        return expectedType;
    }

    /**
     * Returns the fully qualified name of the actual type.
     *
     * @return the fully qualified name of the actual type
     */
    public String actualType() {
        return actualType;
    }
}
