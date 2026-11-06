#!/usr/bin/env python3
"""One-command M1/M2 device gate over USB (or any adb serial).

Usage:
  python3 tools/usb_gate.py <serial> m1   # frozen read-only artifact
  python3 tools/usb_gate.py <serial> m2   # control build

Installs the artifact, pulls/hashes the installed APK, records lastUpdateTime,
measures cold start, opens a chat and the plugins tab, and writes evidence to
team/qa/evidence/usb-<timestamp>/.

M1 expectations: composer_input/composer_send enabled=false, plugin switches
enabled=false, no KILL in the job dialog.
M2 expectations: composer enabled; a send smoke is left to the operator.
"""
import datetime
import os
import time
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ADB = Path(os.environ.get("ANDROID_SDK_ROOT", str(Path.home() / "android-toolchain/sdk"))) / "platform-tools" / "adb"
PKG = "dev.dsh.harness"
EXPECTED_M1_SHA = "0e9cdb85e533e7e1ace5c3d42ad2b6cb85ada83852cef6de7be0f55188fb69b3"


def run(args, serial=None, check=True):
    cmd = [str(ADB)]
    if serial:
        cmd += ["-s", serial]
    cmd += args
    result = subprocess.run(cmd, capture_output=True, text=True)
    if check and result.returncode != 0:
        raise SystemExit(f"adb failed: {' '.join(cmd)}\n{result.stdout}\n{result.stderr}")
    return result


def dump(serial, path):
    run(["shell", "pkill", "-f", "uiautomator"], serial, check=False)
    run(["shell", "uiautomator", "dump", "/sdcard/gate.xml"], serial)
    out = run(["shell", "cat", "/sdcard/gate.xml"], serial).stdout
    Path(path).write_text(out)
    return ET.fromstring(out)


def nodes(root):
    return list(root.iter("node"))


def find(root, rid_suffix):
    for node in nodes(root):
        if node.get("resource-id", "").split("/")[-1] == rid_suffix:
            return node
    return None


def center(node):
    x1, y1, x2, y2 = (int(v) for v in re.findall(r"\d+", node.get("bounds", "")))
    return (x1 + x2) // 2, (y1 + y2) // 2


def tap(serial, node):
    x, y = center(node)
    run(["shell", "input", "tap", str(x), str(y)], serial)


def wait_for(serial, rid_suffix, attempts=6, delay=1.0):
    """Dump until a node with this resource-id suffix appears (dialog/IME timing)."""
    for _ in range(attempts):
        root = dump(serial, "/tmp/usb_gate_wait.xml")
        node = find(root, rid_suffix)
        if node is not None:
            return root, node
        time.sleep(delay)
    return root if 'root' in locals() else None, None


