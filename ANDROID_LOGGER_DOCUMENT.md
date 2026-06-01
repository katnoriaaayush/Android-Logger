# Android Logger — Project Document

---

## 1. Overview

Android Logger is a privileged system application for Samsung Android devices that captures real-time logcat output from one or more target packages and writes the logs continuously to a USB mass-storage device. It is designed to operate silently in the background with no user interaction, surviving device reboots, application updates, and Android user profile switches without any loss of log data.

The system is composed of two layers:

- **APK (`com.sqa.logdaemon`)** — A platform-signed priv-app installed at `/system/priv-app/LogDaemon/`. Responsible for starting the logging process at boot and handling USB plug/unplug events.
- **Native helper (`liblogdaemon_helper.so`)** — A C binary bundled inside the APK and executed as an independent daemon. Responsible for all log capture, filtering, and writing to USB storage.

The target packages to monitor and the minimum log level are configured via a file named `log.sinfo` placed at the root of the USB drive, making the system configurable without modifying or reinstalling the application.

---

## 2. Problem Statement

### 2.1 Continuous Logging Across Profile Switches

Samsung Android devices support multiple user profiles — the Owner profile (user 0) and a secondary SecureFolder or Work Profile (user 10+). When the user switches between profiles, Android sends a series of mount/unmount events and kills processes associated with the outgoing profile's process group.

Standard Android services are destroyed and restarted during this transition. Any logging session running inside a service is interrupted, resulting in a gap in the log output. For quality assurance and diagnostics, this gap is unacceptable — the target application continues executing across the profile switch and its logs must be captured without interruption.

### 2.2 USB Path Instability During Profile Switches

Android exposes USB storage to applications through two distinct mount layers:

| Path | Description |
|---|---|
| `/storage/<uuid>/` | Per-user FUSE overlay managed by MediaProvider. Torn down for the outgoing profile and remounted for the incoming profile during a switch. |
| `/mnt/media_rw/<uuid>/` | Raw FAT volume mounted by `vold`. Persists across profile switches, unaffected by FUSE lifecycle. |

A logging process writing to `/storage/<uuid>/` will lose its write access when the FUSE overlay is torn down mid-switch. The raw mount at `/mnt/media_rw/<uuid>/` is the correct target, but access to it is restricted by SELinux policy on Samsung firmware.

### 2.3 SELinux Enforcement

Samsung devices ship with SELinux in enforcing mode. `setenforce 0` is denied even for root ADB. This blocks:

- Listing `/mnt/media_rw/` to discover which USB volume is mounted.
- Direct read/write to the raw FAT mount from a non-whitelisted domain.

These restrictions require workarounds at both the discovery layer (how the USB path is found) and the write layer (which path is used for output).

### 2.4 Process Group Kills on Profile Switch

When a profile switch occurs, Android kills all processes in the outgoing user's process group. A service running as `com.sqa.logdaemon` under user 0 is a target for this kill. Standard Android mechanisms (`START_STICKY`, `android:persistent`) are insufficient — Samsung's system specifically targets persistent apps during profile transitions.

---

## 3. Idea

Two approaches were designed and evaluated. Both are implemented in this project across two branches (`main` / `Logv2`).

---

### Approach A — Android Service (Logv1)

**Architecture:**
All logic runs inside a standard Android `Service`. The service polls for USB presence using the `StorageManager` API, discovers the USB path, spawns the native helper binary with the path as an argument, and monitors it via a watchdog coroutine. USB attach/detach events are handled by a `BroadcastReceiver`.

**Components:**
- `BootReceiver` — starts the service at boot
- `UsbStateReceiver` — forwards USB attach/detach/mount/unmount events to the service
- `LogDaemonService` — main controller: USB discovery, helper lifecycle management, watchdog loop
- `UsbMountLocator` — translates `/storage/<uuid>` to `/mnt/media_rw/<uuid>` using `StorageManager`
- `logdaemon_helper.c` — native binary that receives the USB path as `argv[1]`, daemonizes, captures logs for that session, then exits

