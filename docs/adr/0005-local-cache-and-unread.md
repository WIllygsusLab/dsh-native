# ADR-0005 — Local cache and unread state

- Status: **proposed**
- Deciders: architect, frontend
- Related: contract §3.1, §4.3, ADR-0001, ADR-0002

## Context

The app must feel instant on a phone and survive flaky Wi-Fi, host restarts, and app process
death. It also needs unread badges — and DSH exposes **no read-state or unread service**
(there is no per-user "last read" anywhere in the session, workspace, or agent services; the
web client keeps that in the browser).

Constraints from ADR-0001: no Room, no androidx, no third-party DB. Android ships
`SQLiteOpenHelper` and `SharedPreferences`.

## Decision

**Cache in SQLite, compute unread on the device, and never send read state to the host.**

### Cache (SQLite, one DB `dsh.db`)

| Table | Contents | Cap |
| --- | --- | --- |
| `sessions` | full `Session` JSON by `sessionId`, `updated_at` | all (≤ ~1,000) |
| `messages` | `Message` JSON by `(session_id, seq)`, `time` | newest **200 per session**, LRU-evicted |
| `jobs` | `Job` JSON by `jobId` | all settled + live |
| `agents` | `Agent` JSON by `agentId` | all |
| `plugins` | `PluginCard` JSON by `entryId` | all |
| `decisions` | `Decision` JSON by `decisionId` | pending + last 50 settled |
| `meta` | key/value: `lastStreamId`, `pairedAt`, `hostName`, `schemaVersion` | — |

Read-state and UI prefs live in `SharedPreferences` (small, synchronous, survives DB clears):

- `lastReadAt/<sessionId>` — epoch ms the user last viewed the transcript at the bottom;
- `lastOpenSessionId`, `lastTab`, `draft/<sessionId>`, `railCollapsed`.

**Unread rule** (client-only, deterministic):

```
unread(session) = session.updatedAt > lastReadAt(session) && !session.blank
```

`updatedAt` advances on a user prompt (DSH `api-session/activity`) and on session activity, so
"someone/something spoke since I looked" is exactly what a badge should mean. No mention
counting in v1: DSH has no mention concept; the app shows a plain unread dot plus a running
indicator. Opening the transcript at the bottom sets `lastReadAt = session.updatedAt`.

### Write-through rules

1. **Bootstrap** replaces `sessions`/`agents`/`plugins`/`jobs`/`decisions` in one transaction.
2. **SSE frames** upsert their row and (for `message.append`) insert the message, all inside
   one transaction per frame batch.
3. **Optimistic sends** insert a `Message` with `status:"sending"`; the durable frame replaces
   it by `clientRequestId`, else by `seq`.
4. **Page fetch** inserts the page's messages (upsert by seq) and keeps the oldest 200.
5. **Unpair / host change** wipes the DB and the read-state prefs.
6. `schemaVersion` mismatch → drop and recreate (the cache is disposable; no migrations).

### Cold start

Render `sessions` + the last `messages` of `lastOpenSessionId` from SQLite immediately, then
bootstrap + open the stream, then reconcile. Never block first paint on the network.

## Why

- **Unread is client truth.** Inventing a server unread field would require a new DSH service
  and a per-user model that does not exist. Deriving it from `updatedAt` is honest, cheap, and
  matches what the user means.
- **A disposable cache needs no migrations.** Drop-and-recreate on schema bump keeps M1–M3
  cheap; the server is the source of truth and a bootstrap refills everything.
- **SQLite is free on the platform** (no dependency), and 200 messages/session bounds the DB
  at a few MB even with 100 sessions.
- **Write-through on stream frames** means the cache is always "as fresh as the last frame",
  so airplane-mode reads show real recent content, not an empty shell.
- **Optimistic send needs a local row anyway**; the same table serves both.

## Consequences

- **frontend** owns a `Store` class with the schema above and a single transaction helper;
  no view reads SQLite directly.
- Badges update from `sessions.updatedAt` vs `lastReadAt`; no server round-trip, so badges are
  correct offline.
- A session archived/deleted host-side disappears on the next bootstrap; the cache row is
  removed by reconciliation (bootstrap is authoritative for the set).
- The You tab shows cache size and a "Clear cache" action (does not unpair).
- QA must test: cold start with airplane mode after a live session; kill/restart mid-turn;
  duplicate frame after reconnect (dedupe by seq); schema bump.

## Fallback

If SQLite write cost shows up in a profile (it will not at these sizes), the escape hatch is
to cache only `sessions` + the open session's last 50 messages in memory and drop persistence
for the rest. Unread state stays in prefs either way, so badges are unaffected.
