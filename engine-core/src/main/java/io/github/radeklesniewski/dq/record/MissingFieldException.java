package io.github.radeklesniewski.dq.record;

import java.io.Serial;

/** A field that rule logic declared as required ({@code require…}) is absent from the record. */
public final class MissingFieldException extends FieldAccessException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Creates an exception for a missing required field.
     *
     * @param path the missing field path
     */
    public MissingFieldException(String path) {
        super(path, "Required field '" + path + "' is missing");
    }
}