**Limitation:**
The service itself is killed during profile switches. Although the native helper daemonizes (double-fork + `setsid()`) and survives as an init-parented process, it has no mechanism to restart a new session after the USB remounts in the new profile context. A new helper can only be launched when the service restarts — which may take several seconds and involves a re-scan of the USB state, introducing a log gap.

---

### Approach B — Self-Contained Native Daemon (Logv2)

**Architecture:**
The APK is reduced to a minimal one-shot launcher. All intelligence — USB discovery, session management, log capture, and restart-after-USB-removal — moves into the native helper. The helper runs an outer loop that never exits, continuously scanning for the USB, running a capture session, and resetting when the USB disappears. The APK's only role is to ensure exactly one helper instance is running.

**Components:**
- `BootReceiver` — starts the service at boot and on APK upgrade
- `UsbStateReceiver` — handles `ACTION_MEDIA_MOUNTED` only; updates a hint file and relaunches helper if dead
- `LogDaemonService` — one-shot: checks if helper is alive via `/proc/*/cmdline` scan, launches if not, then calls `stopSelf()`
- `logdaemon_helper.c` — fully self-contained daemon:
  - Single-instance guard via `/proc/*/cmdline` scan (no lock file; avoids SELinux-blocked paths)
  - Double-fork + `setsid()` for process group detachment
  - Outer loop: discover USB → parse config → capture session → reset → repeat
  - USB discovery via `/storage/` scan (SELinux-accessible) with path translation to `/mnt/media_rw/<uuid>/`
  - Hint file mechanism: service writes USB path to `{filesDir}/usb_hint`; helper reads it as fallback when directory listing is blocked

**Advantage over Approach A:**
The helper process outlives all Android service lifecycle events. It is reparented to init (PID 1) and is not in any Android user's process group, so profile-switch kills do not reach it. Log capture is uninterrupted across profile transitions.

---

### Comparison

| Aspect | Approach A (Logv1) | Approach B (Logv2) |
|---|---|---|
| Survives profile-switch service kill | No | Yes |
| Log continuity across profile switch | Gap of ~5–15s | Uninterrupted (if `/mnt/media_rw/` accessible) |
| USB hot-plug handling | Via BroadcastReceiver | Outer loop + hint file |
| APK complexity | High | Minimal |
| Helper complexity | Low (single session) | High (outer loop, self-discovery) |
| SELinux dependency | Lower | Higher (needs USB write access) |
| Failure recovery | Watchdog in service | Outer loop in helper |

---

## 4. Flow

### 4.1 System Boot

```
Device boots
  └─ LOCKED_BOOT_COMPLETED / BOOT_COMPLETED
       └─ BootReceiver.onReceive()
            └─ LogDaemonService.onCreate()
                 ├─ findUsbHint()          — scan /storage/ for log.sinfo
                 │    └─ resolveWritablePath(uuid)
                 │         ├─ try /mnt/media_rw/<uuid>/   [preferred — raw vold mount]
                 │         └─ fallback /storage/<uuid>/   [FUSE overlay]
                 ├─ writeHintFile(path)    — save path to {filesDir}/usb_hint
                 ├─ isHelperAlive()        — scan /proc/*/cmdline for helper name
                 └─ if not alive → launchHelper(usbHint)
                                       └─ ProcessBuilder(helper, usbHint)
                                              env: LOGDAEMON_HINT_FILE={filesDir}/usb_hint
                      └─ stopSelf()
```

### 4.2 Native Helper Startup

