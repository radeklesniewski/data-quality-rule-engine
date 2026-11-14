# 0010. JSON adapters: input, catalog and output formats

- Status: Accepted (implemented in M5)
- Date: 2026-11-11

## Context
Hosts receive records as JSON (HTTP bodies, batch files) and want rule catalogs editable as data (ADR-0004). The
core has no JSON dependency (ADR-0002), so all JSON handling lives in `engine-json`, built on Jackson 3. Every
adapter must keep F5's streaming guarantee and F3's fault-isolation semantics.

## Decision

**Input: `JsonRecordSources`. There are two formats, because recovery differs.**
- `array(in)`: one JSON array of objects. Jackson's streaming parser reads **one element at a time** into a tree.
  A non-object element is skipped and reported as unreadable (`RecordReadException` → `RecordError`). A
  *syntax* error leaves the parser unable to find the next element, so it aborts the run with
  `JsonSourceException`. That's the honest behaviour: guessing where the next record starts would invent data.
  **Content after the closing `]`** (a second array, stray text) is a `JsonSourceException` too, raised after
  every record of the array has been delivered. The first version silently ignored it, which lost data without a
  trace (found in review).
- `lines(in)`: NDJSON, one object per line. Each line is parsed on its own, so **even a syntax error only loses that
  one line**. This is the format to recommend for large batch files.
- Records are `JsonDataRecord`s, a `DataRecord` that reads directly from the Jackson node without copying it into
  maps. Decimals are `BigDecimal` (no float rounding of amounts or ids).
- Neither format bounds the size of a **single** record or NDJSON line: one record is parsed into memory at a time.
  Memory is bounded in the number of records, not in the size of one record (see DESIGN.md §4 and §10).

**Catalog: `JsonRuleCatalog`. It's strict, all-or-nothing, and reports every problem at once.**
- Format: `{"rules": [{id, label, logic, status, severity, countryScope?, categories?, decisionMapping: {mappings, default}}]}`.
  The decision mapping is plain data. `logic` is an id resolved through `RuleLogicRegistry` (in core, so other
  catalog sources such as a database or YAML can use it too).
- Unknown properties are errors, because they're usually typos ("severty") that would otherwise silently fall back to
  defaults. `status` is required in data, even though the Java builder defaults it to DRAFT.
- Every problem (bad enum, unknown logic id, duplicate id, invalid rule) is collected with its location
  (`rules[1] 'vatFormat': ...`) into one `CatalogLoadException`, so authors fix everything in one pass.
- `JsonRuleCatalog.watching(path)` re-reads the file when its modification time changes. The engine snapshots a
  catalog once per run, so edits apply from the next run, never mid-run. An invalid edit makes the next run fail
  fast instead of silently running stale rules.

**Output: `JsonOutcomeWriter`, an `OutcomeSink` that writes as outcomes arrive.**
- `document(out)`: `{"outcomes": [...], "summary": {...}}` for HTTP responses. `lines(out)`: NDJSON, ending with a
  `{"type":"summary"}` line. If the writer is closed without `finish(summary)`, the JSON is still well-formed but has
  **no summary**. That's how a consumer can tell the run didn't complete.
- Outcomes carry `"type"` (`result` / `ruleError` / `recordError`). Absent fields are explicit:
  `{"path": "vatId", "absent": true}`. Stack traces are never serialised, only the type and message.

**Privacy:** parser error messages never quote input. `INCLUDE_SOURCE_IN_LOCATION` is off, and our messages give
line, column or element positions only. Field values leave the library only inside result provenance.

## Consequences
- `engine-json` exposes Jackson types (`JsonDataRecord.of(JsonNode)`), so it declares Jackson as an `api` dependency
  and `requires transitive` it. Hosts that use a different JSON library can skip this module and adapt
  `DataRecord` themselves. It's a single method.
- The array format can't recover from syntax errors. This is documented, and NDJSON is the recommended format for
  untrusted bulk input.
