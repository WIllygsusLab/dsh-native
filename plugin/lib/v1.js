/**
 * DSH Mobile API v1 — read-only projections for the native client.
 *
 * Every route is registered inside Connection's authenticated `/api` fence.
 * This module projects existing Cordis services; it never invents a store.
 *
 *   GET /api/mobile/v1/ping
 *   GET /api/mobile/v1/bootstrap
 *   GET /api/mobile/v1/sessions
 *   GET /api/mobile/v1/presets
 *   GET /api/mobile/v1/messages?sessionId=...&cursor=...&limit=...
 *   GET /api/mobile/v1/plugins
 *   GET /api/mobile/v1/agents
 *   GET /api/mobile/v1/jobs
 *   GET /api/mobile/v1/decisions
 *   GET /api/mobile/v1/stream
 *
 * v1ah (0.7.1): agent-preset client errors are typed, never 500 —
 *   400 bad-request             { field: 'agentPreset' }  (non-string)
 *   400 unsupported-capability  (no registry on this host)
 *   404 agent-preset-not-found  { presetId }
 *   409 agent-preset-unavailable { presetId, reason } (roster broken or mount failure)
 * and GET /presets rows carry `broken: boolean` + `reason: string|null`
 * (contract §3) instead of the registry's raw diagnostic string.
 *
 * v1ai (0.7.2): workspace attachment (contract §3). `SessionController.create`
 * attaches to the host workspace registry ONLY when `workspaceId` is supplied,
 * so a cwd-only phone create never appeared in the DSH web GUI's workspace
 * list. POST /sessions now resolves a `cwd` that exactly equals a registered
 * workspace path to that workspace's real id, attaches after an omit-both
 * create, and treats attachment as best-effort (still 201, logged host-side, no
 * new wire field). GET /bootstrap runs a bounded, idempotent, membership-only
 * reconcile for sessions whose cwd already equals a registered workspace path,
 * and projects registered workspaces from the registry with their real
 * `sessionIds` (cwd-derived synthetic entries only for unmatched paths).
 *
 * @module dsh-plugin-mobile/v1
 */

import { randomUUID } from 'node:crypto'
import { createStreamHub, streamResponse } from './v1/stream.js'

const API = '/api/mobile/v1'
const SEND_DEDUPE_MS = 10 * 60 * 1000
const sentRequests = new Map()
let activeStreamHub = null

/** Make a just-accepted send observable on the SSE stream deterministically. */
function noteSendAccepted(sessionId, requestId) {
  if (activeStreamHub === null) return
  activeStreamHub.expectUserMessage(sessionId, requestId)
  activeStreamHub.ensureFollow(sessionId)
}

const LIMITS = {
  sessionsPageSize: 50,
  messagesPageSize: 30,
  jobsOutputFrameBytes: 65536,
  // Durable workspace attaches per bootstrap call; the reconcile continues on
  // the next bootstrap, newest sessions first, so a large backfill stays bounded.
  workspaceReconcilePerCall: 10,
}

function json(data, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: {
      'content-type': 'application/json; charset=utf-8',
      'cache-control': 'no-store',
    },
  })
}

function notFound(code, message) {
  return failure(code, message, 404, false)
}

function failure(code, message, status = 400, retryable = false, details = {}) {
  return json({ ok: false, error: { code, message, retryable, details } }, status)
}

function textOf(content) {
  if (!Array.isArray(content)) return ''
  return content
    .filter((block) => block?.type === 'text' && typeof block.text === 'string')
    .map((block) => block.text)
    .join('\n')
    .trim()
}

function truncate(value, max) {
  if (typeof value !== 'string') return ''
  const line = value.replace(/\s+/gu, ' ').trim()
  return line.length > max ? `${line.slice(0, max - 1)}…` : line
}

function pickTitle(summary) {
  const values = summary.projections?.values ?? {}
  const title = values.title
  if (typeof title === 'string' && title.length > 0 && !title.startsWith('<system-reminder>')) {
    return truncate(title, 80)
  }
  const label = values.subagent?.label
  if (typeof label === 'string' && label.length > 0) return truncate(label, 80)
  const outline = Array.isArray(values.turnOutline) ? values.turnOutline : []
  const prompt = outline.length > 0 ? outline[0]?.prompt : undefined
  if (typeof prompt === 'string' && prompt.length > 0) return truncate(prompt, 80)
  return 'Untitled session'
}

function pickPreview(summary) {
  const values = summary.projections?.values ?? {}
  const outline = Array.isArray(values.turnOutline) ? values.turnOutline : []
  for (let index = outline.length - 1; index >= 0; index -= 1) {
    const row = outline[index]
    const text = typeof row?.response === 'string' && row.response.length > 0
      ? row.response
      : row?.prompt
    if (typeof text === 'string' && text.length > 0) return truncate(text, 120)
  }
  return null
}

function modelOf(summary) {
  const values = summary?.projections?.values ?? {}
  const selection = values.modelSelection?.next ?? values.modelSelection?.lastUsed
  if (selection !== null && typeof selection === 'object' && typeof selection.provider === 'string' && typeof selection.model === 'string') {
    return { provider: selection.provider, model: selection.model }
  }
  return null
}

function readWorkspaces(ctx) {
  try {
    const registry = ctx.get('workspaceRegistry')
    if (registry === undefined || typeof registry.list !== 'function') return []
    return (registry.list() ?? []).map((workspace) => ({
      workspaceId: String(workspace.id),
      title: typeof workspace.title === 'string' ? workspace.title : String(workspace.path ?? ''),
      path: typeof workspace.path === 'string' ? workspace.path : '',
      sessionIds: Array.isArray(workspace.sessionIds) ? workspace.sessionIds.map(String) : [],
      createdAt: Number.isFinite(Date.parse(workspace.createdAt)) ? Date.parse(workspace.createdAt) : null,
      updatedAt: Number.isFinite(Date.parse(workspace.updatedAt)) ? Date.parse(workspace.updatedAt) : null,
    }))
  } catch {
    return []
  }
}

/**
 * Workspace path spelling used by the contract (§3 POST /sessions): a string
 * comparison with trailing slashes trimmed. Deliberately no filesystem
 * resolution, symlink expansion, or prefix matching — a path merely inside a
 * workspace does not attach.
 */
function normalizeWorkspacePath(path) {
  if (typeof path !== 'string' || path.length === 0) return null
  const trimmed = path.replace(/\/+$/u, '')
  return trimmed.length === 0 ? '/' : trimmed
}

/** Registered workspaces plus a normalized-path index; empty when the registry is absent. */
function workspaceIndex(ctx) {
  const registry = ctx.get('workspaceRegistry')
  if (registry === undefined || typeof registry.list !== 'function') {
    return { registry: undefined, workspaces: [], byPath: new Map() }
  }
  let workspaces = []
  try {
    workspaces = registry.list() ?? []
  } catch {
    workspaces = []
  }
  const byPath = new Map()
  for (const workspace of workspaces) {
    const normalized = normalizeWorkspacePath(workspace?.path)
    if (normalized === null) continue
    byPath.set(normalized, workspace)
  }
  return { registry, workspaces, byPath }
}

/**
 * Attach a session to the workspace whose registered path equals its cwd.
 *
 * A cwd-only create is never attached by `SessionController.create` (it calls
 * `workspace.attachSession` only when a `workspaceId` was supplied), which is
 * why the DSH web GUI — registry membership — could not see phone-created
 * sessions. Best-effort: callers log a failure and still return the session.
 * @returns `null` when no workspace matches, else `{workspaceId, attached, already?, error?}`.
 */
async function attachToMatchingWorkspace(ctx, sessionId, cwd) {
  const normalized = normalizeWorkspacePath(cwd)
  if (normalized === null) return null
  const workspace = workspaceIndex(ctx).byPath.get(normalized)
  if (workspace === undefined) return null
  const workspaceId = String(workspace.id)
  const members = Array.isArray(workspace.sessionIds) ? workspace.sessionIds.map(String) : []
  if (members.includes(String(sessionId))) return { workspaceId, attached: true, already: true }
  try {
    await workspace.attachSession(sessionId)
    return { workspaceId, attached: true, already: false }
  } catch (error) {
    return { workspaceId, attached: false, error: String(error?.message ?? error) }
  }
}

/** Failed reconcile attempts, retried at most every 10 minutes (bounded memory). */
const reconcileFailures = new Map()
const RECONCILE_RETRY_MS = 10 * 60 * 1000
/** Wall-clock budget for one bootstrap's reconcile, well inside the §2 budget. */
const RECONCILE_TIME_BUDGET_MS = 250

/**
 * Repair workspace membership for sessions whose cwd exactly equals a
 * registered workspace path but which are missing from that workspace's
 * `sessionIds` (contract §3 reconcile).
 *
 * Exactly the phone-created case: the session exists with the workspace's cwd
 * but was never attached, so the GUI cannot group it. Newest sessions first,
 * at most `LIMITS.workspaceReconcilePerCall` durable attaches AND at most
 * `RECONCILE_TIME_BUDGET_MS` per call (the next bootstrap continues); skips
 * archived, subagent and already-attached sessions; idempotent and
 * membership-only. Failures are logged, retried at most every 10 minutes, and
 * never fail the bootstrap.
 * @returns `{attached, failed}` counts for this call.
 */
async function reconcileWorkspaceMembership(ctx, summaries) {
  const { registry, workspaces, byPath } = workspaceIndex(ctx)
  if (registry === undefined || byPath.size === 0) return { attached: 0, failed: 0 }
  const archived = new Set()
  try {
    const ids = registry.archivedSessionIds
    if (Array.isArray(ids)) for (const id of ids) archived.add(String(id))
  } catch {
    // Archived index unavailable: attach-time validation still guards the write.
  }
  const member = new Set()
  for (const workspace of workspaces) {
    if (!Array.isArray(workspace?.sessionIds)) continue
    for (const id of workspace.sessionIds) member.add(String(id))
  }
  const candidates = []
  for (const summary of summaries) {
    const sessionId = String(summary?.sessionId ?? '')
    if (sessionId.length === 0) continue
    if (summary.parentSessionId !== undefined && summary.parentSessionId !== null) continue
    if (summary.origin === 'subagent') continue
    if (archived.has(sessionId) || member.has(sessionId)) continue
    if (normalizeWorkspacePath(summary.cwd) === null) continue
    candidates.push(summary)
  }
  candidates.sort((left, right) => (Number(right.updatedAt) || 0) - (Number(left.updatedAt) || 0))
  const startedAt = Date.now()
  let attached = 0
  let failed = 0
  let attempts = 0
  for (const summary of candidates) {
    if (attempts >= LIMITS.workspaceReconcilePerCall) break
    if (Date.now() - startedAt > RECONCILE_TIME_BUDGET_MS) break
    const sessionId = String(summary.sessionId)
    const lastFailure = reconcileFailures.get(sessionId)
    if (lastFailure !== undefined && Date.now() - lastFailure < RECONCILE_RETRY_MS) continue
    const workspace = byPath.get(normalizeWorkspacePath(summary.cwd))
    if (workspace === undefined) continue
    attempts += 1
    try {
      await workspace.attachSession(sessionId)
      attached += 1
    } catch (error) {
      failed += 1
      reconcileFailures.set(sessionId, Date.now())
      try {
        ctx.logger?.warn?.(`workspace reconcile: session ${sessionId} not attached: ${String(error?.message ?? error)}`)
      } catch {
        // Logging is best-effort.
      }
      if (reconcileFailures.size > 500) {
        const cutoff = Date.now() - RECONCILE_RETRY_MS
        for (const [id, at] of reconcileFailures) if (at < cutoff) reconcileFailures.delete(id)
      }
    }
  }
  if (attached > 0 || failed > 0) {
    try {
      ctx.logger?.info?.(`workspace reconcile: attached ${attached}, failed ${failed}`)
    } catch {
      // Logging is best-effort.
    }
  }
  return { attached, failed }
}