```
liblogdaemon_helper.so starts
  ├─ another_instance_running()   — /proc/*/cmdline scan (pre-daemonize)
  ├─ read LOGDAEMON_HINT_FILE env var → store hint file path
  ├─ validate argv[1] if provided → set g_state.usb_root
  ├─ daemonize()                  — double-fork + setsid() → reparented to init
  ├─ another_instance_running()   — re-check post-daemonize (close race window)
  └─ OUTER LOOP (never exits except on SIGTERM)
       ├─ find_usb()
       │    ├─ scan /storage/ for log.sinfo
       │    │    └─ resolve_usb_path(uuid) → try /mnt/media_rw/, fallback /storage/
       │    └─ check hint file if scan fails
       ├─ parse_config()          — read [packages] and [options] from log.sinfo
       ├─ create_session_dir()    — logs/<YYYY-MM-DD_HH-MM-SS>/
       ├─ open_writers()          — <package>.log + <package>.log.tsv per package
       ├─ write_pid_file()        — .logdaemon.pid at USB root
       ├─ write_session_meta()    — _session.meta
       ├─ rescan_pids()           — match /proc/*/cmdline to package names
       ├─ spawn_logcat()          — fork + exec /system/bin/logcat
       ├─ SESSION LOOP
       │    ├─ fgets() from logcat pipe
       │    ├─ parse_line()       — threadtime format
       │    ├─ pid_to_package()   — match PID to tracked package
       │    ├─ write_entry()      — append to .log and .log.tsv
       │    ├─ rescan_pids()      — every 2s (catches new processes from profile switch)
       │    ├─ usb_present()      — stat(session_dir) every 5s
       │    │    └─ if missing 3× → USB confirmed gone → break
       │    └─ write failure recovery → wait 10s for USB remount → break if not recovered
       ├─ write_summary()         — _summary.tsv (per-package log counts)
       ├─ unlink .logdaemon.pid
       └─ reset_state()           → back to OUTER LOOP
```

### 4.3 USB Hot-Plug After Boot

```
USB drive inserted
  └─ ACTION_MEDIA_MOUNTED broadcast
       └─ UsbStateReceiver.onReceive()
            └─ LogDaemonService.onStartCommand()
                 ├─ usbPathFromIntent()   — extract UUID from file:///storage/<uuid>
                 │    └─ resolveWritablePath(uuid)
                 ├─ writeHintFile(path)   — helper reads this on next poll
                 ├─ isHelperAlive()?
                 │    ├─ YES → helper discovers path via hint file within 5s
                 │    └─ NO  → launchHelper(usbPath)
                 └─ stopSelf()
```

### 4.4 Android Profile Switch

```
User switches profile (Owner → SecureFolder)
  ├─ /storage/<uuid>/ FUSE overlay torn down for user 0
  ├─ ACTION_MEDIA_EJECT / MEDIA_UNMOUNTED → LogDaemonService starts (no-op if helper alive)
  │
  ├─ Helper writing to /storage/<uuid>/ [FUSE fallback path]:
  │    ├─ write fails → 10s recovery loop → USB not recovered → session ends
  │    └─ reset_state() → outer loop rescans → new FUSE overlay for new profile → new session
  │
  └─ Helper writing to /mnt/media_rw/<uuid>/ [preferred path]:
       └─ raw vold mount unaffected → writes continue without interruption ✓
```

### 4.5 Output Structure on USB

```
USB root/
├── log.sinfo                        ← configuration file (user-provided)
├── .logdaemon.pid                   ← PID of running helper (deleted on clean exit)
└── logs/
    └── 2026-05-22_10-30-00/         ← session directory (one per helper run)
        ├── _session.meta            ← start time, packages, helper PID
        ├── _summary.tsv             ← per-package log count by level
        ├── com.samsung.ams.log      ← raw logcat lines
        └── com.samsung.ams.log.tsv  ← parsed: timestamp, pid, tid, level, tag, message
```

### 4.6 Configuration File Format (`log.sinfo`)

```ini
[packages]
com.samsung.ams
com.example.targetapp

[options]
min_level=D        # V | D | I | W | E | F
```

---

## 5. Business Impact

