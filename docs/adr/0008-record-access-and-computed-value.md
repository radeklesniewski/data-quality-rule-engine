# 0008. Record access model and computed value type

- Status: Accepted
- Date: 2026-11-11

## Context
`engine-core` has no JSON dependency (ADR-0002), so it needs its own record abstraction. Provenance must name the
fields and values a rule read (F1), and the decision mapping must be plain data that a catalog file can hold (F2).

## Decision
- **`DataRecord`** (SPI, one method `Optional<Object> get(path)`) is what hosts implement or create with
  `DataRecord.ofMap(...)`. **`RecordView`** is what rule logic sees: typed accessors (`getString`, `has`,
  `require…`) built on `get`. Keeping the SPI minimal makes adapters trivial. The rich API lives in default methods.
- **Provenance is captured, not reported.** The engine hands logic a tracking view that records every distinct
  path read, with the value seen (first read wins, read order kept). Logic can't forget or misreport provenance.
  The recorded fields are also kept when logic fails part-way, which helps locate the cause of a rule error.
- **The computed value is a `String`** (an outcome code such as `"ok"`, `"blocked"`). Mapping keys in a JSON or
  YAML catalog are strings, so a `String` value avoids type-coercion ambiguity (`true` vs `"true"`, `1` vs `1.0`).
- Field semantics: dot paths, no array indexing; JSON `null` equals absent; an empty string is present.
- Absence is either a normal outcome (`get…` gives an empty Optional) or a fault (`require…` throws
  `MissingFieldException`, which becomes a rule error). The rule author chooses per field. A wrong type
  always throws `FieldTypeException`.
- Exception messages carry field paths and types, **never values** (personal or financial data ends up in logs).
  Values appear only in `Result` provenance, which the caller controls.

## Consequences
- Rule logic that wants a number or boolean outcome converts it to a string code. That's a small cost for
  catalogs that stay unambiguous.
- Field names containing `.` can't be addressed, and arrays can't be indexed. This is documented and can be
  added later behind `FieldPaths`.
- Allocating one tracking view per (rule, record) is cheap but not free. The JMH benchmark in M4 measures it.
