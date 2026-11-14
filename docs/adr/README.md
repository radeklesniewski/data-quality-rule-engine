# Architecture Decision Records

One file per decision: `NNNN-short-title.md`. Never delete an ADR. Supersede it with a new one and link both ways.
`DESIGN.md` summarises the important ones for reviewers.

| #    | Title                                         | Status   |
|------|-----------------------------------------------|----------|
| 0001 | [Java 25 toolchain](0001-java-25.md)           | Accepted |
| 0002 | [Multi-module layout](0002-multi-module.md)     | Accepted |
| 0003 | [Jackson + JDK HttpServer](0003-jackson-jdk-httpserver.md) | Accepted |
| 0004 | [Rule logic in Java, catalog as data](0004-rule-definition.md) | Accepted |
| 0005 | [Streaming output and fault isolation](0005-streaming-and-fault-isolation.md) | Accepted |
| 0006 | [Execution mode: ordered by default, opt-in parallel](0006-execution-mode-ordering-vs-parallelism.md) | Accepted |
| 0007 | [Country-scope mismatch: skip, no Result](0007-country-scope-mismatch.md) | Accepted |
| 0008 | [Record access model and computed value type](0008-record-access-and-computed-value.md) | Accepted |
| 0009 | [Rule selection: composable filters that can only narrow](0009-rule-selection.md) | Accepted |
| 0010 | [JSON adapters: input, catalog and output formats](0010-json-formats.md) | Accepted |
| 0011 | [engine-json: one package per concern](0011-engine-json-packages.md) | Accepted |

## Template

```markdown
# NNNN. Title

- Status: Proposed | Accepted | Superseded by NNNN
- Date: YYYY-MM-DD

## Context
What forces are at play (requirements, constraints)?

## Decision
What we do.

## Consequences
Trade-offs: what gets easier and what gets harder. What we'd revisit with more time.
```