function teamMembers(summaries) {
  const members = new Map()
  for (const summary of summaries) {
    const agentTeam = summary?.projections?.values?.agentTeam
    if (agentTeam === null || typeof agentTeam !== 'object' || !Array.isArray(agentTeam.members)) continue
    const teamId = String(summary.sessionId)
    for (const member of agentTeam.members) {
      if (member === null || typeof member !== 'object' || typeof member.id !== 'string') continue
      const id = String(member.id)
      if (members.has(id)) continue
      members.set(id, {
        name: typeof member.name === 'string' ? member.name : null,
        role: typeof member.role === 'string' ? member.role : null,
        phase: typeof member.phase === 'string' ? member.phase : null,
        teamId,
      })
    }
  }
  return members
}

function liveAgentIds(ctx) {
  const ids = new Set()
  try {
    for (const agent of ctx.get('agents')?.list?.() ?? []) {
      const id = agent?.id ?? agent?.sessionId
      if (typeof id === 'string' && id.length > 0) ids.add(id)
    }
  } catch {
    // No agent registry: every session reads as cold.
  }
  return ids
}

function buildExtras(ctx, summaries, workspaces, decisions) {
  const workspaceBySession = new Map()
  for (const workspace of workspaces) {
    for (const sessionId of workspace.sessionIds) workspaceBySession.set(String(sessionId), workspace.workspaceId)
  }
  const decisionsBySession = new Map()
  for (const decision of decisions?.items ?? []) {
    if (typeof decision.sessionId !== 'string' || decision.sessionId.length === 0) continue
    const rows = decisionsBySession.get(decision.sessionId) ?? []
    rows.push(decision)
    decisionsBySession.set(decision.sessionId, rows)
  }
  const depthBySession = new Map()
  const byId = new Map(summaries.map((summary) => [String(summary.sessionId), summary]))
  for (const summary of summaries) {
    const sessionId = String(summary.sessionId)
    let depth = 0
    let cursor = summary
    const seen = new Set([sessionId])
    while (typeof cursor?.parentSessionId === 'string' && depth < 3) {
      const parentId = cursor.parentSessionId
      if (seen.has(parentId)) break
      seen.add(parentId)
      const parent = byId.get(parentId)
      if (parent === undefined) { depth += 1; break }
      depth += 1
      cursor = parent
    }
    depthBySession.set(sessionId, depth)
  }
  return { workspaceBySession, decisionsBySession, team: teamMembers(summaries), liveIds: liveAgentIds(ctx), depthBySession }
}

function projectAll(ctx, summaries, decisions) {
  const extras = buildExtras(ctx, summaries, readWorkspaces(ctx), decisions)
  return summaries.map((summary) => projectSession(summary, extras))
}

function streamExtras(ctx) {
  return { workspaceBySession: new Map(), decisionsBySession: new Map(), team: new Map(), liveIds: liveAgentIds(ctx), depthBySession: new Map() }
}

function projectSession(summary, extras = streamExtras({ get: () => undefined })) {
  const values = summary.projections?.values ?? {}
  const sessionId = String(summary.sessionId)
  const member = extras.team?.get(sessionId)
  const pending = extras.decisionsBySession?.get(sessionId) ?? []
  const questionCount = Array.isArray(values.userQuestions?.active) ? values.userQuestions.active.length : 0
  const pendingDecisionCount = pending.length + (pending.length > 0 ? 0 : questionCount)
  return {
    sessionId,
    title: pickTitle(summary),
    workspaceId: extras.workspaceBySession?.get(sessionId) ?? null,
    cwd: typeof summary.cwd === 'string' ? summary.cwd : null,
    createdAt: Number.isSafeInteger(summary.createdAt) ? summary.createdAt : null,
    updatedAt: Number.isSafeInteger(summary.updatedAt) ? summary.updatedAt : null,
    lastPromptAt: Number.isSafeInteger(values.sessionListMetadata?.lastPromptAt) ? values.sessionListMetadata.lastPromptAt : null,
    lastMessagePreview: pickPreview(summary),
    running: summary.running === true,
    agentAvailable: summary.agentAvailable === true,
    blank: summary.blank === true,
    waiting: pendingDecisionCount > 0,
    pendingDecisionCount,
    parentSessionId: typeof summary.parentSessionId === 'string' ? summary.parentSessionId : null,
    origin: summary.origin === 'subagent' ? 'subagent' : null,
    delegationDepth: Number.isSafeInteger(summary.delegationDepth)
      ? summary.delegationDepth
      : (extras.depthBySession?.get(sessionId) ?? null),
    agentPreset: typeof values.agentPreset === 'string' ? values.agentPreset : null,
    agentId: extras.liveIds?.has(sessionId) ? sessionId : null,
    agentName: member?.name ?? null,
    teamId: member?.teamId ?? null,
    teamRole: member?.role ?? null,
    lastError: null,
    model: modelOf(summary),
  }
}

function pluginCard(entry) {
  const meta = entry?.meta ?? {}
  return {
    entryId: entry?.entryId ?? entry?.id ?? '',
    moduleName: entry?.moduleName ?? '',
    title: typeof meta.title === 'string' ? meta.title : null,
    description: typeof meta.description === 'string' ? meta.description : null,
    version: null,
    group: 'extension',
    enabled: entry?.enabled !== false,
    fiberPhase: entry?.fiberPhase ?? null,
    readOnly: entry?.readOnlyReason !== undefined && entry?.readOnlyReason !== null,
    readOnlyReason: entry?.readOnlyReason ?? null,
    togglable: entry?.patchId !== undefined && entry?.patchId !== null && entry?.readOnlyReason == null,
    restartRequired: false,
    patchId: entry?.patchId ?? null,
  }
}

async function readPlugins(ctx) {
  const manager = ctx.get('pluginManager')
  if (manager === undefined) return []
  try {
    return await manager.listPlugins()
  } catch {
    return []
  }
}

async function readSessions(ctx, signal) {
  const controller = ctx.get('sessionController')
  if (controller === undefined) return []
  const value = await controller.list({}, signal)
  return Array.isArray(value?.items) ? value.items : []
}

function readLiveAgents(ctx) {
  const registry = ctx.get('agents')
  if (registry === undefined) return []
  try {
    const list = registry.list()
    return Array.isArray(list) ? list : []
  } catch {
    return []
  }
}

function projectAgents(ctx, summaries) {
  const byId = new Map(summaries.map((summary) => [summary.sessionId, summary]))
  const live = readLiveAgents(ctx)
  const rows = []
  const seen = new Set()
  for (const agent of live) {
    const sessionId = agent?.id ?? agent?.sessionId
    if (typeof sessionId !== 'string' || seen.has(sessionId)) continue
    seen.add(sessionId)
    const summary = byId.get(sessionId)
    const values = summary?.projections?.values ?? {}
    rows.push({
      agentId: sessionId,
      sessionId,
      name: summary ? pickTitle(summary) : sessionId.slice(0, 8),
      role: summary?.origin === 'subagent' ? 'teammate' : 'lead',
      status: summary?.running === true ? 'running' : 'inactive',
      description: values.subagent?.label ?? '',
      model: values.modelSelection?.next ?? values.modelSelection?.lastUsed ?? null,
      lastActiveAt: summary?.updatedAt ?? null,
      teamId: summary?.parentSessionId ?? null,
    })
  }
  return rows
}

function normalizeOptions(options) {
  if (!Array.isArray(options)) return []
  const rows = []
  for (const option of options.slice(0, 12)) {
    if (typeof option === 'string') {
      rows.push({ id: option, label: option, style: 'default' })
      continue
    }
    if (option === null || typeof option !== 'object') continue
    const label = typeof option.label === 'string' ? option.label : (typeof option.id === 'string' ? option.id : undefined)
    if (label === undefined) continue
    const style = ['default', 'primary', 'destructive'].includes(option.style) ? option.style : 'default'
    rows.push({ id: typeof option.id === 'string' ? option.id : label, label, style })
  }
  return rows
}

function normalizeAnswerMode(value) {
  if (typeof value !== 'string') return 'open'
  return ['approval', 'questions', 'text', 'ask', 'open'].includes(value) ? value : 'open'
}

function mapDecision(card, summaries, workspaces) {
  const byId = new Map(summaries.map((summary) => [String(summary.sessionId), summary]))
  const pathBySession = new Map()
  for (const workspace of workspaces) for (const sessionId of workspace.sessionIds) pathBySession.set(String(sessionId), workspace.path)
  const sessionId = typeof card?.sessionId === 'string' ? card.sessionId : null
  const summary = sessionId === null ? undefined : byId.get(sessionId)
  const sessionTitle = summary === undefined ? (typeof card?.title === 'string' ? card.title : '') : pickTitle(summary)
  const workspacePath = sessionId === null ? null : (typeof summary?.cwd === 'string' ? summary.cwd : pathBySession.get(sessionId) ?? null)
  const at = Number.isSafeInteger(card?.at) ? card.at : 0
  const kind = typeof card?.kind === 'string' ? card.kind : 'decision'
  const callId = typeof card?.callId === 'string' ? card.callId : null
  const waitedMs = Number.isSafeInteger(card?.waitedMs) && card.waitedMs >= 0 ? card.waitedMs : (at > 0 ? Math.max(0, Date.now() - at) : 0)
  const running = card?.running === true || summary?.running === true
  return {
    decisionId: typeof card?.id === 'string' && card.id.length > 0 ? card.id : `${kind}:${sessionId ?? 'unknown'}:${callId ?? String(at)}`,
    source: 'decisions-plugin',
    kind,
    answerMode: normalizeAnswerMode(card?.answer ?? card?.answerMode),
    sessionId,
    sessionTitle,
    workspacePath,
    callId,
    toolName: typeof card?.toolName === 'string' ? card.toolName : null,
    title: typeof card?.title === 'string' && card.title.length > 0 ? card.title : sessionTitle,
    prompt: typeof card?.question === 'string' ? card.question : (typeof card?.prompt === 'string' ? card.prompt : ''),
    detail: typeof card?.detail === 'string' ? card.detail : null,
    recommendation: typeof card?.recommendation === 'string' ? card.recommendation : null,
    questions: Array.isArray(card?.questions)
      ? card.questions.map((question) => ({
          questionId: typeof question?.id === 'string' ? question.id : null,
          question: typeof question?.question === 'string' ? question.question : '',
          header: typeof question?.header === 'string' ? question.header : null,
          detail: typeof question?.detail === 'string' ? question.detail : null,
          multiSelect: question?.multiSelect === true,
          freeText: question?.freeText === true || !Array.isArray(question?.options) || question.options.length === 0,
          options: normalizeOptions(question?.options),
        }))
      : [],
    options: normalizeOptions(card?.options ?? card?.questions?.[0]?.options),
    questionId: typeof card?.questions?.[0]?.id === 'string' ? card.questions[0].id : null,
    multiple: card?.multiple === true || card?.questions?.[0]?.multiSelect === true,
    delivery: card?.delivery === 'next-turn' || card?.late === true || card?.state === 'continued' ? 'next-turn' : 'inline',
    intent: card?.intent !== null && typeof card?.intent === 'object' ? card.intent : null,
    state: card?.cancelled === true || card?.outcome === 'cancelled' || card?.outcome === 'unavailable'
      ? 'withdrawn'
      : (typeof card?.state === 'string' ? card.state : 'pending'),
    urgent: card?.answer === 'approval' || card?.live === true || (kind === 'question' && running === true && card?.answer !== 'open'),
    running,
    waitedMs,
    createdAt: at > 0 ? at : Date.now(),
    expiresAt: null,
    via: card?.via !== null && typeof card?.via === 'object' && typeof card.via.sessionId === 'string'
      ? { sessionId: card.via.sessionId, title: typeof card.via.title === 'string' ? card.via.title : '' }
      : null,
    answer: null,
  }
}

