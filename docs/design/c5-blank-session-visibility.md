# C5 — Blank named-session visibility: recommendation + spec

**Owner:** designer (`team/design/`) · **Status:** PROPOSED — for PO decision (board task-8)
**Card:** task-8 · **Tracked condition:** C5 / P4 in [../ops/followups-phase2.md](../ops/followups-phase2.md) §B
· **Source:** PO phase-2 sign-off [../product/phase2-acceptance.md](../product/phase2-acceptance.md) §5.2-4, §8-1
· **GUI proof:** [../qa/evidence/task5-phase2b/gui-9afd331a/summary.md](../qa/evidence/task5-phase2b/gui-9afd331a/summary.md)
**Scope:** client-side only — no `app-native/` edits in this card (spec only), no host/plugin changes, no commits.

---

## 1. Problem, in user terms

The Principal's flow: tap **+** on the phone → type a name → Create → the chat opens in the app,
named, in the right workspace. Then they look at the DSH web GUI on the computer — **the session
is not there.** Not in the workspace list, not in search. It appears, with the typed name and
correct workspace, the moment its first message is sent (AC-6 amended proof,
`session-56689760…`). The gap between "I created it" and "it exists where I work" is the defect
users feel. Two sub-symptoms ride along:

1. **Where-is-it confusion** — nothing in the app says the GUI gap is expected; the natural
   inference is that create failed or the name was lost. (It didn't; it wasn't.)
2. **Silent host pollution** — a named blank session the user abandons (backs out, never sends)
   stays on the host forever: **blank by definition, excluded from the Test channel** (its real
   name matches no ADR-0010 rule), invisible in the GUI until someone opens it, and **there is no
   delete API** (STATE.md open item 7 / ops hygiene card task-9). Every create-then-abandon
   deposits one permanent junk row.

Inside the app itself there is no visibility problem: `doCreateSession` prepends the created row
to `data.sessions` and `channelRows()` does not filter blanks — the session is visible in the
app's Chats list immediately. C5 is purely about the **host GUI window** between create and first
prompt.

## 2. Why the window exists (host-side facts, re-verified 2026-11-05 against the live build)

All four are host code, read-only verified in the installed package
(`@deepseek-ai/dsh/node_modules/…`):

| # | Fact | Where |
| --- | --- | --- |
| F1 | A mobile-created session is **blank by definition**; `blank` flips to false only at the first prompt (and is reconciled never to re-blank once prompt-attempted/running). | `dsh-api-session-controller/lib/client.js` — "a created session is blank by definition" (≈:2673), `blankBit = false` at first prompt (≈:1761–1762, :1966–1967) |
| F2 | The GUI sidebar hides every blank session **except the currently selected one**. | `dsh-client-ui-workspace/lib/client.js` `sessionVisible()` ≈:352–365 (`session.blank && id !== current → false`), `pinCurrentBlank` ≈:346–351 |
| F3 | GUI **search excludes blanks unconditionally** — even the selected one (`summary.blank → continue`). | `dsh-client-ui-workspace/lib/client.js` ≈:588–593 |
| F4 | A blank row renders the localized **"New Session" label, not its title** — `node.blank ? t("session.new") : node.title`. So even if F2/F3 were relaxed, a renamed blank would still display as "New Session", not the typed name. | `dsh-client-ui-workspace/lib/client.js` `displayTitle()` ≈:1122–1124 |

Consequences for the decision:

- **The client cannot force visibility.** `PATCH /sessions/{id}` carries `title` only (contract
  §PATCH); no mobile route clears `blank`. The PO register's "seed a 'started' marker" variant is
  **not producible client-side** — it would need host API work (→ belongs to option C, §3.3), and
  faking engagement (an empty/system prompt) is rejected: transcript noise, a burned first turn.
- A host GUI change (C) is therefore **not one line**: F2 + F3 + F4 are three independent gates in
  a different product's intended design (the provisional blank is the GUI's own unsent-draft
  affordance; hiding unnamed drafts is deliberate).
- The window is **closed exactly by the first prompt** — which is also what the user does next in
  every observed flow (the AC-6 proof sends "reply with exactly: ready" and captures the GUI
  afterwards).

## 3. Options and tradeoffs

