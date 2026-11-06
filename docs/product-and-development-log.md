# Harness Native (M1)

The native Android client. **No WebView, no browser chrome, zero dependencies** —
Gradle-free Java framework Views, built with the same `aapt2 → javac → d8 → apksigner`
pipeline as the rest of this repo.

![Native shell](dist/screenshot-native-shell.png)

## Package name

`dev.dsh.harness` — **not** `dev.dsh.native`. `native` is a Java reserved keyword;
`package dev.dsh.native;` cannot compile (`<identifier> expected`). The app label is
still **Harness Native**.

## Build

```bash
./build-native.sh
# dist/dsh-native.apk  (~89 KB, signed with signing/)
```

## Run

```bash
adb install -r dist/dsh-native.apk
adb shell am start -n dev.dsh.harness/.ConnectActivity
```

Pair with the QR/link from `tools/pair.py` (`dshnative://connect?url=…&token=…`,
also accepts `dsh://connect`), or paste the host URL + token. The cookie is
persisted in private SharedPreferences, so a cold start goes straight to the shell;
the token is only needed to mint a new cookie.

## What M1 ships

Discord-mobile information architecture, DSH domain:

- **Server rail** — host + workspaces (first 3) + "all spaces".
- **Channel list** — `#decisions`, `#jobs`, `#agents`, `#plugins`; session groups
  Waiting / Running / Team / Recent, only when non-empty; activity dot; preview or
  title-only when the host has no preview.
- **Chat** — read-only transcript from the real session log: user, assistant,
  reasoning, tool results, system notices; reversed ListView; read-only composer
  ("Read-only preview — sending arrives in M2").
- **Bottom tabs** — Chats · Agents · Plugins · Jobs · You.
- **Agents** — live roster (name, role, status, model, description).
- **Plugins** — the extension deck (title, module, phase, enabled), read-only.
- **Jobs** — aggregated jobs (label, status, kind).
- **Decisions** — empty inbox in M1 (API stub); copy is "Answering arrives in M2".
- **You** — host URL, connection, version, unpair.

Testability: stable `@id/` resources and `DshNative` logcat markers
(`bootstrap sessions=…`, `openSession id=…`, `messages … n=…`).

## API

The app talks only to `/api/mobile/v1/*` on the host:

| Endpoint | Used for |
| --- | --- |
| `GET /ping` | host identity, capabilities |
| `GET /bootstrap` | rail, channels, agents, plugins, jobs |
| `GET /sessions` | session list |
| `GET /messages?sessionId=…&limit=…` | transcript page |
| `GET /plugins` | extension deck |
| `GET /agents` | live roster |
| `GET /jobs` | jobs list |
| `GET /decisions` | read-only inbox (empty in M1) |
| `GET /stream` | SSE hello + heartbeat (live append is M2) |

Auth is the host's authority-bound cookie; the app sends `Origin` and
`X-DSH-Client`, and classifies 401/403 by status (the fence body is plain text).

The live v1 routes are served by the hot-loaded **`dsh-plugin-mobile-v1b`** patch
insert (created because the running host does not reload a symlinked module).
`plugin/lib/v1.js` is the reference source; `plugin/lib/index.js` keeps the legacy
`/api/mobile/*` routes used by the WebView app. On a host restart both packages
load from the profile patch, so v1 persists.

## Verified

- Emulator `dsh34` (Android 34) and **Galaxy the phone** (a phone, Android 16) over
  wireless ADB.
- Bootstrap on both: **39 sessions · 202 plugins · 8 agents · 9 jobs**.
- Galaxy transcript: `look at my total usage`, **66 messages** rendered, tool rows,
  read-only composer.
- Tabs verified: Agents (roster), Plugins (extension deck), Jobs (job list), You
  (host info).
- Cold-start persistence: force-stop → launcher → MainActivity (no re-pair).

Screenshots: [shell](dist/screenshot-native-shell.png) ·
[chat](dist/screenshot-native-chat.png) ·
[plugins](dist/screenshot-native-plugins.png) ·
[Galaxy shell](dist/screenshot-galaxy-native.png) ·
[Galaxy chat](dist/screenshot-galaxy-native-chat.png).

## Known M1 gaps (M2 scope)

- Sending messages (composer is read-only).
- Live SSE append; the app fetches a transcript page on open.
- Decisions inbox (API stub) and approvals.
- Attachments, message status/error fields, tool argument/result detail.
- Plugin toggles, job kill/output, terminal and session management.
- Contract path deviations: `messages` uses a query param, sessions pagination is
  one page, decisions are empty.

## Source map

