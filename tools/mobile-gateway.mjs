#!/usr/bin/env node
/**
 * DSH mobile gateway — a transparent TCP bridge from the LAN/Tailscale to the
 * loopback-only `dsh web` host. TCP-level forwarding preserves the Host header,
 * cookies, WebSocket upgrades, and streaming, so the host's auth fence and the
 * 30-day cookie keep working unchanged.
 *
 * Usage: node mobile-gateway.mjs [listenHost] [listenPort] [targetHost] [targetPort]
 *   defaults: 0.0.0.0 3091 127.0.0.1 3080
 */
import net from 'node:net'

const listenHost = process.argv[2] ?? '0.0.0.0'
const listenPort = Number(process.argv[3] ?? 3091)
const targetHost = process.argv[4] ?? '127.0.0.1'
const targetPort = Number(process.argv[5] ?? 3080)

const server = net.createServer((client) => {
  client.setNoDelay(true)
  const upstream = net.connect({ host: targetHost, port: targetPort })
  upstream.setNoDelay(true)

  const close = () => {
    client.destroy()
    upstream.destroy()
  }
  client.on('error', close)
  upstream.on('error', close)
  client.on('close', close)
  upstream.on('close', close)

  client.pipe(upstream)
  upstream.pipe(client)
})

server.on('error', (error) => {
  console.error(`[dsh-mobile-gateway] ${error.message}`)
  process.exitCode = 1
})

server.listen(listenPort, listenHost, () => {
  console.log(`[dsh-mobile-gateway] listening on ${listenHost}:${listenPort} -> ${targetHost}:${targetPort}`)
})
