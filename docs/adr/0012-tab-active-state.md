# ADR-0012 — Tab active state is derived; Tools is a rail destination, not a sixth tab

- Status: **accepted** (architect, 2026-11-05) — ratifies the shipped UI-overhaul navigation model on frozen subject `454c7f44…`; no contract change
- Deciders: architect, frontend, lead, po
- Related: [api-contract.md](../api-contract.md) §10, [ADR-0007](0007-navigation-discord-mobile.md),
  [rail-ia-decision.md](../rail-ia-decision.md), [ui-overhaul-hooks.md](../ui-overhaul-hooks.md) §2–§4,
  [ui-overhaul-spec.md](../../design/ui-overhaul-spec.md) §3/§5

## Context

The Principal's original complaint was that the left rail did not explain itself: workspace
buttons, plugin surfaces, and pseudo-navigation rows were visually interleaved. The shipped fix
split navigation into two levels:

- the **rail** answers "which workspace am I looking at?" — labeled workspaces, Home, Tools;
- the **bottom tabs** remain the five product destinations — `Chats · Agents · Plugins · Jobs · You`;
- **Tools** is a rail affordance that opens a drawer containing the four non-chat surfaces.

`showTab(int index)` is the single navigation primitive for the bottom tabs. It logs
`EVENT tab.select`, clears transient chat/decision state, paints the active tab with accent text +
`bg_tab_active` + `setSelected(true)`, clears the same state on inactive tabs, and then replaces the
content area with the selected screen. The Tools drawer is deliberately **not** a sixth tab: it is
an `AlertDialog` bottom sheet (`tools_drawer` inside `drawer_scrim`) whose rows deep-link to the
existing tabs or `showDecisions()`.

The risk this ADR closes is semantic drift. If Tools were modeled as a tab, the app would have two
navigation systems claiming the same "where am I" answer, and QA hooks (`tab_chats`,
`tab_agents`, `tab_plugins`, `tab_jobs`, `tab_you`) would no longer describe the whole model.

## Decision

**The five bottom tabs are the only tab-level navigation state. Tools is a rail destination that
opens a transient drawer and always resolves to an existing tab or Decisions; it never becomes a
sixth `tab_*` id or a persistent tab selection.**

1. `showTab(index)` remains the only function that changes the active bottom tab. It must continue
   to:
   - set `tab` and clear `chatOpen`, `showingDecisions`, `currentSession`, and the back affordance;
   - paint exactly one tab active using text color, `bg_tab_active`, and `setSelected`;
   - rebuild only the content pane for the selected tab.
2. Active-tab state is **derived from `tab` at paint time**, not stored as independent booleans per
   view. `tabs[i].setSelected(i == tab)` and the same predicate for color/background keep the
   visual state, accessibility `selected` state, and `EVENT tab.select index=<i>` in agreement.
3. `rail_tools` is never `selected`. Its visual state is an affordance, not a location: it opens
   `showToolsDrawer()` and does not mutate `tab`, `filterCwd`, or the channel list. The rail keeps
   its own derived state — Home active iff `filterCwd == null`, a workspace active iff
   `filterCwd.equals(workspace.path)`, Tools never active.
4. The Tools drawer contains exactly four destinations, in this order: Agents, Plugins, Jobs,
   Decisions. Each row is `@id/tools_row` with content-desc `tool <Name>` (`tool Agents`,
   `tool Plugins`, `tool Jobs`, `tool Decisions`). Decisions is the only non-tab destination and
   resolves through `showDecisions()`; the other three call `showTab(TAB_AGENTS)`,
   `showTab(TAB_PLUGINS)`, and `showTab(TAB_JOBS)` respectively.
5. `pickTool()` closes the drawer before navigating and emits `EVENT tools.pick target=<target>`.
   This preserves the "drawer is transient chrome" rule: after navigation, the visible active state
   is the destination tab, not Tools.
6. Plugin cards remain inside the Plugins tab. The drawer names **destinations**, never plugin
   instances, and must not render `plugin_row`/`plugin_section` content.
7. Chats no longer renders `# Decisions`, `# Jobs`, `# Agents`, or `# Plugins` system rows. Those
   surfaces are reachable only through Tools (or the bottom tabs where they already exist). The
   retired `channel_system_row` hook must not reappear.
8. No API/contract amendment. Navigation remains client-side view state; `api-contract.md` §10
   already treats additive client behavior as non-breaking, and this ADR changes no wire shape.

## Consequences

- QA can keep the five-tab hook contract stable: `tab_chats`, `tab_agents`, `tab_plugins`,
  `tab_jobs`, `tab_you`, `<label>, tab <i> of 5`, and `EVENT tab.select` remain authoritative.
- The rail and drawer satisfy the Principal's separation ask without inventing a sixth
  destination or duplicating tab state.
- `showToolsDrawer()` may be opened from any tab; after a pick, `showTab()` resets transient chat
  state exactly as a direct tab tap would.
- Decisions remains a pushed view inside Chats (`showDecisions()`), reachable from Tools; its
  waiting/urgent caption and rail badge are presentation details, not navigation state.
- If a future surface deserves tab-level permanence, it requires a new ADR and a new `tab_*` hook —
  it cannot be smuggled in as another Tools row without revisiting this decision.

## Alternatives considered

1. **Make Tools a sixth bottom tab.** Rejected: it duplicates Agents/Plugins/Jobs/Decisions under
   a second navigation model and breaks the five-tab QA contract.
2. **Keep `# Decisions/# Jobs/# Agents/# Plugins` rows in Chats and add labels only.** Rejected:
   plugin surfaces would still read as chat rows and the rail would remain ambiguous.
3. **Put plugin cards directly in the Tools drawer.** Rejected: the drawer would become a second
   Plugins screen and violate the "names destinations, never plugin instances" rule.
4. **Persist drawer open state or selected Tools row.** Rejected: the drawer is transient chrome;
   the only persistent navigation state is the selected tab, `filterCwd`, and per-tab stacks where
   ADR-0007 already requires them.
5. **Use `selected` only, without `bg_tab_active`/text color.** Rejected: active state must not be
   color-only; the accent bar is the non-color cue required by the shipped spec.

## Verification

- Static: `tabs[]` contains exactly `tab_chats`, `tab_agents`, `tab_plugins`, `tab_jobs`,
  `tab_you`; `showToolsDrawer()` creates `tools_drawer` + four `tools_row` children; no
  `tab_tools` id exists.
- Device/QA: cold start lands on Chats with `tab_chats` selected; tapping each bottom tab emits
  `EVENT tab.select index=<0..4>` and leaves exactly one active tab; `rail_tools` never appears
  selected; opening Tools emits `EVENT tools.open`; each drawer pick emits
  `EVENT tools.pick target=<agents|plugins|jobs|decisions>` and lands on the expected surface.
- Regression: `tools/m2_gate.py` still taps `tab_chats`, `tab_plugins`, and `tab_jobs` by id;
  `phase2b_driver.py` still matches `home all spaces` and `workspace <Title>` on the rail.
- Evidence: shipped dumps under `team/qa/evidence/ui-overhaul-closeout-20261105T145232Z/` show the
  rail labels, Tools drawer rows, and tab active paint.
