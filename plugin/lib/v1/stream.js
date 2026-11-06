/**
 * DSH Mobile API v1 — the single multiplexed SSE stream (contract §4, ADR-0002).
 *
 * One process-wide hub owns:
 *   - the global integer frame id (monotonic, +1 per frame, resets on restart);
 *   - a 500-frame replay ring (assistant.delta frames are ephemeral and never
 *     enter the ring);
 *   - the live client set;
 *   - the host sources: `sessionController.control()` (host-wide projections),
 *     one `sessionController.follow({assistantStream:true})` per subscribed
 *     session (bounded LRU, cap 20), and the `api-session/*` / `agent/*`
 *     Cordis events.
 *
 * `streamResponse()` returns a fetch-style streaming Response so a route can be
 * registered through `ctx.connection.fetch.register` and inherits the /api
 * fence (cookie + Host/Origin) from dsh-client-connection.
 *
 * Wire rules (contract §4.6):
 *   R1 every frame carries `id:`; ids increase by exactly 1 within one
 *      connection (hello/resync consume ids so the first live frame continues
 *      the sequence).
 *   R2 reconnect with `?since=` (or `Last-Event-ID`): replay `id > since` when
 *      the ring still holds it, else `resync`.
 *   R7 `ping {t}` every heartbeatMs; pings are ringed.
 *
 * @module dsh-plugin-mobile/v1/stream
 */
import { randomUUID } from 'node:crypto'

export const HEARTBEAT_MS = 15000
export const REPLAY_WINDOW = 500
export const FOLLOW_CAP = 20
const RECENT_SESSIONS = 10
const CONTROL_REFRESH_MS = 250

function encodeFrame(frame) {
  return `id: ${String(frame.id)}\nevent: ${frame.event}\ndata: ${JSON.stringify(frame.data)}\n\n`
}

/** Flatten one assistant-stream chunk into the contract's {text?, reasoning?}. */
export function flattenChunk(chunk) {
  const out = {}
  if (typeof chunk === 'string') return { text: chunk }
  if (chunk === null || typeof chunk !== 'object') return out
  const text = chunk.text ?? chunk.delta?.text ?? chunk.textDelta ?? chunk.delta
  if (typeof text === 'string' && text.length > 0) out.text = text
  const reasoning = chunk.reasoning ?? chunk.delta?.reasoning ?? chunk.reasoningDelta
  if (typeof reasoning === 'string' && reasoning.length > 0) out.reasoning = reasoning
  return out
}

/** Minimal Agent row for `agent.upsert` (contract §5.1 shape). */
function agentRow(agent, status) {
  const id = String(agent?.id ?? agent?.sessionId ?? '')
  return {
    agentId: id,
    sessionId: id.length > 0 ? id : null,
    name: id.slice(0, 8),
    role: 'subagent',
    status: status ?? (agent?.status === 'running' ? 'running' : 'inactive'),
    description: null,
    teamId: null,
    model: null,
    avatarSeed: id.slice(0, 8),
    lastActiveAt: Date.now(),
  }
}

/**
 * Create the process-wide stream hub for one plugin instance.
 * @param ctx - Cordis plugin context (connection/sessionController/agents).
 * @param options - projectMessage(record), projectSession(summary), heartbeatMs, replayWindow.
 * @returns hub with emit/start/stop/replayFrom and the follow set.
 */