```
app-native/
  AndroidManifest.xml
  res/values/{colors,dimens,ids,strings,styles}.xml   (frontend vocabulary)
  res/layout/{activity_connect,activity_main,item_*}.xml
  src/dev/dsh/harness/
    ApiClient.java     HttpURLConnection + org.json + persisted cookie
    Prefs.java         host, token, cookie, last-read
    Models.java        Session/Message/Agent/PluginCard/Job/Bootstrap
    ConnectActivity.java  pairing (dsh://, dshnative://), cookie-first
    MainActivity.java  rail, channels, chat, tabs, adapters
build-native.sh
```

---

## M2 control surface (API live, UI in progress)

The M2 API is live on the host via the hot-loaded `dsh-plugin-mobile-v1e`
(supersedes v1c/v1d; legacy routes untouched). It adds:

| Endpoint | Purpose |
| --- | --- |
| `POST /api/mobile/v1/sessions` | create a session |
| `POST /api/mobile/v1/send` | send a prompt (`{sessionId,text}`) |
| `POST /api/mobile/v1/plugins/enabled` | toggle a plugin (`{entryId,enabled}`) |
| `GET /api/mobile/v1/jobs/output?jobId=&ownerSessionId=` | job output chunks |
| `POST /api/mobile/v1/jobs/kill` | request job kill |
| `GET /api/mobile/v1/queue?sessionId=` | pending prompt inbox |
| `POST /api/mobile/v1/queue/clear` | remove queued prompts |
| `GET /api/mobile/v1/stream` | SSE: hello, global ids, 500-frame replay, `since`/Last-Event-ID, `resync`, `ping {t}`, session follow |

Verified: create+send produced a real assistant reply (`native app online`);
plugin toggle applied live; job output returned chunks; SSE probe 14/14 cold and
15/15 resync. Decisions remain read-only (503) until M2-01 lands the plugin-first
payload.

## Artifacts

- `dist/dsh-native-m1.apk` — frozen **M1 read-only** snapshot, sha256
  `0e9cdb85…`, `M1_READ_ONLY=true`; the M1 gate subject.
- `dist/dsh-native.apk` — **M2 control build**, `M1_READ_ONLY=false`; the M2
  subject (composer send, plugin switches, job output/kill). Do not install this
  over the frozen M1 artifact until the M1 gate closes.

## Next

M2 UI: enable composer send, plugin switches, job output/kill; add the SSE client
(live append, reconnect with `since`, `resync`); decisions cards once M2-01 lands.

## Live topology (current)

- **v1h** is the live mobile API package (`plugin-v1h`); v1f/v1g are disabled.
  v1h adds an admitted **prefix route** at `/api/mobile/v1` (`webServer.register`
  + `connection.admit`) that delegates unmatched paths to the exact routes and
  owns the dynamic contract paths (`/sessions/{id}`, `/sessions/{id}/messages`,
  `/jobs/{id}`, `/jobs/{id}/output`, `/plugins/{entryId}/enabled`,
  `/jobs/{id}/kill`, `/decisions/{id}/answer`). It must never buffer the SSE
  stream — `text/event-stream` responses are piped through.
- Contract probe: **18/30** (was 12/27); dynamic paths, bad-cursor/bad-limit,
  sessions fields, SSE all pass. Remaining gaps are backend v1i: ping
  `capabilities.decisionsPlugin`, message `sessionId/attachments/status`,
  `before` cursor 400, typed agent/plugin 404s, job fields/output envelope,
  decisions payload/badge, 401/403 bodies.
- Live edits to a symlinked package do not hot-reload: each API revision must
  be a new package (`plugin-v1i`, …) swapped via the profile patch.

## Final verification (2026-11-01)

**M1 — PASS (snapshot scope), signed by PO.** Frozen read-only artifact
`dsh-native-m1.apk` sha256 `0e9cdb85…`: emulator + Galaxy the phone (`a phone`,
Android 16/sdk 36) pulled-hash match, cold start 266–474 ms, 0 FATAL, composer/
send/plugin switches disabled, read-only hint, `EVENT send` 0; matrix + soak PASS.
M1 gate card task-13 closed.

**M2 — PASS for ship (PO verdict 2026-11-01).** Final Galaxy subject
`dist/dsh-native-m2-ship.apk` sha256 `185f873f…` (also `185f873f` in
`team/qa/evidence/m2-gate-20261101T032301Z/base.apk`):
- Galaxy the phone: composer enabled, 8/8 plugin switches enabled, job dialog
  KILL+CLOSE, stream frames flowing, 0 FATAL, cold 476 ms.
- Send delivered end-to-end (host transcript user + assistant reply with
  `clientRequestId`); AC-1 signed PASS earlier on `1889465e…` with full markers.
- AC-1 PASS · AC-2 PASS (Galaxy synthetic hook capture) · AC-3 PASS (depth 0/1;
  approvals blocked-by-host) · AC-4 PASS (question round-trip) · AC-5 PASS
  (decision-kind recommendation, caveat closed) · AC-6 PARTIAL — job dialog
  device-verified; finishing (restart-required banner, dependency warning, real
  stop copy) carried to M3.