function builtinDecisions(summaries, workspaces) {
  const items = []
  for (const summary of summaries) {
    const active = summary?.projections?.values?.userQuestions?.active
    if (!Array.isArray(active)) continue
    for (const call of active) {
      if (call === null || typeof call !== 'object' || typeof call.callId !== 'string') continue
      const first = Array.isArray(call.questions) ? call.questions[0] : undefined
      items.push(mapDecision({
        id: `question:${String(summary.sessionId)}:${call.callId}`,
        kind: 'question',
        sessionId: String(summary.sessionId),
        callId: call.callId,
        question: typeof first?.question === 'string' ? first.question : 'Question',
        options: Array.isArray(first?.options) ? first.options : [],
        answer: call.state === 'continued' ? 'questions' : 'open',
        running: summary.running === true,
        at: 0,
      }, summaries, workspaces))
    }
  }
  return items
}

async function readDecisions(ctx, summaries) {
  const workspaces = readWorkspaces(ctx)
  const gateway = ctx.get('typertGateway')
  if (gateway !== undefined && typeof gateway.invoke === 'function') {
    try {
      const state = await gateway.invoke({ namespace: 'dsh-plugin-decisions', method: 'state', args: {} })
      if (state !== null && typeof state === 'object') {
        const waiting = Array.isArray(state.waiting) ? state.waiting.map((card) => mapDecision(card, summaries, workspaces)) : []
        const dismissed = Array.isArray(state.dismissed) ? state.dismissed.map((card) => ({ ...mapDecision(card, summaries, workspaces), state: 'dismissed' })) : []
        let badge = { waiting: waiting.length, urgent: waiting.filter((item) => item.urgent).length }
        try {
          const hostBadge = await gateway.invoke({ namespace: 'dsh-plugin-decisions', method: 'badge', args: {} })
          if (hostBadge !== null && typeof hostBadge === 'object' && Number.isSafeInteger(hostBadge.waiting)) {
            badge = { waiting: hostBadge.waiting, urgent: Number.isSafeInteger(hostBadge.urgent) ? hostBadge.urgent : badge.urgent }
          }
        } catch {
          // Badge derived from the cards above.
        }
        const problem = typeof state.problem === 'string' ? state.problem : (typeof state.watchProblem === 'string' ? state.watchProblem : null)
        return { source: 'decisions-plugin', items: waiting, all: [...waiting, ...dismissed], badge, sourceProblem: problem }
      }
    } catch {
      // Gateway present but decisions plugin absent/off: fall through.
    }
  }
  if (ctx.get('userQuestions') !== undefined) {
    const items = builtinDecisions(summaries, workspaces)
    return { source: 'builtin', items, all: items, badge: { waiting: items.length, urgent: items.filter((item) => item.urgent).length }, sourceProblem: null }
  }
  return { source: 'none', items: [], all: [], badge: { waiting: 0, urgent: 0 }, sourceProblem: null }
}

function readJobs(ctx, summaries = []) {
  const jobs = ctx.get('jobs')
  if (jobs === undefined || typeof jobs.list !== 'function') return []
  const titles = new Map(summaries.map((summary) => [String(summary.sessionId), pickTitle(summary)]))
  const rows = []
  const seen = new Set()
  const push = (job, fallbackOwner) => {
    const id = job?.id ?? job?.jobId
    if (typeof id !== 'string' || seen.has(id)) return
    seen.add(id)
    const owner = typeof job.owner === 'string' ? job.owner : (typeof fallbackOwner === 'string' ? fallbackOwner : null)
    rows.push({
      jobId: id,
      kind: typeof job.kind === 'string' ? job.kind : 'job',
      label: typeof job.label === 'string' ? job.label : id,
      sessionId: owner,
      ownerSessionId: owner,
      sessionTitle: owner === null ? null : titles.get(owner) ?? null,
      status: typeof job.status === 'string' ? job.status : 'running',
      progress: typeof job.progress === 'string' ? job.progress : null,
      detail: typeof job.detail === 'string' ? job.detail : null,
      startedAt: Number.isSafeInteger(job.startedAt) ? job.startedAt : 0,
      finishedAt: Number.isSafeInteger(job.finishedAt) ? job.finishedAt : null,
      output: {
        total: Number.isSafeInteger(job.output?.total) ? job.output.total : 0,
        earliest: Number.isSafeInteger(job.output?.earliest) ? job.output.earliest : 0,
      },
      canKill: job.status === 'running' || job.status === 'stopping',
    })
  }
  const owners = new Set()
  for (const summary of summaries) if (typeof summary.sessionId === 'string') owners.add(String(summary.sessionId))
  for (const agent of readLiveAgents(ctx)) {
    const sessionId = agent?.id ?? agent?.sessionId
    if (typeof sessionId === 'string') owners.add(sessionId)
  }
  for (const owner of owners) {
    try {
      for (const job of jobs.list(owner) ?? []) push(job, owner)
    } catch {
      // One owner's registry failure must not fail the section.
    }
  }
  try {
    for (const job of jobs.list() ?? []) push(job, undefined)
  } catch {
    // Unowned read is best effort.
  }
  rows.sort((left, right) => {
    const owned = (left.sessionId === null ? 1 : 0) - (right.sessionId === null ? 1 : 0)
    return owned !== 0 ? owned : right.startedAt - left.startedAt
  })
  return rows
}

function workspacesOf(sessions) {
  const seen = new Map()
  for (const session of sessions) {
    const path = session.cwd
    if (typeof path !== 'string' || path.length === 0 || seen.has(path)) continue
    const title = path.split('/').filter(Boolean).pop() ?? path
    seen.set(path, {
      workspaceId: path,
      title,
      path,
      sessionIds: sessions.filter((row) => row.cwd === path).map((row) => row.sessionId),
    })
  }
  return [...seen.values()]
}

/**
 * `bootstrap.workspaces[]` per contract §2: registered workspaces come from the
 * registry with their real `sessionIds` (host membership), and cwd-derived
 * synthetic entries are added only for paths that match no registered
 * workspace — synthetic entries never shadow registry membership.
 */
function mergedWorkspaces(ctx, sessions) {
  const registered = readWorkspaces(ctx)
  const known = new Set(registered.map((workspace) => normalizeWorkspacePath(workspace.path)))
  const synthetic = workspacesOf(sessions).filter((workspace) => !known.has(normalizeWorkspacePath(workspace.path)))
  return [...registered, ...synthetic]
}

function hostInfo(ctx) {
  const profile = ctx.get('profileContext')
  return {
    name: 'dsh',
    profile: profile?.name ?? null,
    platform: process.platform,
    version: profile?.version ?? '0.2.0-rc.2',
    pid: process.pid,
    port: ctx.get('webServer')?.port ?? null,
    uptimeSeconds: Math.round(process.uptime()),
  }
}

function decisionAnswerAvailable(ctx) {
  return ctx.get('dshPluginDecisionsRemote') !== undefined
}

async function invokeDecisions(ctx, method, args) {
  const gateway = ctx.get('typertGateway')
  if (gateway === undefined || typeof gateway.invoke !== 'function') return undefined
  return await gateway.invoke({ namespace: 'dsh-plugin-decisions', method, args })
}

function capabilities(ctx) {
  return {
    sessions: true,
    send: true,
    stream: true,
    agents: true,
    dm: false,
    plugins: ctx.get('pluginManager') !== undefined,
    pluginToggle: ctx.get('pluginManager') !== undefined,
    jobs: ctx.get('jobs') !== undefined,
    jobKill: true,
    decisions: true,
    decisionsPlugin: ctx.get('dshPluginDecisionsRemote') !== undefined || ctx.get('typertGateway') !== undefined,
    decisionAnswer: decisionAnswerAvailable(ctx),
    agentPresets: ctx.get('agentPresets') !== undefined,
    lastMessagePreview: true,
    attachments: false,
    search: true,
  }
}

