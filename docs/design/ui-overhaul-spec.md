# UI Overhaul Spec — Harness Native (overnight 2026-11-02)

**Owner:** designer (`team/design/`) · **Status:** RATIFIED (Lead, 2026-11-02) with one supersession —
see §4.0 (ADR-0010 wins on Test hooks + classifier). Co-sources of record:
[ADR-0010](../architecture/adr/0010-test-channel-classification.md) ·
[rail-ia-decision](../architecture/rail-ia-decision.md) ·
[ui-overhaul-hooks.md](../architecture/ui-overhaul-hooks.md) (authoritative id/desc table).
**Inputs:** overnight brief, `team/frontend/ui-spec.md`, `mobile-usability-review.md` (§1.2, §2.1, §2.7),
`MainActivity.java` (`renderRail` M:806, `channelRows` M:941, `showNewSessionSheet` M:1202),
`tools/m2_gate.py`, `team/qa/probe/phase2b_driver.py`. Live data: 322 sessions, 6 workspaces,
~243 test-classifiable (Lead-verified 2026-11-02).

**Definition of done = the Principal's three complaints.** §2 fixes "can't tell what the rail
buttons do", §4 fixes "test messages pollute the list", §1+§3+§5 fix "plugins and workspaces
are not separated". §6 fixes the the large phone sheet overflow (task-11) in the same pass.

---

## 1. Mental model — one line each surface answers one question

| Surface | Answers | Contains | Never contains |
| --- | --- | --- | --- |
| Left rail | **WHERE am I?** | Workspaces, Home (all), Tools button | Plugins, plugin surfaces, sessions |
| Tools drawer (§3) | **WHAT TOOL?** | Agents, Plugins, Jobs, Decisions | Workspaces, sessions |
| Bottom tabs (§5) | **WHAT TOOL?** (persistent) | Chats, Agents, Plugins, Jobs, You | Workspaces |
| Plugins tab | **Which extensions?** | Plugin cards + toggles (unchanged) | Workspace filter UI |
| Test group (§4) | **WHAT'S NOISE?** | M2GATE/PROBE/test/qa/scratch//tmp sessions | Real work |

Rule of record: **the rail never lists plugins.** Plugin *cards* live only in the Plugins tab;
plugin *destinations* are reachable via tabs and the Tools drawer. The `#`-shortcut rows
**move out of the Chats list into the drawer** (rail-ia-decision; the brief's Chats model has
no `#` rows) — §3. This is enforced structurally: the rail builder renders `workspaces + Home +
Tools` and nothing else, and `channelRows()` renders only §3.4 groups + Test.

## 2. Rail — labeled, sectioned, scrollable

Replace `renderRail()`'s 40dp initials + `∗` with labeled rows. **Rail width 72dp → 88dp**
(`dimens.xml rail_width`). Cost: list pane narrows to ~303dp on the the large phone (rows are 15sp title +
12sp snippet — fine; Discord-mobile list ≈300dp).

### 2.1 Anatomy (top → bottom)

```
rail (LinearLayout vertical, width 88dp, bg_rail)
├─ rail_host        40dp tile "DSH" (unchanged; long-press → Reconnect/Copy URL; desc "host <name>")
├─ caption          "WORKSPACES" — 9sp, muted, caps, +0.06 spacing, padStart 12, marginTop 10
├─ rail_spaces      inside a vertical ScrollView (layout_weight=1) ← workspaces scroll
│   └─ workspace row ×N (see 2.2)
├─ divider          1dp stroke_soft, marginStart/End 12, marginVertical 6
├─ rail_home        row: tile ic_grid + label "Home"      (pins to bottom, always visible)
└─ rail_tools       row: tile ic_gear + label "Tools"    (pins to bottom, always visible)
```

The old 8sp footer string (`rail_footer`, "Multi-host pairing arrives in M4") is **removed**
(R11: it promises a moved milestone; host identity already lives in `rail_host` + top bar).
The ScrollView only engages when workspaces overflow; with 6 they all fit (§2.4).

### 2.2 Row anatomy — every rail item is icon **and** readable label

- Row: **48dp min height** (`touch_min`), full-rail-width touch target, `wrap_content` height
  (min-height, never fixed → grows with font scale). Reuse `@id/rail_item` on every row
  (QA greps `rail_item`; `rail_space_0..2` ids are dead — delete them).
