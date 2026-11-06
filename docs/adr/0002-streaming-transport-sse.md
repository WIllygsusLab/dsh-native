# ADR-0002 — Live updates: one multiplexed SSE stream

- Status: **proposed**
- Deciders: architect, backend
- Related: contract §4, ADR-0003, ADR-0005

## Context

The app needs live data for: session list changes, running state, transcript appends,
assistant token streaming, job lifecycle, agent roster, plugin state, and decisions.

What the host actually offers (`dsh-api-session-controller/lib/types/index.d.ts`):

- `sessionController.control(signal): AsyncIterable<SessionControlFrame>` — host-wide
  projection baseline + replacement frames (session list metadata, model selection).
- `sessionController.follow(request, signal): AsyncIterable<SessionFollowFrame>` — one
  session: `snapshot` (header, cursor, records, projections, assistant-stream baseline), then
  durable `event` frames, then `assistant-stream` frames (start/chunk/end).
- `sessionController.page(...)` — cold, message-aligned backward history.
- `ctx.jobs.events` — job lifecycle events.
- `agent/created`, `agent/disposed`, `api-session/added|removed|status|activity|error` events.
- `approval/request` and `user-questions/request` — in-process waterfalls.

What the transport offers (`dsh-client-connection`): the existing `/api` fence admits
authenticated Fetch routes; `ctx.connection.fetch.register({path, methods, requestBody, fetch})`
returns a WHATWG `Response`, and the node bridge streams the body with backpressure. So SSE is
directly available without touching the host's RPC mux (`/api/remote.mux`, Typert protocol).

Options considered:

1. **One multiplexed SSE stream** (`GET /api/mobile/v1/stream`) with a global event id.
2. **Per-domain SSE streams** (one per session, one for jobs, …).
3. **WebSocket carrying the Typert Remote protocol** (`/api/remote.mux`).
4. **Polling** every 1–2 s.

## Decision

**One multiplexed SSE stream per app process, with a bounded replay ring, a global monotonic
event id, and an explicit `resync` frame.**

Details (normative in contract §4):

- Path `GET /api/mobile/v1/stream?since=<lastEventId>` (or `Last-Event-ID` header).
- `Content-Type: text/event-stream`, `Cache-Control: no-store`, `X-Accel-Buffering: no`.
- Every frame carries `id:` (stream-global integer), `event:`, `data:` (single-line JSON).
- Frame set: `hello`, `session.upsert|removed|status`, `message.append|replace`,
  `assistant.delta|done`, `job.upsert`, `agent.upsert`, `decision.upsert`, `plugin.upsert`,
  `resync`, `ping`.
- Heartbeat every 15 s.
- Replay ring of the last 500 frames. `since` inside the ring → exact replay then live.
  Outside → `resync {reason:"gap"}` then live. Host restart → `resync {reason:"host-restarted"}`.
- The server follows at most **20 sessions** (all live-agent sessions + most recent by
  `updatedAt`, LRU-extended when the client opens a cold session).
- `assistant.delta` is ephemeral and never replayed; durable `message.append` always wins.

## Why

- **One connection, one reconnect path, one backoff.** A phone on Wi-Fi/LAN loses connections
  constantly; N per-session streams would multiply reconnect logic and races.
- **SSE over plain HTTP** needs no protocol library in Java: `HttpURLConnection` +
  `BufferedReader` is ~80 lines, whereas a Typert RPC client is a substantial re-implementation
  of a browser client in Java — high risk, no payoff.
- **The host already produces exactly this fan-in**: `control()` is host-wide and `follow()`
  is per-session, so the mux is a thin composition, not a new event system.
- **Global id + replay ring** gives exactly-once-ish delivery without per-session cursors and
  without a server-side subscription registry. `resync` is the honest failure mode: the client
  re-bootstraps, which it must be able to do anyway (cold start).
- Polling would burn battery and still feel laggy for token streaming.

## Consequences

- The mobile plugin gains a real component: id counter, ring buffer, per-session follow
  supervisors, subscriber lifecycle (close on client disconnect), heartbeat timer. This is the
  largest M1 backend card; it must be its own card, not folded into others.
- The client must implement the reconciliation rule (contract §4.5) and `resync` handling
  from day one, even before replay exists.
- `assistant.delta` is best-effort; the transcript is correct from durable frames alone. The
  UI must never depend on deltas for final state.
- Fan-out is bounded (≤ 20 follows), so host CPU is predictable. Measure on the reference
  host during M1; if a follow set of 20 is too costly, lower it to 10 and extend LRU on open.

## Fallback

If the replay ring is judged too costly for M1, ship **resync-only streaming**: every connect
begins with `resync {reason:"cold"}` and the client re-bootstraps. That is a contained
downgrade — the client already handles `resync` — and the ring can land in M2 without a
contract change. Record the downgrade on the card; do not silently skip replay.

If SSE proves unreliable through some proxy in the field, the escape hatch is a
long-poll `GET /events?since=` endpoint with the same frame shape. Do not switch to
WebSocket/Typert.
