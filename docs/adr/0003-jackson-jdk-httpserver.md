# 0003. Jackson for JSON, JDK HttpServer for the example API

- Status: Accepted
- Date: 2026-11-11

## Context
Records arrive as JSON. The target is ~1M records per run with bounded memory, so input must be parsed
incrementally. The HTTP API is only an example host.

## Decision
- Jackson 3 (`tools.jackson.*`, the current major line). Jackson streaming (`JsonParser`) reads one record at a time from a JSON array. We never load the whole array as a tree.
- The example uses `com.sun.net.httpserver.HttpServer` with a virtual-thread executor. It has no web framework.

## Consequences
- End-to-end streaming is visible and easy to reason about: request body → parser → engine → response writer.
- There's no routing, validation or content negotiation for free. That's acceptable for a demo of a single endpoint.
- A production host would probably use its existing framework. The library doesn't care.
