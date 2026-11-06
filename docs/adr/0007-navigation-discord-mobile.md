# ADR-0007 — Navigation: single Activity, five tabs, per-tab view stacks

- Status: **proposed**
- Deciders: architect, frontend, po (UX fit)
- Related: architecture §4, ADR-0001

## Context

The charter fixes the information architecture: **Discord mobile**, not a browser. Server
rail, channel list, chat pane, member drawer, bottom tabs. No WebView, no double top bars, no
page reloads. Target device: Galaxy the phone (6.4", 1080×2340), one-handed use.

The native stack has no androidx `Navigation`, no `FragmentContainerView`, no `ViewPager`
(ADR-0001). Navigation must therefore be explicit and cheap.

DSH's primitives map onto Discord's:

| Discord | DSH |
| --- | --- |
| Server rail | DSH host + workspaces |
| Channels | Sessions (grouped) + `#decisions`, `#jobs`, `#agents`, `#plugins` |
| Chat | Session transcript |
| Members | Lead, teammates, You |
| DMs | A teammate's session (it *is* a session) |
| Server settings | Plugin deck / connection |

## Decision

**One `MainActivity`, a five-tab bottom bar, and one explicit view stack per tab. Chat is a
pushed full-screen state, not a tab.**

```
MainActivity
  FrameLayout content
  BottomTabBar  Chats · Agents · Plugins · Jobs · You
  Back stacks   EnumMap<Tab, ArrayDeque<ViewState>>, one per tab

Chats stack:
  ChannelListState
    ├─ left drawer: ServerRail (host chip + workspaces)
    └─ list: search, specials, session groups (Active / Recent / Archived)
  ChatPaneState(sessionId)        ← pushed, full screen
    ├─ top bar: title, running dot, overflow (rename, cancel, fork, copy id)
    ├─ reversed message ListView
    ├─ composer (send / stop / attach)
    └─ right drawer: MemberDrawer (Lead, teammates, You → DM)
Agents stack:  AgentListState → AgentDetailState(agentId)
Plugins stack: PluginListState → PluginDetailState(entryId)
Jobs stack:    JobListState → JobDetailState(jobId) (output tail)
You stack:     YouState (host, connection, stream, cache, re-pair, about)
```

Rules:

1. **Back**: drawer open → close; ChatPane → ChannelList; non-root tab state → pop; Chats root
   → previous tab if switched within 3 s, else exit. Predictable, no history surprises.
2. **Tab switch preserves each tab's stack and scroll position.** Re-selecting the active tab
   pops it to root.
3. **Deep links** `dshnative://`: `s/<sessionId>` → Chats → ChatPane; `a/<name>` → Agents →
   detail; `p/plugins`; `j/<jobId>`. While unpaired, stash the link and replay after pairing.
4. **Responsive**: `<600dp` phone = single pane + drawers; `≥600dp` tablet = persistent 72dp
   rail, list + chat side by side, member drawer becomes a fixed right pane.
5. **No double top bars.** Native top bar only; the composer is docked above the IME and the
   message list resizes (`adjustResize`).
6. **State** lives in the repository, not in views: rotation and process death restore tab,
   stack ids, and scroll anchors from `SharedPreferences` + the SQLite cache (ADR-0005).

## Why

- **Discord's shape is the brief.** Users already know rail → channels → chat → members; DSH's
  domain slots in without inventing navigation.
- **One Activity + view swap is the cheapest correct model on a no-androidx stack.** It avoids
  Activity transitions (slow, flicker), avoids Fragment lifecycle bugs, and makes the back
  stack explicit and testable (a pure `ArrayDeque` of state ids).
- **Chat as a pushed state, not a tab,** matches Discord: the chat belongs to the channel you
  came from, and back returns to the list with scroll intact.
- **Per-tab stacks** are what makes the bottom bar feel native: switching tabs never loses
  your place, and re-tapping a tab returns to its root.
- **Drawers instead of extra tabs** for rail and members keeps five bottom tabs (the brief) and
  preserves one-handed reach on a 6.4" phone.

## Consequences

- **frontend** implements a small `Nav` class: `push(state)`, `pop()`, `switchTab(tab)`,
  `restore(bundle)`; states are plain Java objects with an `apply(ViewGroup)` render. No
  fragments.
- The ChatPane's message list is a **reversed `ListView`** with a prepend adapter (ADR-0004);
  the composer is a sibling, not a list header (headers break reversed-list scroll anchoring).
- The member drawer and rail share one `DrawerLayout`-like custom container (framework
  `DrawerLayout` is androidx — hand-roll with a scrim + translation X).
- Accessibility: 48dp touch targets on rail icons and tab items, `contentDescription` on every
  icon-only button, focus order follows the visual order.
- QA: back-stack matrix (each tab × each depth), rotation, deep link cold/warm, tablet layout,
  IME open/close while streaming.

## Fallback

If hand-rolled drawers/rail prove janky, the contained fallback is a **single-pane pager**
(horizontal swipe between ChannelList and ChatPane) for phones, keeping the same state model.
Do not adopt androidx `Navigation`/`DrawerLayout` — that violates ADR-0001; escalate to the
Lead instead.
