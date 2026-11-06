#!/usr/bin/env python3
"""Snapshot the web profile's extensions into the APK's offline catalog.

The live deck always prefers /api/mobile/plugins; this file only keeps the
sheet useful when the companion plugin is not loaded yet.
"""
import json
import os
from pathlib import Path
from typing import Optional

HOME = Path.home()
PROFILE = HOME / ".dsh" / "profiles" / "web"
OUT = Path(__file__).resolve().parent.parent / "app" / "assets" / "plugin-catalog.json"


def resolve(name: str) -> Optional[Path]:
    for base in (PROFILE / "node_modules", HOME / ".local/lib/node_modules/@deepseek-ai/dsh/node_modules"):
        candidate = base / name
        if candidate.exists():
            return candidate.resolve()
    return None


def english(package_dir: Path, manifest: dict) -> tuple[str, str]:
    title = manifest.get("name", "")
    description = manifest.get("description", "")
    locale = package_dir / "locale" / "en.json"
    if locale.exists():
        try:
            data = json.loads(locale.read_text())
            meta = data.get("meta", data)
            if isinstance(meta.get("title"), str):
                title = meta["title"]
            if isinstance(meta.get("description"), str):
                description = meta["description"]
        except Exception:
            pass
    return title, description


def main() -> None:
    manifest = json.loads((PROFILE / "package.json").read_text())
    plugins = []
    for name in sorted(manifest.get("dependencies", {})):
        if not name.startswith("dsh-plugin-"):
            continue
        package_dir = resolve(name)
        if package_dir is None:
            continue
        try:
            meta = json.loads((package_dir / "package.json").read_text())
        except Exception:
            continue
        title, description = english(package_dir, meta)
        plugins.append({
            "moduleName": name,
            "entryId": "catalog:" + name,
            "title": title,
            "description": description,
        })
    payload = {
        "generatedFrom": str(PROFILE),
        "plugins": plugins,
    }
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(payload, indent=2) + "\n")
    print(f"wrote {OUT} ({len(plugins)} plugins)")


if __name__ == "__main__":
    main()
