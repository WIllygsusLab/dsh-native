# ADR-0010 — The Test channel is a client-side classification (view filter), Test renders last

- Status: **Accepted** (architect, overnight 2026-11-02) — **amended after Lead-verified live
  data, 2026-11-02**: the §1 guard is `origin == 'subagent'`, not `teamId != null` (the
  original team guard would have exempted 232 of 244 rule-matching rows — §4.4).
- Deciders: architect (ratified); live-count evidence Lead-verified 2026-11-02 via
  `GET /api/mobile/v1/bootstrap` (322 sessions)
- Related: [api-contract.md](../api-contract.md) §3.4 (amended same day — Test is precedence 5),
  [ADR-0008](0008-session-group-order.md) (group precedence), [ADR-0001](0001-stack-native-views.md)
  (client never parses DSH events), [ui-overhaul-hooks.md](../ui-overhaul-hooks.md) (QA rebase table),
  [rail-ia-decision.md](../rail-ia-decision.md) (where the Tools surfaces live)

## Context

The host grew to **322 sessions**, ~243 of which are gates, probes and scratch seeds
(`M2GATE…`, `probe…`, untitled `/tmp` + `/.hermes/` sessions, "reply with exactly" seeds).
The host has **no delete API**, so the lists can never be cleaned server-side. The Principal
asked for "a test channel to keep the list cleaner". Whatever we do must be **client-only**:
no wire-shape change, no host mutation, no deletion, and reversible.

Lead-verified class census (2026-11-02, case-insensitive on the trimmed title; first pass on
322 sessions, re-verified the same day on 331 — see the note under the table):

| Class | Count | Signal |
| --- | --- | --- |
| `m2gate*` | 53 | title prefix |
| `probe*` | 41 | title prefix |
| `test*` + `qa`/`qa_*` | 5 | title prefix |
| "reply with exactly" seeds | 10 | title prefix (seed prompt echoed as title) |
| cwd `/.hermes/` or under `/tmp` | 134 | cwd (incl. 95 "Untitled session" in scratch) |

> **Re-verification (Lead, 2026-11-02, 331 sessions):** 244 rows match the rule set; after the
> §1 guard the split is **241 test / 90 main**. The per-class counts above are from the earlier
> 322-session snapshot; the totals below are the verified ones.

| | |
| --- | --- |
| **Rule-matching rows** | **244** |
| **Classified (after guard)** | **241** |
| **Main list** | **90** |

Ambiguous leftovers the census deliberately does **not** absorb: 19 "Untitled session" in
`~/Documents/Ideas`, 1 "Call the ask", 4 "Run a bash command that", 2 "Do not call any tools.".
One boundary row, "M2 phone test: reply with", is **classified** (rule R2 below) per the Lead's
recommendation.

## Decision

**Classify test/probe sessions in the client, on the bootstrap `Session` rows only, and render
them in a collapsed-by-default `TEST` group that always sits last (after Recent, ADR-0008
order).** Classification is a **view filter over the existing groups** — the host is never
touched, nothing is deleted, and the classifier input is exactly two fields the client already
has: `Session.title` and `Session.cwd`. `Models.Session` has no prompt field and we are not
adding one (that would be a wire change).

### 1. Classifier rules (normative, ordered)

Input normalization:

- **Title:** `t = title.trim().toLowerCase(Locale.ROOT)` — trim first, then case-fold.
  `title` is never null in the client model (defaults to `"Untitled session"`), but a null
  counts as `""`.
- **cwd:** `c = cwd == null ? "" : cwd` — **case-sensitive, literal** (paths are not prose;
  `/.hermes/` and `/tmp` are literal lowercase on this host).
- **Guard (apply before all rules):** `origin == "subagent"` → **never classified**. Plain
  subagents with no team membership never reach the classifier at all (`plainSubagent()` rows
  are already excluded in `channelRows()`); this guard additionally protects **named
  teammates**, which on this host all carry `origin=subagent` + `teamRole=teammate`.
  **Amended after Lead-verified live data (2026-11-02):** the original guard keyed on
  `teamId != null` and was wrong — see §4.4 for the data. See also "Deliberately not
  classified" §4.