### 3.1 (a) Expectation copy — the sheet + empty-state hint *(client-only, tiny)*

Tell the truth where the question arises: in the sheet, under the name field, and in the open
chat until the first message lands.

| For | Against |
| --- | --- |
| Zero behavior change → zero regression surface; no hooks drift (one additive id + strings) | Does not make the session appear — only explains the gap (the gap survives) |
| Fits the mechanical C1/C2/C4 batch (task-7 is already a SWE2 dispatch of exactly this shape) | A user who genuinely wants to continue an *unnamed-first* session at the desk still finds nothing until they send — but then they'd type the first message there anyway |
| Fixes two stale things on the same edit (`chat_empty_body` is still M1 placeholder copy, `strings.xml:108`) | Abandoned creates still pollute the host (F-pollution above) |

### 3.2 (b) Defer create until first send — draft-session model *(client-only, medium)*

Create opens a **local draft chat**; the session does not exist on the host until the first
composer send, which atomically creates → renames → prompts. The blank window **ceases to exist
by construction**: whenever a session exists on the host, its first prompt is in flight, so it is
GUI-visible essentially immediately. This is the mobile-native pattern (Discord/WhatsApp new-chat
drafts) and it **aligns the app with the host's own semantics** — the host itself treats a
no-message session as provisional (F1–F4).

| For | Against |
| --- | --- |
| Kills the C5 gap structurally — no invisible state, ever | Loses "create now, talk later": an abandoned draft leaves nothing anywhere (in-app too). Judged against evidence — the Principal's flows always send, and abandoned blanks are *pollution*, not a feature — this is the fix for pollution too |
| Kills create-then-abandon host junk (no delete API needed) | Draft state is process-lifetime only (no persistence in scope): process death before first send loses name/preset/workspace/draft text — same as composer text today; documented limitation |
| Reuses existing surfaces: create-error copy (`createErrorCopy`), failed-bubble retry, single-flight disable; no wire change, **no backend/plugin change** | Medium-sized client change in MainActivity (draft state machine threaded into the send path — the hottest code path we have) |
| Host-agnostic: works on every host version (a blank the GUI hides can never be created) | Marker **timing** moves (`session.create.*`, `session.rename.*` land at send, not at Create-tap) → QA harness rebase (`phase2b_driver.py`, any driver asserting markers right after Create-tap) + DEBUG fixture re-point (`debugCreateFailure` fires inside the deferred create now) |
| One additive marker (`session.draft.open`), **zero changes to `channel_row`/hooks ledger** (the draft is intentionally not listed — §5.4) | Contract §New-session-flow step 2 ("Create first via POST /sessions") must be amended — architect proposes, Lead/PO ratify. Client behavior, not wire |
| | Two extra round-trips (create+rename) prepend the first send's latency — ~150ms-class LAN calls, before the model turn that takes seconds; acceptable |

### 3.3 (c) Host GUI change — **note only, out of client scope**

