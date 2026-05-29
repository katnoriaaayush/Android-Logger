#!/usr/bin/env bash
# Build logcollectord for ARM64 Android using a standalone NDK toolchain.
#
# Prerequisites:
#   - Android NDK r25c or later downloaded (set NDK env var below)
#   - Host: Linux x86-64 or macOS
#
# Usage:
#   NDK=/path/to/ndk ./build.sh          # defaults to API 34, arm64-v8a
#   NDK=/path/to/ndk ABI=armeabi-v7a API=26 ./build.sh
#
# Output: out/<ABI>/logcollectord

set -euo pipefail

: "${NDK:?Set NDK to your Android NDK root, e.g. ~/Android/Sdk/ndk/25.2.9519653}"
ABI="${ABI:-arm64-v8a}"
API="${API:-34}"

case "$ABI" in
    arm64-v8a)    TRIPLE="aarch64-linux-android"  ;;
    armeabi-v7a)  TRIPLE="armv7a-linux-androideabi" ;;
    x86_64)       TRIPLE="x86_64-linux-android"   ;;
    x86)          TRIPLE="i686-linux-android"      ;;
    *) echo "Unknown ABI: $ABI"; exit 1 ;;
esac

HOST_TAG="linux-x86_64"
if [[ "$(uname)" == "Darwin" ]]; then
    HOST_TAG="darwin-x86_64"
fi

TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/$HOST_TAG"
CC="$TOOLCHAIN/bin/${TRIPLE}${API}-clang"

if [[ ! -x "$CC" ]]; then
    echo "Compiler not found: $CC"
    echo "Check NDK path and ABI/API combination."
    exit 1
fi

OUTDIR="out/$ABI"
mkdir -p "$OUTDIR"

echo "Building logcollectord  ABI=$ABI  API=$API"
"$CC" \
    -O2 -fstack-protector-strong \
    -Wall -Wextra \
    logcollectord.c \
    -llog \
    -o "$OUTDIR/logcollectord"

echo "Done: $OUTDIR/logcollectord"
ls -lh "$OUTDIR/logcollectord"