export function createStreamHub(ctx, options = {}) {
  const heartbeatMs = options.heartbeatMs ?? HEARTBEAT_MS
  const replayWindow = options.replayWindow ?? REPLAY_WINDOW
  const projectMessage = options.projectMessage ?? (() => null)
  const projectSession = options.projectSession ?? ((summary) => summary ?? null)

  const hub = {
    streamId: randomUUID(),
    seq: 0,
    ring: [],
    clients: new Set(),
    follows: new Map(),
    followOrder: [],
    expectedUserMessages: new Map(),
    followSyncQueued: false,
    disposers: [],
    timers: [],
    started: false,
    stopped: false,
    heartbeatMs,
    replayWindow,
    projectMessage,
    projectSession,
    /** Reserve a per-connection frame id without broadcasting or ring-buffering. */
    nextId() {
      hub.seq += 1
      return hub.seq
    },
    /** Assign the next global id, ring it (unless ephemeral), and broadcast. */
    emit(event, data, { replay = true } = {}) {
      const frame = { id: ++hub.seq, event, data }
      if (replay) {
        hub.ring.push(frame)
        if (hub.ring.length > replayWindow) hub.ring.shift()
      }
      for (const client of hub.clients) client.send(frame)
      return frame.id
    },
    /** Remember one accepted prompt so its durable user message is echoed once. */
    expectUserMessage(sessionId, requestId) {
      if (typeof sessionId !== 'string' || typeof requestId !== 'string' || requestId.length === 0) return
      hub.expectedUserMessages.set(`${sessionId}\u0000${requestId}`, true)
    },
    /** Ensure this session has an open follow now (post-send determinism). */
    ensureFollow(sessionId) {
      void ensureFollowSession(ctx, hub, String(sessionId))
    },
    /** Oldest id still replayable (R2). */
    replayFrom() {
      return hub.ring.length > 0 ? hub.ring[0].id : Math.max(1, hub.seq)
    },
    start() {
      if (hub.started || hub.stopped) return
      hub.started = true
      subscribeSources(ctx, hub)
      const timer = setInterval(() => {
        if (hub.clients.size > 0) hub.emit('ping', { t: Date.now() })
      }, heartbeatMs)
      timer.unref?.()
      hub.timers.push(timer)
    },
    stop() {
      if (hub.stopped) return
      hub.stopped = true
      for (const timer of hub.timers) clearInterval(timer)
      hub.timers.length = 0
      for (const dispose of hub.disposers) {
        try {
          dispose?.()
        } catch {
          // Disposal is best effort.
        }
      }
      hub.disposers.length = 0
      for (const entry of hub.follows.values()) {
        try {
          entry.abort()
        } catch {
          // Already stopped.
        }
      }
      hub.follows.clear()
      hub.clients.clear()
    },
  }
  return hub
}

/** Subscribe the host sources that feed the hub. */
function subscribeSources(ctx, hub) {
  const on = (event, listener) => {
    try {
      const dispose = ctx.on(event, listener)
      if (typeof dispose === 'function') hub.disposers.push(dispose)
    } catch {
      // A host without the event owner simply has no such source.
    }
  }

  // Session roster: added / removed / status are direct projections.
  on('api-session/added', (summary) => {
    const session = safeProject(hub, summary)
    if (session !== null) hub.emit('session.upsert', session)
    scheduleFollowSync(ctx, hub)
  })
  on('api-session/removed', (sessionId) => {
    hub.emit('session.removed', { sessionId: String(sessionId) })
    closeFollow(hub, String(sessionId))
  })
  on('api-session/status', (sessionId, running) => {
    hub.emit('session.status', { sessionId: String(sessionId), running: running === true })
    if (running === true) scheduleFollowSync(ctx, hub)
  })
  on('api-session/activity', (sessionId) => {
    void refreshSummary(ctx, hub, String(sessionId))
  })
  on('api-session/error', () => {
    // Session.lastError rides the next session.upsert; no dedicated frame.
  })

  // Agent roster changes.
  on('agent/created', ({ agent }) => hub.emit('agent.upsert', agentRow(agent, 'inactive')))
  on('agent/status', ({ agent }) => hub.emit('agent.upsert', agentRow(agent)))
  on('agent/disposed', ({ agent }) => hub.emit('agent.upsert', agentRow(agent, 'inactive')))

  // Host-wide control stream: refresh the affected session row on a projection
  // change that a phone actually renders. The baseline is bootstrap's job.
  const controller = ctx.get('sessionController')
  if (controller !== undefined && typeof controller.control === 'function') {
    const abort = new AbortController()
    hub.disposers.push(() => abort.abort())
    void (async () => {
      try {
        for await (const frame of controller.control(abort.signal)) {
          if (hub.stopped) break
          if (frame?.type !== 'projection') continue
          const key = frame.key
          if (key !== 'title' && key !== 'sessionListMetadata' && key !== 'modelSelection' && key !== 'agentTeam') continue
          void refreshSummary(ctx, hub, String(frame.sessionId))
        }
      } catch {
        // The control generation ended; the hub keeps serving its other sources.
      }
    })()
  }

  // Initial follow set (live-agent sessions + most recent), then keep it fresh.
  scheduleFollowSync(ctx, hub)
  const syncTimer = setInterval(() => void scheduleFollowSync(ctx, hub), 30000)
  syncTimer.unref?.()
  hub.timers.push(syncTimer)
}

const pendingRefreshes = new Map()

/** Re-read one session row and emit the full `Session` as `session.upsert`. */
async function refreshSummary(ctx, hub, sessionId) {
  if (pendingRefreshes.has(sessionId)) return
  pendingRefreshes.set(sessionId, true)
  try {
    await new Promise((resolve) => setTimeout(resolve, CONTROL_REFRESH_MS))
    const controller = ctx.get('sessionController')
    if (controller === undefined) return
    const value = await controller.list({}, undefined)
    const summary = (value?.items ?? []).find((row) => String(row.sessionId) === sessionId)
    if (summary === undefined) return
    const session = safeProject(hub, summary)
    if (session !== null) hub.emit('session.upsert', session)
  } catch {
    // A failed refresh is a missing frame, never a stream failure.
  } finally {
    pendingRefreshes.delete(sessionId)
  }
}

