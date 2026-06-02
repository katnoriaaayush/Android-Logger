# Logv4 Firmware Deployment Instructions

This document covers every step required to deploy the `logdaemon` init.rc
daemon (Logv4) onto a rooted Android device.  All shell commands are run from
a host machine with `adb` installed and the target device connected via USB
debugging.

---

## Prerequisites

| Requirement | How to verify |
|---|---|
| ADB root access | `adb shell su -c id` → `uid=0(root)` |
| SELinux permissive | `adb shell getenforce` → `Permissive` (or set it — see §1) |
| Build system | Android NDK r25+ to cross-compile the binary |

---

## Part 1 — Device Preparation

### 1.1 Set SELinux permissive

```bash
adb shell su -c "setenforce 0"
adb shell getenforce        # must print: Permissive
```

> This survives until the next reboot. Re-run after each reboot if needed.
> For a persistent change, edit `/system/etc/selinux/plat_sepolicy.cil` (advanced).

### 1.2 Disable dm-verity and remount system read-write

```bash
# Check if /system is currently read-only
adb shell mount | grep " /system "
# Typical output: /dev/block/... on /system type ext4 (ro,...)

# Disable verity (requires a reboot — do this first)
adb shell su -c "disable-verity"
# Output: "Verity disabled on /system"
# If it says "Already disabled" you can skip the reboot below.

adb reboot
adb wait-for-device

# After reboot, remount /system as read-write
adb shell su -c "mount -o remount,rw /system"
adb shell mount | grep " /system "
# Verify: should now show (rw,...) instead of (ro,...)
```

> **Samsung / locked bootloader devices**: `disable-verity` may not work.
> In that case, boot to TWRP (or another custom recovery) and use its
> "Mount" screen to mount /system as R/W, then push files via `adb push`
> from within the recovery's ADB session.

---

## Part 2 — Build the Native Binary

### 2.1 Cross-compile with NDK

From the repository root, or from Android Studio's *Build > Make Project*:

```bash
cd LogDaemon

# Option A — via Gradle (builds all ABIs configured in build.gradle.kts)
./gradlew assembleDebug

# Binaries land at (example for arm64-v8a):
# app/build/intermediates/cmake/debug/obj/arm64-v8a/logdaemon
```

Find the ABI your device uses:

```bash
adb shell getprop ro.product.cpu.abi
# e.g. arm64-v8a
```

Set a variable for convenience:

```bash
ABI=$(adb shell getprop ro.product.cpu.abi | tr -d '\r')
BINARY="app/build/intermediates/cmake/debug/obj/${ABI}/logdaemon"
echo "Binary: $BINARY"
```

---

## Part 3 — Push Files to Device

### 3.1 Push the daemon binary

```bash
# Stage via /sdcard first (no SELinux objection on the push step)
adb push "$BINARY" /sdcard/logdaemon

# Move to final location as root and set permissions
adb shell su -c "cp /sdcard/logdaemon /system/bin/logdaemon"
adb shell su -c "chmod 755 /system/bin/logdaemon"
adb shell su -c "chown root:root /system/bin/logdaemon"

# Verify
adb shell su -c "ls -la /system/bin/logdaemon"
# Expected: -rwxr-xr-x 1 root root ... /system/bin/logdaemon
```

### 3.2 Push the init.rc service definition

```bash
adb push firmware/system/etc/init/logdaemon.rc /sdcard/logdaemon.rc

adb shell su -c "cp /sdcard/logdaemon.rc /system/etc/init/logdaemon.rc"
adb shell su -c "chmod 644 /system/etc/init/logdaemon.rc"
adb shell su -c "chown root:root /system/etc/init/logdaemon.rc"
```

### 3.3 Push the privapp-permissions whitelist (only if APK is also installed)

```bash
adb push firmware/system/etc/permissions/privapp-permissions-logdaemon.xml \
        /sdcard/privapp-permissions-logdaemon.xml

adb shell su -c "cp /sdcard/privapp-permissions-logdaemon.xml \
    /system/etc/permissions/privapp-permissions-logdaemon.xml"
adb shell su -c "chmod 644 /system/etc/permissions/privapp-permissions-logdaemon.xml"
adb shell su -c "chown root:root /system/etc/permissions/privapp-permissions-logdaemon.xml"
```

### 3.4 Add `media_rw` GID to platform.xml (grants FAT-mount access)

This adds the `media_rw` group (GID 1023) to any process that holds
`WRITE_MEDIA_STORAGE`. Required only if you also want the APK's service to
write to `/mnt/media_rw/`. The init.rc daemon already runs as root (GID 0)
and does **not** need this change.

```bash
# Pull platform.xml for editing
adb shell su -c "cp /system/etc/permissions/platform.xml /sdcard/platform.xml"
adb pull /sdcard/platform.xml /tmp/platform.xml
```

Open `/tmp/platform.xml` in a text editor.  Find the `WRITE_MEDIA_STORAGE`
block (it looks like this):