- API: contract 31/31, stream 14/14, legacy 6/6; canonical `plugin/lib` == live
  `v1ac`; decisions answer path live (`{answers:[{id, selected}]}`,
  `{recommended:true}`, `{decision}`), idempotent send (`duplicate:true`),
  job output/kill fixed.
- Evidence: `team/qa/evidence/m2-gate-20261101T032301Z/`,
  `m2-ac2-hook-galaxy/`, `m2-ac5-decision/`, `m2-ac1-20261031T233823Z/`.

**Tracked residuals (not ship blockers):**
1. Debug-marker regression (`send.ok`/`send.echo` absent when the durable echo is
   neither streamed nor refetched) — fix tracked pre-M3; subject `e071d20e…`
   still shows the gap and needs a post-send refetch or a stream-follow fix.
2. AC-6 finishing fixtures (restart-required plugin toggle, dependency warning,
   disposable-job stop) — M3 polish.
3. Approvals cannot fire on this host (`danger-full-access`); UI source-reviewed.
4. `delegationDepth` 2+ not producible via API; AC-3 scoped to depth 0/1.
5. SSE `resync` gap fixture deferred to a host-reset window.

## M3-track progress (2026-11-01, autonomous)

- **Marker fix verified on both devices:** subject `0b0d7ad8…` (post-202 1.5 s refetch + shared
  single-fire guard). M2 gate on emulator-5556 (`m2-gate-20261101T040912Z/`) and Galaxy the phone
  (`m2-gate-20261101T041149Z/`) both **8/8 PASS**, `send.ok=1`, `send.echo=1`, job dialog
  KILL+CLOSE, stream frames, 0 FATAL. Closes G13 on the M3-track subject.
- **AC-6 real stop captured on the Galaxy:** disposable `sleep 300` job killed from the app's job
  dialog; tool result `[stopped: cancelled by the user] [killed by signal: SIGTERM]`; evidence
  `team/qa/evidence/m3-ac6-stop/`.
- Remaining AC-6 finishing: dependency warning + restart-required banner captures (UI staged;
  need a restart-required toggle fixture).
- M2 ship artifact `dist/dsh-native-m2-ship.apk` (`185f873f…`) unchanged; M2 PASS stands.
- **AC-6 dependency warning captured** (emulator-5556): tapping the Decisions switch shows
  `Dependency warning` — "Decisions depends on Session Watch. Disabling Session Watch may hide
  pending decisions cards…" with CANCEL / DISABLE ANYWAY; cancelled with no state change.
  Evidence `team/qa/evidence/m3-ac6-dependency/`.
- **Restart-required banner — fixture-blocked:** no plugin on this host advertises
  `restartRequired:true` (all `/plugins` entries false), so the banner cannot be exercised
  without a host fixture/plugin that cannot hot-apply. UI is implemented; carried as M3 polish.
- **Restart-required banner captured via synthetic debug fixture** (emulator-5556): build
  `a41f148e…` adds `dshnative://debug/restart` (DEBUG-only, no host mutation); emits
  `EVENT debug.restart.seed source=debug-hook` and renders `plugin_restart_banner`
  ("RESTART REQUIRED · restart the host to apply the plugin change"). Evidence
  `team/qa/evidence/m3-ac6-restart/`. Synthetic-labelled; no plugin on the host advertises
  `restartRequired`, so this is the only exercisable fixture.
- **Debug-fixture build gate:** `a41f148e…` passes 8/8 on emulator-5556
  (`m2-gate-20261101T052319Z/`) and Galaxy the phone (`m2-gate-20261101T052413Z/`):
  send.ok=1/send.echo=1, job dialog KILL+CLOSE, stream frames, 0 FATAL.
- **M3 conditions closed:** G13 marker determinism verified on both devices (v1ae + client
  refetch fallback); AC-6 real stop, dependency warning, and restart-required banner (synthetic
  debug fixture) all captured. M2 ship artifact `185f873f…` unchanged.
- **Restart-required banner root-caused; API mapping bug fixed — live package v1af (2026-11-01
  autonomous).** `restartRequired` is not a plugin-manifest flag: it is the plugin manager's
  `ChangeResult.application`, set to `restart-required` only when the host profile is
  **startup-only (no HMR)**. Every packaged revision through v1ae read a nonexistent
  `result.restartRequired`, so the banner could never fire from real host data even on a
  startup-only host (contract §6 deviation). **v1af** (0.6.11) maps
  `result.application === 'restart-required'`; hot-swapped (v1ae disabled), verified live
  (`fiberPhase: active`): contract 30/30 + 1 env-skip, stream 14/14, success-path toggle
  `{ok, entry, restartRequired:false}` (correct on an HMR host), emulator gate **8/8** on
  subject `a41f148e…` (`m2-gate-20261101T143756Z/`). Galaxy confirmation pending — device
  locked (`m2-gate-20261101T143851Z/`); re-run after a manual unlock. A **real**
  `restartRequired:true` capture requires starting the host without HMR once — same
  **host-reset window** as the SSE `resync` gap fixture. Full story:
  `team/qa/evidence/v1af-restart-required/summary.md` (incl. gate-runner notes: run gates
  sequentially; seed a fresh `M2GATE`-titled session or the probe lands in the newest chat).
