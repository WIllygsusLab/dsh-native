# ADR-0006 — Auth: pairing token → 30-day cookie, Keystore storage

- Status: **proposed**
- Deciders: architect, backend, lead (security)
- Related: contract §0.3, §4.4; existing `dsh-android/README.md` "Security"

## Context

The DSH host authenticates browser clients with `dsh-client-connection`:

- A **process launch token** is minted at host start and appears in the `dsh web:` URL.
  A root request carrying `?token=<launch-token>` mints an **authority-bound signed cookie**
  and redirects to the clean URL (`BrowserAuth.authorizeIndex`).
- The cookie is `HttpOnly`, `Path=/`, signed with a per-harness secret, valid for
  `cookieMaxAgeDays` (default **30**).
- Every `/api` request must present the cookie **and** an `Origin` equal to `Host`; the Host
  must be loopback or listed in `connection.trustedHosts` (else 403).
- The launch token **rotates on every host restart**; the cookie survives restarts.

The existing app already proves the flow: `dsh://connect?url=…&token=…` QR → cookie exchange
→ cookie-first reconnect (`README.md`; `app/src/dev/dsh/mobile/ConnectActivity.java`).

The new app must not regress this, and must not leak credentials.

## Decision

**Pair once with the launch token, exchange it for the cookie, persist only the cookie
(encrypted with an Android Keystore key), and authenticate every request — including SSE —
with that cookie. No credential ever appears in a URL after pairing.**

Pairing flow:

```
1. Scan QR / paste link: dsh://connect?url=http://192.168.1.10:3091&token=<launch-token>
2. GET <url>/?token=<token>            CookieJar attached; follow redirects
     200 + Set-Cookie → step 3
     401              → "token expired, regenerate the QR"  (token rotates on host restart)
3. Persist baseUrl + cookie (Keystore AES-GCM) + host name; discard the token from memory
4. Verify: GET /api/mobile/v1/ping with Cookie + Origin  → 200
```

Per-request headers (contract §0.3):

```
Cookie: <cookie>
Origin: <baseUrl>            # must equal scheme://Host:Port, else 403
Accept: application/json | text/event-stream
```

Rules:

- **Cookie-first reconnect.** Cold start tries the stored cookie; only a `401
  {code:"unauthenticated"}` sends the user to Connect. Never proactively re-pair.
- **SSE uses the same cookie**, no token in the query string, no `EventSource` (it cannot set
  headers anyway — the app uses `HttpURLConnection`).
- **Keystore storage**: AES-GCM 256 key in `AndroidKeyStore`, alias `dsh.cookie.v1`,
  `setUserAuthenticationRequired(false)` (background reconnect must work); ciphertext +
  IV in private `SharedPreferences`. If the key is invalidated (device reset, biometric
  change), the app clears the cookie and shows Connect — it never falls back to plaintext.
- **No logs.** The HTTP layer must never log the `Cookie`, `Origin`-adjacent tokens, or full
  request headers. Debug logging redacts `Cookie` and any `token=` parameter.
- **Cleartext is allowed** (`android:usesCleartextTraffic="true"`) because the local host has
  no TLS. Certificate checks are never disabled. Remote access is Tailscale/SSH or the LAN
  gateway — not the public internet.
- **Unpair** wipes the cookie, the DB, and read-state prefs.

## Why

- **This is the host's existing, supported mechanism.** Re-implementing auth in the plugin
  would duplicate a security boundary and drift from the web client. Registering inside the
  `/api` fence inherits cookie + Host + Origin checks for free.
- **Cookie-first is what makes the app usable.** The launch token rotates on every host
  restart; a token-only design would force a QR scan after every `dsh web` restart. The
  existing app's `tokenUrl=false` reload behaviour proves the value.
- **Origin == Host is non-obvious and load-bearing.** Forgetting it yields 403 on a LAN IP.
  The contract makes it a required header so `backend`/`frontend` cannot "discover" it at
  runtime.
- **Keystore beats plain SharedPreferences** because the cookie is full host control; a
  rooted-device backup of the prefs should not yield a usable credential. No androidx
  `EncryptedSharedPreferences` is available (ADR-0001), so the Keystore path is hand-rolled
  (~60 LOC) and testable.
- **Token in URL after pairing is a leak** (logs, proxies, screenshots). The contract forbids
  it.

## Consequences

- Connect screen handles three distinct failures: unreachable host (network), token stale
  (401 on exchange), fence rejected (403 → tell the user to add the phone authority to
  `connection.trustedHosts` or use the gateway). These are different messages; do not collapse
  them into "pairing failed".
- The gateway (`tools/mobile-gateway.mjs`, TCP bridge) preserves Host/cookie/streaming, so the
  app needs no special mode for gateway vs direct. Pair against whichever authority it will
  use — the cookie is authority-bound, so changing ports/IPs requires re-pairing.
- QA must test: host restart with cookie still valid; cookie expiry (30 d simulated by
  clearing the cookie); 403 from a non-trusted authority; app process death after pairing.
- The You tab must show connection + auth state (host, profile, "paired 3 days ago", re-pair)
  without ever displaying the cookie value.

## Fallback

If Keystore proves unreliable on a target device (key invalidation storms), fall back to a
plain private `SharedPreferences` cookie with a clear risk note in the You tab. Do not
introduce a third-party crypto library; do not store the launch token at all.
