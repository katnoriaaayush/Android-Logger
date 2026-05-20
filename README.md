# Android-Logger

A collection of Android logging tools for production and SQA fleet use cases where ADB is unavailable.

---

## Repository Structure

```
Android-Logger/
└── LogDaemon/               ← Headless system-app daemon (this repo's first tool)
    ├── app/
    │   ├── build.gradle.kts
    │   └── src/main/
    │       ├── AndroidManifest.xml
    │       ├── java/com/sqa/logdaemon/
    │       │   ├── LogDaemonApp.kt          ← Application class
    │       │   ├── LogDaemonService.kt      ← Lifecycle orchestrator
    │       │   ├── BootReceiver.kt          ← Auto-start on boot
    │       │   ├── collector/               ← Logcat capture & parsing
    │       │   ├── config/                  ← INI config reader
    │       │   ├── session/                 ← Session lifecycle
    │       │   ├── usb/                     ← USB mount detection
    │       │   └── writer/                  ← Log output (raw, TSV, summary)
    │       └── res/values/strings.xml
    ├── build.gradle.kts
    ├── settings.gradle.kts
    ├── log.sinfo.example    ← Annotated USB config template
    └── README.md            ← Full LogDaemon documentation
```

---

## LogDaemon

A headless Android **privileged system app** (no UI, no launcher icon) that:

1. Auto-starts at boot (including Direct Boot, before user unlock)
2. Watches for USB mass-storage attachment
3. Reads a `log.sinfo` config file from the USB root
4. Streams logcat output for the configured packages to the USB drive
5. Stops cleanly and writes a session summary when the USB is ejected

Designed for production / customer-fleet scenarios where SQA receives the device or USB drive after an issue is reproduced.

### Key Features

| Feature | Detail |
|---|---|
| Invisible to users | No notification, no launcher icon, no UI |
| Always running | `android:persistent="true"` — survives OOM and reboots |
| Direct Boot aware | Starts logging before the user unlocks the device |
| Per-package output | One `.log` + one `.log.tsv` per monitored package |
| Excel-ready TSV | Tab-separated with header row, sanitised messages, ISO timestamps |
| Session summary | Per-level counts, top tags, session duration |
| Fault-tolerant | Periodic flushes keep partial logs intact on sudden USB removal |
| Forward-compatible config | Unknown keys in `log.sinfo` are silently ignored |

### Requirements

- **Android 14+** (minSdk 34)
- APK signed with the **device platform key**
- Installed in `/system/priv-app/LogDaemon/`
- Privileged permissions whitelist at `/system/etc/permissions/privapp-permissions-logdaemon.xml`

### Build

```bash
# Configure your platform signing key in LogDaemon/app/build.gradle.kts first
cd LogDaemon
./gradlew :app:assembleRelease
```

**Build toolchain:** AGP 8.2.0 · Kotlin 1.9.20 · JVM 17 · compileSdk/minSdk/targetSdk 34

### Install

```bash
adb root
adb remount
adb push app/build/outputs/apk/release/app-release.apk /system/priv-app/LogDaemon/LogDaemon.apk
adb shell chmod 644 /system/priv-app/LogDaemon/LogDaemon.apk
adb reboot
```

For a permanent placement, include the APK in your firmware image under:
```
device/<vendor>/<board>/system/priv-app/LogDaemon/
```

### Privileged Permissions Whitelist

Create `/system/etc/permissions/privapp-permissions-logdaemon.xml`:

```xml
<permissions>
    <privapp-permissions package="com.sqa.logdaemon">
        <permission name="android.permission.READ_LOGS"/>
        <permission name="android.permission.WRITE_MEDIA_STORAGE"/>
        <permission name="android.permission.MANAGE_EXTERNAL_STORAGE"/>
    </privapp-permissions>
</permissions>
```

### USB Configuration (`log.sinfo`)

Place a file named `log.sinfo` at the root of the USB drive:

```ini
[packages]
com.company.mainapp
com.company.launcher

[options]
min_level=D
include_system_logs=false
buffer_size_kb=4096
```

See `LogDaemon/log.sinfo.example` for the full annotated reference.

### Output Structure

```
/<usb_root>/
├── log.sinfo
└── logs/
    └── 2026-05-19_14-32-15/       ← one folder per USB attach
        ├── _session.meta          ← device + session info
        ├── _summary.tsv           ← counts per level / top tags
        ├── com.company.mainapp.log
        ├── com.company.mainapp.log.tsv
        ├── com.company.launcher.log
        └── com.company.launcher.log.tsv
```

If config parsing fails, `/<usb_root>/_error.log` is written instead.

**Importing TSV in Excel:** `Data → From Text/CSV → select file → Tab delimiter → Load`

### Verify Installation

```bash
adb shell ps -A | grep logdaemon
adb shell dumpsys activity services | grep -i logdaemon
adb logcat -s LogDaemon LogDaemon.Boot LogDaemon.Service LogDaemon.Usb \
               LogDaemon.Session LogDaemon.Collector LogDaemon.Config
```

### Architecture

```
LogDaemonApp  (persistent process, system UID)
│
├── BootReceiver ────────────► startService(LogDaemonService)
├── UsbStateReceiver ────────► forward USB / MEDIA intents
│
└── LogDaemonService
      │
      ├── on USB attach:
      │     UsbMountLocator → find volume with log.sinfo
      │     SessionManager.start()
      │       ├── ConfigParser.parse(log.sinfo)
      │       ├── create logs/<timestamp>/ folder
      │       ├── write _session.meta
      │       └── for each package → PackageLogCollector
      │                               ├── logcat --uid=<uid> *:D
      │                               ├── LogcatLineParser → LogEntry
      │                               ├── RawLogWriter   → <pkg>.log
      │                               ├── TsvLogWriter   → <pkg>.log.tsv
      │                               └── StatsCollector
      │
      └── on USB detach:
            SessionManager.stop()
              ├── stop all collectors, flush, close
              └── SummaryWriter → _summary.tsv
```

---

## Contributing

Each tool in this repository lives in its own subdirectory. Add new tools as top-level directories alongside `LogDaemon/`.