- **R1 fixed — stale composer content-desc (2026-11-01 autonomous).** The M3/UI usability review
  (`team/frontend/mobile-usability-review.md`, task-1) found the composer's content-desc still read
  the M1 read-only hint on the control build (TalkBack + QA scripts read a false state). Fixed:
  `MainActivity` now sets it from `M1_READ_ONLY` ("Message" when sending is enabled). New subject
  `dist/dsh-native.apk` sha `fd384853…`; gates **8/8** on emulator-5556
  (`m2-gate-20261101T170732Z/`) and Galaxy the large phone (`m2-gate-20261101T170825Z/`) — send.ok=1,
  send.echo=1, stream 13/12 frames, 0 FATAL; `composer_input` content-desc = "Message" in both
  `chat.xml` and `typed.xml` on both devices. Remaining review items R2–R11 are carded in
  `team/ops/followups-m3.md`; M2 ship artifact `185f873f…` unchanged.
- **R2 + R3 shipped (2026-11-01 autonomous).** Chats list now refreshes **in place** on stream frames
  (`EVENT channels.refresh.inplace`, scroll anchor preserved) instead of rebuilding the tab; the
  `#decisions` row shows `Waiting: N · urgent` (amber) and the badge is refreshed on bootstrap + a
  15 s foreground poll. **Finding:** the live plugin does **not** emit `decision.upsert` today
  (logcat during a fixture run), so a frame-only fix would never fire — the poll is load-bearing.
  Subject `73854d41…`; emulator gate 8/8 (`m2-gate-20261101T172028Z/`); evidence
  `team/qa/evidence/m3-r2r3-inplace/`. `followups-m3.md` §C records R1–R3 done; R4–R11 + M4 open.
- **R4 + R7 + R8 shipped (2026-11-01 autonomous).** Rail: 48dp targets, `rail_item` +
  `rail_selected_indicator` active pill, host-logo long-press → Reconnect / Copy URL, footer reworded
  to M4. Composer: `IME_ACTION_SEND` + hardware Enter send (verified `send.ok`), `KEYBOARD_TAP` haptic,
  row time `faint`→`muted`. Decision cards: **Open session** action (`decision_open_session`) that
  re-bootstraps if the owning session is not in the current list. Subject `ce6c3dfb…`; emulator gate
  8/8 (`m2-gate-20261101T172905Z/`); evidence `team/qa/evidence/m3-r4r7r8/`. Remaining review items:
  R5 (mark read/unread), R6 (collapse notices), R9 (job jump), R10 (architect call), R11 (copy).
- **R5 + R6 + R9 + R11 shipped (2026-11-01 autonomous) — M3-polish batch complete except R10.** Session
  rows: long-press → Mark read/Mark unread + Copy id (ListView item long-click; per-row listeners
  swallowed item clicks and were reverted). System rows >160 chars / >3 newlines collapse to a 2-line
  **NOTICE** chip (tap expands); short system rows unchanged. Jobs: subtitle tap opens the owning
  session; output dialog is monospace/selectable with **COPY** + KILL + CLOSE. Footer reworded to M4;
  `decision_open_session` now used. Subject `244b5023…`; **8/8 gates on both devices**
  (`m2-gate-20261101T173937Z/`, `m2-gate-20261101T174025Z/`); evidence `team/qa/evidence/m3-r5r6r9/`.
  Only **R10** (Team-group display order vs contract §3.4) remains — an architect/contract call.

## Phase 2 — new-session sheet, model picker, workspace picker, named session (2026-11-01/02, resumed session)

**Principal ask (2026-11-01, verbatim):** *"i need the option to choose the model when starting the
new session. also I don't see the session started in mobile in DSH here."*

**Outcome: PASS for ship** — PO-signed 2026-11-02 00:00Z on the frozen subject, all 10 phase-2 ACs
PASS (`team/product/phase2-acceptance.md` §5.2). Both asks are proven end-to-end: the picker's chosen
preset **actually runs that model**, and a phone-created session with a typed name in the **Ideas**
workspace **appears in the DSH GUI with that name** after its first message.

