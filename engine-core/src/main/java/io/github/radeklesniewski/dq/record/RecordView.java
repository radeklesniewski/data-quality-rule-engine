package io.github.radeklesniewski.dq.record;

import io.github.radeklesniewski.dq.internal.FieldPaths;
import io.github.radeklesniewski.dq.internal.RecordFields;
import java.util.Objects;
import java.util.Optional;

/**
 * Read-only view of a record, handed to rule logic.
 *
 * <p>During a validation run the engine passes a view that records every field path read, together with the
 * value it saw. That record becomes the {@code Result}'s provenance automatically, so rule authors don't (and
 * can't) report it by hand. Read every field the computed value depends on through this view.
 *
 * <p>Accessors fall into two families:
 * <ul>
 *   <li>{@code get…} / {@link #has}: absence is a normal outcome (an empty {@link Optional}). Use these when
 *       a missing field is itself something the rule judges, e.g. "no vatId" leads to {@code "bad"}.</li>
 *   <li>{@code require…}: absence is a fault. A {@link MissingFieldException} is thrown and the engine reports
 *       it as a rule error for this record, without stopping the run.</li>
 * </ul>
 * A present value of the wrong type always throws {@link FieldTypeException}.
 */
@FunctionalInterface
public interface RecordView {

    /**
     * Returns the raw value at {@code path}.
     *
     * @param path a dot-separated field path
     * @return the value, or empty if absent
     * @throws IllegalArgumentException if {@code path} is malformed
     */
    Optional<Object> get(String path);

    /**
     * Returns whether the field at {@code path} is present.
     *
     * @param path a dot-separated field path
     * @return {@code true} if present (an empty string counts as present)
     */
    default boolean has(String path) {
        return get(path).isPresent();
    }

    /**
     * Returns the string value at {@code path}.
     *
     * @param path a dot-separated field path
     * @return the value, or empty if absent
     * @throws FieldTypeException if the value is present but not a {@link String}
     */
    default Optional<String> getString(String path) {
        return get(path).map(value -> RecordFields.as(path, value, String.class));
    }

    /**
     * Returns the value at {@code path}, treating absence as a fault.
     *
     * @param path a dot-separated field path
     * @return the value
     * @throws MissingFieldException if absent
     */
    default Object require(String path) {
        return get(path).orElseThrow(() -> new MissingFieldException(path));
    }

    /**
     * Returns the string value at {@code path}, treating absence as a fault.
     *
     * @param path a dot-separated field path
     * @return the value
     * @throws MissingFieldException if absent
     * @throws FieldTypeException if present but not a {@link String}
     */
    default String requireString(String path) {
        return getString(path).orElseThrow(() -> new MissingFieldException(path));
    }

    /**
     * Wraps a record in a view that does <em>not</em> record reads. Useful for unit-testing rule logic in isolation.
     *
     * @param record the record to view
     * @return a non-tracking view
     */
    static RecordView of(DataRecord record) {
        Objects.requireNonNull(record, "record");
        return path -> RecordFields.read(record, FieldPaths.requireValid(path));
    }
}
