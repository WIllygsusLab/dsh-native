#!/usr/bin/env bash
# Reproduce the Gradle-free Android toolchain used by build.sh.
# Downloads Temurin JDK 17 and Android cmdline-tools, then installs platform 34.
set -euo pipefail

ROOT="${ANDROID_TOOLCHAIN:-$HOME/android-toolchain}"
SDK="$ROOT/sdk"
ARCH="$(uname -m)"
case "$ARCH" in
  arm64) AARCH=aarch64 ;;
  x86_64) AARCH=x64 ;;
  *) echo "unsupported arch: $ARCH" >&2; exit 1 ;;
esac

mkdir -p "$ROOT"
if ! ls -d "$ROOT"/jdk-*/Contents/Home >/dev/null 2>&1; then
  echo "==> downloading Temurin JDK 17"
  curl -L --fail --retry 3 -o "$ROOT/jdk17.tar.gz" \
    "https://api.adoptium.net/v3/binary/latest/17/ga/mac/$AARCH/jdk/hotspot/normal/eclipse"
  tar xzf "$ROOT/jdk17.tar.gz" -C "$ROOT"
fi
JAVA_HOME="$(ls -d "$ROOT"/jdk-*/Contents/Home | head -1)"
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"

if [ ! -x "$ROOT/cmdline-tools/bin/sdkmanager" ]; then
  echo "==> downloading Android cmdline-tools"
  curl -L --fail --retry 3 -o "$ROOT/cmdline-tools.zip" \
    "https://dl.google.com/android/repository/commandlinetools-mac-11076708_latest.zip"
  unzip -q -o "$ROOT/cmdline-tools.zip" -d "$ROOT"
fi

export ANDROID_SDK_ROOT="$SDK"
export ANDROID_HOME="$SDK"
mkdir -p "$SDK"
yes | "$ROOT/cmdline-tools/bin/sdkmanager" --sdk_root="$SDK" --licenses >/dev/null 2>&1 || true
"$ROOT/cmdline-tools/bin/sdkmanager" --sdk_root="$SDK" \
  "platform-tools" "platforms;android-34" "build-tools;34.0.0"

echo
echo "JAVA_HOME=$JAVA_HOME"
echo "ANDROID_SDK_ROOT=$SDK"
echo "now run: ./build.sh"
