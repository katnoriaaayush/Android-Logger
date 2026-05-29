#!/usr/bin/env bash
# Push and install logcollectord onto a connected device via ADB.
#
# Usage:
#   ABI=arm64-v8a ./install.sh
#
# What it does:
#   1. Remounts /system read-write
#   2. Pushes the binary to /system/bin/logcollectord (chmod 755)
#   3. Installs the init .rc file to /system/etc/init/
#   4. Optionally sets SELinux to permissive (required if no custom policy)
#   5. Triggers init to re-read services (or reboots if that fails)

set -euo pipefail

ABI="${ABI:-arm64-v8a}"
BINARY="out/$ABI/logcollectord"
RC_FILE="logcollectord.rc"

if [[ ! -f "$BINARY" ]]; then
    echo "Binary not found: $BINARY"
    echo "Run build.sh first."
    exit 1
fi

ADB="${ADB:-adb}"

echo "=== remounting /system ==="
"$ADB" root
"$ADB" remount

echo "=== pushing binary ==="
"$ADB" push "$BINARY" /system/bin/logcollectord
"$ADB" shell chmod 755 /system/bin/logcollectord

echo "=== pushing init .rc ==="
"$ADB" push "$RC_FILE" /system/etc/init/logcollectord.rc
"$ADB" shell chmod 644 /system/etc/init/logcollectord.rc

echo ""
echo "=== SELinux mode ==="
SELINUX_MODE=$("$ADB" shell getenforce 2>/dev/null | tr -d '\r')
echo "Current mode: $SELINUX_MODE"

if [[ "$SELINUX_MODE" == "Enforcing" ]]; then
    echo ""
    echo "  NOTICE: Device is in enforcing mode."
    echo "  The daemon uses seclabel u:r:shell:s0 as a workaround."
    echo "  If it fails to start, run:"
    echo "    adb shell setenforce 0"
    echo "  For a permanent fix, add a custom SELinux policy (see README.md)."
fi

echo ""
echo "=== restarting device ==="
echo "A reboot is required for init to load the new .rc service."
read -r -p "Reboot now? [y/N] " answer
if [[ "${answer,,}" == "y" ]]; then
    "$ADB" reboot
    echo "Rebooting... wait for device, then check:"
    echo "  adb logcat -s logcollectord"
else
    echo "Skipped. Reboot the device manually when ready."
fi