function messageOf(record, sessionId) {
  const event = record?.event
  if (event === undefined || !Number.isSafeInteger(event.seq)) return null
  const data = event.data ?? {}
  const sid = typeof sessionId === 'string' ? sessionId : ''
  const row = {
    id: `${sid}:${event.seq}:message`,
    seq: event.seq,
    sessionId: sid,
    role: 'system',
    kind: 'notice',
    text: '',
    blocks: [],
    attachments: [],
    time: Number.isSafeInteger(event.time) ? event.time : null,
    turn: Number.isSafeInteger(data.turn) ? data.turn : null,
    step: Number.isSafeInteger(data.step) ? data.step : null,
    status: 'complete',
    clientRequestId: null,
    tool: null,
    error: null,
    noticeKind: null,
    peerName: null,
  }
  const blockList = (content) => {
    const blocks = []
    const attachments = []
    for (const block of Array.isArray(content) ? content : []) {
      if (block === null || typeof block !== 'object') continue
      if (block.type === 'text' && typeof block.text === 'string') blocks.push({ type: 'text', text: block.text })
      else if (block.type === 'reasoning' && typeof block.text === 'string') blocks.push({ type: 'reasoning', text: block.text })
      else if (block.type === 'tool-call') blocks.push({ type: 'tool-call', callId: block.id ?? block.callId ?? null, name: typeof block.name === 'string' ? block.name : null, arguments: typeof block.arguments === 'string' ? block.arguments : null })
      else if (block.type === 'image') {
        blocks.push({ type: 'image', attachmentId: block.attachmentId ?? null, mediaType: block.mediaType ?? null })
        attachments.push({ attachmentId: block.attachmentId ?? null, kind: 'image', mediaType: typeof block.mediaType === 'string' ? block.mediaType : null, name: typeof block.name === 'string' ? block.name : null, bytes: Number.isSafeInteger(block.bytes) ? block.bytes : null, width: Number.isSafeInteger(block.width) ? block.width : null, height: Number.isSafeInteger(block.height) ? block.height : null, url: null })
      } else if (block.type === 'file') {
        blocks.push({ type: 'file', attachmentId: block.attachmentId ?? null, name: block.name ?? null, bytes: block.bytes ?? null })
        attachments.push({ attachmentId: block.attachmentId ?? null, kind: 'file', mediaType: null, name: typeof block.name === 'string' ? block.name : null, bytes: Number.isSafeInteger(block.bytes) ? block.bytes : null, width: null, height: null, url: null })
      }
    }
    return { blocks, attachments }
  }
  switch (event.type) {
    case 'user/message': {
      const kind = data.source?.kind
      const peerName = (() => {
        const source = data.source
        if (source !== null && typeof source === 'object') {
          for (const key of ['peerName', 'sender', 'from', 'name']) if (typeof source[key] === 'string' && source[key].length > 0) return source[key]
          if (typeof source.kind === 'string' && source.kind.includes('team')) {
            const match = /^Team message [^\n]* from ([^:\n]+):/u.exec(textOf(data.content))
            if (match !== null) return match[1].trim()
          }
        }
        return null
      })()
      const isHuman = kind === 'user' || kind === 'user-rpc' || (peerName === null && typeof data.source?.rpcId === 'string')
      const text = textOf(data.content)
      if (text.length === 0) return null
      const { blocks, attachments } = blockList(data.content)
      if (blocks.length === 0) blocks.push({ type: 'text', text })
      row.role = isHuman || peerName !== null ? 'user' : 'system'
      row.kind = isHuman || peerName !== null ? 'message' : 'notice'
      row.text = text
      row.blocks = blocks
      row.attachments = attachments
      row.clientRequestId = typeof data.source?.rpcId === 'string' ? data.source.rpcId : null
      row.noticeKind = isHuman || peerName !== null ? null : 'other'
      row.peerName = peerName
      if (typeof data.id === 'string') row.id = data.id
      break
    }
    case 'assistant/message': {
      const content = data.message?.content
      const { blocks } = blockList(content)
      const text = textOf(content)
      if (blocks.length === 0) return null
      row.role = 'assistant'
      row.kind = 'message'
      row.text = text
      row.blocks = blocks
      row.status = data.interrupted === true ? 'interrupted' : 'complete'
      if (typeof data.message?.id === 'string') row.id = data.message.id
      break
    }
    case 'tool/result': {
      const content = data.message?.content
      const text = textOf(content)
      const callId = typeof data.message?.toolCallId === 'string' ? data.message.toolCallId : null
      const isError = data.message?.isError === true
      row.role = 'tool'
      row.kind = 'tool_result'
      row.text = text
      row.blocks = [{ type: 'text', text }]
      row.status = isError ? 'failed' : 'complete'
      row.tool = {
        callId,
        name: null,
        isError,
        argumentsSummary: null,
        resultPreview: truncate(text, 2048),
        durationMs: null,
      }
      row.error = isError ? { code: 'tool-failed', message: typeof data.error?.reason === 'string' ? data.error.reason : text } : null
      if (typeof data.message?.id === 'string') row.id = data.message.id
      break
    }
    case 'approval/asked': {
      const approvalId = data.id ?? data.approvalId
      row.role = 'system'
      row.kind = 'decision'
      row.text = typeof data.reason === 'string' && data.reason.length > 0 ? data.reason : 'Approval requested'
      row.decision = { decisionId: `approval:${sid}:${typeof approvalId === 'string' ? approvalId : String(event.seq)}`, kind: 'approval', answerMode: 'approval', prompt: row.text, options: [], state: 'pending' }
      row.id = `${sid}:${event.seq}:decision`
      break
    }
    default: {
      const noticeKind = ({ 'system/message': 'system', 'developer/message': 'developer', 'approval/policy': 'policy', 'permission/preset': 'preset', 'sandbox/mode': 'sandbox', 'subagent/descriptor': 'subagent' })[event.type]
      if (noticeKind === undefined) return null
      if (event.type === 'subagent/descriptor') row.text = typeof data.label === 'string' && data.label.length > 0 ? data.label : 'Spawned subagent'
      else if (event.type === 'system/message' || event.type === 'developer/message') row.text = truncate(textOf(data.message?.content), 200)
      else row.text = truncate(typeof data.reason === 'string' ? data.reason : event.type, 200)
      row.role = 'system'
      row.kind = 'notice'
      row.noticeKind = noticeKind
      row.blocks = row.text.length > 0 ? [{ type: 'text', text: row.text }] : []
      break
    }
  }
  return row
}


async function oneFrameSnapshot(ctx, summary, sessionId, signal) {
  const controller = ctx.get('sessionController')
  if (controller === undefined || typeof controller.follow !== 'function') return null
  const abort = new AbortController()
  const combined = signal === undefined ? abort.signal : AbortSignal.any([signal, abort.signal])
  const address = summary?.origin === 'subagent'
    ? { kind: 'subagent', parentSessionId: String(summary.parentSessionId ?? ''), childSessionId: sessionId, mode: 'unknown' }
    : { kind: 'session', sessionId }
  try {
    for await (const frame of controller.follow({ address, maxMessages: 1 }, combined)) {
      abort.abort()
      return frame?.type === 'snapshot' ? frame : null
    }
  } catch {
    return null
  }
  return null
}

async function readRequestBody(req) {
  const chunks = []
  for await (const chunk of req) chunks.push(chunk)
  return Buffer.concat(chunks).toString('utf8')
}

/**
 * Prefix route for the contract's dynamic paths. Admits with Connection's own
 * fence, then delegates to the exact routes (or answers session/job detail
 * directly) so no route logic is duplicated and no auth is reimplemented.
 */
