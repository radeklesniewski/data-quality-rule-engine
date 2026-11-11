/**
 * Data-quality rule engine: validates records against a catalog of rules with declarative decision mappings.
 *
 * <p>Only API packages are exported. {@code io.github.radeklesniewski.dq.internal} is implementation detail
 * and is never exported.
 */
module io.github.radeklesniewski.dq.core {
    exports io.github.radeklesniewski.dq;
    exports io.github.radeklesniewski.dq.record;
    // io.github.radeklesniewski.dq.select is exported in M3, once it has types
    // (javac rejects exporting a package that contains only package-info.java).
}
