# M2 device gate

One command for the final end-to-end M2 run on a real Galaxy over USB:

```bash
python3 tools/m2_gate.py <serial> [apk]
```

Default APK: `dist/dsh-native-m2-04.apk` (frozen `1889465e…`).

## What it does

- installs the APK, pulls/hashes the installed `base.apk`, records `lastUpdateTime`
- cold start + FATAL count
- opens the first chat and sends a probe from the composer (type, hide IME, tap Send)
- asserts `EVENT send.ok` once and `send.echo` present; counts `stream.frame` and `decisions.load`
- opens Plugins and checks switches are enabled
- opens Jobs and checks the running-job dialog has KILL + CLOSE
- writes `summary.json` + all dumps under `team/qa/evidence/m2-gate-<timestamp>/` and exits non-zero on failure

## Pass criteria

composer enabled · send.ok exactly once · send.echo present · plugin switches enabled ·
job dialog KILL+CLOSE · stream frames > 0 · FATAL 0 · pulled hash matches the declared subject.

## Notes

- Run it only when the device is authorized and no one else is driving that emulator/device.
- For the frozen M1 read-only gate use `tools/usb_gate.py <serial> m1`.
- **Run devices strictly sequentially** — the evidence dir is named to the second, so
  concurrent runs collide in one directory and their UI/marker checks race each other.
- **Seed a fresh `M2GATE`-titled session first** (POST `/api/mobile/v1/sessions` `{cwd}` +
  `/send`), or the probe lands in whatever chat is newest — including a live agent
  conversation, where the durable echo never fires (`send.ok=0` with everything else PASS).
  **The seed prompt must be self-answering** ("M2GATE …: reply with exactly: ready") —
  **never an imperative task**: an imperative seed gets picked up by the auto-spawned
  workspace agent as real work (it rebuilt `dist/` and started its own gate during the
  task-2 run — contained; see `team/qa/evidence/task2-fab-captions/summary.md`).
- The gate needs no pairing (the app's persisted cookie works), but the device must be
  unlocked — a keyguarded screen produces a systemui dump and all-FAIL checks.
