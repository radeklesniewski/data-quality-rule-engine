/**
 * Data-quality rule engine: validates records against a catalog of rules with declarative decision mappings.
 *
 * <p>Only API packages are exported. {@code io.github.radeklesniewski.dq.internal} is implementation detail
 * and is never exported.
 */
module io.github.radeklesniewski.dq.core {
    // Exports are added as the API packages gain types (javac rejects exporting a package with no types):
    //   io.github.radeklesniewski.dq, io.github.radeklesniewski.dq.rule, io.github.radeklesniewski.dq.select
}