Upstream ask (drafted §7): list and search blank sessions **that carry a user-set title**, and
render their title instead of "New Session" (F2+F3+F4 together, gated on "has an explicit title"
so the GUI's own unnamed provisional blanks stay hidden). Not a client dependency under any
outcome:

- If (b) ships, C5 is already fixed client-side and (c) is nice-to-have (it would additionally
  rescue *externally* created blanks, which the app can't control anyway).
- If only (a) ships, (c) is the true fix but the client must not wait on another product's
  backlog. The app targets arbitrary host versions; copy must survive regardless.

### 3.4 Rejected variant — "seed a started marker" (from PO §5.2-4)

Not implementable client-side (no route clears `blank`; PATCH is title-only). Host-side it becomes
option (c) with extra wire. Faking a first message pollutes the transcript and burns the first
agent turn. **Rejected**; recorded so it doesn't resurface.

## 4. Recommendation

**Ship (a) now; card (b) for M4; file (c) as an upstream note.**

- (a) costs two strings and one TextView, answers the "did it fail?" confusion immediately, and is
  safe to land beside C1/C2/C4. It is needed **regardless** of (b) — with (b) the copy changes
  tense, not disappears (§5.2 gives both variants).
- (b) is the real fix: it closes the window by construction and ends abandoned-blank pollution
  (the ops angle task-9 inherits). It is client-only, wire-compatible, hooks-stable — its cost is
  honest (draft state in the send path + harness rebase + one contract wording amendment).
- (c) goes out as a Lead-sent upstream note (§7); no client work gated on it.

Sequence: (a) lands with the mechanical batch under the current freeze discipline (task-7 gate:
never churn while a subject is gated); (b) is a normal M4 frontend card with an architect
contract amendment and a QA harness pass.

## 5. Spec — (a) expectation copy *(exact, ready for the frontend card)*

Both placements are **additive only**; all existing ids preserved (§5.4). Never color-only, real
TextViews (a11y), inside the sheet's scroll area (task-11 geometry: Create stays pinned).

### 5.1 A1 — sheet helper line under the name input

```
session_name_input                    (unchanged, min 44dp)
@id/session_visibility_hint            ← NEW TextView, immediately below nameInput,
                                         inside scrollContent (MainActivity.showNewSessionSheet)
session_workspace section              (unchanged)
```

- **Text (string `session_visibility_hint`, exact):**
  `Appears in DSH after your first message.`
  (Approved alt if PO wants the fuller form: `Your new session appears in DSH after you send the
  first message.` — PO picks one; word "DSH" is the product's established name for the host GUI.)
- Style mirrors `workspaceLabel` conventions: 11sp, `@color/muted`, `maxLines 2`, no letter
  spacing, `paddingTop 4dp` (`dp(4)`), full width. Wraps (not ellipsized) — at 2.0× font scale it
  takes two lines inside the ScrollView; no fit risk to the pinned footer.
- TalkBack: plain TextView, automatically read after the field — no content-desc needed.
- **Visibility: always shown** (every create is blank until first prompt on every host — §2/F1;
  there is no host capability to branch on).
- Placement rationale: sits under the NAME field, the exact field that triggers "I named it,
  where is it?" — and under the input, not under Create, so it never competes with the primary
  action's tap target.

### 5.2 A2 — open-chat empty state for a blank session

When `openSession()` opens a session with `blank == true` and the transcript is empty, the empty
view (`@id/chat_empty`, currently `chat_empty_title`/`chat_empty_body` — the latter still M1
placeholder copy, `strings.xml:108`) renders:

- Title (string `chat_empty_blank_title`): `No messages yet`
- Body (string `chat_empty_blank_body`): `Send your first message and this session will appear in
  DSH.`
- Non-blank empty transcript keeps the existing title and gets a de-staled body (string
  `chat_empty_body` **revised**): `Say something to start the conversation.`
- Branch on `currentSession.blank` at `openSession` / message-load time; a `session.upsert`
  (or stream frame) that clears `blank` may leave the copy until the next open — acceptable
  (transient, ≤ the duration of the first send, where the pending bubble already covers it).

**(b)-era variants** (use only if/when defer-create ships — PO approves both sets now to avoid a
second copy round):

- A1-under-(b): `Created in DSH when you send your first message.`
- A2-under-(b) body: `Nothing has been sent yet — your first message creates the session in DSH.`

### 5.3 What must NOT change

- No new network calls, no marker changes, no toast change (`session_created` "New session
  started" stays — transient surfaces stay uncluttered).
- All sheet ids preserved byte-for-byte (`session_name_input`, `session_workspace`,
  `workspace_row`, `preset_*`, `session_create_error`, `session_create`, `new_session_sheet`,
  `new_session_sheet_scroll`/`footer`) — `phase2b_driver.py` depends on them; the hint is inserted
  between `session_name_input` and the workspace section without re-ordering anything.
- `session_name_input`'s hint (`Name (optional)`) and content-desc unchanged.

### 5.4 Hook registry (a) — additive

| Kind | Name | Value |
| --- | --- | --- |
| id (`res/values/ids.xml`) | `session_visibility_hint` | A1 TextView |
| strings | `session_visibility_hint`, `chat_empty_blank_title`, `chat_empty_blank_body`; revised `chat_empty_body` | §5.1–5.2 exact copy |
| markers | none new | — |

**Acceptance (frontend card):** dump shows the hint TextView with the exact text between name
input and WORKSPACE section at 1.0/1.3/2.0 font scale; Create still pinned+reachable on the large phone
1080×2094 with IME open (re-run task-11 sheet case); opening a blank session dumps
`chat_empty` carrying `chat_empty_blank_body`; zero `channel_row`/desc drift.

## 6. Spec — (b) defer-create until first send *(design-complete; PO accepts direction)*

### 6.1 Flow

```
sheet: name + workspace + preset chosen → tap Create
  ├─ no network. Client mints Draft { name?, cwd (resolved, explicit), presetId?, state=DRAFT }
  ├─ EVENT session.draft.open named=<0|1> cwd=<path> preset=<id|none>
  └─ openSession-shape chat opens: top bar = name || "New session", composer enabled,
     transcript empty (chat_empty renders A2-under-(b) copy)          [draft NOT listed in Chats]

composer send #1 (the only trigger):
  disable Send (single-flight) → POST /sessions {cwd[, agentPreset]}
    ├─ fail → user bubble status=failed, text retained, in-chat copy from
    │         createErrorCopy() (§MainActivity, reused verbatim); 409 also triggers
    │         one GET /presets re-read (shares the C2 recovery path); tap-to-retry re-runs
    └─ ok   → Draft.sessionId; PATCH title (if name non-empty) — rename fail = toast only,
              same as today; then POST messages with the client-minted requestId
              → from here: 100% today's pipeline (pending → echo → send.ok → refetch)
              session enters data.sessions like today; markers fire in today's grammar:
              session.create / session.create.preset / session.create.ok /
              [session.rename.ok|fail] / send.pending / send.start / send.echo / send.ok

composer send #2+ : unchanged.
back/discard from an unsent draft chat: draft dropped silently, no host trace.
back after send #1 started: session exists — identical to today.
```

### 6.2 Draft lifecycle rules

| Rule | Decision |
| --- | --- |
| Where the draft lives | MainActivity fields only (process lifetime). No Prefs persistence — matches composer-text behavior; documented limitation, PO confirms (§8-4) |
| Chats list | **Never listed while unsent.** No id → no `channel_row`, no classifier/unread/team surface. This keeps the hooks ledger byte-stable and is the single biggest regression-safety lever |
| Back from unsent draft | Silent discard (mobile new-chat convention). This is precisely the pollution case C5 leaves behind today |
| Second Create while a draft is open | Replace the draft (no confirm) — one draft at a time |
| Draft + app backgrounded | Survives while the process lives; re-entry restores the open draft chat |
| Retry after partial failure | State machine DRAFT → CREATING → CREATED{sessionId} → LIVE. If create+rename succeeded but the message POST failed, retry reuses `sessionId` + the same `requestId` — never a second session |
| Cancel long-press | N/A (no row). Drawer/rail unaffected |
| FAB fallback path (no presets capability) | Same deferral, body without `agentPreset` — one code path, no legacy branch in the draft model |

### 6.3 Hooks + QA impact (b)

- **New:** marker `session.draft.open named=<0|1> cwd=<…> preset=<…|none>` (grammar per
  ui-overhaul hooks ledger; architect ratifies exact token form).
- **Timing moves, grammar identical:** `session.create*`, `session.rename.*` land at first send.
  Any driver that taps Create and greps `session.create.ok` must send a message first —
  `phase2b_driver.py` create cases, the `create-fail` fixture flow (`debugCreateFailure` now
  fires inside the deferred create → the seeded failure renders on the failed bubble, and the
  fixture's error-row assertion re-targets to the in-chat copy), and `m2_gate` only if it drives
  the sheet (its API-side M2GATE seeding is unaffected — no wire change).
- **Unchanged:** everything else in the ledger, all wire shapes, all backend/plugin code.
- **Contract amendment (architect drafts, Lead/PO ratify):** §New-session-flow step 2 becomes
  "Create is deferred to the first composer send (draft session): first send performs
  create → rename → message. A client MAY create eagerly only if it renders the host-visibility
  expectation copy of §5." No new endpoints, no wire change.

### 6.4 Acceptance (b) — checkable

1. Create-tap with name+workspace+preset → **zero** `POST /sessions` on the wire (logcat:
   `session.draft.open`, no `session.create` yet); Chats list has no new row; top bar shows name.
2. First send → markers in order `session.create… session.create.ok [session.rename.ok]
   send.pending… send.ok`; API row: `title` = typed name, `cwd` = chosen workspace,
   `blank=false`, registered in `bootstrap.workspaces[].sessionIds` (v1ai attach path,
   GUI-list-findable) — re-run the AC-6 GUI capture with **no separate** pre-send.
3. Create 409 (fixture) → failed bubble + readable copy + one roster re-read (C2 path); retry
   with the network restored completes into the same session id (no duplicate).
4. Back out of an unsent draft → host session count unchanged (pollution fix, count via
   `qa_api.py get /sessions`).
5. Gate 8/8 on emulator-5554 + the large phone with the rebased harness; `channel_row:` descs byte-identical.

## 7. (c) Upstream note — drafted for the Lead to send (no client action)

> **Request: show *named* blank sessions in the DSH GUI.** Sessions created by external clients
> (mobile app) with an explicit title are hidden from the sidebar list (unless selected), from
> search, and render as "New Session" regardless of title (`client.js` `sessionVisible` ≈:352,
> search filter ≈:588–593, `displayTitle` ≈:1122). The provisional-draft hiding is right for
> unnamed GUI-created blanks; could the filters admit blanks that carry a **user-set title**, and
> could `displayTitle` prefer `title` when present? That closes the "created from my phone,
> invisible on my desktop until the first message" gap for all clients without weakening the
> provisional-draft design.

## 8. What PO must decide

1. **Adopt (a) now** — both A1 + A2 — as the copy bridge beside C1/C2/C4? *(recommended: yes)*
2. **Adopt (b) as the M4 behavior fix**, with the architect's one-paragraph contract amendment
   and a QA harness rebase? *(recommended: yes — it is the structural fix and the pollution fix)*
3. **Copy approval**, both eras: A1/A2 under eager-create (§5.1–5.2, incl. the A1 alt) and the
   A1/A2-under-(b) variants (§5.2). Also the de-staled `chat_empty_body`.
4. **Draft-lifetime policy:** process-lifetime only (no persistence), silent discard on back,
   replace-on-second-Create. Confirm or adjust (§6.2).
5. **Draft invisible in the Chats list until first send** — confirm (recommended; anything else
   re-opens hooks drift) — accepting that "create now, talk from the desktop later" leaves nothing
   on the phone either. If PO wants that workflow preserved, that is a vote for (a)+(c) and
   against (b) — say so on the card.
6. **Send (c) upstream** (note text §7, Lead sends) with zero client dependency. *(recommended:
   yes either way)*
7. Ratify (with architect) the `session.draft.open` marker token grammar if (b) is accepted.

## Handoff

From: designer
To: lead → po
Changed: `team/design/c5-blank-session-visibility.md` (new) — problem, host-verified facts
F1–F4, options a/b/c + rejected "started marker", recommendation (a now + b M4 + c upstream note),
exact copy + behavior specs with hooks/QA impact, PO decision list.
Verified: host facts read-only re-verified in the installed build this session
(`dsh-client-ui-workspace/lib/client.js`, `dsh-api-session-controller/lib/client.js`); client
facts from `MainActivity.java` (sheet 1432, create 1792, send 2271, channelRows 1133),
`strings.xml:87–110`, `DshLog.java`, contract §New-session-flow/§POST-sessions/§PATCH.
**Refuted (2026-11-05, Lead query):** a circulated claim that "`ensureFresh` resurrects completed
sessions on rename with a 'resume this session' prompt" is FALSE — `ensureFresh`
(`dsh-api-session-controller/lib/client.js:882`) is a `Notifier` snapshot-cache rebuild ("rebuild
synchronously when dirty"; body `if (!dirty) return; rebuild()`), called only from `getSnapshot`
(≈:1958) / `getListSnapshot` (≈:2790); `rename` (:1825, :2751) is a thin `remote.session.rename`
pass-through documented "update its title projection **without opening its history**", and no
"resume this session" string exists in any `@deepseek-ai` package. The rename-after-create step of
§6.1 is safe on that account.
Open: PO decisions 1–7 (§8); nothing blocks. No `app-native/` edits made (spec only, per card).
