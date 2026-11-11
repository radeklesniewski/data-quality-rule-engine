# 0007. Country-scope mismatch: rule is skipped and no Result is emitted

- Status: Accepted
- Date: 2026-11-11

## Context
F6: "a rule applies when its scope is WORLD or its country equals the record's country". F1: the engine runs
each *applicable* rule and makes one Result *when a rule produces an outcome*. When a rule is scoped to a
different country than the record, the engine could either skip it silently or emit a synthetic `NOT_APPLICABLE`.

## Decision
- **Default:** a rule whose country scope doesn't match the record's country is **not executed and produces no Result**.
  `NOT_APPLICABLE` is only ever a decision *computed* by a rule's own mapping (e.g. `countryBlocked` → default).
- **Opt-in:** a `RunOptions` setting (working name `emitOutOfScope`) makes the engine emit a `Result` with decision
  `NOT_APPLICABLE` for skipped rules. Its provenance is explicitly marked *out of scope* (no computed value, no fields read)
  and the rule logic still isn't executed. This is for hosts that need full rule × record coverage in their output.
- If a record has no country, it matches only WORLD-scoped rules. Country-scoped rules are skipped as above.

## Consequences
- Output and summary counts reflect only the checks that actually ran. Output size doesn't grow with catalog size × records.
- `NOT_APPLICABLE` keeps a single meaning by default. With the option on, consumers can tell the two kinds apart by the provenance marker.
- The summary counts out-of-scope Results under `NOT_APPLICABLE` only when the option is on. This is documented on the option.