def main():
    if len(sys.argv) < 2:
        raise SystemExit(__doc__)
    serial = sys.argv[1]
    mode = sys.argv[2] if len(sys.argv) > 2 else "m1"
    apk = ROOT / "dist" / ("dsh-native-m1.apk" if mode == "m1" else "dsh-native.apk")
    if not apk.exists():
        raise SystemExit(f"missing artifact: {apk}")

    stamp = datetime.datetime.utcnow().strftime("%Y%m%dT%H%M%SZ")
    out = ROOT / "team" / "qa" / "evidence" / f"usb-{stamp}"
    out.mkdir(parents=True, exist_ok=True)

    props = {"serial": run(["get-serialno"], serial).stdout.strip()}
    for prop in ("ro.product.model", "ro.build.version.release", "ro.build.version.sdk", "ro.build.version.incremental"):
        props[prop] = run(["shell", "getprop", prop], serial).stdout.strip()
    (out / "device.txt").write_text("\n".join(f"{k}={v}" for k, v in props.items()) + "\n")
    print("device:", props)

    print(f"== install {apk.name} on {serial}")
    print(run(["install", "-r", str(apk)], serial).stdout.strip())
    package = run(["shell", "dumpsys", "package", PKG], serial).stdout
    for line in package.splitlines():
        if "lastUpdateTime" in line or "versionName" in line:
            print(line.strip())
            (out / "package.txt").open("a").write(line.strip() + "\n")

    remote = run(["shell", "pm", "path", PKG], serial).stdout.replace("package:", "").strip()
    run(["pull", remote, str(out / "base.apk")], serial)
    digest = subprocess.run(["shasum", "-a", "256", str(out / "base.apk")], capture_output=True, text=True).stdout.strip()
    print(digest)
    (out / "sha256.txt").write_text(digest + "\n")
    actual_sha = digest.split()[0] if digest else ""
    hash_ok = (mode != "m1") or (actual_sha == EXPECTED_M1_SHA)
    print(f"sha match expected={EXPECTED_M1_SHA} actual={actual_sha} -> {'PASS' if hash_ok else 'FAIL'}")

    run(["logcat", "-c"], serial, check=False)
    run(["shell", "am", "force-stop", PKG], serial)
    cold = run(["shell", "am", "start", "-W", "-n", f"{PKG}/.ConnectActivity"], serial).stdout
    for line in cold.splitlines():
        if "TotalTime" in line or "Status" in line:
            print(line.strip())
            (out / "coldstart.txt").open("a").write(line.strip() + "\n")
    run(["shell", "sleep", "3"], serial)

    home = dump(serial, out / "home.xml")
    result = {"mode": mode, "sha256": actual_sha, "hashOk": hash_ok, "device": props,
              "composer": None, "send": None, "composerHint": None, "eventSend": None,
              "switches": [], "fatal": None, "jobDialog": None}
    logcat = run(["logcat", "-d"], serial).stdout
    fatal = logcat.count("FATAL EXCEPTION")
    (out / "fatal.txt").write_text(f"FATAL EXCEPTION count={fatal}\n")
    result["fatal"] = fatal
    print(f"FATAL count={fatal}")

    chat_tab = find(home, "tab_chats")
    if chat_tab is not None:
        tap(serial, chat_tab)
        run(["shell", "sleep", "2"], serial)
        listing = dump(serial, out / "channels.xml")
        first = None
        for node in nodes(listing):
            if node.get("resource-id", "").split("/")[-1] == "channel_row_title":
                text = (node.get("text") or "").strip()
                if text.startswith("#"):
                    continue  # special channel, not a session transcript
                first = node
                break
        if first is not None:
            tap(serial, first)
            run(["shell", "sleep", "3"], serial)
            chat, composer = wait_for(serial, "composer_input")
            if chat is None:
                chat = dump(serial, out / "chat.xml")
            Path(out / "chat.xml").write_text(ET.tostring(chat, encoding="unicode")) if chat is not None else None
            send = find(chat, "composer_send")
            result["composer"] = None if composer is None else composer.get("enabled")
            result["send"] = None if send is None else send.get("enabled")
            result["composerHint"] = None if composer is None else (composer.get("text") or "")
            print(f"composer enabled={result['composer']} send enabled={result['send']} hint={result['composerHint']}")

    plugins_tab = find(home, "tab_plugins")
    if plugins_tab is not None:
        tap(serial, plugins_tab)
        run(["shell", "sleep", "2"], serial)
        plugins = dump(serial, out / "plugins.xml")
        for node in nodes(plugins):
            if node.get("resource-id", "").split("/")[-1] == "plugin_enabled_switch":
                result["switches"].append(node.get("enabled"))
        print(f"plugin switches enabled={result['switches'][:8]}")

    jobs_tab = find(home, "tab_jobs")
    if jobs_tab is not None:
        tap(serial, jobs_tab)
        run(["shell", "sleep", "2"], serial)
        jobs = dump(serial, out / "jobs.xml")
        job_row = None
        for node in nodes(jobs):
            if node.get("resource-id", "").split("/")[-1] == "job_row":
                job_row = node
                break
        if job_row is not None:
            tap(serial, job_row)
            buttons = []
            for attempt in range(6):
                run(["shell", "sleep", "1"], serial)
                dialog = dump(serial, out / "job-dialog.xml")
                for node in nodes(dialog):
                    rid = node.get("resource-id", "").split("/")[-1]
                    text = (node.get("text") or "").strip()
                    if (rid.startswith("button") or text in ("CLOSE", "KILL")) and text:
                        if text not in buttons:
                            buttons.append(text)
                if buttons:
                    break
            result["jobDialog"] = buttons
            print(f"job dialog buttons={buttons}")

    logcat2 = run(["logcat", "-d"], serial).stdout
    result["eventSend"] = logcat2.count("EVENT send")
    (out / "event-send.txt").write_text(f"EVENT send count={result['eventSend']}\n")
    print(f"EVENT send count={result['eventSend']}")

    # M1 assertions
    checks = {}
    if mode == "m1":
        checks["hash"] = hash_ok
        checks["composer_disabled"] = result["composer"] == "false"
        checks["send_disabled"] = result["send"] == "false"
        checks["hint_read_only"] = "Read-only" in (result["composerHint"] or "")
        checks["switches_disabled"] = bool(result["switches"]) and all(v == "false" for v in result["switches"])
        checks["fatal_zero"] = result["fatal"] == 0
        checks["event_send_zero"] = result["eventSend"] == 0
        checks["job_dialog_close_only"] = (result["jobDialog"] or []) == ["CLOSE"]
        for name, ok in checks.items():
            print(f"{'PASS' if ok else 'FAIL'} {name}")
        (out / "summary.json").write_text(__import__("json").dumps({**result, "checks": checks}, indent=2) + "\n")
        if not all(checks.values()):
            print(f"evidence: {out}")
            raise SystemExit(1)
    else:
        (out / "summary.json").write_text(__import__("json").dumps(result, indent=2) + "\n")

    print(f"evidence: {out}")
    print(f"summary: {result}")


if __name__ == "__main__":
    main()