Rules — evaluated **in order**; **first match assigns the reason tag**, and membership is
`any match`. The order matters only for the reason label (evidence/diagnostics), never for
membership:

| # | Rule (on normalized input) | Reason tag | Catches |
| --- | --- | --- | --- |
| R1 | `t.startsWith("m2gate")` | `title:m2gate` | all gate seeds (53) |
| R2 | `t.startsWith("m2") && t.contains("test")` | `title:m2+test` | "M2 phone test: reply with" (1); must sit **after** R1 so gate rows tag `m2gate` |
| R3 | `t.startsWith("probe")` | `title:probe` | probe fleet (41) |
| R4 | `t.startsWith("test")` | `title:test` | `test…` titles (incl. bare `test`, `testing…`) |
| R5 | `t.equals("qa") \|\| t.startsWith("qa ") \|\| t.startsWith("qa_")` | `title:qa` | `qa`, `qa smoke`, `qa_123` — **not** `qax`, not `qaengineer` |
| R6 | `t.startsWith("reply with exactly")` | `title:seed` | typed seed prompts echoed as titles (10) |
| R7 | `c.contains("/.hermes/")` | `cwd:hermes` | host scratch/system workspaces (same literal `isSystemWorkspace()` already uses in `mainWorkspace()`) |
| R8 | `c.equals("/tmp") \|\| c.startsWith("/tmp/")` | `cwd:tmp` | tmp scratch (incl. the 95 untitled ones) |

Prefix rules are **prefix, not contains** — "contains" would classify a real session whose
title merely mentions a gate ("notes on the m2gate run"). The only `contains` in the set is
inside R2 (`contains("test")`), gated behind the `m2` prefix. The prefix list is **closed**:
no fuzzy "looks like a probe" heuristics. New patterns are added as explicit numbered rules,
never as a generalization of an existing one.

### 2. Rendering: Test is precedence 5, collapsed by default

- **Partition, don't re-group.** In `channelRows()`, the filtered session list is split into
  `test` (any rule matched) and `main` (no rule matched). The four ADR-0008 groups are computed
  from `main` only. A classified session therefore appears in **exactly one** place — the §3.4
  one-representation rule survives; nothing is duplicated into Waiting/Team/Running/Recent.
- **Order: `Waiting → Team → Running → Recent → Test`.** The TEST block renders **after
  Recent**, last in the list (contract §3.4 amended today; ADR-0008's "Team second" is
  untouched).
- **The collapsed header is not a `channel_row`.** The TEST block is a single interactive row,
  `@id/channel_test_toggle`, text `TEST · N`, content-desc
  `channel_test_toggle:<collapsed|expanded>:<N>`. It is **not** a `channel_group_header` and
  **not** a `channel_row`/`channel_system_row` — uiautomator sees no session rows for test
  sessions while collapsed, and `channel_group_header` texts remain exactly
  `WAITING/TEAM/RUNNING/RECENT`. The header renders **iff N > 0** in the current filter/search
  scope (empty groups never render, §3.4).
- **Tapping the header toggles expansion.** Expanded, the classified sessions render as
  **ordinary `channel_row`s** (identical id, content-desc format `channel_row:<title>,
  <state>[, unread][, team_depth=N]`, unread dot, tap/long-press behavior). No new row type,
  so every existing QA path (open row → `composer_input`) works unchanged once expanded.
- **No "show test sessions inline in main groups" mode exists.** The persisted toggle expands
  the Test group; it never re-injects classified rows into the four main groups. One state,
  one representation.
- **Counts are scope-aware.** `N` = classified sessions passing the active workspace filter
  (`filterCwd`) and the title search query — the same filters applied to `main`.
- **Waiting test sessions stay answerable.** Their decisions still surface in `#decisions`
  (`decisionsWaiting` is computed from the host decisions payload, independent of channel
  rows). Only the Chats row moves.
