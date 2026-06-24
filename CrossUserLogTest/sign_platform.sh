#!/usr/bin/env bash
# sign_platform.sh — Sign the debug APK with a platform key and install it.
#
# Usage:
#   ./sign_platform.sh [path/to/platform.pk8] [path/to/platform.x509.pem]
#
# Defaults to AOSP test keys (works on Android emulator or AOSP builds).
# For a stock OEM device you need the OEM's private platform.pk8 and x509.pem.
#
# Requirements:
#   - apksigner  (Android SDK build-tools, e.g. $ANDROID_HOME/build-tools/34.0.0/apksigner)
#   - adb        (Android SDK platform-tools)
#   - A device/emulator already running with USB debugging enabled

set -e

AOSP_KEYS_URL="https://android.googlesource.com/platform/build/+/refs/heads/main/target/product/security"

# ── Key paths ─────────────────────────────────────────────────────────────────
PK8="${1:-platform.pk8}"
CERT="${2:-platform.x509.pem}"

# ── APK path (build first: ./gradlew assembleDebug) ───────────────────────────
APK_IN="app/build/outputs/apk/debug/app-debug.apk"
APK_SIGNED="/tmp/cross_user_log_test_signed.apk"

# ── Locate apksigner ──────────────────────────────────────────────────────────
APKSIGNER=""
if command -v apksigner &>/dev/null; then
    APKSIGNER="apksigner"
else
    # Search common Android SDK locations
    for sdk_root in "$ANDROID_HOME" "$HOME/Android/Sdk" "$HOME/Library/Android/sdk"; do
        [ -d "$sdk_root/build-tools" ] || continue
        # Pick the newest build-tools version
        latest=$(ls -1 "$sdk_root/build-tools" | sort -V | tail -1)
        if [ -x "$sdk_root/build-tools/$latest/apksigner" ]; then
            APKSIGNER="$sdk_root/build-tools/$latest/apksigner"
            break
        fi
    done
fi

if [ -z "$APKSIGNER" ]; then
    echo "[ERROR] apksigner not found. Install Android SDK build-tools."
    exit 1
fi
echo "[OK] apksigner: $APKSIGNER"

# ── Validate keys ─────────────────────────────────────────────────────────────
if [ ! -f "$PK8" ] || [ ! -f "$CERT" ]; then
    echo ""
    echo "[ERROR] Platform keys not found:"
    echo "  pk8 : $PK8"
    echo "  cert: $CERT"
    echo ""
    echo "For AOSP emulator / AOSP builds, download the public AOSP test keys:"
    echo "  $AOSP_KEYS_URL"
    echo "  Files needed: platform.pk8  platform.x509.pem"
    echo ""
    echo "For a stock OEM device you need the OEM's signing keys (not public)."
    exit 1
fi

# ── Validate APK ──────────────────────────────────────────────────────────────
if [ ! -f "$APK_IN" ]; then
    echo "[ERROR] APK not found: $APK_IN"
    echo "Build first:  ./gradlew assembleDebug"
    exit 1
fi

# ── Sign ──────────────────────────────────────────────────────────────────────
echo "[1/3] Signing $APK_IN → $APK_SIGNED ..."
"$APKSIGNER" sign \
    --key "$PK8" \
    --cert "$CERT" \
    --out "$APK_SIGNED" \
    "$APK_IN"
echo "[OK] Signed."

# ── Install ───────────────────────────────────────────────────────────────────
echo "[2/3] Installing on device..."
# -r: replace existing  -t: allow test APKs  -g: grant all permissions
adb install -r -t -g "$APK_SIGNED"
echo "[OK] Installed."

# ── Launch ────────────────────────────────────────────────────────────────────
echo "[3/3] Launching MainActivity..."
adb shell am start -n "com.sqa.logtest/.MainActivity"

echo ""
echo "Done. Check the app on the device:"
echo "  • UID info box at the top should show 'UID 1000 ✓ (system)'"
echo "  • Tap 'Run Cross-User Log Test'"
echo "  • If cross-user logs are visible, RESULT: PASS will appear"
