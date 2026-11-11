# 0002. Multi-module layout

- Status: Accepted
- Date: 2026-11-11

## Context
The library is embedded by several hosts (HTTP API, batch workers, scheduled jobs). A library that drags in a
JSON stack or a web framework forces version conflicts on its hosts.

## Decision
Three Gradle modules:
- `engine-core`: the library. No runtime dependencies. It defines the model, the engine and the SPI seams.
- `engine-json`: Jackson-based adapters (streaming record reader, catalog loader, result writer).
- `example-http`: the demo host. Never a dependency of the others.

## Consequences
- Hosts that already parse JSON (or use Avro, Parquet or JDBC) depend only on `engine-core`.
- The core can't use Jackson's `JsonNode` as its record type. We need our own `DataRecord` abstraction.
- There is slightly more build configuration, handled by shared conventions.