- **Search:** with Test expanded, classified rows are ordinary rows and participate in the
  client search filter exactly like main rows — nothing is hidden from search when expanded.
  Collapsed, they are absent from the rendered list by design; the toggle's `· N` shows that
  matches exist.

### 3. Persistence

- SharedPreferences file `dsh_native` (existing `Prefs.NAME`), key **`showTestSessions`**,
  type **boolean**, default **`false`** (collapsed).
- `Prefs.showTestSessions()` / `Prefs.setShowTestSessions(boolean)` in the existing style of
  `lastCwd()`. No host round-trip, no per-session keys, nothing synced.
- The key persists the **expansion state only**. Classification itself is always-on and
  non-configurable (a "disable classification" switch would reintroduce the 243-row dump the
  Principal complained about; if ever wanted, it is a new ADR).

### 4. Deliberately NOT classified (and why)

1. **19 "Untitled session" in `~/Documents/Ideas`.** The default title is never a signal by
   itself: real work is frequently untitled, and the 95 untitled scratch sessions already
   classify via R7/R8 on cwd. Conservative default — they stay in the main list until they
   earn a signal.
2. **"Call the ask" (1), "Run a bash command that" (4), "Do not call any tools." (2).** These
   look like imperative probe seeds, but the prefix list is closed; a "sounds imperative"
   heuristic would misclassify real imperative work ("Run the deploy"). Accepted cost:
   ~7 noisy rows in the main list. Revisit only with a durable host-side marker, not a
   client guess.
