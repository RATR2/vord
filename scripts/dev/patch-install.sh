#!/usr/bin/env bash
# Dev loop: build the loader dex, patch + re-sign a Discord bundle, update the phone IN PLACE
# (never uninstalls, so the app's data and login survive as long as the key and package match).
#   scripts/dev/patch-install.sh <bundle.apkm> <adb-serial> [out-dir]
# Run inside the claude distrobox (JDK 21). Key: ~/.local/share/vord/vord-test.p12.
set -euo pipefail
BUNDLE=${1:?bundle.apkm}; SERIAL=${2:?adb serial ip:port}
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
OUT=${3:-${TMPDIR:-/tmp}/vord-out}
ADB=${ADB:-$HOME/Android/Sdk/platform-tools/adb}
export ANDROID_HOME=${ANDROID_HOME:-$HOME/Android/Sdk}
export JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk}
cd "$ROOT"
rm -rf "$OUT"
bash gradlew --no-daemon -q :loader:loaderDex
bash gradlew --no-daemon -q :patcher:run --args="$BUNDLE $OUT --loader $ROOT/loader/build/dex/classes.dex --key $HOME/.local/share/vord/vord-test.p12 --splits arm64_v8a,en,es,xxhdpi --package io.github.r4t2.vord.discord --core $ROOT/runtime/core.js" | grep -v '^adb '
"$ADB" -s "$SERIAL" install-multiple -r "$OUT"/base.apk "$OUT"/split_config.*.apk
# Compile the new code right away. Without this the first start after an install runs on the
# interpreter while Android compiles in the background, and the UI can ignore input for a while.
"$ADB" -s "$SERIAL" shell cmd package compile -m speed -f io.github.r4t2.vord.discord