- Next: parameterised logic in the catalog (e.g. `{"logic": "minLength", "params": {"field": "vatId", "min": 9}}`)
  so that new rules of an existing kind need no code. And a JSON Schema for catalog files, to help editors.

## Review notes (2026-11-14)

Added after an independent review of `engine-json`. They record fixes and the reasoning behind them. The formats
themselves didn't change.

- **NDJSON is split into lines as bytes, and Jackson parses each line's bytes.** The first version decoded the
  input with an `InputStreamReader`. That replaced invalid UTF-8 with U+FFFD without telling anyone, so rules
  validated altered values, and it rejected a UTF-8 byte-order mark, so a file saved on Windows lost its first
  record. Jackson validates UTF-8 and accepts a BOM, so a bad line is now a `RecordError` for that line only, as the
  format promises. The array format already behaved this way. CRLF line endings and a missing final newline are fine.
- **A failing stream is an I/O error, not a syntax error.** Jackson 3 wraps stream failures in `JacksonIOException`,
  a subtype of the `JacksonException` that also means "malformed". A dropped connection or a disk error used to be
  reported as `Malformed JSON at line …` (array source) or `Catalog is not valid JSON`. Both now throw
  `UncheckedIOException`, as the NDJSON source always did.
- **`toString()` of a record shows field names only** (`JsonDataRecord[id, iban]`, and the same for core's
  `MapDataRecord`). Values are personal data and must not reach logs or exception messages; they leave the library
  only inside result provenance.
- **`JsonOutcomeWriter` rejects any write after `close()`** with `IllegalStateException` (it used to fail inside
  Jackson with an NPE). `close()` relies on Jackson's `AUTO_CLOSE_CONTENT`, enabled explicitly in `Json.MAPPER`, to
  close whatever is open. So the output stays well-formed even when a write failed half-way through an outcome, where
  closing the array and object by hand used to throw.
- **`CatalogLoadException.problems()` survives serialization** (the field was `transient`, so it came back `null`) and
  the exception needs at least one problem.
- **Why the catalog is parsed by hand rather than bound with Jackson.** Binding straight into Java records would be
  shorter, but it stops at the first error, which would lose "report every problem at once". The parser builds a
  rule only when every part of it parsed, so it never relies on "no new problems means every value is present".
- **Not done yet:** JSON with duplicate keys keeps the last value silently. For catalogs, Jackson's
  `STRICT_DUPLICATE_DETECTION` would turn `{"ok": "VALID", "ok": "INVALID"}` into a load problem, in the same spirit as
  rejecting unknown properties. The two layouts also name the failure field differently (`"error"` in a document,
  `"message"` on the NDJSON error line); aligning them would change the wire format.

**Module structure after the single-responsibility refactor (2026-11-14).** The public API is unchanged: the
entry points `JsonRecordSources`, `JsonRuleCatalog`, `JsonOutcomeWriter`, `JsonDataRecord` and the two exceptions.
Each entry point now delegates to package-private classes with one job each, so none of these is API:

| Concern | Entry point | Helpers |
|---|---|---|
| Records in | `JsonRecordSources` (factories) | `JsonArraySource`, `NdjsonSource`, `ByteLineReader` (byte lines, no decoding), `RecordNodes` (node → record, shared error wording) |
| Record values | `JsonDataRecord` (path navigation) | `JsonValues` (JSON → plain value model, ADR-0008) |
| Catalog | `JsonRuleCatalog` (load, watch, read JSON) | `CatalogParser` (catalog format → rules), `CatalogFields` (validated typed property access), `CatalogProblems` (located problem collection), `WatchingCatalog` (change detection and caching) |
| Outcomes out | `JsonOutcomeWriter` (lifecycle: finish, abort, close) | `OutcomeJson` (shape of outcomes and summary), `OutputLayout` (document vs NDJSON framing) |
