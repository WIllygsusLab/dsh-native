# ADR-0009 — Preset model pinning is applied as a session-local model selection

- Status: **accepted** (architect, 2026-11-02)
- Deciders: architect (ratified), backend (feasibility evidence), lead
- Related: [api-contract.md](../api-contract.md) §3.1 (`agentPreset`, `model`), §3
  (`POST /sessions`, `GET /presets`), [ADR-0003](0003-server-side-transcript-projection.md)

## Context

The Principal asked for **"the option to choose the model when starting a new session"**.
The phase-1/2 client implements a preset picker: `GET /presets` feeds the sheet, the picked
`id` rides to `POST /sessions` as `agentPreset`, and `Session.agentPreset` echoes it.

Backend's live investigation (2026-11-02) established that **preset-level model pinning is
structurally impossible in this DSH build**:

- a preset `agent-default-model` row either collides in the root realm (cordis provide throws,
  `cordis/lib/index.js:813`), or
- if isolated, the row is realm-private and invisible to the host-plane consumer
  (`api-session-controller/lib/types/agent.js:307/514`); the only cross-scope read is
  `serviceFor(agent, 'skills')`.

So a preset can never pin the model through its composition. The DSH-native mechanism that
does pin a model is a **session-local selection** (`session/selectModel` /
`selectForNextRequest`, which appends `model/selection`).

## Decision

**Keep the wire shape unchanged and emulate preset pinning at the API layer: on
`POST /sessions`, the mobile plugin resolves the preset's declared model row from the
`agentPresets` registry and applies it as a session-local model selection at create.**

- `POST /sessions {agentPreset}` is unchanged — the client sends a preset id, nothing else.
- `Session.agentPreset` echoes the preset (the session logs it).
- `Session.model` (modelSelection.next ?? lastUsed) shows the **applied** model — this is the
  QA assertion that the picker really yields the model.
- A preset that declares no model row (e.g. composition-only presets such as `minimal`/`ptc`)
  leaves the host default model in place; that is not an error.
- `GET /presets` MAY carry an **optional additive** `model: {provider, model} | null` per row
  (the declared model, null when none/ambiguous) so a future picker can show the model badge
  and QA can verify the mapping without a create. Not required for phase 2; clients ignore it
  if absent.
- Typed preset errors are unchanged (contract §3): a preset that cannot be applied — whether
  its composition fails to load or its declared model selection fails — is
  `409 agent-preset-unavailable`, `retryable:false`.

## Why

- **The product need is met with zero client change.** The picker already sends `agentPreset`;
  making the session actually run on the preset's declared model is a server-side application
  step, not a new wire concept.
- **Presets stay the product concept.** Exposing a separate `model` field on `POST /sessions`
  would double the wire surface, force a client change, and split "what the user picked" from
  "what the host applies".
- **It works with the isolate fix, not against it.** Isolated preset composition can be
  healthy (no collision); the model is then applied explicitly by the API, which is exactly
  what the host-plane consumer would otherwise never see.
- **Verifiable.** `Session.model` gives QA a device/API-level proof that the picked preset
  produced the expected model, without reading host internals.

## Alternatives considered

1. **Fix preset composition at the host plane.** Rejected: the collision/isolation is
   cross-realm by design in this DSH build; not a mobile-API fix.
2. **Add a `model` field to `POST /sessions` (client sends model directly).** Rejected:
   duplicates the preset concept, changes the client, and creates two sources of truth.
3. **Leave presets cosmetic (name/description only, no model effect).** Rejected: fails the
   Principal's ask — the picker would be a lie.
4. **Session-local selection at first prompt instead of create.** Rejected as the primary
   mechanism: the model must be in effect for the session's first turn and visible in
   `Session.model` immediately; doing it at create is simpler and observable.

## Consequences

- No client change; contract §3.1/§3 semantics clarified, not reshaped.
- Backend v1ah owns: registry model-row lookup, session-local selection at create, optional
  `model` projection, and the typed-error mapping.
- Risk: parsing the registry's declared model row is implementation detail; if it is
  unavailable the fallback is host default (documented, not an error). The optional `model`
  field must not block the pinning fix.
- Verification: create with `deepseek-v41` → `201`, `session.agentPreset=deepseek-v41`,
  `session.model` = the declared DeepSeek provider/model, **and a seeded self-answering prompt
  produces an assistant reply on that model** (a projection alone is not proof — see the
  qwen38 correction in [v1ah-contract-crosscheck.md](../v1ah-contract-crosscheck.md): a preset
  can project a model the gateway does not serve and then silently produce no reply). Default/
  omit keeps host default. `qwen38` was removed from this host's config on 2026-11-02; presets
  are deployment config, so verification is per-live-roster, never by hardcoded id.