```xml
<permission name="android.permission.WRITE_MEDIA_STORAGE">
    <group gid="media_rw" />
    ...
</permission>
```

If `<group gid="media_rw" />` is already present, no edit is needed.
If it is absent, add it inside the `<permission>` block.  Then push back:

```bash
adb push /tmp/platform.xml /sdcard/platform.xml
adb shell su -c "cp /sdcard/platform.xml /system/etc/permissions/platform.xml"
adb shell su -c "chmod 644 /system/etc/permissions/platform.xml"
adb shell su -c "chown root:root /system/etc/permissions/platform.xml"
```

---

## Part 4 — Create the config file on internal storage

The daemon writes logs to owner internal storage (`/data/media/0/LogDaemon/`),
which appears as **Internal Storage/LogDaemon/** in the file manager.
No USB drive is required.

Create the config file at `/data/media/0/LogDaemon/log.sinfo`:

```bash
adb shell su -c "mkdir -p /data/media/0/LogDaemon"
adb shell su -c "cat > /data/media/0/LogDaemon/log.sinfo" << 'EOF'
[packages]
com.example.targetapp
com.other.package

[options]
min_level=D
EOF
```

Or push a file from the host:
```bash
cat > /tmp/log.sinfo << 'EOF'
[packages]
com.example.targetapp
com.other.package

[options]
min_level=D
EOF
adb push /tmp/log.sinfo /sdcard/log.sinfo
adb shell su -c "cp /sdcard/log.sinfo /data/media/0/LogDaemon/log.sinfo"
```

The daemon polls for this file every 5 seconds. Once it appears, capture starts.
To change packages: edit the file — the new config takes effect on the next
daemon restart (or `stop logdaemon && start logdaemon`).

---

## Part 5 — Reboot and Verify

### 5.1 Reboot

```bash
adb reboot
adb wait-for-device
```

Re-apply SELinux permissive if not persisted:

```bash
adb shell su -c "setenforce 0"
```

### 5.2 Verify the daemon started

```bash
# Check if the process is running
adb shell su -c "ps -A | grep logdaemon"
# Expected: root  <pid>  1  ...  /system/bin/logdaemon

# Check logcat for daemon messages
adb logcat -s LogDaemon
```

Expected logcat output when a USB drive with `log.sinfo` is mounted:

```
LogDaemon: logdaemon started pid=<N> uid=0 (Logv4)
LogDaemon: USB: /mnt/media_rw/<uuid> [raw vold mount]
LogDaemon: Packages: com.example.targetapp (and N more)
LogDaemon: Session dir: /mnt/media_rw/<uuid>/logs/<timestamp>/
LogDaemon: Opened writers for N package(s)
```

### 5.3 Manual start (without reboot)

If `sys.boot_completed=1` has already been set and the service did not
auto-start (e.g. first push after boot):

```bash
adb shell su -c "start logdaemon"
```

### 5.4 Stop the daemon

```bash
adb shell su -c "stop logdaemon"
# or
adb shell su -c "kill $(adb shell su -c 'pidof logdaemon')"
```

---

## Part 6 — File Ownership / Permission Reference

| File | Owner | Mode |
|---|---|---|
| `/system/bin/logdaemon` | `root:root` | `0755` |
| `/system/etc/init/logdaemon.rc` | `root:root` | `0644` |
| `/system/etc/permissions/privapp-permissions-logdaemon.xml` | `root:root` | `0644` |

---

## Troubleshooting

### Daemon not starting after reboot

```bash
adb logcat -b events | grep "start_service\|service_start_failed"
# Also check:
adb shell su -c "logcat -d | grep 'logdaemon'"
```

Common causes:
- Binary not executable (`chmod 755`)
- Binary is for wrong ABI (`file /system/bin/logdaemon` vs `getprop ro.product.cpu.abi`)
- SELinux enforcing — set permissive: `setenforce 0`
- `/system` remounted read-only after reboot → push failed silently (verify with `ls -la /system/bin/logdaemon`)

### Logs not appearing in Internal Storage/LogDaemon/

```bash
adb shell su -c "ls /data/media/0/LogDaemon/"
# Must show log.sinfo and a logs/ directory
adb shell su -c "ls /data/media/0/LogDaemon/logs/"
# Must show timestamped session directories
```

Common causes:
- `log.sinfo` missing — create it (see Part 4)
- Package names in `log.sinfo` do not match installed packages — verify with `adb shell pm list packages`
- `/data/media/0/` not yet mounted — daemon starts `class late_start`, should be available

---

## Summary of Changed/Added Firmware Files

```
/system/bin/logdaemon                                    ← new binary
/system/etc/init/logdaemon.rc                            ← new init service
/system/etc/permissions/privapp-permissions-logdaemon.xml← new (if APK installed)
```

Log output location (not a firmware file — created at runtime):
```
/data/media/0/LogDaemon/log.sinfo       ← config (create before first run)
/data/media/0/LogDaemon/logs/<ts>/      ← captured logs per session
```
Visible in file manager as: **Internal Storage → LogDaemon → logs**