function safeProject(hub, summary) {
  try {
    const projected = hub.projectSession(summary)
    return projected ?? null
  } catch {
    return null
  }
}

let followSyncPending = false

/** Reconcile the follow set: live-agent sessions + most recent, cap 20. */
async function scheduleFollowSync(ctx, hub) {
  if (hub.stopped) return
  if (followSyncPending) {
    hub.followSyncQueued = true
    return
  }
  followSyncPending = true
  try {
    const controller = ctx.get('sessionController')
    if (controller === undefined || typeof controller.follow !== 'function') return
    const value = await controller.list({}, undefined)
    const summaries = Array.isArray(value?.items) ? value.items : []
    const live = new Set()
    try {
      const registry = ctx.get('agents')
      for (const agent of registry?.list?.() ?? []) live.add(String(agent?.id ?? agent?.sessionId ?? ''))
    } catch {
      // No agent registry: fall back to running sessions only.
    }
    const recent = [...summaries]
      .sort((left, right) => Number(right.updatedAt ?? 0) - Number(left.updatedAt ?? 0))
      .slice(0, RECENT_SESSIONS)
    const targets = new Set()
    for (const summary of summaries) {
      const sessionId = String(summary.sessionId)
      if (live.has(sessionId) || summary.running === true) targets.add(sessionId)
    }
    for (const summary of recent) targets.add(String(summary.sessionId))
    for (const sessionId of targets) {
      if (hub.follows.size >= FOLLOW_CAP) break
      if (hub.follows.has(sessionId)) continue
      openFollow(ctx, hub, sessionId, summaries.find((row) => String(row.sessionId) === sessionId))
    }
  } catch {
    // The next tick retries; a failed sync never fails the stream.
  } finally {
    followSyncPending = false
    if (hub.followSyncQueued) {
      hub.followSyncQueued = false
      void scheduleFollowSync(ctx, hub)
    }
  }
}

/** Open a follow immediately for one session (used after an accepted send). */
async function ensureFollowSession(ctx, hub, sessionId) {
  if (hub.stopped || hub.follows.has(sessionId)) return
  // A post-send session must be followed now even when the cap is saturated:
  // evict the oldest follow to make room (the set is reconciled again later).
  while (hub.follows.size >= FOLLOW_CAP) {
    const oldest = hub.followOrder.shift()
    if (oldest === undefined) break
    const entry = hub.follows.get(oldest)
    try {
      entry?.abort()
    } catch {
      // Already stopped.
    }
    hub.follows.delete(oldest)
  }
  const controller = ctx.get('sessionController')
  if (controller === undefined) return
  let summary
  try {
    const value = await controller.list({}, undefined)
    summary = (value?.items ?? []).find((row) => String(row.sessionId) === sessionId)
  } catch {
    summary = undefined
  }
  if (hub.follows.has(sessionId)) return
  openFollow(ctx, hub, sessionId, summary)
}

/** Open one durable follow for a session and project its frames into the hub. */
function openFollow(ctx, hub, sessionId, summary) {
  const controller = ctx.get('sessionController')
  if (controller === undefined) return
  const abort = new AbortController()
  hub.follows.set(sessionId, { abort: () => abort.abort() })
  hub.followOrder.push(sessionId)
  const address = summary?.origin === 'subagent'
    ? {
        kind: 'subagent',
        parentSessionId: String(summary.parentSessionId ?? ''),
        childSessionId: sessionId,
        mode: 'unknown',
      }
    : { kind: 'session', sessionId }
  void (async () => {
    try {
      for await (const frame of controller.follow({ address, assistantStream: true }, abort.signal)) {
        if (hub.stopped || abort.signal.aborted) break
        if (frame?.type === 'snapshot') {
          // Deterministic post-send echo: the accepted user message may already
          // be in the opening snapshot (prompt admitted before follow opened).
          // Emit it once as message.append so the client reconciles its
          // optimistic row and fires send.ok/send.echo.
          const records = Array.isArray(frame.records) ? frame.records : []
          for (const record of records) {
            const event = record?.event
            if (event?.type !== 'user/message') continue
            const rpcId = event.data?.source?.rpcId
            if (typeof rpcId !== 'string') continue
            const key = `${sessionId}\u0000${rpcId}`
            if (!hub.expectedUserMessages.has(key)) continue
            hub.expectedUserMessages.delete(key)
            let echoed = null
            try {
              echoed = hub.projectMessage(record, sessionId)
            } catch {
              echoed = null
            }
            if (echoed !== null) hub.emit('message.append', { sessionId, message: { ...echoed, sessionId } })
          }
          continue
        }
        if (frame?.type === 'event') {
          let message = null
          try {
            message = hub.projectMessage(frame, sessionId)
          } catch {
            message = null
          }
          if (message !== null) hub.emit('message.append', { sessionId, message: { ...message, sessionId } })
          continue
        }
        if (frame?.type !== 'assistant-stream') continue
        const inner = frame.frame
        if (inner?.type === 'chunk') {
          const flat = flattenChunk(inner.chunk)
          if (flat.text === undefined && flat.reasoning === undefined) continue
          hub.emit('assistant.delta', {
            sessionId,
            attemptId: String(inner.attemptId ?? ''),
            seq: Number(inner.index ?? 0),
            index: Number(inner.index ?? 0),
            ...flat,
          }, { replay: false })
        } else if (inner?.type === 'end') {
          hub.emit('assistant.done', {
            sessionId,
            attemptId: String(inner.attemptId ?? ''),
            seq: Number(inner.index ?? 0),
            outcome: inner.outcome ?? { kind: 'abandoned' },
          }, { replay: false })
        }
      }
    } catch {
      // Cancelled follow (session closed, cap eviction, plugin unload).
    } finally {
      hub.follows.delete(sessionId)
      const index = hub.followOrder.indexOf(sessionId)
      if (index >= 0) hub.followOrder.splice(index, 1)
    }
  })()
}