async function handleDynamic(ctx, shared, req, res) {
  const admission = ctx.connection.admit({ headers: req.headers })
  if ('rejection' in admission) {
    res.writeHead(admission.rejection, { 'content-type': 'text/plain;charset=UTF-8', 'cache-control': 'no-store' })
    res.end(admission.rejection === 401 ? 'unauthorized' : 'forbidden')
    return
  }
  const url = new URL(req.url ?? '/', 'http://localhost')
  const rest = url.pathname.slice(API.length)
  const method = req.method ?? 'GET'
  const writeJson = (status, data) => {
    res.writeHead(status, { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' })
    res.end(JSON.stringify(data))
  }
  const notFound = (code, what) => writeJson(404, { ok: false, error: { code, message: `${what} not found`, retryable: false } })

  let match = rest.match(/^\/sessions\/([^/]+)$/)
  if (match !== null && method === 'GET') {
    const sessionId = decodeURIComponent(match[1])
    try {
      const summaries = await readSessions(ctx)
      const summary = summaries.find((row) => String(row.sessionId) === sessionId)
      if (summary === undefined) return notFound('session-not-found', 'session')
      const controller = ctx.get('sessionController')
      let counts = { messages: 0, jobs: 0, decisions: 0 }
      let throughSeq = 0
      if (controller !== undefined) {
        try {
          const projections = await controller.projections({ sessionId })
          throughSeq = typeof projections?.asOfSeq === 'number' ? projections.asOfSeq : 0
          const page = await controller.page({ address: { kind: 'session', sessionId }, throughSeq, maxMessages: 100 })
          counts.messages = page?.records?.length ?? 0
        } catch {
          // counts stay zero; the session row still answers.
        }
      }
      counts.jobs = readJobs(ctx, summaries).filter((job) => job.sessionId === sessionId).length
      counts.decisions = summary.projections?.values?.userQuestions?.active?.length ?? 0
      return writeJson(200, { ok: true, session: projectSession(summary, buildExtras(ctx, summaries, readWorkspaces(ctx), { items: [] })), counts, throughSeq })
    } catch (error) {
      return writeJson(500, { ok: false, error: { code: 'sessions-failed', message: String(error?.message ?? error), retryable: true } })
    }
  }

  match = rest.match(/^\/agents\/([^/]+)$/)
  if (match !== null && method === 'GET') {
    const idOrName = decodeURIComponent(match[1])
    const summaries = await readSessions(ctx)
    const rows = projectAgents(ctx, summaries)
    const wanted = idOrName.toLowerCase()
    const agent = rows.find((row) => row.agentId === idOrName || row.sessionId === idOrName)
      ?? rows.find((row) => row.role !== 'human' && String(row.name).toLowerCase() === wanted)
    if (agent === undefined) return notFound('agent-not-found', 'agent')
    return writeJson(200, { ok: true, agent })
  }

  let target = null
  let body = null
  match = rest.match(/^\/jobs\/([^/]+)\/output$/)
  if (match !== null && method === 'GET') {
    const jobId = decodeURIComponent(match[1])
    const params = new URLSearchParams(url.search)
    params.set('jobId', jobId)
    target = `${API}/jobs/output?${params.toString()}`
  }
  match = rest.match(/^\/jobs\/([^/]+)\/kill$/)
  if (match !== null && method === 'POST') {
    const jobId = decodeURIComponent(match[1])
    const incoming = await readRequestBody(req)
    const parsed = incoming.length > 0 ? JSON.parse(incoming) : {}
    body = JSON.stringify({ ...parsed, jobId })
    target = `${API}/jobs/kill`
  }
  match = rest.match(/^\/jobs\/([^/]+)$/)
  if (match !== null && method === 'GET' && !['output', 'kill'].includes(decodeURIComponent(match[1]))) {
    const jobId = decodeURIComponent(match[1])
    const summaries = await readSessions(ctx).catch(() => [])
    const job = readJobs(ctx, summaries).find((row) => String(row.jobId) === jobId)
    if (job === undefined) return notFound('job-not-found', 'job')
    return writeJson(200, { ok: true, job })
  }

  match = rest.match(/^\/sessions\/([^/]+)\/messages$/)
  if (match !== null && method === 'GET') {
    const params = new URLSearchParams(url.search)
    params.set('sessionId', decodeURIComponent(match[1]))
    target = `${API}/messages?${params.toString()}`
  }
  match = rest.match(/^\/plugins\/([^/]+)\/enabled$/)
  if (match !== null && method === 'POST') {
    const incoming = await readRequestBody(req)
    const parsed = incoming.length > 0 ? JSON.parse(incoming) : {}
    body = JSON.stringify({ ...parsed, entryId: decodeURIComponent(match[1]) })
    target = `${API}/plugins/enabled`
  }
  match = rest.match(/^\/decisions\/([^/]+)\/answer$/)
  if (match !== null && method === 'POST') {
    const decisionId = decodeURIComponent(match[1])
    let parsed = null
    try {
      const incoming = await readRequestBody(req)
      parsed = incoming.length > 0 ? JSON.parse(incoming) : null
    } catch {
      return writeJson(400, { ok: false, error: { code: 'bad-json', message: 'the request body must be JSON', retryable: false } })
    }
    const response = await answerDecision(ctx, decisionId, parsed?.payload ?? parsed)
    res.writeHead(response.status, { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' })
    res.end(await response.text())
    return
  }

  // M2 contract writes answered directly by the prefix (no static twin).
  match = rest.match(/^\/sessions\/([^/]+)\/messages$/)
  if (match !== null && method === 'POST') {
    const sessionId = decodeURIComponent(match[1])
    const controller = ctx.get('sessionController')
    if (controller === undefined) return writeJson(503, { ok: false, error: { code: 'service-unavailable', message: 'session controller unavailable', retryable: true } })
    let parsed = {}
    try {
      const incoming = await readRequestBody(req)
      parsed = incoming.length > 0 ? JSON.parse(incoming) : {}
    } catch {
      return writeJson(400, { ok: false, error: { code: 'bad-json', message: 'the request body must be JSON', retryable: false } })
    }
    const text = typeof parsed.text === 'string' ? parsed.text : ''
    if (text.trim().length === 0) return writeJson(422, { ok: false, error: { code: 'invalid-prompt', message: 'text must not be empty', retryable: false } })
    if (Buffer.byteLength(text, 'utf8') > 32768) return writeJson(422, { ok: false, error: { code: 'invalid-prompt', message: 'text exceeds 32768 bytes', retryable: false } })
    const supplied = typeof parsed.requestId === 'string' && parsed.requestId.trim().length > 0 && parsed.requestId.trim().length <= 128 ? parsed.requestId.trim() : null
    const requestId = supplied ?? randomUUID()
    if (supplied !== null) {
      const seenAt = sentRequests.get(`${sessionId}\u0000${supplied}`) ?? sentRequests.get(supplied)
      if (typeof seenAt === 'number' && Date.now() - seenAt < SEND_DEDUPE_MS) {
        return writeJson(202, { ok: true, accepted: true, requestId: supplied, clientRequestId: supplied, sessionId, duplicate: true })
      }
    }
    try {
      await controller.prompt({ requestId, sessionId, mode: parsed.mode === 'steer' ? 'steer' : 'queue', content: [{ type: 'text', text }], ...(typeof parsed.clientTimeZone === 'string' ? { clientTimeZone: parsed.clientTimeZone } : {}) }, new AbortController().signal)
      if (supplied !== null) sentRequests.set(supplied, Date.now())
      noteSendAccepted(sessionId, requestId)
      return writeJson(202, { ok: true, accepted: true, requestId, clientRequestId: supplied, sessionId })
    } catch (error) {
      return writeJson(500, { ok: false, error: { code: 'internal', message: String(error?.message ?? error), retryable: true } })
    }
  }

  match = rest.match(/^\/sessions\/([^/]+)\/cancel$/)
  if (match !== null && method === 'POST') {
    const sessionId = decodeURIComponent(match[1])
    const controller = ctx.get('sessionController')
    if (controller === undefined) return writeJson(503, { ok: false, error: { code: 'service-unavailable', message: 'session controller unavailable', retryable: true } })
    try {
      controller.cancel({ sessionId })
      return writeJson(200, { ok: true, accepted: true })
    } catch (error) {
      return writeJson(500, { ok: false, error: { code: 'internal', message: String(error?.message ?? error), retryable: true } })
    }
  }

  match = rest.match(/^\/sessions\/([^/]+)$/)
  if (match !== null && method === 'PATCH') {
    const sessionId = decodeURIComponent(match[1])
    const controller = ctx.get('sessionController')
    if (controller === undefined) return writeJson(503, { ok: false, error: { code: 'service-unavailable', message: 'session controller unavailable', retryable: true } })
    let parsed = {}
    try {
      const incoming = await readRequestBody(req)
      parsed = incoming.length > 0 ? JSON.parse(incoming) : {}
    } catch {
      return writeJson(400, { ok: false, error: { code: 'bad-json', message: 'the request body must be JSON', retryable: false } })
    }
    const title = typeof parsed.title === 'string' ? parsed.title.trim() : ''
    if (title.length === 0 || title.length > 200) return writeJson(422, { ok: false, error: { code: 'session-title-invalid', message: 'title must be 1-200 characters', retryable: false } })
    try {
      await controller.rename({ sessionId, title })
      return writeJson(200, { ok: true, session: { sessionId, title } })
    } catch (error) {
      return writeJson(500, { ok: false, error: { code: 'internal', message: String(error?.message ?? error), retryable: true } })
    }
  }

  match = rest.match(/^\/agents\/([^/]+)\/messages$/)
  if (match !== null && method === 'POST') {
    const idOrName = decodeURIComponent(match[1])
    const summaries = await readSessions(ctx)
    const rows = projectAgents(ctx, summaries)
    const wanted = idOrName.toLowerCase()
    const agent = rows.find((row) => row.agentId === idOrName || row.sessionId === idOrName)
      ?? rows.find((row) => row.role !== 'human' && String(row.name).toLowerCase() === wanted)
    if (agent === undefined) return notFound('agent-not-found', 'agent')
    if (agent.role === 'human' || typeof agent.sessionId !== 'string') return writeJson(409, { ok: false, error: { code: 'dm-unavailable', message: 'the human operator is not messageable', retryable: false } })
    const controller = ctx.get('sessionController')
    if (controller === undefined) return writeJson(503, { ok: false, error: { code: 'service-unavailable', message: 'session controller unavailable', retryable: true } })
    let parsed = {}
    try {
      const incoming = await readRequestBody(req)
      parsed = incoming.length > 0 ? JSON.parse(incoming) : {}
    } catch {
      return writeJson(400, { ok: false, error: { code: 'bad-json', message: 'the request body must be JSON', retryable: false } })
    }
    const text = typeof parsed.text === 'string' ? parsed.text : ''
    if (text.trim().length === 0) return writeJson(422, { ok: false, error: { code: 'invalid-prompt', message: 'text must not be empty', retryable: false } })
    const requestId = typeof parsed.requestId === 'string' && parsed.requestId.trim().length > 0 ? parsed.requestId.trim() : randomUUID()
    try {
      await controller.prompt({ requestId, sessionId: agent.sessionId, mode: 'queue', content: [{ type: 'text', text }] }, new AbortController().signal)
      noteSendAccepted(agent.sessionId, requestId)
      return writeJson(202, { ok: true, accepted: true, requestId, clientRequestId: parsed.requestId ?? null, sessionId: agent.sessionId })
    } catch (error) {
      return writeJson(500, { ok: false, error: { code: 'internal', message: String(error?.message ?? error), retryable: true } })
    }
  }

  // Everything the prefix does not own is delegated to the shared exact-route
  // handler, so the prefix never shadows the v1 API (or the SSE stream).
  if (target === null) target = url.pathname + url.search
  if (shared === null) return writeJson(503, { ok: false, error: { code: 'unavailable', message: 'shared handler unavailable', retryable: true } })
  const headers = new Headers()
  for (const [key, value] of Object.entries(req.headers)) {
    if (value === undefined) continue
    headers.set(key, Array.isArray(value) ? value.join(',') : String(value))
  }
  const init = { method, headers }
  if (body !== null) {
    init.body = body
  } else if (method === 'POST' || method === 'PATCH' || method === 'PUT') {
    try {
      const incoming = await readRequestBody(req)
      if (incoming.length > 0) init.body = incoming
    } catch {
      // An unreadable body reaches the exact route as empty, which validates it.
    }
  }
  try {
    const response = await shared.fetch(new Request(`http://localhost${target}`, init))
    const contentType = response.headers.get('content-type') ?? 'application/json; charset=utf-8'
    if (contentType.includes('text/event-stream')) {
      res.writeHead(response.status, { 'content-type': contentType, 'cache-control': 'no-store' })
      const reader = response.body?.getReader()
      if (reader !== undefined) {
        for (;;) {
          const { done, value } = await reader.read()
          if (done) break
          res.write(Buffer.from(value))
        }
      }
      res.end()
      return
    }
    const text = await response.text()
    res.writeHead(response.status, { 'content-type': contentType, 'cache-control': 'no-store' })
    res.end(text)
  } catch (error) {
    writeJson(500, { ok: false, error: { code: 'dynamic-failed', message: String(error?.message ?? error), retryable: true } })
  }
}

function normalizeAnswerInput(payload, decision) {
  const questions = Array.isArray(decision?.questions) ? decision.questions : []
  const firstId = decision?.questionId ?? questions[0]?.questionId ?? null
  if (payload?.decision === 'allow' || payload?.decision === 'reject') return { decision: payload.decision }
  if (payload?.recommended === true) return { recommended: true }
  if (Array.isArray(payload?.answers)) {
    const answers = payload.answers.map((item, index) => {
      const id = typeof item?.questionId === 'string' && item.questionId.length > 0
        ? item.questionId
        : (typeof item?.id === 'string' && item.id.length > 0 ? item.id : (questions[index]?.questionId ?? firstId ?? null))
      return {
        questionId: id,
        selected: Array.isArray(item?.selected) ? item.selected.filter((label) => typeof label === 'string') : [],
        ...(typeof item?.custom === 'string' && item.custom.length > 0 ? { custom: item.custom } : {}),
      }
    })
    return { answers }
  }
  if (Array.isArray(payload?.selected)) {
    return { answers: [{ questionId: firstId, selected: payload.selected.filter((label) => typeof label === 'string'), ...(typeof payload.custom === 'string' && payload.custom.length > 0 ? { custom: payload.custom } : {}) }] }
  }
  if (typeof payload?.option === 'string') return { answers: [{ questionId: firstId, selected: [payload.option] }] }
  if (typeof payload?.text === 'string') return { answers: [{ questionId: firstId, selected: [], custom: payload.text }] }
  return null
}

function answerPayloadError(payload) {
  if (payload === null || typeof payload !== 'object') return 'the answer payload must be an object'
  const hasDecision = payload.decision === 'allow' || payload.decision === 'reject'
  const hasAnswers = Array.isArray(payload.answers)
  const hasText = typeof payload.text === 'string'
  const hasRecommended = payload.recommended === true
  const hasOption = typeof payload.option === 'string'
  if (!hasDecision && !hasAnswers && !hasText && !hasRecommended && !hasOption) return 'the answer must carry decision, answers, text, recommended, or option'
  return null
}

const answeredDecisions = new Map()
const ANSWER_MEMORY_MS = 10 * 60 * 1000

async function answerDecision(ctx, decisionId, payload) {
  if (!decisionAnswerAvailable(ctx)) return failure('decision-answering-unavailable', 'no decision answerer is composed on this host', 503)
  const summaries = await readSessions(ctx).catch(() => [])
  const before = await readDecisions(ctx, summaries).catch(() => undefined)
  const known = before?.all?.find((card) => card.decisionId === decisionId)
  if (known === undefined) {
    const answeredAt = answeredDecisions.get(decisionId)
    if (typeof answeredAt === 'number' && Date.now() - answeredAt < ANSWER_MEMORY_MS) {
      return failure('decision-stale', 'the decision was already answered', 409)
    }
    return notFound('decision-not-found', `no such decision "${decisionId}"`)
  }
  if (known.state !== 'pending') return failure('decision-stale', 'the decision is no longer pending', 409)
  const invalid = answerPayloadError(payload)
  if (invalid !== null) return failure('bad-request', invalid, 400)
  const input = normalizeAnswerInput(payload, known)
  if (input === null) return failure('bad-request', 'the answer could not be normalized', 400)
  if (Array.isArray(input.answers)) {
    const required = (Array.isArray(known.questions) ? known.questions : []).map((question) => question.questionId).filter((id) => typeof id === 'string')
    if (required.length > 0) {
      const answered = new Set(input.answers.map((item) => item.questionId).filter((id) => typeof id === 'string'))
      const missing = required.filter((id) => !answered.has(id))
      if (missing.length > 0) {
        return failure('bad-request', `send one answer for each question (missing: ${missing.join(', ')})`, 400)
      }
    }
  }
  try {
    // The client wire uses spec v2.1 `questionId`; the decisions plugin's
    // inbox names the same item `id`. Translate at the boundary, accept both.
    const pluginInput = Array.isArray(input.answers)
      ? { answers: input.answers.map((answer) => ({ id: answer.questionId ?? answer.id, selected: answer.selected, ...(answer.custom === undefined ? {} : { custom: answer.custom }) })) }
      : input
    const result = await invokeDecisions(ctx, 'answer', { id: decisionId, answer: pluginInput })
    answeredDecisions.set(decisionId, Date.now())
    if (answeredDecisions.size > 500) {
      for (const [id, at] of answeredDecisions) if (Date.now() - at >= ANSWER_MEMORY_MS) answeredDecisions.delete(id)
    }
    const after = await readDecisions(ctx, summaries).catch(() => undefined)
    const settled = after?.all?.find((card) => card.decisionId === decisionId)
    return json({ ok: true, decision: settled ?? { ...known, state: 'answered' }, delivered: true, where: typeof result?.where === 'string' ? result.where : 'session' })
  } catch (error) {
    const message = String(error?.message ?? error)
    if (/no longer waiting|already|answered|moved on/iu.test(message)) return failure('decision-stale', 'the decision is no longer pending', 409)
    return failure('internal', message, 500, true)
  }
}

/** The registry diagnostic of one roster row as a reason string, or null. */
function presetReason(row) {
  if (typeof row?.broken === 'string' && row.broken.length > 0) return row.broken
  if (row?.broken === true) return 'the preset is marked broken'
  return null
}

/**
 * Validate a requested agent preset before the controller creates anything.
 *
 * Contract §3: client-caused failures are typed 4xx and must never surface as
 * a 500 from the controller. The omit path performs no roster read.
 * @returns `{ agentPreset }` when usable, or `{ response }` to return as-is.
 */
async function validateAgentPreset(ctx, body) {
  // Contract §3: agentPreset absent OR null means "the host default".
  if (!Object.prototype.hasOwnProperty.call(body, 'agentPreset') || body.agentPreset === undefined || body.agentPreset === null) {
    return { agentPreset: undefined }
  }
  if (typeof body.agentPreset !== 'string') {
    return { response: failure('bad-request', 'agentPreset must be a string', 400, false, { field: 'agentPreset' }) }
  }
  const agentPreset = body.agentPreset
  const registry = ctx.get('agentPresets')
  if (registry === undefined || typeof registry.remoteExportList !== 'function') {
    return {
      response: failure('unsupported-capability', 'this deployment has no agent-preset registry', 400, false, { capability: 'agentPresets' }),
    }
  }
  let roster
  try {
    roster = await registry.remoteExportList()
  } catch (error) {
    return { response: failure('internal', `could not read the agent-preset roster: ${String(error?.message ?? error)}`, 500, true) }
  }
  const rows = Array.isArray(roster?.presets) ? roster.presets : []
  const row = rows.find((candidate) => candidate?.id === agentPreset)
  if (row === undefined) {
    return { response: failure('agent-preset-not-found', `unknown agent preset "${agentPreset}"`, 404, false, { presetId: agentPreset }) }
  }
  const reason = presetReason(row)
  if (reason !== null) {
    return {
      response: failure('agent-preset-unavailable', `agent preset "${agentPreset}" cannot load: ${reason}`, 409, false, { presetId: agentPreset, reason }),
    }
  }
  return { agentPreset }
}

/** Map a controller create failure onto the preset contract codes. */
function presetCreateFailure(error, requestedPreset) {
  const code = typeof error?.code === 'string' ? error.code : ''
  const details = error?.details ?? {}
  const presetId = typeof details.agentPreset === 'string' && details.agentPreset.length > 0 ? details.agentPreset : requestedPreset
  if (code === 'agent-preset/not-found') {
    return failure('agent-preset-not-found', `unknown agent preset "${String(presetId)}"`, 404, false, { presetId })
  }
  if (code === 'agent-preset/invalid') {
    const reason = typeof details.reason === 'string' && details.reason.length > 0 ? details.reason : String(error?.message ?? error)
    return failure('agent-preset-unavailable', `agent preset "${String(presetId)}" cannot load: ${reason}`, 409, false, { presetId, reason })
  }
  return failure('internal', String(error?.message ?? error), 500, true)
}

/** Extract the live Agent from an ApiSessionAgentController resolve result. */
function agentOf(resolved) {
  if (resolved === null || typeof resolved !== 'object') return undefined
  if (resolved.error !== undefined) return undefined
  if (resolved.agent !== undefined && resolved.agent?.session !== undefined) return resolved.agent
  return resolved.session === undefined ? undefined : resolved
}

/**
 * Apply the model a preset declares as a SESSION-LOCAL selection.
 *
 * A preset's `agent-default-model` row cannot reach the Host-plane session
 * controller: unisolated it collides in the root realm (cordis `provide`
 * throws "service … has been registered at"), isolated it lives in the
 * loader's per-entry LocalRealm and the controller never sees it. The
 * registry's cross-scope `serviceFor(agent, name)` is the supported read, so
 * the pin is emulated here: read the preset's own instance, then install it
 * for this session only. `selectForNextRequest` appends `model/selection`
 * (which the Session.model projection reads) and does NOT save the deployment
 * default, unlike `sessionController.selectModel`.
 * @returns `{ applied: false }` when the preset declares no model row.
 */
async function applyPresetModel(ctx, controller, sessionId) {
  const registry = ctx.get('agentPresets')
  const agents = controller?.agents
  if (registry === undefined || typeof registry.serviceFor !== 'function') return { applied: false }
  if (agents === undefined || typeof agents.resolveAgent !== 'function' || typeof agents.selectForNextRequest !== 'function') {
    return { applied: false }
  }
  const agent = agentOf(await agents.resolveAgent(sessionId))
  if (agent === undefined) return { applied: false }
  const pinned = registry.serviceFor(agent, 'agentDefaultModel')
  if (pinned === undefined || typeof pinned.currentSelection !== 'function') return { applied: false }
  const declared = pinned.currentSelection()
  if (typeof declared?.provider !== 'string' || typeof declared?.model !== 'string') return { applied: false }
  const selection = {
    provider: declared.provider,
    model: declared.model,
    ...(typeof declared.reasoningEffort === 'string' ? { reasoningEffort: declared.reasoningEffort } : {}),
  }
  // Fail early with the contract's 409 when the declared model is not routable
  // on this host, instead of letting the first turn fail.
  const requireModel = controller?.commands?.requireModel
  if (typeof requireModel === 'function') await requireModel.call(controller.commands, selection)
  agents.selectForNextRequest(agent, selection)
  return { applied: true, selection }
}

export function registerV1(ctx) {
  const register = (path, methods, fetch) =>
    ctx.connection.fetch.register({ path, methods, requestBody: 'buffered', fetch })

  register(`${API}/ping`, ['GET'], () =>
    json({
      ok: true,
      service: 'dsh-mobile-api',
      apiVersion: '1',
      host: hostInfo(ctx),
      capabilities: capabilities(ctx),
    }),
  )

  register(`${API}/sessions`, ['GET', 'POST'], async (request) => {
    if (request.method === 'POST') {
      const body = await request.json().catch(() => null)
      if (typeof body !== 'object' || body === null || Array.isArray(body)) {
        return failure('bad-request', 'the request body must be a JSON object', 400)
      }
      const requestedWorkspaceId = typeof body?.workspaceId === 'string' && body.workspaceId.length > 0 ? body.workspaceId : undefined
      const requestedCwd = typeof body?.cwd === 'string' && body.cwd.startsWith('/') ? body.cwd : undefined
      const controller = ctx.get('sessionController')
      if (controller === undefined) return failure('service-unavailable', 'session controller unavailable', 503, true)
      // Validate the requested preset BEFORE create (contract §3): an unknown,
      // broken, or mistyped preset is a client error, never a 500.
      const preset = await validateAgentPreset(ctx, body)
      if (preset.response !== undefined) return preset.response
      const agentPreset = preset.agentPreset
      // Workspace attachment (contract §3, v1ai). `SessionController.create`
      // attaches to the host workspace registry ONLY when it receives a
      // `workspaceId`, so a cwd-only create stayed invisible in the DSH web
      // GUI's workspace list. Resolution: explicit workspaceId is authoritative
      // (cwd is then only a working-directory hint and cannot be forwarded —
      // the controller accepts workspaceId OR cwd); a cwd that exactly matches
      // a registered workspace path becomes that workspace's id; any other cwd
      // keeps the cwd fallback and stays unattached (by design). Neither ⇒ host
      // default cwd, then the created session's actual cwd is resolved.
      let workspaceId = requestedWorkspaceId
      let cwd
      if (workspaceId === undefined) {
        if (requestedCwd === undefined) {
          // Neither: create first, resolve the actual cwd below.
        } else {
          const match = workspaceIndex(ctx).byPath.get(normalizeWorkspacePath(requestedCwd))
          if (match !== undefined) workspaceId = String(match.id)
          else cwd = requestedCwd
        }
      }
      let value
      let attachFailure = null
      try {
        value = await controller.create({
          ...(workspaceId === undefined ? {} : { workspaceId }),
          ...(cwd === undefined ? {} : { cwd }),
          ...(agentPreset === undefined ? {} : { agentPreset }),
        })
      } catch (error) {
        if (typeof error?.code === 'string' && error.code === 'workspace/not-found' && typeof error.details?.workspaceId === 'string') {
          return failure('workspace-not-found', `unknown workspace "${error.details.workspaceId}"`, 404, false, { workspaceId: error.details.workspaceId })
        }
        // The controller creates the session BEFORE attaching it, so an attach
        // failure must not lose the created session: still 201, logged host-side
        // (contract: best-effort, no new wire field).
        if (typeof error?.code === 'string' && error.code === 'session/workspace-attach-failed' && typeof error.details?.sessionId === 'string') {
          value = { sessionId: String(error.details.sessionId), agentPreset: undefined }
          attachFailure = { workspaceId: error.details.workspaceId ?? null, message: String(error?.message ?? error) }
        } else {
          return presetCreateFailure(error, agentPreset)
        }
      }
      // Emulate the preset's declared model as a session-local selection; a
      // preset without a model row inherits the host default unchanged.
      let pinned = { applied: false }
      if (agentPreset !== undefined) {
        try {
          pinned = await applyPresetModel(ctx, controller, value.sessionId)
        } catch (error) {
          const reason = String(error?.message ?? error)
          return failure('agent-preset-unavailable', `agent preset "${agentPreset}" cannot be applied: ${reason}`, 409, false, { presetId: agentPreset, reason })
        }
      }
      const summaries = await readSessions(ctx).catch(() => [])
      const summary = summaries.find((row) => String(row.sessionId) === String(value.sessionId))
      // Neither workspaceId nor cwd was supplied: resolve the created
      // session's ACTUAL cwd and attach when it names a registered workspace.
      let workspaceAttachment = null
      if (requestedWorkspaceId === undefined && requestedCwd === undefined) {
        workspaceAttachment = await attachToMatchingWorkspace(ctx, value.sessionId, summary?.cwd ?? null).catch(() => null)
      }
      if (attachFailure !== null) {
        try {
          ctx.logger?.warn?.(`POST /sessions: session ${value.sessionId} created but could not attach to workspace ${String(attachFailure.workspaceId)}: ${attachFailure.message}`)
        } catch {
          // Logging is best-effort.
        }
      }
      if (workspaceAttachment !== null && workspaceAttachment.attached === false) {
        try {
          ctx.logger?.warn?.(`POST /sessions: session ${value.sessionId} created but could not attach to workspace ${workspaceAttachment.workspaceId}: ${workspaceAttachment.error}`)
        } catch {
          // Logging is best-effort.
        }
      }
      const session = summary === undefined
        ? {
            sessionId: String(value.sessionId), title: 'Untitled session',
            workspaceId: workspaceId ?? workspaceAttachment?.workspaceId ?? attachFailure?.workspaceId ?? null,
            cwd: cwd ?? requestedCwd ?? summary?.cwd ?? null, createdAt: null, updatedAt: null,
            lastPromptAt: null, lastMessagePreview: null, running: false, agentAvailable: false, blank: true, waiting: false, pendingDecisionCount: 0,
            parentSessionId: null, origin: null, delegationDepth: null, agentPreset: value.agentPreset ?? null, agentId: null, agentName: null,
            teamId: null, teamRole: null, lastError: null, model: pinned.applied ? pinned.selection : null,
          }
        : projectSession(summary, buildExtras(ctx, summaries, readWorkspaces(ctx), { items: [] }))
      // The projection is synchronous, but keep the create response honest if a
      // projection read raced the selection event.
      if (pinned.applied && session.model === null) session.model = pinned.selection
      return json({ ok: true, session }, 201)
    }
    const url = new URL(request.url)
    const rawLimit = url.searchParams.get('limit')
    const rawCursor = url.searchParams.get('cursor')
    let limit = LIMITS.sessionsPageSize
    if (rawLimit !== null) {
      const parsed = Number(rawLimit)
      if (!Number.isInteger(parsed) || parsed < 1 || parsed > 100) return failure('bad-limit', 'limit must be an integer between 1 and 100', 400)
      limit = parsed
    }
    let cursor = null
    if (rawCursor !== null) {
      const parsed = Number(rawCursor)
      if (!Number.isInteger(parsed)) return failure('bad-cursor', 'cursor must be an integer watermark', 400)
      cursor = parsed
    }
    try {
      const summaries = await readSessions(ctx, request.signal)
      const sessions = projectAll(ctx, summaries).sort((a, b) => (b.updatedAt ?? 0) - (a.updatedAt ?? 0))
      const filtered = cursor === null ? sessions : sessions.filter((row) => (row.updatedAt ?? 0) < cursor)
      const items = filtered.slice(0, limit)
      const nextCursor = filtered.length > items.length && items.length > 0 ? String(items[items.length - 1].updatedAt ?? 0) : null
      return json({ ok: true, items, nextCursor, hasMore: filtered.length > items.length })
    } catch (error) {
      return failure('internal', String(error?.message ?? error), 500, true)
    }
  })

  // Agent-preset roster for the new-session model picker (contract §3). The
  // registry projects read-only display metadata; v1 has no preset writes, so
  // `managementAvailable` is deliberately the constant `false` — not the §6
  // manager-present semantic — and a missing registry is a 503, never a false
  // body. `order` is dropped; the picker sorts isDefault first, then id.
  // v1ah: `broken` is the canonical boolean and `reason` the registry's
  // diagnostic verbatim (null when healthy) — contract §3, replacing v1ag's
  // raw string passthrough.
  register(`${API}/presets`, ['GET'], async () => {
    const registry = ctx.get('agentPresets')
    if (registry === undefined || typeof registry.remoteExportList !== 'function') {
      return failure('service-unavailable', 'the agent-preset registry is unavailable', 503, true)
    }
    try {
      const roster = await registry.remoteExportList()
      const presets = (Array.isArray(roster?.presets) ? roster.presets : [])
        .map((row) => {
          const reason = presetReason(row)
          return {
            id: row.id,
            ...(typeof row?.name === 'string' && row.name.length > 0 ? { name: row.name } : {}),
            ...(typeof row?.description === 'string' && row.description.length > 0 ? { description: row.description } : {}),
            isDefault: row?.isDefault === true,
            broken: reason !== null,
            reason,
          }
        })
        .sort((a, b) => (b.isDefault ? 1 : 0) - (a.isDefault ? 1 : 0) || String(a.id).localeCompare(String(b.id)))
      return json({ ok: true, managementAvailable: false, presets })
    } catch (error) {
      return failure('internal', String(error?.message ?? error), 500, true)
    }
  })

  register(`${API}/bootstrap`, ['GET'], async (request) => {
    let sessions = []
    let summaries = []
    let sessionError = null
    try {
      summaries = await readSessions(ctx, request.signal)
      // Repair workspace membership for phone-created sessions BEFORE projecting:
      // a cwd-only create is never attached by the controller, so the GUI could
      // not group it. Bounded (count + time budget), best-effort, idempotent, and
      // membership-only; it never fails the bootstrap and adds no wire field.
      try {
        await reconcileWorkspaceMembership(ctx, summaries)
      } catch (error) {
        try {
          ctx.logger?.warn?.(`bootstrap workspace reconcile failed: ${String(error?.message ?? error)}`)
        } catch {
          // Logging is best-effort.
        }
      }
      sessions = projectAll(ctx, summaries).sort((a, b) => (b.updatedAt ?? 0) - (a.updatedAt ?? 0))
    } catch (error) {
      sessionError = String(error?.message ?? error)
    }
    let plugins = []
    try {
      plugins = (await readPlugins(ctx)).map(pluginCard)
    } catch {
      // Degrade the section.
    }
    let jobs = []
    try {
      jobs = readJobs(ctx, summaries)
    } catch {
      // Degrade the section.
    }
    let decisions = { source: 'none', items: [], badge: { waiting: 0, urgent: 0 }, sourceProblem: null }
    try {
      decisions = await readDecisions(ctx, summaries)
    } catch {
      // Degrade the section.
    }
    return json({
      ok: true,
      apiVersion: '1',
      generatedAt: Date.now(),
      host: hostInfo(ctx),
      workspaces: mergedWorkspaces(ctx, sessions),
      sessions,
      agents: projectAgents(ctx, summaries),
      plugins: { managementAvailable: ctx.get('pluginManager') !== undefined, entries: plugins },
      jobs,
      decisions: decisions.items,
      capabilities: capabilities(ctx),
      stream: { path: `${API}/stream`, heartbeatMs: 15000, replayWindow: 500 },
      limits: LIMITS,
      unreadPolicy: 'client',
      ...(sessionError === null ? {} : { warnings: [{ section: 'sessions', message: sessionError }] }),
    })
  })

  register(`${API}/messages`, ['GET'], async (request) => {
    const url = new URL(request.url)
    const sessionId = url.searchParams.get('sessionId')
    if (sessionId === null || sessionId.length === 0) return failure('bad-request', 'sessionId is required', 400)
    const controller = ctx.get('sessionController')
    if (controller === undefined) return failure('service-unavailable', 'session controller unavailable', 503, true)
    const rawLimit = url.searchParams.get('limit')
    let limit = LIMITS.messagesPageSize
    if (rawLimit !== null) {
      if (!/^\d+$/u.test(rawLimit)) return failure('bad-limit', 'limit must be an integer between 1 and 100', 400)
      const parsed = Number(rawLimit)
      if (!Number.isInteger(parsed) || parsed < 1 || parsed > 100) return failure('bad-limit', 'limit must be an integer between 1 and 100', 400)
      limit = parsed
    }
    const beforeRaw = url.searchParams.get('before') ?? url.searchParams.get('cursor')
    let beforeSeq
    if (beforeRaw !== null) {
      if (!/^\d+$/u.test(beforeRaw)) return failure('bad-cursor', 'the before cursor is invalid or expired', 400)
      beforeSeq = Number(beforeRaw)
      if (!Number.isSafeInteger(beforeSeq) || beforeSeq < 0) return failure('bad-cursor', 'the before cursor is invalid or expired', 400)
    }
    try {
      const summaries = await readSessions(ctx, request.signal)
      const summary = summaries.find((row) => String(row.sessionId) === sessionId)
      if (summary === undefined) return notFound('session-not-found', `no such session "${sessionId}"`)
      let throughSeq = summary.projections?.kind === 'sequenced' && Number.isSafeInteger(summary.projections?.asOfSeq)
        ? summary.projections.asOfSeq
        : null
      if (throughSeq === null) {
        const snapshot = await oneFrameSnapshot(ctx, summary, sessionId, request.signal)
        throughSeq = snapshot !== null && Number.isSafeInteger(snapshot.cursor) ? snapshot.cursor : 0
      }
      const subagent = summary?.origin === 'subagent'
      const address = subagent
        ? { kind: 'subagent', parentSessionId: summary.parentSessionId, childSessionId: sessionId, mode: 'unknown' }
        : { kind: 'session', sessionId }
      const page = await controller.page({
        address,
        throughSeq,
        ...(beforeSeq === undefined ? {} : { beforeSeq }),
        maxMessages: limit,
      }, request.signal)
      const items = []
      for (const record of page?.records ?? []) {
        const message = messageOf(record, sessionId)
        if (message !== null) items.push(message)
      }
      const firstSeq = items.length > 0 ? Math.min(...items.map((row) => row.seq)) : null
      return json({
        ok: true,
        sessionId,
        items,
        throughSeq,
        nextCursor: page?.hasMore === true && firstSeq !== null ? String(firstSeq) : null,
        hasMore: page?.hasMore === true,
      })
    } catch (error) {
      return failure('internal', String(error?.message ?? error), 500, true)
    }
  })

  register(`${API}/plugins`, ['GET'], async () => {
    try {
      const entries = (await readPlugins(ctx)).map(pluginCard)
      return json({
        ok: true,
        managementAvailable: ctx.get('pluginManager') !== undefined,
        entries,
      })
    } catch (error) {
      return failure('plugins-failed', String(error?.message ?? error), 500, true)
    }
  })

  register(`${API}/agents`, ['GET'], async (request) => {
    try {
      const summaries = await readSessions(ctx, request.signal)
      return json({ ok: true, items: projectAgents(ctx, summaries), teamAvailable: false })
    } catch (error) {
      return failure('agents-failed', String(error?.message ?? error), 500, true)
    }
  })

  register(`${API}/jobs`, ['GET'], async (request) => {
    const summaries = await readSessions(ctx, request.signal).catch(() => [])
    return json({ ok: true, items: readJobs(ctx, summaries) })
  })

  register(`${API}/decisions`, ['GET'], async (request) => {
    const url = new URL(request.url)
    const state = url.searchParams.get('state') ?? 'pending'
    const sessionId = url.searchParams.get('sessionId')
    try {
      const summaries = await readSessions(ctx, request.signal)
      const decisions = await readDecisions(ctx, summaries)
      let items = state === 'all' ? decisions.all : decisions.items
      if (sessionId !== null && sessionId.length > 0) items = items.filter((decision) => decision.sessionId === sessionId)
      return json({ ok: true, items, answerable: decisionAnswerAvailable(ctx), source: decisions.source, badge: decisions.badge, sourceProblem: decisions.sourceProblem })
    } catch (error) {
      return json({ ok: true, items: [], answerable: decisionAnswerAvailable(ctx), source: 'none', badge: { waiting: 0, urgent: 0 }, sourceProblem: String(error?.message ?? error) })
    }
  })

  register(`${API}/send`, ['POST'], async (request) => {
    const body = await request.json().catch(() => null)
    const sessionId = body?.sessionId
    const text = body?.text
    if (typeof sessionId !== 'string' || sessionId.length === 0) {
      return failure('bad-session', 'sessionId is required')
    }
    if (typeof text !== 'string' || text.trim().length === 0) {
      return failure('bad-text', 'text must be a non-empty string')
    }
    if (Buffer.byteLength(text, 'utf8') > 32768) {
      return failure('text-too-large', 'text exceeds 32768 bytes', 413)
    }
    const controller = ctx.get('sessionController')
    if (controller === undefined) return failure('unavailable', 'session controller unavailable', 503, true)
    const supplied = typeof body?.requestId === 'string' && body.requestId.trim().length > 0
      ? body.requestId.trim()
      : null
    const requestId = supplied ?? randomUUID()
    if (supplied !== null) {
      const seenAt = sentRequests.get(supplied)
      if (typeof seenAt === 'number' && Date.now() - seenAt < SEND_DEDUPE_MS) {
        return json({ ok: true, accepted: true, requestId: supplied, clientRequestId: supplied, duplicate: true })
      }
    }
    try {
      await controller.prompt(
        { requestId, sessionId, mode: 'queue', content: [{ type: 'text', text }] },
        new AbortController().signal,
      )
      if (supplied !== null) sentRequests.set(supplied, Date.now())
      noteSendAccepted(sessionId, requestId)
      return json({ ok: true, accepted: true, requestId, clientRequestId: supplied })
    } catch (error) {
      return failure('send-failed', String(error?.message ?? error), 500, true)
    }
  })

  register(`${API}/plugins/enabled`, ['POST'], async (request) => {
    const body = await request.json().catch(() => null)
    const entryId = typeof body?.entryId === 'string' ? body.entryId : ''
    const manager = ctx.get('pluginManager')
    const entries = await readPlugins(ctx)
    const entry = entries.find((candidate) => String(candidate.entryId ?? candidate.id) === entryId)
    if (entry === undefined) return notFound('plugin-not-found', `no plugin entry "${entryId}"`)
    if (typeof body?.enabled !== 'boolean') return failure('bad-request', 'enabled must be a boolean', 400)
    if (manager === undefined) return failure('management-unavailable', 'no plugin manager', 503)
    try {
      const result = await manager.setPluginEnabled(entryId, body.enabled)
      if (result?.error !== undefined) return failure('plugin-toggle-refused', String(result.error.message ?? 'refused'), 409)
      const refreshed = (await readPlugins(ctx)).map(pluginCard)
      const updated = refreshed.find((candidate) => candidate.entryId === entryId) ?? pluginCard(entry)
      // The manager reports apply mode on ChangeResult.application ('applied' on a
      // live HMR profile, 'restart-required' on a startup-only one) — v1ae read a
      // result.restartRequired property that does not exist, so a host that could
      // not hot-apply could never raise the banner (contract §6: true only when
      // the host could not hot-apply).
      return json({ ok: true, entry: updated, restartRequired: result?.application === 'restart-required' })
    } catch (error) {
      return failure('plugin-toggle-refused', String(error?.message ?? error), 409)
    }
  })

  register(`${API}/jobs/output`, ['GET'], async (request) => {
    const url = new URL(request.url)
    const jobId = url.searchParams.get('jobId')
    if (jobId === null || jobId.length === 0) return failure('bad-request', 'jobId is required', 400)
    const fromRaw = url.searchParams.get('from') ?? '0'
    if (!/^\d+$/u.test(fromRaw)) return failure('bad-request', 'from must be a byte offset', 400)
    const from = Number(fromRaw)
    const summaries = await readSessions(ctx, request.signal).catch(() => [])
    const job = readJobs(ctx, summaries).find((row) => row.jobId === jobId)
    const registry = ctx.get('jobs')
    if (registry === undefined || typeof registry.readAt !== 'function') return failure('service-unavailable', 'job registry unavailable', 503, true)
    const candidates = []
    if (job !== undefined && typeof job.sessionId === 'string') candidates.push(job.sessionId)
    const queryOwner = url.searchParams.get('ownerSessionId') ?? url.searchParams.get('sessionId')
    if (typeof queryOwner === 'string' && queryOwner.length > 0 && !candidates.includes(queryOwner)) candidates.push(queryOwner)
    candidates.push(undefined)
    for (const caller of candidates) {
      try {
        const read = registry.readAt(jobId, from, caller)
        const chunks = (read?.chunks ?? []).map((chunk) => ({ at: Number.isSafeInteger(chunk.at) ? chunk.at : 0, text: typeof chunk.text === 'string' ? chunk.text : '', channel: typeof chunk.channel === 'string' ? chunk.channel : null }))
        return json({
          ok: true,
          jobId,
          from,
          next: Number.isSafeInteger(read?.next) ? read.next : from,
          chunks,
          lossy: read?.lossy === true,
          job: job ?? { jobId, sessionId: typeof caller === 'string' ? caller : null, ownerSessionId: typeof caller === 'string' ? caller : null },
        })
      } catch {
        // Try the next caller candidate (owner → query owner → unowned).
      }
    }
    return notFound('job-not-found', `no such job "${jobId}"`)
  })

  register(`${API}/jobs/kill`, ['POST'], async (request) => {
    const body = await request.json().catch(() => null)
    const jobId = typeof body?.jobId === 'string' ? body.jobId : ''
    if (jobId.length === 0) return failure('bad-request', 'jobId is required', 400)
    const summaries = await readSessions(ctx, request.signal).catch(() => [])
    const job = readJobs(ctx, summaries).find((row) => row.jobId === jobId)
    if (job === undefined) return notFound('job-not-found', `no such job "${jobId}"`)
    const registry = ctx.get('jobs')
    if (registry === undefined || typeof registry.kill !== 'function') return failure('service-unavailable', 'job registry unavailable', 503, true)
    try {
      const outcome = registry.kill(jobId, job.sessionId ?? undefined, typeof body?.reason === 'string' ? body.reason : 'stopped from phone')
      return json({ ok: true, requested: outcome === 'requested', job })
    } catch {
      return notFound('job-not-found', `no such job "${jobId}"`)
    }
  })

  register(`${API}/queue`, ['GET'], async (request) => {
    const url = new URL(request.url)
    const sessionId = url.searchParams.get('sessionId')
    if (sessionId === null || sessionId.length === 0) return failure('bad-session', 'sessionId is required')
    const controller = ctx.get('sessionController')
    if (controller === undefined) return failure('unavailable', 'session controller unavailable', 503, true)
    try {
      const projections = await controller.projections({ sessionId }, request.signal)
      const inbox = projections?.values?.inbox ?? {}
      const items = [...(inbox['next-turn'] ?? []), ...(inbox['next-step'] ?? [])].map((item) => ({
        id: item?.id ?? null,
        text: typeof item?.content === 'string' ? item.content : (Array.isArray(item?.content) ? item.content.filter((b) => b?.type === 'text').map((b) => b.text).join(' ') : ''),
      }))
      return json({ ok: true, items })
    } catch (error) {
      return failure('queue-failed', String(error?.message ?? error), 500, true)
    }
  })

  register(`${API}/queue/remove`, ['POST'], async (request) => {
    const body = await request.json().catch(() => null)
    const sessionId = body?.sessionId
    const itemId = body?.itemId
    if (typeof sessionId !== 'string' || typeof itemId !== 'string') return failure('bad-request', 'sessionId and itemId are required')
    const controller = ctx.get('sessionController')
    if (controller === undefined) return failure('unavailable', 'session controller unavailable', 503, true)
    try {
      await controller.updateQueue({ sessionId, itemId, action: { kind: 'remove' } })
      return json({ ok: true, accepted: true })
    } catch (error) {
      return failure('queue-remove-failed', String(error?.message ?? error), 409)
    }
  })

  register(`${API}/queue/clear`, ['POST'], async (request) => {
    const body = await request.json().catch(() => null)
    const sessionId = body?.sessionId
    if (typeof sessionId !== 'string') return failure('bad-session', 'sessionId is required')
    const controller = ctx.get('sessionController')
    if (controller === undefined) return failure('unavailable', 'session controller unavailable', 503, true)
    try {
      const projections = await controller.projections({ sessionId }, request.signal)
      const inbox = projections?.values?.inbox ?? {}
      const items = [...(inbox['next-turn'] ?? []), ...(inbox['next-step'] ?? [])]
      let removed = 0
      for (const item of items) {
        if (typeof item?.id !== 'string') continue
        try {
          await controller.updateQueue({ sessionId, itemId: item.id, action: { kind: 'remove' } })
          removed += 1
        } catch {
          // Keep clearing the rest.
        }
      }
      return json({ ok: true, removed, remaining: Math.max(0, items.length - removed) })
    } catch (error) {
      return failure('queue-clear-failed', String(error?.message ?? error), 500, true)
    }
  })

  register(`${API}/decisions/answer`, ['POST'], async (request) => {
    const body = await request.json().catch(() => null)
    const decisionId = typeof body?.decisionId === 'string' ? body.decisionId : (typeof body?.id === 'string' ? body.id : '')
    if (decisionId.length === 0) return failure('bad-request', 'decisionId is required', 400)
    return answerDecision(ctx, decisionId, body?.payload ?? body)
  })

  // Single multiplexed SSE stream (contract §4, ADR-0002). The hub owns the
  // global frame id, the 500-frame replay ring, the follow fan-out (cap 20)
  // and the host-wide control/event sources.
  const streamHub = createStreamHub(ctx, {
    projectMessage: (record, sessionId) => messageOf(record, sessionId),
    projectSession: (summary) => projectSession(summary, streamExtras(ctx)),
  })
  activeStreamHub = streamHub
  try {
    ctx.effect(() => () => {
      if (activeStreamHub === streamHub) activeStreamHub = null
      streamHub.stop()
    })
  } catch {
    // A context without effect ownership still serves the stream.
  }
  register(`${API}/stream`, ['GET'], (request) => streamResponse(ctx, streamHub, request))

  const webServer = ctx.get('webServer')
  if (webServer !== undefined) {
    let shared = null
    try {
      shared = ctx.connection.createSharedFetchHandler('/api')
    } catch {
      shared = null
    }
    const dispose = webServer.register({
      kind: 'prefix',
      path: API,
      handler: (req, res) => handleDynamic(ctx, shared, req, res),
    })
    try {
      ctx.effect(() => () => dispose())
    } catch {
      // A context without effect ownership still serves the prefix.
    }
  }
}