- Tile: **24dp** (`icon_m`), radius 8, `bg_logo` for workspaces, `bg_tile` for Home/Tools;
  glyph = workspace initial (11sp medium, centered) for workspaces; 14dp tinted drawable
  (`ic_grid` / `ic_gear`) for Home/Tools. Existing drawables only — no new art.
- Label: **11sp**, single line, ellipsize END, `text_soft`; full workspace title
  (`Models.Workspace.title`, else path basename), in an inner **`@id/rail_item_label`**
  TextView (hooks ledger §3 — makes "dump proves labels" greppable and gives TalkBack the
  name as text). Centered under the tile.
- Active treatment: keep the **3×28dp accent pill** at the row's left edge
  (`@id/rail_selected_indicator`, unchanged) **plus** label + tile tint `accent`, label
  `sans-serif-medium`. Never color-only (a11y rule): pill + tint + weight. Home is active iff
  `filterCwd == null`; a workspace is active iff `filterCwd.equals(path)`; Tools is never active.
- Content-descs (type-first per PO AC-2, Lead ruling 2026-11-02; exact strings — QA parses them):
  - workspace row → `workspace <Title>` (e.g. `workspace Ideas`)
  - `@id/rail_home` → `home all spaces` (keeps the `all spaces` substring `phase2b_driver.py`
    matches)
  - `@id/rail_tools` → `tools drawer` (+ 8dp amber dot `@id/rail_tools_badge` when
    `decisionsWaiting > 0` — the Waiting badge retargets here from the removed `#decisions` row)
  - `@id/rail_host` → `host <hostName>`

### 2.3 Sections & order

**WORKSPACES** (all host workspaces, host order) → **divider** → **Home** → **Tools**.
Home = existing `filterCwd = null` behavior (was `∗`); Tools opens the drawer (§3).
No `+ add workspace` (pairing is M4), no plugin entries, no sessions in the rail.

### 2.4 Fit + font scale

- With 6 workspaces: rail content ≈ 40 + 24 + 6×48 + 9 + 48 + 48 + paddings ≈ **470dp** — fits
  the large phone (≈758–798dp content height) and the phone with room; Home/Tools pinned bottom, workspace
  block scrolls if a host ever reports more (or fonts grow).
- **1.3× font scale:** labels render ≈14.3sp; rows are min-height so they grow to ~52dp — no
  clipping, rail ≈510dp, still fits. **2.0×:** rows ≈68dp, rail ≈610dp — still under budget;
  ScrollView absorbs the remainder. Long names ellipsize on screen but **content-descs always
  carry the full name** (TalkBack + QA unaffected by scale). a11y re-verifies at 1.3/1.5/2.0.
- Phone-width sanity: designed to the narrower **391dp** the large phone figure (Lead 2026-11-02; the older
  usability-review math got 411dp at ρ=2.625 — 391dp is the safe bound). the phone 411dp is roomier.

## 3. Tools drawer — the explicit plugin/tool surface

A **bottom sheet** (thumb-reachable, reuses `bg_sheet` + handle pattern; `rail_drawer`/`drawer_scrim`
stay reserved for the S14 channel drawer). Id `@id/tools_drawer`, title "Tools" (`tools_drawer_title`),
scrim dismiss + back close (AlertDialog semantics). Rows: 52dp, 24dp tile glyph + 14sp name +
11sp muted caption. **One reusable row id `@id/tools_row`** (4 instances), content-desc
**`tool <Name>`** (Lead ruling 2026-11-02 — supersedes the per-row `tool_item_*` ids drafted
earlier).

| Order | Label | Goes to | Desc | Why here |
| --- | --- | --- | --- | --- |
| 1 | Agents | `showTab(TAB_AGENTS)` | `tool Agents` | roster is a tool, not a place |
| 2 | Plugins | `showTab(TAB_PLUGINS)` | `tool Plugins` | plugin deck lives in its tab |
| 3 | Jobs | `showTab(TAB_JOBS)` | `tool Jobs` | background work |
| 4 | Decisions | `showDecisions()` | `tool Decisions` | approvals inbox (no tab) |

**The `#` system rows move here** (rail-ia-decision): `channelRows()` drops the four
`# decisions/# jobs/# agents/# plugins` rows, and the drawer is their new home. Per the hooks
ledger §4.4, the `channel_system_row:<special>[, <state>]` id + desc format is **preserved and
re-used inside the drawer** (tag stays `decisions|jobs|agents|plugins`) — the format is
relocated, not retired — and the Decisions waiting state also surfaces as the rail-tools badge
(§2.2) so "waiting on you" remains visible without opening anything.

