# ADR-0008 — Session group order: precedence order (Team second), not Team last

- Status: **accepted** (architect ratified 2026-11-02; contract §3.4 amended same day; QA row-order cases rebase)
- Deciders: architect (ratified), po (UX fit, concurred), lead
- Related: [api-contract.md](../api-contract.md) §3.4, [m1-plan.md](../m1-plan.md) §0,
  usability review §1.3/§2.2 (R10), [r10-ratification.md](../r10-ratification.md)

## Context

The Chats list renders status-first groups. Two documents disagree about the order:

- **Contract §3.4** says: *"SESSIONS = Waiting → Running → Recent; then DIRECT MESSAGES
  (Team)"* — i.e. Team **last**, after all session groups.
- **m1-plan.md §0** paraphrases the same rule as *"Waiting > Team > Running > Recent"*.
- **Shipped code** (`MainActivity.channelRows`) implements first-match precedence:
  `Waiting → Team → Running → Recent` — Team **second**. Device evidence
  (`channels.xml`, 2026-11-01) shows TEAM directly under CHANNELS.

So the contract text and the plan paraphrase already disagree; the code followed the plan.
The usability review (task-1, R10) flagged the drift and leaned toward precedence order: at
80 sessions, burying a live Lead/teammate conversation under dozens of idle Recent rows is
the wrong default for a triage-first list. QA row-order cases assert whichever order is
declared, so the question must be settled once.

## Decision

**Adopt precedence order — `Waiting → Team → Running → Recent` — as the contract of record.**

- Amend api-contract §3.4 to state the precedence order (Team second) and to say that
  first-match wins (a session appears in exactly one group).
- No client code change: `channelRows()` already implements this.
- Unchanged: empty groups never render; Team nesting (Lead first, children adjacent, depth
  capped at 2); `EVENT channel.group` marker names; `@id/channel_group_header` rows.
- QA row-order cases are re-baselined to this order in the next gate.

### Ratification record (2026-11-02)

- **Accepted by architect.** PO concurred (UX fit: triage-first ordering); Lead informed.
- Contract §3.4 amended in place: the "Display order" bullet now states the precedence order
  (Team second) and explicitly supersedes the old "Team at the bottom" wording. The precedence
  table itself was already correct and is unchanged.
- **Client code: no change.** `MainActivity.channelRows()` already implements
  `Waiting → Team → Running → Recent` first-match and already cites ADR-0008 in-source; the
  shipped subject needs no rebuild for this call.
- **QA: row-order assertions change.** M1-06 and M1-06b expect "Team at the bottom" today and
  must be rebased to "Team second"; `EVENT channel.group` names and
  `@id/channel_group_header` rows are unchanged. No re-gate is required for the app — the
  current 8/8 gates stand.
- Evidence for the shipped order: `team/qa/evidence/m2-gate-20261101T152026Z/channels.xml`
  (TEAM directly under CHANNELS), review §1.3/§2.2.

## Consequences

- One contract edit + QA case rebase; zero code risk.
- Active team conversations stay above idle recents, matching the "what needs a human now"
  default the grouping was designed for.
- The plan/contract drift is closed in the contract's favour of the plan.

## Alternatives considered

1. **Move `addTeamGroup` last (follow the current contract text).** Rejected: at 80 sessions
   it pushes a live teammate conversation below every idle Recent row; it also contradicts
   m1-plan §0, so it would require editing the plan instead.
2. **Separate `DIRECT MESSAGES` section after sessions (literal Discord model).** Rejected
   in contract §3.4 already: Team sessions are one representation, not duplicated as DMs.

## Follow-up

- ~~Architect/PO: ratify (status → accepted) and apply the §3.4 text edit.~~ **DONE 2026-11-02** —
  status accepted; contract §3.4 display-order bullet amended to `Waiting → Team → Running →
  Recent` (Team second); precedence table unchanged. Ratification record:
  [r10-ratification.md](../r10-ratification.md).
- QA: rebase row-order assertions (M1-06, M1-06b) to Team second. No app change to re-gate;
  `channelRows()` already implements this order.
