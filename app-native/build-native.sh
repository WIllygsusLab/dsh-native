#!/usr/bin/env bash
# Build Harness Native (dev.dsh.native) without Gradle:
#   aapt2 -> javac -> d8 -> zipalign -> apksigner
# Derived from ../build.sh. Output: dist/harness-native.apk (QA install path).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP="$ROOT/app-native"
SDK="${ANDROID_SDK_ROOT:-$HOME/android-toolchain/sdk}"
if [ -z "${JAVA_HOME:-}" ]; then
  JAVA_HOME="$(ls -d "$HOME"/android-toolchain/jdk-*/Contents/Home 2>/dev/null | head -1)"
fi
BT="$SDK/build-tools/34.0.0"
PLATFORM="$SDK/platforms/android-34"
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"

BUILD="$ROOT/build-native"
DIST="$ROOT/dist"
OUT="$DIST/harness-native.apk"

for tool in "$JAVA_HOME/bin/javac" "$BT/aapt2" "$BT/d8" "$BT/zipalign" "$BT/apksigner" "$PLATFORM/android.jar"; do
  if [ ! -e "$tool" ]; then
    echo "missing tool: $tool" >&2
    echo "set ANDROID_SDK_ROOT / JAVA_HOME, or run tools/setup-toolchain.sh" >&2
    exit 1
  fi
done

rm -rf "$BUILD"
mkdir -p "$BUILD/gen" "$BUILD/classes" "$BUILD/dex" "$DIST"

echo "==> aapt2 compile"
"$BT/aapt2" compile --dir "$APP/res" -o "$BUILD/res.zip"

echo "==> aapt2 link"
"$BT/aapt2" link \
  -o "$BUILD/base.apk" \
  -I "$PLATFORM/android.jar" \
  --manifest "$APP/AndroidManifest.xml" \
  --java "$BUILD/gen" \
  --min-sdk-version 26 \
  --target-sdk-version 34 \
  --version-code 1 \
  --version-name 0.1.0 \
  --auto-add-overlay \
  "$BUILD/res.zip"

echo "==> javac"
find "$APP/src" "$BUILD/gen" -name '*.java' > "$BUILD/sources.txt"
"$JAVA_HOME/bin/javac" \
  -source 11 -target 11 \
  -Xlint:-options \
  -classpath "$PLATFORM/android.jar" \
  -d "$BUILD/classes" \
  @"$BUILD/sources.txt"

echo "==> d8"
find "$BUILD/classes" -name '*.class' > "$BUILD/classes.txt"
"$BT/d8" \
  --lib "$PLATFORM/android.jar" \
  --min-api 26 \
  --output "$BUILD/dex" \
  @"$BUILD/classes.txt"

echo "==> package"
cp "$BUILD/base.apk" "$BUILD/unaligned.apk"
( cd "$BUILD/dex" && zip -q "$BUILD/unaligned.apk" classes.dex )
"$BT/zipalign" -f 4 "$BUILD/unaligned.apk" "$BUILD/aligned.apk"

echo "==> sign"
SIGN="$ROOT/signing"
mkdir -p "$SIGN"
KEYSTORE="$SIGN/dsh-mobile.jks"
PROPS="$SIGN/keystore.properties"
if [ ! -f "$PROPS" ]; then
  PASS="$(openssl rand -hex 16)"
  printf 'storePass=%s\nkeyPass=%s\n' "$PASS" "$PASS" > "$PROPS"
fi
# shellcheck disable=SC1090
source "$PROPS"
if [ ! -f "$KEYSTORE" ]; then
  "$JAVA_HOME/bin/keytool" -genkeypair \
    -keystore "$KEYSTORE" -storetype PKCS12 \
    -storepass "$storePass" -keypass "$keyPass" \
    -alias dsh -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=DSH Mobile, OU=Personal, O=DSH, C=US" >/dev/null 2>&1
fi
"$BT/apksigner" sign \
  --ks "$KEYSTORE" \
  --ks-pass "pass:$storePass" \
  --key-pass "pass:$keyPass" \
  --out "$OUT" \
  "$BUILD/aligned.apk"
"$BT/apksigner" verify --print-certs "$OUT"

echo
echo "APK: $OUT"
ls -lh "$OUT"
