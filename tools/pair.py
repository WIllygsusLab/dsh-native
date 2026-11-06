#!/usr/bin/env python3
"""Generate a dsh:// pairing link and QR for DSH Mobile.

The link carries the host origin and the current process launch token. The app
exchanges it once for a 30-day cookie; the QR is only as sensitive as that token.

Examples:
  python3 tools/pair.py                       # LAN + gateway defaults
  python3 tools/pair.py --host 100.107.174.24 --port 3091
  python3 tools/pair.py --url http://192.168.1.10:3091 --token ...
"""
import argparse
import re
from pathlib import Path
from urllib.parse import parse_qs, urlencode, urlparse

DEFAULT_LOG = Path.home() / ".dsh" / "web-restart.log"
DEFAULT_OUT = Path(__file__).resolve().parent.parent / "dist" / "pair-qr.png"


def latest_url(log: Path) -> str:
    if not log.exists():
        raise SystemExit(f"no log at {log}; pass --url")
    matches = re.findall(r"dsh web: (https?://\S+)", log.read_text(errors="ignore"))
    if not matches:
        raise SystemExit(f"no 'dsh web:' URL in {log}; pass --url")
    return matches[-1]


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default="192.168.1.10", help="phone-reachable host")
    parser.add_argument("--port", default="3091", help="gateway or dsh web port")
    parser.add_argument("--url", help="full dsh web URL (reads the token from it)")
    parser.add_argument("--token", help="token override")
    parser.add_argument("--log", default=str(DEFAULT_LOG))
    parser.add_argument("--out", default=str(DEFAULT_OUT))
    args = parser.parse_args()

    if args.url:
        source = args.url
    else:
        source = latest_url(Path(args.log))
    token = args.token or (parse_qs(urlparse(source).query).get("token") or [None])[-1]
    if not token:
        raise SystemExit("no token found; pass --token")

    base = f"http://{args.host}:{args.port}"
    link = "dsh://connect?" + urlencode({"url": base, "token": token})
    print(link)

    try:
        import segno
    except ImportError:
        print("QR skipped: install with `python3 -m pip install --user segno`")
        return
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    qr = segno.make(link, error="m")
    qr.save(str(out), scale=9, border=3, dark="#0A0D13", light="#FFFFFF")
    print(f"wrote {out}")


if __name__ == "__main__":
    main()
