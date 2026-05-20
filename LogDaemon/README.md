# Log Daemon — Production Log Collection via USB

A headless Android **system app** that runs as a persistent daemon, detects USB
mass-storage attachment, reads a configuration file from the USB drive, and
streams logcat output for the configured packages directly onto the USB.

Designed for production / customer-fleet log collection where ADB is not
available and SQA receives the device or USB drive after the issue is reproduced.

---

## Capabilities

- **No UI, no notification, no launcher icon** — invisible to end users
- **Always running** — `android:persistent="true"` keeps it alive across reboots and OOM
- **Auto-starts at boot** — including Direct Boot (before user unlock)
- **Captures Log.d and above** from release-build apps (system signature → `READ_LOGS`)
- **Per-package output** — one `.log` and one `.log.tsv` per monitored package
- **Excel-importable** — TSV format with header row, sanitised messages, full ISO timestamps
- **Session summary** — counts per level, top tags, duration
- **Survives USB pulls** — periodic flushes keep partial logs intact
- **Forward-compatible config** — unknown keys in `log.sinfo` are tolerated

---

## Output Structure

```
/<usb_root>/
├── log.sinfo                                    ← config you write
└── logs/
    └── 2026-05-19_14-32-15/                     ← one folder per USB attach
        ├── _session.meta                        ← device + session info
        ├── _summary.tsv                         ← counts per level / top tags
        ├── com.company.mainapp.log              ← raw logcat (dev-friendly)
        ├── com.company.mainapp.log.tsv          ← Excel-ready (SQA)
        ├── com.company.launcher.log
        └── com.company.launcher.log.tsv
```

If config parsing fails: `/<usb_root>/_error.log` is written instead.

---

## Configuration: `log.sinfo`

INI-style file at the USB root.

```ini
[packages]
com.company.mainapp
com.company.launcher

[options]
min_level=D
include_system_logs=false
```

See `log.sinfo.example` for the full annotated example.

---

## TSV Format (Excel-ready)

```
timestamp                  pid    tid    level  tag             message
2026-05-19 14:32:15.123    1234   5678   D      MyActivity      onCreate called
2026-05-19 14:32:15.145    1234   5678   I      NetworkManager  Request to /api/users
```

- One row per logcat line (multi-line stack traces remain as separate rows, since logcat itself emits them line-by-line)
- Tabs in messages replaced with 4 spaces
- Newlines in messages replaced with literal `\n`
- Null bytes stripped
- Header row included for automatic Excel column detection

**Importing in Excel:** `Data → From Text/CSV → select file → Tab delimiter → Load`.

---

## Build & Install

### 1. Signing
The APK must be signed with the **device platform key** to be accepted as a system app under `android:sharedUserId="android.uid.system"`.

In `app/build.gradle.kts`, uncomment and configure the `signingConfigs` block with your platform keystore.

### 2. Build
```bash
./gradlew :app:assembleRelease
```

### 3. Place into Firmware

Copy the signed APK into the system partition:

```bash
adb root
adb remount
adb push app/build/outputs/apk/release/app-release.apk /system/priv-app/LogDaemon/LogDaemon.apk
adb shell chmod 644 /system/priv-app/LogDaemon/LogDaemon.apk
adb reboot
```

For a permanent placement, include it in your firmware image under
`device/<vendor>/<board>/system/priv-app/LogDaemon/`.

### 4. (Optional) Privileged Permissions Whitelist

On Android 14, some permissions for system apps need to be whitelisted in
`/etc/permissions/privapp-permissions-<package>.xml`:

```xml
<permissions>
    <privapp-permissions package="com.sqa.logdaemon">
        <permission name="android.permission.READ_LOGS"/>
        <permission name="android.permission.WRITE_MEDIA_STORAGE"/>
        <permission name="android.permission.MANAGE_EXTERNAL_STORAGE"/>
    </privapp-permissions>
</permissions>
```

Place this file at `/system/etc/permissions/privapp-permissions-logdaemon.xml`.

---

## Verifying Installation

After install + reboot, check the daemon is alive:

```bash
adb shell ps -A | grep logdaemon
adb shell dumpsys activity services | grep -i logdaemon
adb logcat -s LogDaemon LogDaemon.Boot LogDaemon.Service LogDaemon.Usb \
                 LogDaemon.Session LogDaemon.Collector LogDaemon.Config
```

You should see `LogDaemonApp.onCreate (persistent system app)` shortly after boot.

---

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│  LogDaemonApp  (persistent process, system UID)             │
│                                                              │
│  BootReceiver ─────────► startService(LogDaemonService)     │
│                                                              │
│  UsbStateReceiver ─────► forward USB / MEDIA intents         │
│                                                              │
│  LogDaemonService                                            │
│    │                                                         │
│    ├── on USB attach:                                        │
│    │     UsbMountLocator → find volume with log.sinfo        │
│    │     SessionManager.start()                              │
│    │       ├── ConfigParser.parse(log.sinfo)                 │
│    │       ├── create logs/<timestamp>/ folder               │
│    │       ├── write _session.meta                           │
│    │       └── for each Found package:                       │
│    │              PackageLogCollector                        │
│    │                ├── logcat --uid=<uid> *:D               │
│    │                ├── LogcatLineParser → LogEntry          │
│    │                ├── RawLogWriter   → <pkg>.log           │
│    │                ├── TsvLogWriter   → <pkg>.log.tsv       │
│    │                └── StatsCollector                       │
│    │                                                         │
│    └── on USB detach:                                        │
│          SessionManager.stop()                               │
│            ├── stop all collectors, flush, close             │
│            └── SummaryWriter → _summary.tsv                  │
└─────────────────────────────────────────────────────────────┘
```

---

## File-by-File Overview

| File | Role |
|---|---|
| `LogDaemonApp.kt` | Application class — process bootstrap |
| `BootReceiver.kt` | Starts service at BOOT_COMPLETED |
| `LogDaemonService.kt` | Headless background service, lifecycle orchestrator |
| `usb/UsbStateReceiver.kt` | Receives USB / MEDIA broadcasts |
| `usb/UsbMountLocator.kt` | Finds the mounted USB volume containing `log.sinfo` |
| `config/DaemonConfig.kt` | Parsed config data class |
| `config/ConfigParser.kt` | INI-style parser with validation |
| `session/SessionManager.kt` | Owns one collection session end-to-end |
| `collector/LogEntry.kt` | Single log entry; raw + TSV serialisation |
| `collector/LogcatLineParser.kt` | Parses logcat threadtime format |
| `collector/PackageLogCollector.kt` | One logcat process per package, routes to writers |
| `writer/RawLogWriter.kt` | Writes `<pkg>.log` |
| `writer/TsvLogWriter.kt` | Writes `<pkg>.log.tsv` |
| `writer/StatsCollector.kt` | Aggregates per-level / per-tag counts |
| `writer/SummaryWriter.kt` | Writes `_summary.tsv` |
| `writer/SessionMetaWriter.kt` | Writes `_session.meta` |

---

## Phase 3 (Future) — Deferred Features

- Pre-trigger ring buffer (last 30s before USB attach)
- File rotation (split at N MB)
- Tag whitelist / blacklist in config
- Tombstone capture (`/data/tombstones/`) and ANR traces (`/data/anr/`)
- Live status file updated every 10s
- Encrypted output for customer-data-sensitive logs
- Remote kill switch (flag file on USB to disable collection)
