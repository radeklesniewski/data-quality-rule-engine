# 0011. engine-json: one package per concern

- Status: Accepted
- Date: 2026-11-14

## Context
After the single-responsibility refactor (ADR-0010, review notes), `engine-json` had 19 classes in one package,
`io.github.radeklesniewski.dq.json`: six public entry points and thirteen package-private helpers for three unrelated
concerns (reading records, loading catalogs, writing outcomes). A reader couldn't tell from the package which helper
belongs to which entry point.

Java's package-private access stops at the package. Splitting *by layer* (`dq.json` for the API, `dq.json.internal`
for helpers) would have forced every helper to become `public`. The helpers form three groups with no references
between groups; the only class all three use is the shared Jackson configuration.

## Decision
Split **by feature**, matching core's `dq.record` / `dq.rule` naming:

| Package | Exported | Public types | Package-private helpers |
|---|---|---|---|
| `dq.json.record` | yes | `JsonRecordSources`, `JsonDataRecord`, `JsonSourceException` | `JsonArraySource`, `NdjsonSource`, `ByteLineReader`, `RecordNodes`, `JsonValues` |
| `dq.json.catalog` | yes | `JsonRuleCatalogs`, `CatalogLoadException` | `CatalogParser`, `CatalogFields`, `CatalogProblems`, `WatchingCatalog` |
| `dq.json.outcome` | yes | `JsonOutcomeWriter` | `OutcomeJson`, `OutputLayout` |
| `dq.json.internal` | **no** | `Json` (shared Jackson config) | |

Every helper keeps its package-private access. Only `Json` became `public`, and `module-info` doesn't export its
package, the same pattern as core's `dq.internal`. Class names keep their `Json` prefix: `JsonDataRecord` next to
core's `DataRecord` reads better than two types called `DataRecord`.

## Consequences
- The package tells you the concern, and each package's helpers are invisible outside it, which the compiler enforces.
- **Breaking change** for hosts: imports move from `dq.json.X` to `dq.json.{record,catalog,outcome}.X`. Acceptable
  before a first release; the only consumer, `example-http`, is updated.
- Tests follow their package. `JsonFixtureScenarioTest` stays in `dq.json` because it covers all three packages end to
  end.
- **`JsonRuleCatalog` was renamed `JsonRuleCatalogs`** in the same change. It only has static factories that return
  `RuleCatalog`s (a fixed snapshot or a reloading one), but the singular name read as "a kind of `RuleCatalog`" and
  made people expect it to implement the interface. The plural matches `JsonRecordSources`, which returns
  `RecordSource`s, and the JDK convention (`Files`, `Executors`, `Collections`).
