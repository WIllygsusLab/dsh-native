# ADR-0013 — Decisions badge is a derived client state; test seeds use the ADR-0010 prefix convention

- Status: **accepted for the badge/seed convention** (architect, 2026-11-05); **the `/private/tmp`
  cwd amendment below is PROPOSED only** — pending Lead/PO ratification; no contract change yet
- Deciders: architect, backend, qa, lead, po
- Related: [api-contract.md](../api-contract.md) §3.4/§4/§8/§10,
  [m4-verdicts.md](../m4-verdicts.md) M4-1, [ADR-0010](0010-test-channel-classification.md),
  [ui-overhaul-hooks.md](../ui-overhaul-hooks.md), [host-session-hygiene.md](../../ops/host-session-hygiene.md),
  [closeout-host-verify.md](../../backend/closeout-host-verify.md) §4–§5,
  [tools/M2-GATE.md](../../../tools/M2-GATE.md), [tools/m2_gate.py](../../../tools/m2_gate.py),
  [team/qa/probe/seed_m2gate.py](../../qa/probe/seed_m2gate.py)

## Context

Two close-out facts need one durable decision record:

1. **Decisions badge.** The UI overhaul moved `# Decisions` out of the Chats list into the Tools
   drawer, so the old `#decisions` row badge had nowhere normative to live. The host already
   exposes `GET /decisions` → `badge {waiting, urgent}` (contract §8), and bootstrap already
   returns `decisions[]`. The live plugin does **not** reliably emit `decision.upsert` for badge
   aggregation (R3 finding in `team/ops/status.md`), so a stream-only badge would be decorative.
   The shipped client therefore derives the badge from the decisions payload and refreshes it on a
   foreground poll.

2. **Test-seed naming.** The host has no delete API and the roster is polluted by gates/probes.
   ADR-0010 already classifies test rows client-side, but only when the title/cwd match its closed
   prefix set. External projects leak non-canonical seeds (`HEARTBEAT *`, `LFM3_CTX_*`,
   `OPENCODE2DSH OK`, headless `/private/tmp` rows), so the convention has to be explicit: **QA and
   host-side seeders must title synthetic work with prefixes the classifier already owns** and
   keep prompts self-answering.

The same capture also verified a literal-cwd gap: `/private/tmp` does **not** match ADR-0010 R8,
which is `cwd == "/tmp"` or `cwd.startsWith("/tmp/")`. On 2026-11-05 the three `/private/tmp` rows
classified only because their titles were `Reply with exactly:*` (R6); a non-seed-titled
`/private/tmp` session would remain in Main. This is a rule-coverage question for ADR-0010, not
proof that the current implementation is broken.

## Decision

**The decisions badge is a derived client-side state, refreshed from `GET /decisions` on bootstrap,
decision frames when present, and a 15-second foreground poll; it is not a new wire field and it is
not computed from session rows.**

- Source of truth: `bootstrap.decisions.badge {waiting, urgent}` when present, else the
  `badge {waiting, urgent}` object returned by `GET /decisions` (contract §8). `items` may be used
  only as a fallback diagnostic, never as the badge authority.
- Refresh cadence: bootstrap application calls `refreshDecisionsBadge()`; `decision.upsert` frames
  trigger the same refresh when they arrive; the foreground poll runs every **15 s** while
  `foreground == true` and stops in `onStop()`/`stopDecisionsPoll()`. The poll is load-bearing
  because the live plugin does not emit usable `decision.upsert` for aggregation today.
- Display surfaces:
  - `rail_tools_badge` — amber dot on `rail_tools`, visible iff `decisionsWaiting > 0`;
  - Tools drawer `tool Decisions` row — caption shows waiting count and appends ` · urgent` when
    `decisionsUrgent > 0`;
  - Decisions screen — `decisions_badge` and row order continue to render from the same
    `decisionsWaiting`/`decisionsUrgent` counters;
  - Chats list — no `#decisions` system row remains; `refreshChannelsInPlace()` is only a repaint
    path, not a badge source.
- Urgency semantics: `urgent` is host-derived (`Decision.urgent`, contract §8.1) and is used only
  for ordering/emphasis. It does not change membership, does not create a second badge count, and
  does not imply answerability — v1 remains read-only (`capabilities.decisionAnswer == false`,
  `POST /decisions/{id}/answer` → `503`).
- No API/contract amendment for the badge itself. Contract §8 already defines `badge`; §10 keeps
  additive fields non-breaking. If a future `badge.update` SSE frame is proposed, it must be marked
  **PROPOSED** until Lead/PO ratify it.

**Test/probe seeds use the ADR-0010 prefix convention.** Canonical seed titles are the prefixes the
classifier already owns:

- `M2GATE…` / `M2GATE_…` — gate seeds (R1 `title:m2gate`);
- `PROBE…` / `PROBE/…` — probe fleet seeds (R3 `title:probe`);
- `Reply with exactly: …` — self-answering prompt seeds (R6 `title:seed`);
- `M2 … test …` — milestone test rows only when they also contain `test` (R2 `title:m2+test`).