**Final client subject:** `dist/dsh-native.apk` sha256
**`9afd331a55903bd14e9195f1d382ed1a66d57186b99b50063886a4d5cb8e7a19`** (built 17:26Z; pulled-hash
match, dist = installed on Galaxy the phone `a phone` + emulator-5554). Sources: uncommitted
`app-native/` worktree changes (Lead commits after the durable write). Previous subjects in this
track: `f8a5b06f…` (phase 1, sheet behind graceful degrade), `569fd459…` (F1/F2/F3 fixes),
`9afd331a…` (adds the workspace/cwd picker; final).

### What shipped (client, `app-native/`)

- **New-session FAB** on Chats (56dp, `@id/new_session_fab`, content-desc "New session") — prior
  session's task-2, subject `8ffce21e…`.
- **Sheet** (`@id/new_session_sheet`): optional name (`@id/session_name_input` → PATCH rename after
  create), Create/Cancel, stays open until create settles, in-sheet typed failure copy
  (`@id/session_create_error`), Create re-enabled for retry; sheet survives the IME.
- **Model picker** (`@id/preset_section` + `@id/preset_row`/`@id/preset_check`): renders the live
  roster, `isDefault` preselected (exactly one ✓), broken rows disabled with `Unavailable: <reason>`
  + content-desc reason, `agentPreset` omitted for the default row, `EVENT preset.pick`.
  Gate: `capabilities.agentPresets` **and** a successful `GET /presets`; otherwise hidden (phase-1
  graceful degrade).
- **Workspace picker** (`@id/session_workspace` + `@id/workspace_row`/`@id/workspace_check` +
  `@id/session_workspace_summary` "Create in <title> · <path>"): sends an explicit cwd. Default order
  = active rail space → last-opened session cwd (`Prefs.lastCwd`) → main workspace (most non-blank,
  non-probe sessions; host scratch excluded but selectable) → first. Fixes the **create-cwd defect**
  found post-freeze: `bootstrap.workspaces[0]` is activity-ordered, so sheet-created sessions could
  silently land in `/Users/you/.hermes/cache/scratch` instead of Ideas — an independent cause
  of the Principal's "I don't see the session" complaint.
- **Client fixes found by the gate:** F1 `broken` string vs boolean (now tolerated; string doubles as
  reason), F2 default preselection, F3 typed-error surface, C3 nested `{ok:false,error:{…}}` envelope.
- Hooks preserved (`@id/` + `EVENT` vocabulary); new markers `EVENT sheet.open kind=new_session
  presets=<bool>`, `presets.load count=N`, `preset.pick preset=<id|default>`,
  `session.create[.ok|.fail]`, `session.create.preset`, `session.rename.ok|fail`,
  `sheet.workspace.default path=… source=rail|last-open|main|first`, `workspace.pick path=…`.
  DEBUG-only fixtures (remove for M4): `dshnative://debug/presets-broken`,
  `dshnative://debug/presets-unknown`, `dshnative://debug/create-fail` (the host is healthy now, so
  those states are only reachable synthetically; all labelled `source=debug-hook`).

### What shipped (API + host config)

- **`dsh-plugin-mobile-v1ah` 0.7.1 is live** (v1ag disabled; swap backups
  `cordis.patch.yml.bak-20261101T1651Z`). New package `plugin-v1ah/`; canonical `plugin/lib/` updated.
- **Preset activation fixed:** a bare `agent-default-model` row inside a preset mounts in the root
  service realm and collides with the host-plane `agentDefaultModel` (`service "agentDefaultModel"
  has been registered`). Wrapping it in `cordis:group` + `isolate: {agentDefaultModel: true}` mounts
  clean; **ADR-0009** then applies the preset's declared model as a **session-local selection at
  create** (the isolated row is realm-private, so the API reads it via the registry's cross-scope
  `serviceFor(agent,'agentDefaultModel')` + `selectForNextRequest`; no global-default save). Wire
  shape unchanged: `Session.agentPreset` echoes, `Session.model` proves the applied model.
- **Typed preset errors (no 500s for client-caused errors, retryable:false):** non-string → 400
  `bad-request {field}`; unknown/removed → 404 `agent-preset-not-found {presetId}`; roster-broken /
  create-invalid / pin-unroutable → 409 `agent-preset-unavailable {presetId, reason}`;
  absent/null → host default. `GET /presets` canonical shape `broken:boolean` + `reason:string|null`.
- **Host config (Lead-applied):** host default model **GLM-5.3-Flash-EXL3**; `deepseek-v41` pins
  **DeepSeek-V4.1-Flash-1017**; **qwen38 removed** (buddy-gateway serves only DeepSeek + GLM —
  Qwen3.8-Flash-Next returned "Unknown model" and its sessions silently never replied; backup
  `cordis.patch.yml.bak-qwen-20261101T230420Z`). Live roster = **6 rows**, all healthy.
