#!/usr/bin/env bash
# Reproduce the Gradle-free Android toolchain used by build-native.sh.
# Downloads Temurin JDK 17 + Android cmdline-tools, then installs platform 34.
# Supported: macOS (arm64/x64) and Linux (x64/aarch64).
set -euo pipefail

ROOT="${ANDROID_TOOLCHAIN:-$HOME/android-toolchain}"
SDK="$ROOT/sdk"
OS="$(uname -s)"
ARCH="$(uname -m)"
case "$ARCH" in
  arm64|aarch64) AARCH=aarch64 ;;
  x86_64|amd64)  AARCH=x64 ;;
  *) echo "unsupported arch: $ARCH" >&2; exit 1 ;;
esac
case "$OS" in
  Darwin)
    JDK_PLATFORM="mac"
    CT_ZIP="commandlinetools-mac-11076708_latest.zip"
    JDK_HOME_GLOB="$ROOT"/jdk-*/Contents/Home
    ;;
  Linux)
    JDK_PLATFORM="linux"
    CT_ZIP="commandlinetools-linux-11076708_latest.zip"
    JDK_HOME_GLOB="$ROOT"/jdk-*
    ;;
  *)
    echo "unsupported OS: $OS (macOS and Linux only; on Windows use WSL, or any Android SDK with JAVA_HOME/ANDROID_SDK_ROOT)" >&2
    exit 1
    ;;
esac

mkdir -p "$ROOT"
if ! ls -d $JDK_HOME_GLOB >/dev/null 2>&1; then
  echo "==> downloading Temurin JDK 17 ($JDK_PLATFORM/$AARCH)"
  curl -L --fail --retry 3 -o "$ROOT/jdk17.tar.gz" \
    "https://api.adoptium.net/v3/binary/latest/17/ga/$JDK_PLATFORM/$AARCH/jdk/hotspot/normal/eclipse"
  tar xzf "$ROOT/jdk17.tar.gz" -C "$ROOT"
fi
JAVA_HOME="$(ls -d $JDK_HOME_GLOB | head -1)"
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"

if [ ! -x "$ROOT/cmdline-tools/bin/sdkmanager" ]; then
  echo "==> downloading Android cmdline-tools"
  curl -L --fail --retry 3 -o "$ROOT/cmdline-tools.zip" \
    "https://dl.google.com/android/repository/$CT_ZIP"
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
echo "now run: ./build-native.sh"
