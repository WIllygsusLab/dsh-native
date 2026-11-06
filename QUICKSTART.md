# Quick start — from a fresh clone to a paired phone

This is the end-to-end path. You need: a **DeepSeek Harness** install (the `dsh` CLI), an Android
phone on the same network, and an Android SDK + JDK 17 (or let `tools/setup-toolchain.sh` fetch them
on macOS/Linux).

## 1. Start your DSH host and copy the launch token

```bash
dsh web
```

It prints a URL like `http://127.0.0.1:3080/?token=XXXXXXXX`. **That token is your pairing token.**
It rotates on every host restart; the cookie you get from pairing lasts 30 days.

## 2. Trust your LAN address (one-time)

The phone reaches the host through the gateway, and the gateway preserves the `Host` header — so the
host must trust your LAN IP. Add it to the profile's `cordis.patch.yml` (port-less IP literals match
any port):

```yaml
- id: connection
  config:
    trustedHosts:
      - 192.168.1.10        # <- your machine's LAN IP
```

Live-reloads on profiles with `patchReload: live`; otherwise restart the host.

## 3. Install the mobile API plugin

```bash
./tools/install-plugin.sh web      # backs up profile files, links the plugin, adds the patch row
```

Verify it mounted (authenticated request, using the same cookie your browser has):

```bash
curl -s http://127.0.0.1:3080/api/mobile/ping
```

A new bundle only loads after a host restart; the patch row applies live.

## 4. Start the LAN gateway

The host listens on loopback only, so the phone needs a bridge:

```bash
./tools/start-gateway.sh 0.0.0.0 3091 3080
```

## 5. Build the APK

```bash
./tools/setup-toolchain.sh     # macOS/Linux: fetches JDK 17 + Android SDK pieces (~1 GB)
./build-native.sh              # -> dist/dsh-native.apk
```

Already have an Android SDK? Set `JAVA_HOME` and `ANDROID_SDK_ROOT` (needs build-tools 34.0.0 and
platforms;android-34) and skip the setup script.

## 6. Install and pair the phone

```bash
adb install -r dist/dsh-native.apk
adb shell am start -n dev.dsh.harness/.ConnectActivity
```

Then either paste the pairing URL into the app, or scan it as a QR:

```bash
./tools/pair-url.sh 192.168.1.10 <launch-token>     # prints dsh://connect?url=...&token=...
```

The app exchanges the token for a cookie (stored in the Android Keystore) and reconnects with it.

## Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| `401 unauthenticated` | token expired (host restarted) | copy the fresh token from the `dsh web` URL, re-pair |
| `403` on every request | LAN host not trusted | add your IP to `connection.trustedHosts` (step 2) |
| "Can't reach the host" | gateway not running / wrong IP | run `tools/start-gateway.sh`, use your LAN IP not `127.0.0.1` |
| Sheet shows no presets | plugin not mounted | `./tools/install-plugin.sh web`, restart the host, check `/api/mobile/ping` |
| Build fails: `missing tool` | SDK layout differs | set `JAVA_HOME` + `ANDROID_SDK_ROOT` to a real SDK |
| Emulator can't reach the host | loopback alias | use `10.0.2.2` as the host IP, or trust it (Android emulator's alias) |

## Notes

- The mobile API mounts inside the host's authenticated `/api` fence; it needs no separate auth of
  its own — the app's cookie is the host's cookie.
- Pairing token = credential. Don't share it; it rotates on restart anyway.
