# 0004. Rule logic in Java, catalog (metadata + decision mapping) as data

- Status: Accepted
- Date: 2026-11-11

## Context
F2 requires the value → decision mapping to be data, not if/else. Rule *logic* (computing the value) must
still be expressed somehow. Options: Java code, an expression language (SpEL/JEXL/CEL), or a mix.

## Decision
- Rule logic is a Java functional interface (`RuleLogic`: tracked record → value), registered under a stable logic id.
- A `Rule` binds id, label, logic, `DecisionMapping` and metadata (status, severity, country scope, categories).
- Catalogs come through a `RuleCatalog` SPI. There are two implementations: in-code (builder) and JSON (`engine-json`),
  where metadata and mapping are data and logic is referenced by id from a `RuleLogicRegistry`.
- Unknown logic ids or a missing default mapping make catalog loading fail fast.

## Consequences
- Logic is type-safe, testable and debuggable. Business users can change mappings and metadata without a deploy.
- Adding a new *kind* of check needs code. An expression-language `RuleLogic` would be the next extension,
  and it fits behind the same interface.