- **Contract (architect, all committed):** route 20 `GET /presets`; `POST /sessions` omit-both MUST;
  preset-validation table + §9 error codes; **ADR-0008 accepted** (Team-group order ratified:
  Waiting → Team → Running → Recent — R10 closed, no client change); **ADR-0009 accepted**;
  M4-1…M4-7 verdicts (`team/architecture/m4-verdicts.md`).

### Verification (evidence)

- **Final gates 8/8, pulled-hash `9afd331a…`:** the phone `team/qa/evidence/m2-gate-20261101T233037Z/`;
  emulator-5554 `…233645Z/`; the large phone (extra) `…235418Z/`.
- **Feature, the phone:** `team/qa/evidence/task5-phase2/s23fe-final-9afd331a/` (sheet, 6 rows, one ✓
  default, workspace "Create in Ideas", IME reachable).
- **Phase 2b:** `team/qa/evidence/task5-phase2b/{final-9afd331a, final-9afd331a-emu5554, gui-9afd331a}/`
  — create `M2GATE picker deepseek-v41` → `agentPreset=deepseek-v41`, `model {buddy-gateway,
  DeepSeek-V4.1-Flash-1017}`; default → GLM; workspace pick → cwd=/tmp; last-open persistence;
  app→GUI end-to-end `session-56689760…` (Ideas, typed name, after one message) with the GUI capture
  `gui-ideas-search-M2GATE-gui-proof-app.png`.
- **Frontend:** `team/frontend/evidence/task5-phase2/cwd-9afd331a/` (summary + no-history/last-open/
  pick-tmp sheets, creates, C3 nested 409, real qwen38 404, broken regression) +
  `team/frontend/task5-phase2-notes.md`.
- **API/backend:** `team/backend/v1ah-preset-fix.md` (root cause §1, rows §2, pin proof §4 incl.
  durable `request/header` + assistant reply, typed errors §5); M2 contract **30/30 + 1 env-skip**,
  preset suite **4/4**, pre-swap unit **19/19**; architect live cross-check **27/27**
  (`team/architecture/v1ah-contract-crosscheck.md`).
- **PO verdict:** `team/product/phase2-acceptance.md` §5.2 (PASS with tracked conditions);
  `team/product/brief.md` §10 AC register updated.

### Open / follow-ups (not ship blockers)

- **task-7 / [followups-phase2.md](../team/ops/followups-phase2.md):** C1 humanize broken-row reason
  copy; C2 re-read `GET /presets` after typed 409; C4 "MODEL" label on composition presets
  (cordis/minimal/ptc/standard inherit GLM); **C5** blank named sessions are invisible in the DSH
  GUI by host design (M4 decision: sheet hint / defer create until first send / host GUI change);
  P1–P5 (presets.model additive, D3 orphan edge, pin-smoke standard asserting a completed reply,
  etc.).
- **Ops:** host test-session pollution (~115 → ~230 sessions, many "Untitled session" from probes;
  no delete API) — cleanup/ops note before the Principal re-checks the GUI.
- **QA:** phase-2b harness false negatives (`markerPreset` / typed-404 tap) to fix; keep contaminated
  runs. Rebase M1-06/M1-06b row-order assertions to Team second (R10, no re-gate).
- **Unchanged open items:** host-reset window (SSE `resync` + real `restartRequired:true`), approvals
  unexercisable (`never` policy), `delegationDepth` 2+ not producible.

