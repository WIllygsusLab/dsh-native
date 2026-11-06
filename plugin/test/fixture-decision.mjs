#!/usr/bin/env node
/**
 * M2 fixture — create one PENDING question decision for QA's answer round-trip.
 *
 * Creates a scratch session, asks the agent to call `ask_user_question` once,
 * then polls `/api/mobile/v1/decisions` until the card appears. Prints the
 * decisionId + payload. The card stays pending until answered (or the turn is
 * cancelled); answering it exercises the G5 answer surface.
 *
 * Usage: node plugin/test/fixture-decision.mjs [baseUrl] [cookieJar]
 */
import { readFileSync } from 'node:fs'

const BASE = process.argv[2] ?? 'http://127.0.0.1:3080'
const JAR = process.argv[3] ?? '/tmp/dsh.cookies'
const cookie = (() => {
  const pairs = []
  for (const raw of readFileSync(JAR, 'utf8').split('\n')) {
    const line = raw.startsWith('#HttpOnly_') ? raw.slice(10) : raw
    if (line.startsWith('#') || line.trim() === '') continue
    const f = line.split('\t')
    if (f.length >= 7) pairs.push(`${f[5]}=${f[6]}`)
  }
  return pairs.join('; ')
})()
const call = async (method, path, body) => {
  const response = await fetch(`${BASE}${path}`, {
    method,
    headers: { cookie, 'content-type': 'application/json' },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  })
  return { status: response.status, body: await response.json() }
}
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

const created = await call('POST', '/api/mobile/v1/sessions', { cwd: '/tmp' })
if (created.status !== 201 || created.body?.session?.sessionId === undefined) {
  console.error('create failed', created.status, JSON.stringify(created.body))
  process.exit(1)
}
const sessionId = created.body.session.sessionId
console.log('session', sessionId)
await sleep(2500)
const prompt = await call('POST', `/api/mobile/v1/sessions/${sessionId}/messages`, {
  requestId: `fixture-decision-${Date.now()}`,
  text: 'Call the ask_user_question tool exactly once with one question: id "qa-fixture", question "QA fixture: pick a colour", options "red" and "blue", multiSelect false. Do not answer it yourself and do not do anything else.',
})
console.log('prompt', prompt.status, JSON.stringify(prompt.body))
for (let attempt = 0; attempt < 30; attempt += 1) {
  await sleep(3000)
  const decisions = await call('GET', '/api/mobile/v1/decisions?state=all')
  const card = (decisions.body?.items ?? []).find((item) => item.sessionId === sessionId && item.kind === 'question')
  if (card !== undefined) {
    console.log(JSON.stringify({ sessionId, decisionId: card.decisionId, kind: card.kind, answerMode: card.answerMode, prompt: card.prompt, options: card.options, multiple: card.multiple, delivery: card.delivery, state: card.state, workspacePath: card.workspacePath }, null, 2))
    process.exit(0)
  }
}
console.error('no pending question card appeared within 90s (agent may not have called the tool)')
process.exit(2)