function closeFollow(hub, sessionId) {
  const entry = hub.follows.get(sessionId)
  if (entry === undefined) return
  try {
    entry.abort()
  } catch {
    // Already stopped.
  }
  hub.follows.delete(sessionId)
}

/**
 * Build the fetch-style streaming Response for one `/stream` request.
 * @param ctx - plugin context (only for parity with the hub factory).
 * @param hub - hub from createStreamHub.
 * @param request - the WHATWG Request received by the fetch route.
 * @returns a 200 text/event-stream Response.
 */
export function streamResponse(ctx, hub, request) {
  hub.start()
  const url = new URL(request.url)
  const headerSince = request.headers.get('last-event-id')
  const rawSince = url.searchParams.get('since') ?? headerSince
  let since = 0
  if (rawSince !== null && rawSince !== '') {
    const parsed = Number(rawSince)
    if (Number.isFinite(parsed) && parsed >= 0) since = Math.trunc(parsed)
  }
  const encoder = new TextEncoder()
  let controllerRef
  let closed = false
  const client = {
    send(frame) {
      if (closed || controllerRef === undefined) return
      try {
        controllerRef.enqueue(encoder.encode(encodeFrame(frame)))
      } catch {
        closed = true
      }
    },
  }
  const close = () => {
    if (closed) return
    closed = true
    hub.clients.delete(client)
    try {
      controllerRef?.close()
    } catch {
      // The carrier already closed the body.
    }
  }
  const stream = new ReadableStream({
    start(controller) {
      controllerRef = controller
      const replayable = since > 0 && since >= hub.replayFrom() - 1 && since <= hub.seq
      // hello id: continue the sequence so the first live frame is hello.id + 1.
      const helloId = replayable && since > 0 ? since : hub.nextId()
      controller.enqueue(encoder.encode(encodeFrame({
        id: helloId,
        event: 'hello',
        data: {
          streamId: hub.streamId,
          serverTime: Date.now(),
          replayFrom: hub.replayFrom(),
          heartbeatMs: hub.heartbeatMs,
        },
      })))
      // Per-connection control frames consume a global id. Mirror each one to
      // every other live connection as a benign ping carrying the same id, so
      // no observer sees an id gap (contract R1: +1 per frame, one process).
      const mirrorId = (id) => {
        if (replayable && since > 0) return
        for (const other of hub.clients) other.send({ id, event: 'ping', data: { t: Date.now() } })
      }
      mirrorId(helloId)
      if (since > 0) {
        if (since > hub.seq) {
          const id = hub.nextId()
          client.send({ id, event: 'resync', data: { reason: 'host-restarted' } })
          mirrorId(id)
        } else if (since < hub.replayFrom() - 1) {
          const id = hub.nextId()
          client.send({ id, event: 'resync', data: { reason: 'gap' } })
          mirrorId(id)
        } else {
          for (const frame of hub.ring) {
            if (frame.id > since) client.send(frame)
          }
        }
      }
      hub.clients.add(client)
      request.signal?.addEventListener('abort', close, { once: true })
    },
    cancel() {
      close()
    },
  })
  return new Response(stream, {
    status: 200,
    headers: {
      'content-type': 'text/event-stream; charset=utf-8',
      'cache-control': 'no-store',
      'connection': 'keep-alive',
      'x-accel-buffering': 'no',
    },
  })
}