**Board/ops note:** the GUI board is session-scoped and the prior session ended **without a handoff
prompt**, so this work had to be reconstructed from commits + evidence at the 22:15Z resume. The
durable mirror is [team/ops/board-snapshot.md](team/ops/board-snapshot.md); audits in
[team/ops/status.md](team/ops/status.md) (#19–#21). A session that ships work must end by writing
the mirror + a cold-start handoff prompt.

## Phase-2 follow-up: workspace attachment fix (v1ai, 2026-11-02)

**Defect found by the Principal after phase-2 ship:** a session created from the phone ("new idea",
cwd `~/Documents/Ideas`, with messages) existed and appeared in GUI *search* but **not in the DSH web
GUI's Ideas workspace list**. Root cause: `POST /sessions` forwarded only `cwd` to
`sessionController.create`, which attaches to a workspace **only when `workspaceId` is passed** — so
cwd-only creates were never in the host workspace registry. The app hid this because its bootstrap
derives workspaces from session cwd (`workspacesOf`); the GUI uses real registry membership.

**Fix — `dsh-plugin-mobile-v1ai` 0.7.2 (live, v1ah disabled; API-only, no client change):**
`POST /sessions` resolves a registered workspace by exact normalized `cwd` and passes its real id
(workspaceId authoritative > cwd resolution > unmatched unattached); omit-both resolves the created
session's actual cwd; best-effort attach never loses the session (still 201); a **bounded bootstrap
reconcile** (≤10 attaches/250 ms per call) backfills previously unattached sessions; explicit
unknown `workspaceId` → typed 404 `workspace-not-found`. Contract: §3 workspace-attachment semantics
(commits `ca167a7`, `4b7a743`); backend `team/backend/v1ai-workspace-attach.md`; QA
`team/qa/evidence/v1ai-workspace-attach/`.

**Verified:** first bootstrap after swap attached the real **"new idea"** session to Ideas
(`workspaceId=c305a519-c9d3-4fcb-b378-bdd74c5d1d80`); new cwd-create attached immediately; GUI Ideas
list renders "new idea" (QA `postfix-v1ai/gui-ideas-new-idea.png`, Lead
`lead-gui-proof/gui-ideas-list-new-idea.png`); backfill 39/39 Ideas-cwd sessions attached, 0 left;
regression contract 30/30 + 1 env-skip, 6 healthy presets, deepseek pin. App subject unchanged
`9afd331a…`.

## Overnight UI overhaul (2026-11-02, in progress)

Principal feedback (2026-11-01 late): *"i can't tell what the buttons on the left task bar are used
for … there needs to be a test channel to keep the list cleaner … clear separation from plugins and
workspaces. Build a new version with a better UI and layout. Send it to a new sesssion and build over
night. Take the dev team. Expand if needed."*

An **overnight autonomous session** owns this build:
`session-05895664-d6a1-4108-8906-de0a93d7193f` ("Overnight UI overhaul — rail, Test channel,
plugins/workspaces", Ideas, GLM). It runs with a goal + expanded team (existing six roles plus
**designer** and **a11y**), brief and definition of done at
[team/ops/overnight-ui-overhaul-brief.md](../team/ops/overnight-ui-overhaul-brief.md).

Scope: labeled/clarified rail with workspaces vs tools separated; collapsed **Test** group/filter for
`M2GATE`/probe sessions (client-side classification, no host deletion); plugins no longer mixed into
the rail; plus the task-11 the large phone sheet-overflow fix. It must leave a morning handoff at
`team/ops/morning-handoff-ui-overhaul.md` (subject sha, gates on emulator/the phone/the large phone, screenshots,
STATE/NATIVE/board updates).

## UI overhaul — shipped subject 454c7f44 (2026-11-05 close-out of the cut-off overnight run)

The overnight session that started 2026-11-02 was cut off mid-close-out (~05:13Z) with the build
already frozen and the emulator gate already passed. The 2026-11-05 resumed session picked it up:
rebuilt the board, re-ran the remaining gates, signed the verdict, and closed the handoff.

**Subject:** `dist/dsh-native.apk` sha256 **`454c7f44ed57ae7f7464c4d9aa35d7bb3bd004d08cc36accf6df993c486bc1fb`**
(build `ef22c03`; frozen 2026-11-02). Installed + hash-verified on **emulator-5554** and the
**physical the large phone** (`a large phone`, USB `<serial>`). the phone deferred (locked/absent,
Principal-approved) — install + walkthrough at the Principal's convenience.

**What shipped:** labeled rail (icon+text on every item, six workspaces reachable, sectioned
WORKSPACES→Home→Tools, tools behind a single Tools drawer) · collapsed **`TEST · N`** channel row
after Recent (ADR-0010 classifier: `origin=="subagent"` guard + R1–R8, client-side only, persisted
toggle, scope-aware count) · plugins separated from workspaces · the large phone sheet fix (scrollable body,
pinned Create/Cancel, IME-safe at 1080×2094).

**Gates:** emulator-5554 8/8 (`team/qa/evidence/m2-gate-20261102T051205Z/`) · the large phone physical 8/8
(`team/qa/evidence/ui-overhaul-closeout-20261105T145232Z/s9plus/gate/`) · 0 FATAL both ·
group order TEAM→RECENT→TEST · Test channel collapsed:253 / expanded:253 with M2GATE rows rendering
and persistence proven both ways · live host `v1ai` 0.7.2, 6 healthy presets, 359 sessions
(end-of-run 253 test / 106 main).

**Verdict:** PO **PASS for ship** on emulator + the large phone (`team/product/ui-overhaul-acceptance.md` §3),
conditional on the the phone walkthrough. Tracked conditions: S23FE-WALK, AC-20-GUI proof, AC-17 pin
carry, QA harness scroll fix.

**Deviations recorded:** the phone deferred; QA Test-channel harness false negative (dumps without
scrolling to the list bottom — product behavior correct); LAN gateway `:3091` restarted after a host
restart (`tools/start-gateway.sh`, node pid 86192); the po teammate's turn was cut off on the
session's degraded fallback model, so the Lead signed §3 using po's pre-registered rubric.

**Docs:** handoff `team/ops/morning-handoff-ui-overhaul.md` · mirror `team/ops/board-snapshot.md` ·
audit `team/ops/status.md` #24 · decisions `team/product/decision-c4-preset-label.md` +
`decision-c5-blank-session.md` · C5 spec `team/design/c5-blank-session-visibility.md` · backend
verify `team/backend/closeout-host-verify.md` · next SWE2 batch
`team/ops/swe2-execution-brief-task7.md` (C1/C2/C4/C5a on a NEW subject).

## Follow-up subject f5fea92f — phase-2 batch C1/C2/C4/C5(a) (2026-11-05)

After the UI-overhaul close-out, the SWE2 batch (`swe-2-flash-medium`, headless print mode) applied
the four mechanical follow-ups; the Lead reviewed the diff against the approved plan + hooks ledger,
built, and gated it.

**Subject:** `dist/dsh-native.apk` sha256 **`f5fea92fd08ce2305e0fe9ee86ac5c107a94c537f8c0f10270a63a1268f749af`**
(prior signed subject `454c7f44`). Control gate **8/8** on emulator-5554
(`team/qa/evidence/m2-gate-20261105T222915Z/`) and the physical the large phone (`…223007Z/`), 0 FATAL.

**Changes:** C1 broken-preset row shows human copy ("This preset couldn't load on the host") + a
Details dialog carrying the raw host diagnostic; the row content-desc keeps the full reason (hooks
ledger intact). C2 exactly ONE `GET /presets` re-read after a typed `409 agent-preset-unavailable`,
with additive `EVENT presets.reload count=N` / `presets.reload.fail` (initial `presets.load`
unchanged). C4 section label MODEL→PRESET, composition rows get "Runs the host default model",
optional `presets.model` parsed tolerantly (string/object/absent/garbage → fallback, row never
dropped) shown as visible secondary text only. C5(a) sheet hint "Appears in DSH after your first
message." + refreshed empty-chat copy.

**Verified on-device (emulator-5554):** C1 visible line clean + Details dialog opens with the raw
diagnostic + content-desc keeps the full reason; C2 `presets.load count=6` → `session.create.fail
HTTP_409` → exactly one `presets.reload count=6`; C4 PRESET label + composition qualifiers; C5a hint.
Evidence `team/qa/evidence/newsubject-f5fea92f/` (`summary.json` + dumps).

**Pending:** PO re-check of C1/C2/C4/C5a ACs on this subject, and the the phone walkthrough (which
covers both subjects). Register rows C1/C2/C4/C5 flipped in `team/ops/followups-phase2.md`.

**the phone walkthrough (2026-11-05):** subject `f5fea92f` installed on the Principal's primary device
(a phone, USB `<serial>`), pulled-hash match, control gate **8/8** (`team/qa/evidence/m2-gate-20261105T223640Z/`),
0 FATAL; complaints captured (labeled rail with all six workspaces + Tools drawer, `TEST · 253`
collapsed/expanded with M2GATE rows, labeled tabs, sheet `PRESET` + hint); **GUI proof:** the
phone-created named session `S23 gui proof 20261105.` (Ideas cwd) sent its first message and appears
in the DSH GUI Ideas list (`team/qa/evidence/s23fe-walkthrough-20261105/gui-proof/ideas.png`).
Conditions S23FE-WALK + AC-20-GUI satisfied.

## Collapsible rail — subject 1d0117e3 (2026-11-05)

**Principal request:** *"the side bar needs to somehow hide and come back when needed. it looks way
too cramped this way."* Implemented per `team/design/rail-collapse-spec.md` (SWE2 execution, Lead
diff review): hamburger `@id/rail_toggle` as the first top-bar item (state-aware content-desc
`show sidebar`/`hide sidebar`, ≥48dp), rail **hidden by default** (`Prefs.railHidden` default true),
toggle flips `@id/server_rail` GONE/VISIBLE so `@id/main_screen` takes the full 1080px width, state
persisted across restarts, and selecting a workspace/Home/Tools while the rail is shown auto-hides it
(`EVENT rail.hide reason=select`). Markers: `EVENT rail.toggle hidden=<bool>`. All existing rail,
topbar and channel hooks unchanged; additive ids/drawable/pref only.

**Verified on the phone** (`team/qa/evidence/rail-collapse-1d0117e3/`): default hidden with full-width
content, toggle shows the rail (`hidden=false`), workspace select auto-hides (`rail.hide
reason=select`), restart restores the persisted state, restored to hidden at close. Gate **8/8 on all three devices**: the phone **wireless**
(`adb-<serial>-cPQuyb._adb-tls-connect._tcp`, `m2-gate-20261105T233428Z/`), the large phone USB
(`…235411Z/`) and emulator-5554 (`…225700Z/`), 0 FATAL. Harness fix: `tools/m2_gate.py` now decodes
adb output with `errors="replace"` (OEM logcat bytes had aborted a run with UnicodeDecodeError).
