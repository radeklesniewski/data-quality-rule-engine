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
- **The request body is spooled to a temporary file first, then validated and streamed back** (changed after
  review). The first version wrote results while still reading the request. That deadlocked half-duplex clients,
  which upload the whole body before reading anything (`HttpURLConnection`, Python `requests`, many proxies): once
  the response filled the socket buffers, neither side progressed. This was reproduced with 200k records and is now
  covered by `ExampleHttpServerTest#halfDuplexClientWithALargeBodyGetsTheCompleteResponse`. Spooling keeps memory
  bounded (the copy uses a 64 KiB buffer, and the engine still reads records lazily from the file) and works with any
  client. The costs are disk I/O and a later first byte. The temp file is owner-only and deleted when the request
  ends. A body over the limit (`--max-body-mb`, default 1 GiB) gets `413` before any validation.
- The `200` status is sent once the upload is stored but before the records are parsed. If the body is malformed
  half-way, the response ends with `"error"` and **no `"summary"`** (`JsonOutcomeWriter.abort`). Clients must treat a
  response without a summary as incomplete. Returning a `400` instead would mean parsing everything twice.
- The catalog is snapshotted per request *before* streaming starts, so a broken catalog file is a clean 500.
- Not done (it's an example): auth, rate limits, read/write timeouts, a cap on concurrent requests, a shared worker
  pool for parallel requests (each parallel request creates its own pool), metrics. See DESIGN.md §10–11.
