# Cross-User Log → USB Sync (Android-only pathway)

Pure-Android implementation of the cross-user log → USB sync plan. No native
daemon: the **foreground profile** (the only user with the USB mounted) does the
write, and the only thing that crosses the user boundary is a **ParcelFileDescriptor**
from a `singleUser` ContentProvider in user 0.

## Components

| Class | Runs in | Role |
|-------|---------|------|
| `LoggerService` | user 0 (singleUser) | PRODUCER. UID+PID hybrid logcat capture → rotated segments in `dataDir/logs/` |
| `LogProvider` | user 0 (singleUser) | Read-only window onto completed `log-*.log`; `query()`, `openFile()` (PFD), `call("rotate")` |
| `CrossUser` | — | builds `content://0@authority/…` (maybeAddUserId, with manual fallback) |
| `UsbWriteSurface` | foreground user | resolves a writable removable volume (direct path; SAF would need a grant) |
| `UsbSyncService` | foreground user | force-rotate → read USB manifest → diff → stream new segments → rewrite manifest |
| `UsbMountReceiver` | foreground user | `MEDIA_MOUNTED` trigger → starts `UsbSyncService` |

## Producer = UID+PID hybrid filter

`LoggerService` reuses the proven FILTER_HYBRID method:

```
per logcat line (logcat -v uid,threadtime -b all):
  uid % 100_000 == targetAppId   ?   (UID pre-filter, cross-user aware)
  pid ∈ amPidSet                 ?   (PID verify, kills shared-UID noise)
     amPidSet = getRunningAppProcesses() ∪ `ps -A`, refreshed every 3 s
  → write "[owner]"/"[userN]" line to current.log
```

Output rotates `current.log → log-<ms>.log` at 256 KB or every 15 s. `current.log`
is the active writer file and is **never** exposed by the provider.

## Cross-user read (the only boundary crossing)

```
UsbSyncService (foreground user, holds INTERACT_ACROSS_USERS_FULL)
  └─ content://0@com.sqa.logtest.logs/logs            → query() list
  └─ content://0@com.sqa.logtest.logs/logs/<name>     → openFileDescriptor("r")
        FileInputStream(pfd.fileDescriptor).copyTo(usbOut)   // FD survives the hop
```

## State lives on the USB

`UsbSyncService` has no stable per-user identity (run #1 may be user 10, run #2
user 11), so progress can't live in app storage. The manifest
`.logsync_manifest.json` at the USB root is the single source of truth:

```json
{ "synced": ["log-1718045000000.log", "log-1718045015000.log"], "updated": 1718045020000 }
```

Each run reads it, diffs against the provider's list, streams the gap, rewrites
it — idempotent across fresh inserts, user switches, and retries.

## Manifest entries (AndroidManifest.xml)

```xml
<uses-permission android:name="android.permission.INTERACT_ACROSS_USERS_FULL"/>
<uses-permission android:name="android.permission.WRITE_MEDIA_STORAGE"/>

<permission android:name="com.sqa.logtest.permission.LOGS"
            android:protectionLevel="signature"/>

<provider android:name=".LogProvider"
          android:authorities="com.sqa.logtest.logs"
          android:singleUser="true" android:exported="true"
          android:permission="com.sqa.logtest.permission.LOGS"/>

<receiver android:name=".UsbMountReceiver" android:exported="true">
    <intent-filter>
        <action android:name="android.intent.action.MEDIA_MOUNTED"/>
        <data android:scheme="file"/>
    </intent-filter>
</receiver>

<service android:name=".UsbSyncService" android:foregroundServiceType="dataSync"/>
<service android:name=".LoggerService" android:singleUser="true"
         android:foregroundServiceType="dataSync"/>
```

## Trigger matrix

| Scenario | Behavior |
|----------|----------|
| Stick mounted while user foreground | `MEDIA_MOUNTED` → sync |
| Stick already inserted at boot | broadcast may be missed → startup scan resolves the volume |
| Stick "mounted" under background user | ignored — only the foreground mount is real |
| Logs accumulate, no stick | flushed on next insert; manifest covers the gap |
| Retry after partial sync | manifest makes it idempotent |

## Cert / EDLA notes (shipping)

- `INTERACT_ACROSS_USERS_FULL` + `WRITE_MEDIA_STORAGE` are `signature|privileged`
  → must be allowlisted in `privapp-permissions-*.xml` (`PrivappPermissionsTest`).
- Give the app a dedicated `seinfo` domain rather than widening `platform_app`.
- Gate activation behind enterprise provisioning so cross-user-read + USB-export
  reads as a disclosed managed capability in privacy review.

## Debugging

All components log under their class tags. Watch the whole pathway with:

```bash
adb logcat -s LoggerService LogProvider CrossUser UsbWriteSurface UsbSyncService UsbMountReceiver BootReceiver
```

What to look for:

| Tag | Key lines |
|-----|-----------|
| `LoggerService` | `start … targetAppId=…`; `refreshPids: AM=… ps=… → N unique`; `captured matched=…`; `rotated → log-<ms>.log` |
| `LogProvider` | `query(callingUid=…) → N segment(s)`; `openFile(name) → PFD (size=…)` |
| `CrossUser` | `addUserId(uri,0) → content://0@…` |
| `UsbWriteSurface` | per-volume `uuid/removable/state/dir`; `writable removable volume at …` |
| `UsbSyncService` | `list URI=…`; `rotate call → rotated=…`; `manifest: N already-synced`; `synced <name> (<bytes>) → …` |

Common signals:
- `targetAppId unresolved` → target package not installed.
- `drop: uid matched but pid ∉ amPidSet` → a target process is missing from the PID
  set (usual cross-user case) — check `refreshPids` AM/ps counts.
- `resolve: no writable removable volume found` → USB not mounted in this user, or
  the board blocks direct `/storage/<uuid>` writes (see Open decision #1).

## Open decisions

1. **USB write surface** — implemented as runtime-detect **direct path**. SAF
   fallback is stubbed out conceptually but needs a persisted tree grant; confirm
   the board allows direct `/storage/<uuid>` writes for the system app.
2. **Scheduled backstop** — optional `WorkManager`/`JobScheduler` sweep in the
   foreground user in addition to the mount broadcast.
