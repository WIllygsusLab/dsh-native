# ADR-0003 — Message rendering: the server projects the transcript

- Status: **proposed**
- Deciders: architect, backend, frontend
- Related: contract §3.2, §4, ADR-0002

## Context

DSH's durable session log is a rich, merge-extensible event stream
(`dsh-session/lib/types/types.d.ts`, `SessionEventMap`):

- `turn/start|end`, `step/start|end`
- `user/message`, `assistant/message` (with `stream`, `usage`, `interrupted`),
  `assistant/attempt`, `system/message`, `developer/message`
- `tool/call` (raw `arguments` string), `tool/result` (`error`, `meta`)
- `request/header`, `request/context`, `session/end-seed`, `session/title`,
  `model/selection`, `approval/asked|decided`, `session/title`, `goal/*`, `plan/*`, …
- plugin-merged unknown types, some marked `ignorable`

The Web client handles this with a large browser-side projection layer
(`dsh-client-ui-conversation/lib/types/client/contract/records.d.ts` defines
`UserMessageNode`, `AssistantMessageNode`, `SteeringMessageNode`, `ContextMessageNode`,
`ModelRetryNode`, tool nodes, timing, grouping…).

Two options for the native app:

1. **Ship raw events to the phone and project client-side in Java.** The phone would need to
   understand DSH's event map, content blocks, surface ops, assistant streams, compaction
   replacement, and every plugin event — a permanent compatibility tax on a platform without
   the web's shared TypeScript types.
2. **Project server-side** into a small, stable `Message` shape; the phone renders rows.

## Decision

**The mobile API owns the transcript projection. The phone never parses a DSH session event.**

Wire shape: **normative field-by-field definition in [api-contract.md §3.2](../api-contract.md)
plus the record→row table in §3.3**. The example below is illustrative; the contract wins.

```json
{
  "id": "msg_01J...", "seq": 812, "sessionId": "01J...",
  "role": "user|assistant|tool|system",
  "kind": "message|tool_result|notice|decision|error",
  "text": "flattened primary text (always present, may be \"\")",
  "blocks": [
    { "type": "text", "text": "…" },
    { "type": "reasoning", "text": "…" },
    { "type": "tool-call", "callId": "call_1", "name": "bash", "arguments": "{\"cmd\":\"ls\"}" },
    { "type": "image", "attachmentId": "att_1", "mediaType": "image/png" },
    { "type": "file", "name": "a.txt", "size": 42 }
  ],
  "time": 1735689500000,
  "turn": 3, "step": 2,
  "status": "complete|streaming|interrupted|failed",
  "clientRequestId": "8f14e...",
  "tool": { "callId": "call_1", "name": "bash", "isError": false, "resultPreview": "…" },
  "decision": { "decisionId": "q:...", "kind": "question", "prompt": "…", "options": [...] }
}
```

Projection rules (normative in contract §3.2): `user/message` with `source.kind === 'user'` →
`role:"user"` + `clientRequestId = source.rpcId`; other user-role injections → `role:"system",
kind:"notice"`; `assistant/message` → `role:"assistant"` with text/reasoning/tool-call blocks;
`tool/result` → `role:"tool", kind:"tool_result"` with a ≤ 2 KB preview and `isError`; an
`approval/asked` with no matching `approval/decided` → `kind:"decision"`; unknown/`ignorable`
events are dropped (logged once host-side).

`seq` is always the source event seq. The client dedupes by `(sessionId, seq)` and keeps the
newest version of a seq (surface replacement / compaction). Streaming text arrives out-of-band
as `assistant.delta` and is replaced by the durable `assistant/message` on commit.

Additional fields required by the frontend UI spec (all additive, defined in contract §3.2):
`attachments[]` (images/files as references, `url:null` in v1), `tool.argumentsSummary`,
`tool.durationMs`, `error{code,message}`, `noticeKind` (system/policy/preset/sandbox/subagent/
peer), and `peerName` for team-mailbox messages. `turn/start`/`step/start` produce **no row** —
the "Working" indicator is client-derived from `Session.running` + the newest in-flight tool
card. `agent/inbox/spliced` is inbox queue state and is dropped; the peer message appears as a
`user/message` when admitted.

## Why

- **One place to absorb DSH churn.** When DSH adds an event type or changes a block, backend
  updates one projection function; the shipped APK keeps working. A client-side projector
  would need an app release for every host change, and the host updates independently of the
  phone.
- **The phone has no type system for DSH.** Java + `org.json` with no shared schema means
  every projection bug is a runtime crash on the phone. Server-side projection is testable in
  Node against recorded session logs.
- **Smaller payloads.** A raw `assistant/message` carries the full provider stream
  (`stream: AssistantStreamRecord[]`) and `usage`; the phone needs text and a few blocks.
  Server-side trimming is the difference between a 3 KB and a 40 KB message.
- **Keeps ADR-0001 honest.** A Java client-side projector would be thousands of lines of
  untested parsing on a zero-dependency stack.
- **`clientRequestId` gives optimistic-send reconciliation** without the client matching on
  text or timestamps.

## Consequences

- **backend owns rendering fidelity.** Tool cards, decision cards, interrupted markers, and
  notice collapsing are backend projection decisions, reviewed against `frontend`'s UI spec.
  That is a real load on backend and must be a named deliverable (`projectMessage(records)` +
  fixture tests with recorded logs).
- **The contract shape is now a public API.** Adding a block type or `kind` is additive;
  changing one is breaking and needs `/v2`. The `unknown` fallback (`role:"system",
  kind:"notice"`, empty text) is mandatory so the client never crashes.
- **`assistant.delta` needs its own minimal shape**: `{text?, reasoning?}` only — not raw
  provider chunks. Backend flattens.
- **Images/files stay as references** (`attachmentId`, name, size) in v1; bytes are fetched
  per-attachment in M2 via `GET /attachments/{id}` backed by
  `sessionController.attachment()`. No base64 in the transcript.

## Fallback

If a screen needs a detail the projection dropped (e.g. a tool's `meta` diff for a file edit),
the fix is a **new optional field on `Message`**, not shipping raw events. For M2, a debug-only
`?raw=1` query may return `SessionWireEvent[]` for QA comparison — never used by the app.