Markers: `EVENT tools.open`, `EVENT tools.pick target=<agents|plugins|jobs|decisions>` (hooks-ledger
grammar); drawer closes before navigating. **Deliberate scope cut:** plugin *surfaces* (wallpaper,
canon, recall, autopilot, agent-terminals …) get **no drawer row tonight** — the wire has no
"plugin has a screen" capability, and a dead drawer button is worse than two taps via Plugins tab
→ card. Revisit when the host exposes a plugin-surface capability; until then the drawer is the
4 rows above, always.

## 4. Test group — collapsed `TEST · N`, client-side classification only

**No host mutation, no deletion** (host has no delete API). Classification is a pure client
predicate applied in `channelRows()` in the same pass as the existing filters.

### 4.0 SUPERSEDED by ADR-0010 — read this first (Lead ruling, 2026-11-02)

The interaction design below (§4.2) stands, but the classifier and hooks of my original draft
are superseded **verbatim** by
[ADR-0010](../architecture/adr/0010-test-channel-classification.md) (zero-drift argument wins)
and the hooks ledger:

- Toggle id **`@id/channel_test_toggle`** (text `TEST · N`), content-desc
  **`channel_test_toggle:<collapsed|expanded>:<N>`** — never a `channel_row`, `channel_system_row`,
  or `channel_group_header`; rendered iff N > 0.
- Prefs key **`showTestSessions`** (existing `dsh_native` file, boolean, default false).
- Classifier = ADR-0010 §1 rules **R1–R8 verbatim**: case-folded title + literal cwd, **team
  guard** (`teamId != null` → never classified) before all rules, ordered prefix rules. Title
  and cwd are the only inputs — **no `preview`, no prompt**. No `, test` desc suffix: expanded
  test rows are byte-identical `channel_row:` rows.
- Implement as one static `TestClassifier.isTest(Session)` + `reasonOf(Session)` so QA can
  unit-check the rules table.

### 4.1 Classifier — superseded pointer

Do not implement from the draft rules that stood here. ADR-0010 §1 (R1–R8 + team guard) is the
only normative source; my draft differed (case sensitivity, `PROBE-`/`PROBE ` variants,
workspace-identity cwd rule, preview rule 5) and is withdrawn in ADR-0010's favour. ADR-0010
§4 records what stays deliberately *un*classified (19 untitled Ideas sessions, ~7 imperative
seeds, team sessions).

### 4.2 Interaction

- Main groups **exclude** test sessions entirely: Waiting → Team → Running → Recent render
  without them (this is what cleans the list; ~79 sessions remain).
