# Android Service Approach — Architecture, Challenges & Deprecation

**Status:** Discarded — superseded by the init.rc Native Daemon
**Component:** `com.sqa.logdaemon` APK

---

## Overview

The Android Service approach used a privileged APK installed in `/system/priv-app/` to capture logcat output from target applications across all Android user profiles and write logs continuously to a USB drive.

> **Why it was discarded:** Every fix to one constraint introduced another. The root cause — an Android app process cannot run as root — is not solvable within the APK model.

---

## Architecture

| Component | Role |
|---|---|
| `LogDaemonService` | Persistent background service. Spawns logcat, filters lines by package, streams to native helper. |
| `BootReceiver` | Starts the service on `BOOT_COMPLETED`. |
| `UsbStateReceiver` | Listens for `ACTION_MEDIA_MOUNTED`, updates USB hint file, relaunches helper if dead. |
| `logdaemon_helper` (native `.so`) | Reads filtered lines from service via abstract Unix socket `@logdaemon`. Writes to USB. Manages 8 MB ring buffer for write-gap resilience. |

### Required Firmware Changes

```
/system/priv-app/LogDaemon/LogDaemon.apk
/system/etc/permissions/privapp-permissions-logdaemon.xml
APK signed with device platform key
```

---

## Challenges

### 1. logd User Isolation

Android's log daemon (`logd`) filters log entries by user ID at the runtime level. A process reading logcat only receives entries from its own user profile. `READ_LOGS` permission does **not** bypass this filter.

**Impact:** The service running in the owner profile could not capture logs from applications in secondary user profiles.

---

### 2. USB Storage Access

The raw FAT mount at `/mnt/media_rw/<uuid>/` is owned by `root:media_rw`. Writing to it requires root UID or `media_rw` group membership. The APK runs as an unprivileged app UID — blocked by Unix file permissions regardless of SELinux mode.

---

### 3. FUSE Overlay Instability

The `/storage/<uuid>/` path available to apps is served by a per-user FUSE daemon torn down and rebuilt on every profile switch. This caused write gaps in log files during transitions. An 8 MB ring buffer was added to absorb these gaps, adding complexity without fully solving the problem.

---

### 4. vold SIGTERM on Profile Switch

vold sends `SIGTERM` to every process holding open file handles on a storage volume before it can manage (unmount/remount) that volume. The native helper kept log files open on USB continuously, so every profile switch killed the capture session.

---

### 5. USB Not Visible in Secondary User Profile

When the helper held open file handles on the USB volume, vold's profile-switch sequence sometimes left the volume in an inconsistent state — making the USB drive invisible in the secondary user's file manager for the remainder of that session. This was a direct consequence of open file handles conflicting with vold's mount management.

---

### 6. SELinux Cross-User Socket Restriction

The abstract socket `@logdaemon` used for inter-instance communication assigned different MCS labels to processes in different user profiles. In enforcing mode, `connectto` could be denied due to label mismatch — requiring a custom SELinux policy to resolve reliably.

---

## Why It Was Discarded

| Requirement | Result | Root Cause |
|---|---|---|
| Logs from all user profiles | Failed | logd UID filtering — app is not root |
| Write to USB raw mount | Blocked | DAC permissions on `/mnt/media_rw/` |
| Survive profile switches | Failed | vold SIGTERM on every switch |
| USB visible in secondary profile | Broken | Open handle race with vold |
| No SELinux policy changes | Failed | Cross-user socket MCS mismatch |

All issues trace to one constraint: **an Android app process cannot run as root.** The security model — logd, vold, DAC, SELinux — is built on that assumption. No amount of workarounds changes the underlying UID.

---

## Successor

The init.rc Native Daemon runs a binary as `user root` via Android `init`, eliminating all of the above constraints at the source.
See: **[init.rc Native Daemon — Architecture & Deployment](./logv4-init-daemon.md)**
