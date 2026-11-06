#!/usr/bin/env bash
# Install dsh-plugin-mobile into a DSH profile without restarting it.
# Adds the link dependency, the node_modules link, and a profile patch insert.
# The profile's HMR watcher mounts the insert live.
set -euo pipefail

PROFILE="${1:-web}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PLUGIN_DIR="$ROOT/plugin"
PROFILE_DIR="${DSH_HOME:-$HOME/.dsh}/profiles/$PROFILE"

if [ ! -d "$PROFILE_DIR" ]; then
  echo "no profile at $PROFILE_DIR" >&2
  exit 1
fi
if [ ! -f "$PLUGIN_DIR/package.json" ]; then
  echo "no plugin at $PLUGIN_DIR" >&2
  exit 1
fi

STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
cp "$PROFILE_DIR/package.json" "$PROFILE_DIR/package.json.bak-$STAMP"
cp "$PROFILE_DIR/cordis.patch.yml" "$PROFILE_DIR/cordis.patch.yml.bak-$STAMP"

python3 - "$PROFILE_DIR/package.json" "$PLUGIN_DIR" <<'PY'
import json
import sys

manifest_path, plugin_dir = sys.argv[1], sys.argv[2]
with open(manifest_path) as handle:
    manifest = json.load(handle)
dependencies = manifest.setdefault("dependencies", {})
dependencies["dsh-plugin-mobile"] = f"link:{plugin_dir}"
with open(manifest_path, "w") as handle:
    json.dump(manifest, handle, indent=2)
    handle.write("\n")
PY

ln -sfn "$PLUGIN_DIR" "$PROFILE_DIR/node_modules/dsh-plugin-mobile"

if ! grep -q "dsh-plugin-mobile" "$PROFILE_DIR/cordis.patch.yml"; then
  cat >> "$PROFILE_DIR/cordis.patch.yml" <<'YAML'

# Mobile control surface for the Android app: /api/mobile/{ping,plugins}.
- insert:
    - id: mobile
      name: dsh-plugin-mobile
YAML
fi

if command -v pnpm >/dev/null 2>&1; then
  ( cd "$PROFILE_DIR" && pnpm install --lockfile-only --ignore-scripts >/dev/null 2>&1 ) || true
fi

echo "installed dsh-plugin-mobile into profile '$PROFILE'"
echo "backups: package.json.bak-$STAMP, cordis.patch.yml.bak-$STAMP"
echo "the HMR watcher should mount it live; verify with an authenticated request to:"
echo "  GET /api/mobile/ping"
