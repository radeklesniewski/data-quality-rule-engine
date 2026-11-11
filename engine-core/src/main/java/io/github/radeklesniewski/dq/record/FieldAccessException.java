package io.github.radeklesniewski.dq.record;

import java.io.Serial;
import java.util.Objects;

/**
 * A rule could not read a field the way it expected. The engine reports this as a rule error for the record.
 *
 * <p>Messages deliberately contain the field path and types but never the field <em>value</em>. Values are
 * often personal or financial data (names, tax ids, IBANs) and exception messages tend to end up in logs.
 */
public abstract sealed class FieldAccessException extends RuntimeException
        permits MissingFieldException, FieldTypeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final String path;

    FieldAccessException(String path, String message) {
        super(message);
        this.path = Objects.requireNonNull(path, "path");
    }

    /**
     * Returns the field path that could not be read.
     *
     * @return the field path that could not be read
     */
    public String path() {
        return path;
    }
}
