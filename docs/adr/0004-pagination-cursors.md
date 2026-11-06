# ADR-0004 — Pagination: opaque backward cursors, ascending pages

- Status: **proposed**
- Deciders: architect, backend, frontend
- Related: contract §3, §4.5

## Context

Two lists paginate: the **session list** (could be hundreds of rows) and the **message
transcript** (hundreds to thousands of rows per session, appended live).

DSH gives us the primitives:

- `sessionController.list()` returns **all** visible summaries ordered by `updatedAt` — there
  is no server-side session cursor. Slicing must happen in the mobile API.
- `sessionController.page({ address, throughSeq, beforeSeq?, maxMessages?, turnWindow? })`
  returns a **message-aligned backward page** plus `hasMore`. `throughSeq` is the inclusive
  log cut, `beforeSeq` the exclusive upper bound of the next older page.

The app renders chat reversed (newest at the bottom, like Discord). Live appends arrive
continuously via SSE. Pagination must not fight the stream.

Options:

1. **Offset pagination** (`?offset=0&limit=30`).
2. **Opaque backward cursors** (`?before=<opaque>&limit=30`).
3. **Timestamp cursors** (`?beforeTime=...`).

## Decision

**Opaque backward cursors for both lists. Message pages are returned in ascending
chronological order; the client prepends them to a reversed list. Session pages are ordered
`updatedAt desc, sessionId asc`.**

Message endpoint (`GET /sessions/{id}/messages?before=<cursor>&limit=30`):

```json
{ "ok": true, "items": [Message], "nextCursor": "eyJz...", "hasMore": true, "throughSeq": 918 }
```

- Cursor is opaque base64url of `{"s": <seq>}` (older than this seq).
- `throughSeq` is returned on every page so the client can reconcile with the stream
  (drop frames with `seq <= throughSeq`, apply the rest) — the rule in contract §4.5.
- Page size default 30, max 100. `hasMore` comes from `sessionController.page`.

Session endpoint (`GET /sessions?cursor=<opaque>&limit=50`):

```json
{ "ok": true, "items": [Session], "nextCursor": "eyJ1...", "hasMore": true }
```

- Cursor is opaque base64url of `{"k":"updatedAt","v":<ms>,"id":"<sessionId>"}`.
- Slicing is done in the mobile API over `sessionController.list()` output.
- A malformed/stale cursor → `400 bad-cursor`, never a silent restart.

## Why

- **Offsets break under live insertion.** A new message at the tail shifts every offset; the
  user would see duplicates or gaps while scrolling history during a running turn. `beforeSeq`
  is immune because it anchors on the durable log position.
- **DSH already speaks backward seq cursors.** `page({beforeSeq})` is the native primitive;
  wrapping it 1:1 keeps the backend thin and testable, and `hasMore` is authoritative.
- **Ascending pages** let the client prepend without sorting and keep the reversed adapter's
  scroll anchor stable (insert at the top of the reversed list = older messages below the
  current viewport). Returning descending would force a sort per page and fight ListView's
  position retention.
- **Opaque cursors** let us change the encoding (e.g. add a `turnWindow` hint) without a
  client release. Clients must never parse them — that is the contract.
- **`throughSeq` on the page** removes the need for a per-session stream cursor: the client
  buffers stream frames while the page loads, then drops ≤ `throughSeq`. One rule, no races.

## Consequences

- The client stores exactly one cursor per list (`nextCursor`) plus its scroll anchor; no
  total counts, no page numbers. "Jump to oldest" is repeated paging until `hasMore:false`.
- The session list is fully materialized host-side before slicing (DSH has no cursor); with
  ≤ a few hundred sessions this is fine. If the list ever exceeds ~2,000, add a
  `sessionQuery`-backed cursor and keep the same wire shape.
- Empty pages must never happen: a page with `items: []` and `hasMore: true` is a backend bug
  (assert in tests).
- The client must not request a page while a `resync` is pending; after resync it drops all
  cursors and refetches the open transcript (contract §4.3).
- `limit` outside 1..max → `400 bad-limit`; do not silently clamp (clamping hides client bugs).

## Fallback

If `throughSeq` from projections proves unavailable for cold sessions, the backend may open a
one-frame `follow()` to obtain the snapshot cursor (contract §3, Open gap 5). The wire shape
does not change. If the session list grows beyond the materialization budget, swap the cursor
payload to a server-maintained index — again without changing the wire shape.