Seed prompts must be **self-answering** (e.g. `M2GATE …: reply with exactly: ready`) and must never
be imperative work orders. The wrapper `team/qa/probe/seed_m2gate.py` enforces this for the app gate;
`tools/m2_gate.py` still recognizes `M2GATE`, `M2 phone test`, and `Refetch Marker` as seed-row
markers, and `tools/M2-GATE.md` requires a fresh seeded session per gate run.

The gate interaction is normative: **expand the collapsed Test group before looking for a seed
row**. `tools/m2_gate.py` calls `expand_test_channel()` after `tab_chats` and before
`find_seed_row()`/`reveal_seed_row()`; QA drivers that assert created titles must do the same. A
classified seed is still an ordinary `channel_row` once expanded, with the same
`channel_row:<title>, <state>…` content-desc format.

### PROPOSED ADR-0010 amendment — literal `/private/tmp` cwd

**PROPOSED — pending Lead/PO ratification. Do not implement until ratified.**

- **Gap.** ADR-0010 R8 is literal: `cwd == "/tmp" || cwd.startsWith("/tmp/")`. macOS exposes the
  same scratch area as `/private/tmp`, so `/private/tmp` and `/private/tmp/…` escape R8.
- **Observed behavior.** Close-out capture `20261105T143133Z` found three `/private/tmp` rows; all
  classified because their titles matched R6 (`Reply with exactly:*`). No current seed-titled row is
  misclassified, and no client bug is claimed — the gap is coverage of a non-seed-titled
  `/private/tmp` session.
- **Proposed rule.** Amend R8 to `cwd.equals("/tmp") || cwd.startsWith("/tmp/") ||
  cwd.equals("/private/tmp") || cwd.startsWith("/private/tmp/")`, keeping the same literal,
  case-sensitive semantics and the same `cwd:tmp` reason tag. Do **not** generalize to
  `contains("tmp")`, do **not** classify `/private/var/folders/…` by analogy, and do not touch the
  `origin == "subagent"` guard.
- **Rejected alternatives.** (a) Normalize cwd through `File.getCanonicalPath()` — rejected for v1:
  it adds filesystem semantics to a wire field and can surprise on symlinked workspaces; (b) add a
  separate R9 — rejected as needless rule-number churn when R8 is already the tmp-scratch rule;
  (c) leave it to seed naming — insufficient, because external headless probes can create
  `/private/tmp` rows with non-seed titles.
- **Contract impact.** `api-contract.md` §3.4 describes Test membership as "cwd under `/tmp` or
  containing `/.hermes/`". If ratified, that wording should be amended to include the literal
  `/private/tmp` forms; until then this ADR records the proposal only and `api-contract.md` stays
  unchanged.
- **Verification if ratified.** Unit-check `TestClassifier.reasonOf()` for `/tmp`, `/tmp/x`,
  `/private/tmp`, `/private/tmp/x`, and a non-match such as `/Users/x/private-tmp`; live recount via
  `team/backend/probe/closeout_probe.py` must be same-moment, because roster counts drift.

## Consequences

- Badge state has one authority (`badge.waiting`/`badge.urgent`), three render surfaces, and a
  refresh path that works today without depending on `decision.upsert`.
- Seed authors have a closed, classifier-aligned naming convention; the gate already encodes the
  required expand-before-seed behavior.
- The `/private/tmp` gap is documented as a proposal rather than silently patched: current rows are
  covered by title rules, and any future classifier change still requires Lead/PO ratification.
- Live counts remain volatile: quote them only with a capture timestamp and re-pull before QA
  assertions (`closeout-host-verify.md` §4.1 is the current end-of-run baseline).

## Alternatives considered

1. **Aggregate badges from `session.waiting`/`pendingDecisionCount`.** Rejected: session rows are
   not the decision ledger; `badge` already exists for this purpose and includes urgency.
2. **Rely on `decision.upsert` only.** Rejected: the live plugin does not emit usable frames for
   aggregation today; the 15 s foreground poll is load-bearing.
3. **Add a new `badge.update` SSE frame now.** Deferred/proposed only: it is additive under §10 but
   still needs Lead/PO ratification and backend emission before the client can depend on it.
4. **Classify `/private/tmp` by `contains("tmp")`.** Rejected: too broad; would catch unrelated
   paths and violate ADR-0010's closed literal-prefix discipline.
5. **Rename existing `/private/tmp` sessions host-side.** Rejected: no delete/rename policy for
   external-project seeds, and classification must not depend on host mutation.

## Verification

- Static: `refreshDecisionsBadge()` reads `badge.waiting`/`badge.urgent`; `updateRailToolsBadge()`
  shows `rail_tools_badge` iff `decisionsWaiting > 0`; `showToolsDrawer()` renders the Decisions
  caption from the same counters; `scheduleDecisionsPoll()` posts every 15 s only while
  `foreground`.
- Device/QA: cold start with a pending decision shows the rail dot and drawer caption; withdrawing
  the decision clears both on the next poll/refresh; `EVENT badge.update` may be added later but is
  not required today.
- Seed/gate: fresh `M2GATE …` seed → `expand_test_channel()` → `channel_test_toggle:expanded` →
  seed row visible as `channel_row_title`; gate exits 8/8 with pulled-hash match.
- Amendment: if `/private/tmp` is ratified, rerun the classifier census and record the delta in a
  new closeout capture; do not edit `api-contract.md` until the ADR status changes from PROPOSED.
