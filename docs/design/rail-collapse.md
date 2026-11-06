# Rail collapse — spec (Principal request, 2026-11-05)

**Principal:** *"the side bar needs to somehow hide and come back when needed. it looks way too cramped this way."*

**Decision (Lead/design-proxy — designer teammate degraded, recorded as a deviation):**
the rail becomes **collapsible and hidden by default**, with a persistent hamburger toggle in the
top bar. Content gets the full width when the rail is hidden; the rail comes back on demand.

## Interaction

1. **Hamburger in the top bar**, first item (start side), always visible, ≥48dp touch, icon =
   three-line menu (`@id/rail_toggle`). content-desc is state-aware: `show sidebar` when hidden,
   `hide sidebar` when shown.
2. **Tap → toggle.** Hidden: `@id/server_rail` visibility `GONE` → `@id/main_screen` (width 0dp,
   weight 1) takes the full width. Shown: rail returns at `@dimen/rail_width`.
3. **Default: hidden** on first launch after this build (`railHidden` pref default true). The
   Principal's complaint is the cramped look; the rail is now on demand.
4. **Auto-hide on selection** (phone pattern): choosing a workspace, Home, or Tools hides the rail
   after the action applies, so the content stays full width. Marker
   `EVENT rail.hide reason=select`.
5. **Persisted** in Prefs (`railHidden`), restored on launch. Marker `EVENT rail.toggle hidden=<bool>`.

## Hooks

Unchanged: `@id/server_rail`, `rail_host`, `rail_host_name`, `rail_scroll`, `rail_spaces`,
`rail_home`, `rail_home_icon`, `rail_tools`, `rail_tools_icon`, `rail_item`, `rail_item_label`,
`rail_selected_indicator`, `rail_tools_badge`, `main_screen`, `topbar*`, `bottom_tabs`,
`tools_drawer`/`drawer_scrim`, all `channel_*` ids and `channel_row:`/`channel_test_toggle:` descs.

Additive: `@id/rail_toggle` (top bar), `@drawable/ic_menu`, Prefs `railHidden`, markers
`EVENT rail.toggle hidden=<bool>` and `EVENT rail.hide reason=select`.

## A11y / QA

- Toggle: content-desc `show sidebar` / `hide sidebar`, focusable, ≥48dp.
- QA checks: fresh install → rail hidden, content full width; tap toggle → rail visible; tap again →
  hidden; restart → persisted; select a workspace with the rail visible → rail hides; 0 FATAL.
- No host mutation; client-only.

## Out of scope

Overlay/scrim drawer variant (inline collapse is the shipped shape), tablet-specific defaults.
