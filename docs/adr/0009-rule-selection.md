# 0009. Rule selection: composable filters that can only narrow

- Status: Accepted (implemented in M3)
- Date: 2026-11-11

## Context
F6: callers restrict a run with filters that compose: by status, country scope and category. Whatever the
filters select, only RELEASED rules run. The HTTP example (M6) needs to build filters from request parameters.

## Decision
- **`RuleFilter`** is a functional interface (`matches(Rule)`) with `and` / `or` / `negate` and `allOf` / `anyOf`.
  The factories are `all()`, `status(…)`, `country(code)`, `category(…)` and `of(description, predicate)`.
- **The built-in filters are immutable values** (records). They have structural equality and a readable
  `toString()`, e.g. `((country DE (incl. WORLD) and category any of [tax]) and not (status in [DRAFT]))`. A run's
  selection can be logged, asserted in tests and compared. Custom predicates go through `of(description, …)` so
  they stay describable.
- **Semantics:**
  - `status(a, b)`: the rule's status is any of these.
  - `country(C)`: the rule *can apply to records of C*, meaning its scope is WORLD or exactly C. This is a rule-level
    filter ("the rules relevant to German records"). It doesn't replace the engine's **per-record** scope check
    (ADR-0007), which always applies. Both hold together.
  - `category(a, b)`: the rule has at least one of these. Uncategorised rules never match. To require all of
    several categories, combine with `and`.
- **Filters can only narrow.** The engine applies the caller's filter, *then* `status == RELEASED`. No filter,
  `negate()` included, can make a DRAFT or DEPRECATED rule run.
- The filter is evaluated **once per run, before any record is read**. A throwing filter is a configuration error
  that aborts the run up front.
- `RunSummary.rulesSelected` reports how many rules took part. A filter that selects nothing (e.g. `status(DRAFT)`)
  isn't an error: records are still read and counted, and the summary shows `rulesSelected = 0`.

## Consequences
- Filters are plain Java and compose freely. Selection logic stays out of the engine.
- Filters see rule metadata only, not records. Record-dependent applicability belongs to rule logic
  (`NOT_APPLICABLE` via the mapping) or to the country scope.
- An empty selection is silent apart from the summary. A host that treats it as a mistake can check
  `rulesSelected == 0`.
