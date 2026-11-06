# ADR-0011 — Rail scroll is view-owned; rail renders are guarded to the latest bootstrap

- Status: **accepted** (architect, 2026-11-05) — ratifies the shipped UI-overhaul rail behavior on frozen subject `454c7f44…`; no contract change
- Deciders: architect, frontend, lead, po
- Related: [api-contract.md](../api-contract.md) §3.0/§3.4/§10, [ADR-0007](0007-navigation-discord-mobile.md),
  [rail-ia-decision.md](../rail-ia-decision.md), [ui-overhaul-hooks.md](../ui-overhaul-hooks.md),
  [ui-overhaul-spec.md](../../design/ui-overhaul-spec.md) §2

## Context

The UI overhaul turned the rail into a workspace navigator: a static `rail_scroll` `ScrollView`
contains the dynamic `rail_spaces` `LinearLayout`, followed by the pinned Home/Tools rows. Workspace
count is host-derived and variable, so the workspace block must scroll without moving Home/Tools or
resetting the user's place on every render.

`renderRail()` currently rebuilds only the children of `rail_spaces`; it does not recreate
`rail_scroll`, `rail_home`, `rail_tools`, or the rail chrome. That split matters: scroll state belongs
to the `ScrollView`, while row identity belongs to the latest committed bootstrap. If either side is
rebuilt together, a bootstrap refresh, offline cached render, resync, or badge update can drop the
rail back to the top or paint rows from a superseded workspace set.

The stale-render risk is real because bootstrap application is asynchronous (`io.execute` →
`runOnUiThread`) and stream/resync paths can call `load()` again. The guard must therefore be
structural, not a UI convenience: rail rows may be painted only from the currently committed
`Models.Bootstrap`, on the UI thread, after `data` has been replaced as one object.

## Decision

**Rail scroll position is owned by `@id/rail_scroll`; rail row content is owned by the latest
committed bootstrap and may be rebuilt only through `renderRail()` on the UI thread.**

1. `rail_scroll` is static layout. `renderRail()` may remove and re-add children of `rail_spaces`
   only; it must never recreate `rail_scroll`, `rail_spaces`, `rail_home`, `rail_tools`, or the rail
   container in response to data refresh.
2. Scroll restoration is by ownership, not by persistence. The rail is one transient list, not a
   per-workspace navigation stack; do not store rail `scrollY` in `Prefs`, SQLite, or bootstrap.
   If a rebuild clamps the offset because the workspace set shrank, that is correct behavior.
3. `renderRail()` reads `data.workspaces` at call time. It must not capture a workspace list in an
   async callback, patch rows incrementally from stream frames, or render a partially applied
   bootstrap. Workspace rows appear only after `Models.Bootstrap.from(...)` has produced a complete
   object and `data = bootstrap` has run on the UI thread.
4. A stale render is defined as any rail paint whose workspace rows do not match the currently
   committed `data.workspaces`. The guard is: `render()` returns when `data == null`; `renderRail()`
   returns when `railSpaces == null`; all row creation and click handlers run on the UI thread;
   click handlers capture only the immutable `workspace.path` needed for `filterCwd`, not a mutable
   bootstrap snapshot.
5. Workspace activation remains derived, not stored separately: Home is active iff `filterCwd ==
   null`; a workspace row is active iff `filterCwd.equals(workspace.path)`; Tools is never active.
   Recomputing this on every `renderRail()` prevents a stale "selected" pill after a filter change.
6. No API/contract amendment. `api-contract.md` §3.0 already derives spaces client-side and §10 keeps
   additive changes non-breaking; this ADR changes only view ownership and render ordering.

## Consequences

- Bootstrap refresh, offline cached render, resync, rotation/config change, and badge refresh cannot
  reset rail scroll by recreating the `ScrollView`.
- A superseded bootstrap cannot leave orphan rail rows because rows are children of `rail_spaces`
  and are replaced only by a render that reads the committed `data` object.
- The rail remains a pure view filter over `filterCwd`; it does not become per-space navigation
  state (that remains a separate M4 candidate).
- Verification can assert structure (`rail_scroll` present, `rail_spaces` rebuilt, pinned rows
  unchanged) rather than depending on pixel-identical scroll offsets.

## Alternatives considered

1. **Persist rail `scrollY` in `Prefs`.** Rejected: the rail is a single transient navigator, not a
   saved route; persistence adds stale-state risk across host/workspace changes and process death
   with no user-visible payoff.
2. **Rebuild the whole rail including `rail_scroll` on every render.** Rejected: it would reset
   scroll and recreate static chrome for no benefit.
3. **Patch rail rows incrementally from stream frames.** Rejected: the stream has no workspace
   upsert contract; workspaces are bootstrap-derived, so incremental patching would create a second,
   drift-prone source of truth.
4. **Cap the workspace list at three rows like the old rail.** Rejected: it hides workspaces and
   violates the shipped AC that all host workspaces remain reachable.

## Verification

- Static: `activity_main.xml` keeps `rail_scroll` outside `rail_spaces`; `renderRail()` only calls
  `railSpaces.removeAllViews()` / `addView(...)`; pinned Home/Tools rows are not rebuilt.
- Device/QA: cold start shows every workspace label and `workspace <Title>` desc; with overflow,
  `rail_scroll` scrolls while Home/Tools stay pinned; workspace tap sets `filterCwd` and lands on
  Chats; Home clears the filter; Tools opens `tools_drawer`.
- Refresh: bootstrap reload/resync and offline cached render do not duplicate, orphan, or reorder
  rail rows; active pill always matches `filterCwd`.
- Regression: PO AC-1/AC-2/AC-3/AC-4 evidence in `team/product/ui-overhaul-acceptance.md` §3 and the
  close-out dumps under `team/qa/evidence/ui-overhaul-closeout-20261105T145232Z/`.
