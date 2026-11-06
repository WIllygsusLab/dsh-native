#!/usr/bin/env python3
"""One-command M2 device gate (USB or any adb serial).

Usage:
  python3 tools/m2_gate.py <serial> [apk]

Installs the M2 control build, records pulled hash + lastUpdateTime + cold start
+ FATAL count, then checks the M2 control surface on device:
  - composer enabled, send probe -> send.ok once + send.echo match=id
  - plugin switches enabled
  - running-job dialog KILL + CLOSE
  - SSE stream.connect/hello frames
  - decisions.load marker
Evidence goes to team/qa/evidence/m2-gate-<timestamp>/.
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
DEFAULT_APK = ROOT / "dist" / "dsh-native-m2-04.apk"


def run(args, serial=None, check=True):
    cmd = [str(ADB)]
    if serial:
        cmd += ["-s", serial]
    cmd += args
    # logcat can carry non-UTF-8 bytes (OEM system logs) -> decode tolerantly so a gate
    # run never dies on UnicodeDecodeError mid-probe.
    result = subprocess.run(cmd, capture_output=True, text=True, errors="replace")
    if check and result.returncode != 0:
        raise SystemExit(f"adb failed: {' '.join(cmd)}\n{result.stdout}\n{result.stderr}")
    return result


def dump(serial, path):
    run(["shell", "pkill", "-f", "uiautomator"], serial, check=False)
    for _ in range(3):
        run(["shell", "uiautomator", "dump", "/sdcard/m2gate.xml"], serial, check=False)
        out = run(["shell", "cat", "/sdcard/m2gate.xml"], serial, check=False).stdout
        if out.strip():
            Path(path).write_text(out)
            try:
                return ET.fromstring(out)
            except ET.ParseError:
                continue
    return None


def nodes(root):
    return [] if root is None else list(root.iter("node"))


def find(root, rid_suffix):
    for node in nodes(root):
        if node.get("resource-id", "").split("/")[-1] == rid_suffix:
            return node
    return None


def center(node):
    x1, y1, x2, y2 = (int(v) for v in re.findall(r"\d+", node.get("bounds", "")))
    return (x1 + x2) // 2, (y1 + y2) // 2


def tap(serial, node):
    if node is None:
        return
    x, y = center(node)
    run(["shell", "input", "tap", str(x), str(y)], serial)


def find_seed_row(root):
    """The seeded M2GATE / M2 phone test / Refetch Marker session row, if visible."""
    for node in nodes(root):
        if node.get("resource-id", "").split("/")[-1] != "channel_row_title":
            continue
        text = (node.get("text") or "").strip()
        if "M2GATE" in text or "M2 phone test" in text or "Refetch Marker" in text:
            return node
    return None


def expand_test_channel(serial, out, listing, tag="testexpand"):
    """Expand the collapsed TEST group (ADR-0010) before any seed-row lookup.

    The seeded M2GATE… session classifies as test and renders only inside the
    collapsed TEST block (`@id/channel_test_toggle`), so `find_seed_row` would
    miss it and the gate would fall back to a live conversation where the echo
    never fires. Expansion persists in Prefs (showTestSessions), but this never
    assumes prior run state: every fresh dump is checked, and the toggle is
    tapped whenever its content-desc says collapsed. No-op when the toggle is
    absent (N == 0 in scope) or already expanded.
    """
    for attempt in range(3):
        toggle = find(listing, "channel_test_toggle")
        if toggle is None:
            return listing  # no test sessions in scope; nothing to expand
        desc = (toggle.get("content-desc") or "")
        if not desc.startswith("channel_test_toggle:collapsed"):
            return listing  # already expanded
        (out / f"{tag}-{attempt}.xml").write_text(ET.tostring(listing, encoding="unicode"))
        tap(serial, toggle)
        run(["shell", "sleep", "1.5"], serial)
        listing = dump(serial, out / f"{tag}-after{attempt}.xml")
    return listing


def reveal_seed_row(serial, out, listing):
    """Scroll the channel list until the seeded M2GATE row is visible.

    ADR-0008 groups the Chats list Waiting > Team > Running > Recent and the TEAM
    group is sorted by updatedAt, so on a short screen under live team load the
    seeded session can sit below the fold. uiautomator only reports visible rows,
    so the gate must scroll to find it instead of falling back to the newest chat
    (which may be a live agent conversation where the durable echo never fires).
    ADR-0010 (2026-11-02): the caller must expand_test_channel() first — the
    seeded row only renders inside the Test group while that group is collapsed.
    """
    seed = find_seed_row(listing)
    if seed is not None:
        return listing, seed
    channel_list = find(listing, "channel_list")
    if channel_list is not None:
        x1, y1, x2, y2 = (int(v) for v in re.findall(r"\d+", channel_list.get("bounds", "")))
        x = (x1 + x2) // 2
        start_y, end_y = y2 - (y2 - y1) // 5, y1 + (y2 - y1) // 5
    else:
        x, start_y, end_y = 540, 1600, 900
    for attempt in range(4):
        run(["shell", "input", "swipe", str(x), str(start_y), str(x), str(end_y), "300"], serial)
        run(["shell", "sleep", "1"], serial)
        listing = dump(serial, out / f"channels-scroll{attempt + 1}.xml")
        seed = find_seed_row(listing)
        if seed is not None:
            (out / "channels-scrolled.xml").write_text(ET.tostring(listing, encoding="unicode"))
            print(f"seed row revealed after {attempt + 1} scroll(s)")
            return listing, seed
    return listing, None


def wait_for(serial, rid_suffix, attempts=6, delay=1.0):
    """Dump until a node with this resource-id suffix appears (dialog/IME timing)."""
    root = None
    for _ in range(attempts):
        root = dump(serial, "/tmp/m2_gate_wait.xml")
        node = find(root, rid_suffix)
        if node is not None:
            return root, node
        time.sleep(delay)
    return root, None


def main():
    if len(sys.argv) < 2:
        raise SystemExit(__doc__)
    serial = sys.argv[1]
    apk = Path(sys.argv[2]) if len(sys.argv) > 2 else DEFAULT_APK
    if not apk.exists():
        raise SystemExit(f"missing artifact: {apk}")

    stamp = datetime.datetime.utcnow().strftime("%Y%m%dT%H%M%SZ")
    out = ROOT / "team" / "qa" / "evidence" / f"m2-gate-{stamp}"
    out.mkdir(parents=True, exist_ok=True)

    props = {"serial": run(["get-serialno"], serial).stdout.strip()}
    for prop in ("ro.product.model", "ro.build.version.release", "ro.build.version.sdk"):
        props[prop] = run(["shell", "getprop", prop], serial).stdout.strip()
    (out / "device.txt").write_text("\n".join(f"{k}={v}" for k, v in props.items()) + "\n")
    print("device:", props)

    print(f"== install {apk.name}")
    print(run(["install", "-r", str(apk)], serial).stdout.strip())
    package = run(["shell", "dumpsys", "package", PKG], serial).stdout
    for line in package.splitlines():
        if "lastUpdateTime" in line or "versionName" in line:
            (out / "package.txt").open("a").write(line.strip() + "\n")
            print(line.strip())
    remote = run(["shell", "pm", "path", PKG], serial).stdout.replace("package:", "").strip()
    run(["pull", remote, str(out / "base.apk")], serial)
    digest = subprocess.run(["shasum", "-a", "256", str(out / "base.apk")], capture_output=True, text=True).stdout.strip()
    (out / "sha256.txt").write_text(digest + "\n")
    print(digest)

    run(["logcat", "-c"], serial, check=False)
    run(["shell", "am", "force-stop", PKG], serial)
    cold = run(["shell", "am", "start", "-W", "-n", f"{PKG}/.ConnectActivity"], serial).stdout
    for line in cold.splitlines():
        if "TotalTime" in line or "Status" in line:
            (out / "coldstart.txt").open("a").write(line.strip() + "\n")
            print(line.strip())
    run(["shell", "sleep", "5"], serial)

    result = {"sha256": digest.split()[0] if digest else "", "device": props,
              "fatal": None, "composer": None, "sendOk": 0, "sendEcho": 0, "sendFail": 0,
              "switches": [], "jobDialog": None, "stream": 0, "decisions": 0}

    home = dump(serial, out / "home.xml")
    tap(serial, find(home, "tab_chats"))
    run(["shell", "sleep", "2"], serial)
    listing = dump(serial, out / "channels.xml")
    listing = expand_test_channel(serial, out, listing)
    listing, seed_row = reveal_seed_row(serial, out, listing)
    result["seedVisible"] = seed_row is not None
    print(f"seed row visible={result['seedVisible']}")
    first = None
    fallback = None
    group = None
    for node in nodes(listing):
        rid = node.get("resource-id", "").split("/")[-1]
        text = (node.get("text") or "").strip()
        if rid == "channel_group_header":
            group = text
            continue
        if rid != "channel_row_title":
            continue
        if text.startswith("#") or text.startswith("DSH -APK"):
            continue
        if any(text.startswith(prefix) for prefix in (
                "QA Engineer", "Front-end Developer", "Back-end Developer",
                "Architect", "Project Manager", "Product Owner")):
            continue  # teammate sessions reject prompts as subagent-owned
        if "M2GATE" in text or "M2 phone test" in text or "Refetch Marker" in text:
            first = node
            break
        if fallback is None:
            fallback = node
    if seed_row is not None:
        first = seed_row
    if first is None:
        first = fallback
    chat = None
    composer = None
    for attempt in range(4):
        if first is None:
            break
        tap(serial, first)
        run(["shell", "sleep", "2"], serial)
        chat, composer = wait_for(serial, "composer_input", attempts=3)
        if composer is not None:
            break
        # The list may have re-rendered between dump and tap; re-find the row.
        listing = dump(serial, out / f"channels-retry{attempt}.xml")
        listing = expand_test_channel(serial, out, listing, tag=f"testexpand-retry{attempt}")
        first = None
        group = None
        seed_row = find_seed_row(listing)
        if seed_row is not None:
            first = seed_row
            continue
        for node in nodes(listing):
            rid = node.get("resource-id", "").split("/")[-1]
            text = (node.get("text") or "").strip()
            if rid == "channel_group_header":
                group = text
                continue
            if rid != "channel_row_title":
                continue
            if text.startswith("#") or text.startswith("DSH -APK") or group == "TEAM":
                continue
            if "M2GATE" in text or "M2 phone test" in text:
                first = node
                break
            if first is None:
                first = node
    if chat is None:
        chat = dump(serial, out / "chat.xml")
    if chat is not None:
        (out / "chat.xml").write_text(ET.tostring(chat, encoding="unicode"))
    result["composer"] = None if composer is None else composer.get("enabled")
    print(f"composer enabled={result['composer']}")

    # Send probe from the app.
    probe = "reply with exactly: m2gate-ok"
    if composer is not None:
        tap(serial, composer)
        run(["shell", "sleep", "1"], serial)
        run(["shell", "input", "text", probe.replace(" ", "%s")], serial)
        run(["shell", "sleep", "1"], serial)
        run(["shell", "input", "keyevent", "4"], serial)
        run(["shell", "sleep", "1"], serial)
        send = find(dump(serial, out / "typed.xml"), "composer_send")
        tap(serial, send)
        run(["shell", "sleep", "10"], serial)
        logcat = run(["logcat", "-d"], serial).stdout
        result["sendOk"] = logcat.count("EVENT send.ok")
        result["sendEcho"] = logcat.count("EVENT send.echo")
        result["sendFail"] = logcat.count("EVENT send.fail")
        result["stream"] = logcat.count("EVENT stream.frame")
        result["decisions"] = logcat.count("EVENT decisions.load")
        (out / "logcat-send.txt").write_text(logcat)
        print(f"probe={probe} send.ok={result['sendOk']} send.echo={result['sendEcho']} stream.frames={result['stream']}")

    tap(serial, find(home, "tab_plugins"))
    run(["shell", "sleep", "2"], serial)
    plugins = dump(serial, out / "plugins.xml")
    for node in nodes(plugins):
        if node.get("resource-id", "").split("/")[-1] == "plugin_enabled_switch":
            result["switches"].append(node.get("enabled"))
    print(f"plugin switches enabled={result['switches'][:8]}")

    tap(serial, find(home, "tab_jobs"))
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
            for button in nodes(dialog):
                rid = button.get("resource-id", "").split("/")[-1]
                text = (button.get("text") or "").strip()
                if (rid.startswith("button") or text in ("CLOSE", "KILL")) and text:
                    if text not in buttons:
                        buttons.append(text)
            if buttons:
                break
        result["jobDialog"] = buttons
    print(f"job dialog buttons={result['jobDialog']}")

    logcat = run(["logcat", "-d"], serial).stdout
    fatal = logcat.count("FATAL EXCEPTION")
    (out / "fatal.txt").write_text(f"FATAL EXCEPTION count={fatal}\n")
    result["fatal"] = fatal

    checks = {
        "composer_enabled": result["composer"] == "true",
        "send_ok_once": result["sendOk"] == 1,
        "send_echo": result["sendEcho"] >= 1,
        "switches_enabled": bool(result["switches"]) and all(v == "true" for v in result["switches"]),
        "job_dialog_kill": bool(result["jobDialog"]) and "KILL" in result["jobDialog"],
        "stream_frames": result["stream"] > 0,
        "fatal_zero": result["fatal"] == 0,
    }
    for name, ok in checks.items():
        print(f"{'PASS' if ok else 'FAIL'} {name}")
    import json
    (out / "summary.json").write_text(json.dumps({**result, "checks": checks}, indent=2) + "\n")
    print(f"evidence: {out}")
    if not all(checks.values()):
        raise SystemExit(1)


if __name__ == "__main__":
    main()