3. **`preview` (last-reply snippet) is never an input.** It changes every turn, so sessions
   would flicker between groups; worse, a real session echoing a gate phrase ("reply with
   exactly: m2gate-ok") would classify itself. Title and cwd are stable identities; preview
   is not.
4. **Subagent-origin sessions are exempt** (`origin == "subagent"` → guard). The Team section
   is the live-crew surface and ADR-0008's "what needs a human now" contract; a teammate
   titled "QA Engineer" must never vanish into Test.
   **Why the guard is `origin`, not `teamId` (Lead-verified live data, 2026-11-02, 331
   sessions — this amends the original ADR text):**
   - `teamId != null` is **near-universal on this host** — 302/330 sessions carry it, and
     **all 53 `M2GATE` rows** have `teamId` set with `teamRole:"lead"`, `origin:null`. A
     teamId guard would exempt 232 of the 244 rule-matching rows and the Test channel would
     render ~12 rows instead of ~241: it protects nothing and hides everything. `teamId` on
     this host marks *team-scoped sessions*, not *teammates*.
   - **All 23 real-teammate rows** (the "roster restored" role sessions — QA Engineer,
     Architect, PO, PM, …) carry `origin=subagent` + `teamRole=teammate`, so the origin
     guard keeps every one of them in the main list.
   - The three R5-matching teammate rows — "QA engineer: phase-2 device gate…",
     "QA Engineer: test strategy…", "QA Engineer — roster restored" — are exempted by the
     origin guard; the two rows R5 still classifies are genuine fixtures with `origin:null`
     ("QA fixture colour question", "QA fixture question tool test").
   - Verified split: **241 test / 90 main**. If a *test team* ever needs hiding, that is a
     new rule with an explicit team-name condition, never a field shortcut.
5. **Prompts are not an input at all.** `Models.Session` carries no prompt field; adding one
   would be a wire change (forbidden for this card). R6 works because the host echoes seed
   prompts into titles; if it ever stops, R6 silently matches nothing — acceptable.
6. **Archived sessions** are out of these groups already (§3.4) and stay out of Test.

### 5. View filter only — the non-negotiables

- **No host mutation.** No rename, no archive flag, no workspace detach, no deletion — the
  host has no delete API and we are not simulating one.
- **No wire-shape change.** The classifier reads fields the bootstrap already returns; it
  sends nothing.
- **Nothing is destroyed.** A classified session is one toggle away (`TEST · N` → expanded),
  remains reachable by search when expanded, opens like any other session, and its unread
  dots/decisions keep working.
- **Client never parses DSH events** (ADR-0001) — the classifier runs on the projected
  `Session` rows the mobile API already returns, at `channelRows()` time. No new fetch, no
  cache invalidation concerns: re-evaluated per render, cheap (string prefix on ~331 rows).

### 6. Interaction with ADR-0008 / contract §3.4

- ADR-0008's ratified order `Waiting → Team → Running → Recent` is **unchanged**; Test is
  appended as the fifth, last-rendered block. Team stays second.
- The §3.4 first-match membership principle is preserved by partitioning (§2): every listed
  session appears exactly once.
- **Corrected exemption (Lead-verified 2026-11-02):** classification keys on
  `origin == "subagent"`, not `teamId`. Classified sessions may still carry `teamId` (on this
  host most do — including every `M2GATE` row, with `teamRole:"lead"`); they render in
  **Test**, not Team. The Team group therefore keeps only *unclassified* sessions; a
  `teamRole:"lead"` row that matches a rule is a gate seed, not the live crew. Named
  teammates (`origin=subagent`, `teamRole=teammate`) are exempt and stay in Team.
- `channel_group_header` continues to name only the four ADR-0008 groups; TEST is its own
  `channel_test_toggle` row. The contract §3.4 amendment (same day) records the fifth group
  and the "collapsed header is not a channel_row" rule; QA count/order cases rebase per
  [ui-overhaul-hooks.md](../ui-overhaul-hooks.md) — in particular **`tools/m2_gate.py` must
  expand the Test group before `find_seed_row`**, or the gate probes a live session instead
  of the seeded M2GATE row (the exact failure the gate-runner notes already warn about).

## Alternatives considered

1. **Host-side cleanup (delete/archive via API).** Rejected: no delete API exists, and
   archiving is host mutation with irreversible blast radius on sessions the gates re-use.
2. **Filter chip instead of a group (hide entirely until toggled).** Rejected for v1: a chip
   hides the *existence* of test sessions and needs its own row anyway; the collapsed
   `TEST · N` header is the same cost with better affordance. A chip can be added later
   without a contract change (it is a view of the same predicate).
3. **Classify on `preview`/prompt text.** Rejected: unstable input (flicker) and unavailable
   field (prompt) respectively — see §4.3/§4.5.
4. **Per-workspace exclusion lists (hide `/tmp` rows from counts).** Rejected: the cwd rules
   already do this uniformly; a second mechanism would drift from `isSystemWorkspace()`.
5. **Classify with no origin guard.** Rejected: R5 would hide named teammates such as
   "QA Engineer" (three live rows verified) — §4.4. Equally rejected: keying the guard on
   `teamId != null` (the original draft) — teamId is near-universal on this host and sits on
   every M2GATE row; see §4.4.

## Consequences

- The main Chats list drops from ~331 to ~90 rows (241 classified behind the Test block);
  Waiting/Team/Running/Recent semantics are untouched.
- `m2_gate.py`, `m2_ac1.sh` and `phase2b_driver.py` need the rebases catalogued in
  [ui-overhaul-hooks.md](../ui-overhaul-hooks.md) (Test-expand step before seed-row lookup;
  created-M2GATE-session assertions must expand first). These are **blocking** for the gate,
  not cosmetic.
- Frontend implements the rules table verbatim, including the `origin == "subagent"` guard
  and rule order (reason tags are evidence). The predicate lives in one place (e.g. a static
  `TestClassifier.isTest(Session)` + `reasonOf(Session)`) so QA can unit-check the table.
- New strings/ids are proposed in the hooks ledger (`group_test`, `channel_test_toggle`,
  …); `res/values/ids.xml` keeps its "do not rename" contract — additions only.

## Follow-up

- Frontend: implement + gate with the rebased `m2_gate.py` (Test-expand step).
- QA: add a classifier case (unit-style, against the rules table) and a device case:
  collapsed by default on the live host (~331 sessions), `TEST · N` visible, expand → seeded
  M2GATE row present and openable, collapse → absent, decision badge unaffected.
- Revisit the closed prefix list when the host grows a durable marker for seeds; revisit the
  ~7 imperative leftovers only with host-side evidence.