- After Recent (extending ADR-0008's order `Waiting → Team → Running → Recent` **→ Test**),
  a single toggle row renders: text **`TEST · N`**, leading glyph `▸` (collapsed) / `▾`
  (expanded). View id **`@id/channel_test_toggle`**; **content-desc exactly
  `channel_test_toggle:<collapsed|expanded>:<N>`** (ADR-0010 §2 — it is never a `channel_row`,
  `channel_system_row`, or `channel_group_header`). Tap toggles in place; **state persists** in
  SharedPreferences key **`showTestSessions`** (default **false**, ADR-0010 §3) and is read on
  every list build, so stream rebuilds (`showTab`) and cold starts keep the choice. Marker:
  `EVENT test.toggle state=<collapsed|expanded> count=<N>`.
- **Expanded rows are ordinary session rows** — same `item_channel.xml`, same 56dp anatomy, and
  the `channel_row:<title>, <state>[, unread][, team_depth=N]` content-desc format
  **byte-identical, no suffix** (ADR-0010 §2 — every existing QA path works unchanged once
  expanded). No `channel_group_header` for TEST — the toggle row *is* the header, count included.
- **Count semantics:** N = test-classified sessions passing the *current* query + workspace
  filters (excluding plain subagents). **N == 0 → no row at all** (same rule as empty groups).
  Query and rail filters apply inside Test exactly as in main groups — filtering to `tmp`
  shows a mostly-test list with main groups nearly empty, which is honest.
- **Decisions are never hidden:** approvals/questions keep rendering in `#decisions`, inline
  cards, and `decision.upsert` regardless of classification — a probe session with a real
  approval is still answerable.
- Long-press menu on test rows = existing session menu (no new affordance tonight).

## 5. Bottom tabs — keep 5 labeled tabs, sharpen the active state

Ids, order, and strings **unchanged**: `tab_chats/agents/plugins/jobs/you`, labels
Chats · Agents · Plugins · Jobs · You, 56dp band, 11sp. Refinements only:

- Active tab gets a **3dp×24dp accent bar at its top edge** (new layer-list drawable
  `bg_tab_active`) + accent text — mirrors the rail pill; fixes the color-only active state
  called out in the usability review §2.7. `setSelected(true)` on the active tab so TalkBack
  announces selection.
- 16dp icons above labels (`tab_icon_*` ids exist) are an **optional stretch**, not an AC —
  don't block the overnight gate on them. `tab_badge_*` stays unused (M4-1).

## 6. New-session sheet — compact, scrollable, Create pinned (task-11)

Current sheet is one vertical `LinearLayout` in an AlertDialog (name + workspace section + preset
section + error + button row); on the the large phone (1080×2094) with IME open, Create lands below the fold.

```
AlertDialog (bottom gravity, width match_parent, max 560dp)
├─ ScrollView @id/session_sheet_scroll        ← NEW; fillViewport, the ONLY scrolling part
│   └─ LinearLayout (form, pad 20/12)
│       ├─ session_name_input                 (unchanged id, min 44dp)
│       ├─ session_workspace                  (unchanged: label + summary + workspace_row ×6)
│       ├─ preset_section                     (unchanged: label + preset_row ×6)
│       └─ session_create_error               (unchanged)
└─ footer (pinned): session_cancel + @id/session_create   ← always visible, never scrolled away
```

- The pinned footer can be the AlertDialog button bar (negative/positive) or a fixed
  `@id/session_sheet_footer` LinearLayout — either satisfies "Create reachable"; keep
  `@id/session_create`, its "Create session" content-desc, and the creating/disabled flow.
- Dialog window: `SOFT_INPUT_ADJUST_RESIZE`, max height 90% of content area; form rows keep
  44dp minimums so nothing becomes untouchable while scrolled.
- **All existing ids preserved** (`session_name_input`, `session_workspace`,
  `session_workspace_summary`, `workspace_row`, `preset_section`, `preset_row`, `preset_check`,
  `session_create_error`, `session_create`) — `phase2b_driver.py` workspace/preset cases depend
  on them; do not rename or re-order the picker rows.
- IME check: with keyboard open on the large phone, the scroll area shrinks and Create stays visible.

## 7. Hook registry

The authoritative before/after table is [ui-overhaul-hooks.md](../architecture/ui-overhaul-hooks.md);
this section summarizes it.

**New ids** (`res/values/ids.xml`, additions only): `rail_home`, `rail_item_label` (inner label
TextView of every rail row — greppable labels), `rail_tools`, `rail_tools_badge` (decisions
waiting dot), `tools_drawer`, `tools_row` (reusable ×4), `channel_test_toggle` (ADR-0010),
`session_sheet_scroll`, `session_sheet_footer`. **Removed:** `rail_space_0..2` (dead).
New drawable: `bg_tab_active`. New strings: `rail_section_workspaces` ("WORKSPACES"),
`rail_home_label`, `rail_tools_label`, `tools_drawer_title`, `group_test`,
`group_test_sub_collapsed/expanded`, `tools_row_*`; `rail_footer` deleted. New prefs key:
**`showTestSessions`** (ADR-0010 §3). New markers: `EVENT test.toggle state=<collapsed|expanded>
count=<N>`, `EVENT tools.open`, `EVENT tools.pick target=<target>`.

**Changed (deliberate, Lead ruling 2026-11-02 — type-first descs per PO AC-2):** rail
content-descs `space <Name>` → `workspace <Title>`, `all spaces` → `home all spaces` (the
`all spaces` substring survives), Tools button → `tools drawer`; drawer rows → `@id/tools_row`
with desc `tool <Name>`. The `channel_system_row` id + desc format **relocates** from the Chats
list into the Tools drawer (rail-ia-decision; hooks ledger §4.4).

**Preserved byte-for-byte:** `channel_row:<title>, <state>[, unread][, team_depth=N]` — **no
suffix anywhere** (ADR-0010 §2: expanded Test rows are ordinary rows); `channel_group_header`
texts stay `WAITING/TEAM/RUNNING/RECENT` (TEST is never a group header);
`channel_system_row:<special>[, <state>]` (relocated, §3); ids `channel_row*`, `channel_list`,
`rail_item`, `rail_spaces`, `rail_host`, `rail_selected_indicator`, `new_session_fab`,
`new_session_sheet`, `session_*`, `tab_*` (descs `<label>, tab <i> of 5`); markers `tab.select`,
`channel.group`, `workspace.pick`, `send.*`, `unread.compute`.

## 8. QA script impact (QA owns the patches; before the gate — hooks ledger §4 is authoritative)

1. **`tools/m2_gate.py` — BLOCKING.** The seeded `M2GATE…` row renders only inside the Test
   group (collapsed by default). After `tap(tab_chats)` + the first dump: find
   `channel_test_toggle`; if its desc starts `channel_test_toggle:collapsed` → tap, sleep,
   re-dump — then run `reveal_seed_row` as today. Apply before every seed lookup, including the
   `channels-retry*` loop. The toggle can never be picked as a fallback row (its rid is not
   `channel_row_title`).
2. **`phase2b_driver.py`:** the `"all spaces"` rail matcher survives (substring of
   `home all spaces`); the exact `"space ideas"` match becomes `"workspace ideas"` (type-first
   ruling). Created-`M2GATE…` title assertions need an `expand_test_channel()` helper (find
   `channel_test_toggle`, tap if collapsed) before `back_to_list`.
3. **`m2_ac1.sh`:** visible `channel_row` counts shrink ~243 collapsed (hooks ledger §4.3) —
   treat as informational, or assert `visible + N == expanded total` via the toggle's `<N>`.
4. **`m1-frontend-testability.md` §A:** system-row assertions rebase to the drawer
   (`tools_row` / relocated `channel_system_row`); `channel_group_header` stays the four
   ADR-0008 groups.
5. **`tools/usb_gate.py`** — same expand-first rebase if it looks up test-classified seed rows.
6. **Row-order cases (M1-06/06b):** `TEST · N` toggle row **last**, after Recent; zero
   classified titles inside Waiting/Team/Running/Recent while collapsed; expanded dump shows
   seeded rows as ordinary `channel_row:` rows; every `rail_item` has non-empty label text and
   a `workspace |home|tools|host` desc.

## 9. Acceptance-criteria traceability

| Brief AC | Spec |
| --- | --- |
| Every rail item shows icon **and** readable label; dump proves labels | §2.2 (11sp labels are TextView `text=` in dumps; descs typed) |
| Workspaces vs tools structurally separated; rail shows workspaces + Home/Tools only | §1 rule, §2.1, §2.3 |
| Test sessions collected under Test, excluded from main groups by default; toggle reveals; count shown | §4 + ADR-0010 (§4.0 supersession) |
| Plugins reachable only via Tools drawer / Plugins tab; rail never lists plugins | §1, §3 |
| Sheet: Create reachable on the large phone + the phone, IME check | §6 |
| No regression: gates 8/8, hooks preserved | §7, §8 |
| Font-scale + phone-width sanity | §2.4 |

## 10. Open items (decided-by-default; Lead may override, nothing blocks)

1. **the large phone width 391 vs 411dp** — designed to 391dp (safe bound); verify pill/label fit on device.
2. **Test count ≈243 vs my draft's ≳200 estimate** — resolved: ADR-0010 §Context carries the
   case-folded census; its §4 documents the deliberately-unclassified leftovers (19 untitled
   Ideas, ~7 imperative seeds, team-guard rows). AC is "main lists visibly cleaner", not a count.
3. **Per-session "not test" override** — deferred; needs a persisted client allowlist + row
   affordance. The toggle covers false positives tonight (ADR-0010 §3 keeps classification
   non-configurable by design).
4. **Plugin-surface drawer rows** — deferred until the host declares a surface capability (§3).
5. **Tab icons** — stretch only (§5); ship the active-bar if time is short.
6. **`rail_tools_badge` vs ledger** — the hooks ledger §3 has no badge id; I added it to carry
   the retargeted Decisions waiting state (Lead's ruling). If the Lead prefers zero new ids,
   the drawer's relocated `channel_system_row:decisions, Waiting: N` row alone carries the state.