### 5.1 Uninterrupted QA and Field Diagnostics

The primary driver for this project is the need to capture diagnostic logs from target applications running on production Samsung devices during normal user workflows that include profile switches. Without continuous logging, defects that manifest only during or after a profile transition are impossible to trace. Android Logger eliminates this blind spot entirely.

### 5.2 No Cloud or Network Dependency

All captured data is written directly to a physical USB drive. There is no requirement for the device to be connected to a network, a test server, or any external infrastructure. This makes the solution viable for field testing, factory floor validation, and scenarios where network connectivity cannot be guaranteed.

### 5.3 Zero Configuration After Deployment

Once installed, the system requires no further interaction with the device. The configuration (`log.sinfo`) lives on the USB drive. Changing the packages to monitor or the log level requires only replacing the file on the USB — not modifying or reinstalling the application.

### 5.4 Resilience to Device State Changes

The native daemon architecture ensures log capture is not interrupted by:
- Device reboots
- APK upgrades (`MY_PACKAGE_REPLACED` triggers a relaunch)
- Android profile switches
- OOM kills of the Android service layer
- USB hot-plug events

### 5.5 Structured Output for Automated Analysis

Each session produces both a raw `.log` file and a tab-separated `.log.tsv` file per package. The TSV format (timestamp, PID, TID, level, tag, message) is directly importable into spreadsheet tools, log analysis pipelines, or custom scripts without any post-processing.

---

## 6. Remarks

### 6.1 SELinux — Primary Constraint

The single largest technical constraint on this project is Samsung's SELinux enforcement. The key restrictions encountered are:

- **`/mnt/media_rw/` directory listing denied** — prevents the helper from autonomously discovering the USB volume UUID. Worked around via `/storage/` scanning (accessible) with path translation.
- **Hint file mechanism** — the service writes the resolved USB path to `{filesDir}/usb_hint`; the helper reads this as a fallback. Adds a latency of up to 5 seconds for USB hot-plug in edge cases.
- **`setenforce 0` denied** — SELinux cannot be disabled via ADB even with root, confirming this is a firmware-level restriction.

A formal request has been submitted to the firmware team to whitelist the required SELinux permissions for the application's domain. Granting these permissions would eliminate all current workarounds and allow the helper to access `/mnt/media_rw/<uuid>/` directly for both discovery and writes.

### 6.2 Write Path Priority

When the preferred `/mnt/media_rw/<uuid>/` path is accessible, log capture is fully uninterrupted across profile switches. When only `/storage/<uuid>/` is available (FUSE fallback), a brief session interruption (~10s) occurs at the moment of profile switch as the FUSE overlay is torn down and remounted. The new session automatically resumes as soon as the new overlay is available.

### 6.3 Single-Instance Guard Without a Lock File

Standard lock-file approaches (e.g., writing a PID to `/var/run/`) are not viable because writable paths outside the app's data directory are blocked by SELinux. The helper uses a `/proc/*/cmdline` scan to detect if another instance is running. This check is performed twice — before and after `daemonize()` — to close the fork race window.

### 6.4 Logv1 vs Logv2

The project currently maintains two implementations. `main` (Logv1) is the service-centric approach suitable for environments without profile switching concerns. `Logv2` is the production-recommended approach with the self-contained native daemon for full profile-switch continuity. Logv2 is the active development branch.

### 6.5 Known Limitations

| Limitation | Status |
|---|---|
| Logs during the ~15s USB detection debounce window at the exact moment of profile switch may be lost when writing to `/storage/` path | Resolved if firmware team grants `/mnt/media_rw/` access |
| Only one USB volume with `log.sinfo` is captured at a time | By design — first matching volume wins |
| Helper logs (logcat TAG: `LogDaemon.Helper`) not yet confirmed visible on target device | Under investigation — likely requires helper binary rebuild and reinstall |
| ARM64 only | By design — target device is `aarch64` |
