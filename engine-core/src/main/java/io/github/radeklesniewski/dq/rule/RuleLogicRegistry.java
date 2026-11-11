package io.github.radeklesniewski.dq.rule;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Resolves rule logic by a stable logic id. This is the bridge between rules defined as data (in a JSON file or a
 * database) and logic written in Java (ADR-0004). A catalog source stores the logic id next to each rule's
 * metadata and decision mapping, and resolves it here when loading.
 *
 * <p>Implementations must be thread-safe.
 */
public interface RuleLogicRegistry {

    /**
     * Looks up logic by id.
     *
     * @param logicId the logic id stored in the catalog
     * @return the logic, or empty if no logic is registered under {@code logicId}
     */
    Optional<RuleLogic> find(String logicId);

    /**
     * Returns every registered logic id, used in error messages that list the valid choices.
     *
     * @return every registered logic id
     */
    Set<String> logicIds();

    /**
     * Returns an immutable registry backed by a copy of {@code logic}.
     *
     * @param logic logic id to logic; ids follow the same format as rule ids
     * @return the registry
     * @throws IllegalArgumentException if an id is malformed
     */
    static RuleLogicRegistry of(Map<String, ? extends RuleLogic> logic) {
        Objects.requireNonNull(logic, "logic");
        Pattern idFormat = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");
        Map<String, RuleLogic> copy = new LinkedHashMap<>();
        logic.forEach((id, function) -> {
            Objects.requireNonNull(id, "logic id");
            if (!idFormat.matcher(id).matches()) {
                throw new IllegalArgumentException("Malformed logic id: '" + id + "'");
            }
            copy.put(id, Objects.requireNonNull(function, () -> "logic for id '" + id + "'"));
        });
        Map<String, RuleLogic> registered = Collections.unmodifiableMap(copy);
        return new RuleLogicRegistry() {
            @Override
            public Optional<RuleLogic> find(String logicId) {
                return Optional.ofNullable(registered.get(logicId));
            }

            @Override
            public Set<String> logicIds() {
                return registered.keySet();
            }

            @Override
            public String toString() {
                return "RuleLogicRegistry" + registered.keySet();
            }
        };
    }
}
