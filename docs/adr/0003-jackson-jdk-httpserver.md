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
- **Implemented (M6)** as `ExampleHttpServer`: `POST /validate` (JSON array or NDJSON in; document or NDJSON out),
  `GET /rules`, `GET /health`. Rule selection and execution mode come from query parameters, which are validated
  before the body is read (400). The handler runs on virtual threads.
- **Streaming has an HTTP consequence:** the `200` status goes out before the body has been fully read. If the body
  turns out to be malformed half-way, the response ends with `"error"` and **no `"summary"`**
  (`JsonOutcomeWriter.abort`). Clients must treat a response without a summary as incomplete. The alternative,
  buffering the whole result to pick a status code, would break bounded memory.
- The catalog is snapshotted per request *before* streaming starts, so a broken catalog file is a clean 500.
- Not done (it's an example): auth, request size or rate limits, a shared worker pool for parallel requests
  (each parallel request creates its own pool, capped at the core count), metrics.
