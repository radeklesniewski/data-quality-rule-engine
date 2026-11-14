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

## Review notes (2026-11-14)

Added after an independent review of `example-http`. They record the reasoning behind questions reviewers asked.
None of them changes the decision above.

- **Why `/validate` is an action, not a noun.** REST names resources, but validation stores nothing: it's a
  stateless computation over the request body. `POST /validations` would imply a `GET /validations/{id}` that doesn't
  exist. An action URL is the accepted convention for such calls (Google AIP-136 spells it `POST /records:validate`).
  Nouns become right with the asynchronous job API (DESIGN.md §11 #8): `POST /validation-runs` returns `202` and a
  `Location`, `GET /validation-runs/{id}` gives the status, `GET /validation-runs/{id}/outcomes` the results. A real
  API would also add a version prefix (`/v1`).
- **Why it deadlocked.** The handler was one thread doing "read a record, evaluate it, write its outcomes" over
  blocking sockets. Each TCP direction buffers a finite amount (send plus receive buffer, KBs to a few MB). A
  half-duplex client reads nothing until it has sent everything. So the server→client buffers fill and the server's
  `write` blocks. The server then stops reading, the client→server buffers fill and the client's `write` blocks. Each
  side waits for the other to read. Output is larger than input (about three outcomes with provenance per record), so
  it only shows once a request is big enough, hence 200k records to reproduce it. `java.net.http.HttpClient` reads and
  writes concurrently, which is why the original tests passed.
- **Which failures are the server's.** A failure before the `200` is sent gets an answer. An invalid or unreadable
  catalog (any exception from `RuleCatalog.rules()`, on both `/validate` and `/rules`) and a temp file that can't be
  created or written (e.g. a full disk) are logged at ERROR and returned as `500`. Only I/O on the connection itself
  (reading the request, writing the response) means the client has gone away; that is logged at DEBUG. `spool()`
  keeps the two apart: failures to read the request are thrown unchecked, failures to write the file checked. A temp
  file that can't be deleted is logged at WARNING. An earlier version reported all of these as a client disconnect
  and dropped the connection without a response.
- **Is the temp file safe?** `Files.createTempFile` picks the name with `SecureRandom` and creates the file atomically
  (`CREATE_NEW`), so there are no symlink or name-guessing races. On POSIX it gets `rw-------`, so "owner-only" is a
  JDK default rather than something the code asks for. On Windows the file inherits the per-user `%TEMP%` ACL.
  Remaining risks, accepted for an example: partner data (VAT ids, IBANs) sits unencrypted on disk while a request
  runs; a crash or `kill -9` skips the `finally` and leaves the file behind; concurrent requests aren't capped, so
  N × the body limit can fill a shared `/tmp`. Cheap hardening: a configurable owner-only spool directory on tmpfs or
  an encrypted volume, explicit POSIX permissions, a startup sweep of stale `dq-request-*` files, rejecting a
  `Content-Length` over the limit before copying anything, a free-space check, and a request semaphore (`503` with
  `Retry-After`). On Linux the file can also be deleted right after it's opened and read back through the same
  `FileChannel`, so it's never visible and can't be left behind, even after a crash.
- **What a production server would do instead.** Avoid large synchronous uploads. The client uploads to object
  storage or a job endpoint and gets `202`. Batch workers, which the library already targets, run the job, and the
  client polls and downloads the results. Encryption at rest, retention and audit then come from the storage layer.
  For a synchronous API the simple alternative is a small request cap (e.g. 10k records) buffered in memory, with
  larger batches going through the batch path. Another is memory up to a threshold and disk beyond it, as servlet
  multipart handling does.
