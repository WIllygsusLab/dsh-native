/**
 * dsh-plugin-mobile — a native-friendly control surface for DeepSeek Harness.
 *
 * The Web GUI is the full product; this plugin adds the small, stable JSON
 * surface a phone app needs to map the Cordis plugin tree and toggle plugins
 * without scraping the desktop UI. Every route lives inside Connection's
 * authenticated `/api` fence, so it inherits the same browser cookie, Host
 * fence, and Origin check as the rest of the app.
 *
 * Routes:
 *   GET  /api/mobile/ping             liveness + host identity
 *   GET  /api/mobile/plugins          plugin inventory (Loader order)
 *   POST /api/mobile/plugins/enabled  { entryId, enabled } -> plugin manager
 *
 * @module dsh-plugin-mobile
 */

export const name = 'mobile'

/** The authenticated Fetch registry and the Loader/plugin manager services. */
export const inject = ['connection']

const PHASES = ['pending', 'loading', 'active', 'failed', null, 'unloading']

function json(data, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: {
      'content-type': 'application/json; charset=utf-8',
      'cache-control': 'no-store',
    },
  })
}

function failure(code, message, status = 400) {
  return json({ ok: false, error: { code, message } }, status)
}

function phaseOf(entry) {
  const state = entry.fiber?.state
  return state === undefined ? null : PHASES[state] ?? null
}

function entryOf(entry) {
  return {
    entryId: entry.id,
    moduleName: entry.options?.name ?? 'unknown',
    enabled: !entry.disabled,
    fiberPhase: phaseOf(entry),
  }
}

/** Read the Loader directly; this is the fallback when pluginManager is absent. */
function readLoader(ctx) {
  const loader = ctx.get('loader')
  if (loader === undefined) return []
  return [...loader.entries()]
    .filter((entry) => !entry.options?.group)
    .map(entryOf)
}

/** The manager's projection adds patchId and readOnlyReason; it is the control truth. */
async function readPlugins(ctx) {
  const manager = ctx.get('pluginManager')
  if (manager !== undefined) return await manager.listPlugins()
  return readLoader(ctx)
}

function hostInfo(ctx) {
  const profile = ctx.get('profileContext')
  return {
    pid: process.pid,
    node: process.version,
    uptimeSeconds: Math.round(process.uptime()),
    port: ctx.get('webServer')?.port ?? null,
    profile: profile?.name ?? null,
  }
}

export function apply(ctx) {
  const register = (path, methods, fetch) =>
    ctx.connection.fetch.register({
      path,
      methods,
      requestBody: 'buffered',
      fetch,
    })

  register('/api/mobile/ping', ['GET'], () =>
    json({ ok: true, service: 'dsh-plugin-mobile', host: hostInfo(ctx) }),
  )

  register('/api/mobile/plugins', ['GET'], async () => {
    try {
      const entries = await readPlugins(ctx)
      return json({
        ok: true,
        host: hostInfo(ctx),
        managementAvailable: ctx.get('pluginManager') !== undefined,
        entries,
      })
    } catch (error) {
      return failure('inventory-failed', String(error?.message ?? error), 500)
    }
  })

  register('/api/mobile/plugins/enabled', ['POST'], async (request) => {
    let body
    try {
      body = await request.json()
    } catch {
      return failure('bad-json', 'the request body must be JSON')
    }
    const entryId = body?.entryId
    const enabled = body?.enabled
    if (typeof entryId !== 'string' || entryId.length === 0) {
      return failure('bad-entry', 'entryId must be a non-empty string')
    }
    if (typeof enabled !== 'boolean') {
      return failure('bad-enabled', 'enabled must be a boolean')
    }
    const manager = ctx.get('pluginManager')
    if (manager === undefined) {
      return failure('management-unavailable', 'this host has no plugin manager', 503)
    }
    try {
      const result = await manager.setPluginEnabled(entryId, enabled)
      if (result?.error !== undefined) {
        return json(
          {
            ok: false,
            error: {
              code: result.error.code ?? 'toggle-failed',
              message: result.error.message ?? 'the plugin manager refused the change',
            },
            result,
          },
          409,
        )
      }
      const entries = await readPlugins(ctx)
      return json({ ok: true, result, entries })
    } catch (error) {
      return failure(
        error?.code ?? 'toggle-failed',
        String(error?.message ?? error),
        409,
      )
    }
  })

}
