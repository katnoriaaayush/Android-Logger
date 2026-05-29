# Android Logger — Approaches & Feasibility

All discussed architectures for capturing logcat across Android user profiles
and writing to USB, with flow diagrams and feasibility assessment.

---

## Quick Comparison

```
Approach                  | Owner logs | User-profile logs | Firmware needed | Status
--------------------------|------------|-------------------|-----------------|--------
Logv2 (helper only)       |     ✅     |        ❌         |       No        | Replaced
Logv3 (socket + service)  |     ✅     |     ⚠️ SELinux    |       No        | Current
Logv3 multi-user fix      |     ✅     |     ⚠️ SELinux    |       No        | Current
LogCapture class          |     ✅     |        ❌         |       No        | Utility only
Internal storage write    |     ✅     |     ⚠️ SELinux    |       No        | Alternative
Firmware: patch logd      |     ✅     |        ✅         |       Yes       | Best
Firmware: sharedUserId    |     ✅     |        ✅         |       Yes       | Alternative
init.rc daemon            |     ✅     |        ✅         |       Yes       | Cleanest
```

---

## Approach 1 — Logv2: Helper Spawns Logcat

The native `.so` helper runs as a daemon, spawns `logcat` directly, scans
`/proc` for PIDs, filters lines by package, writes to USB.

```mermaid
flowchart TD
    BOOT([Device Boot]) --> SVC[LogDaemonService\nuser 0]
    SVC --> ALIVE{Helper\nalive?}
    ALIVE -- No --> LAUNCH[Launch liblogdaemon_helper.so]
    ALIVE -- Yes --> STOP[stopSelf]
    LAUNCH --> FORK[double-fork + setsid\nreparent to init]
    FORK --> USBS{USB with\nlog.sinfo?}
    USBS -- No --> WAIT[sleep 5s] --> USBS
    USBS -- Yes --> PARSE[parse_config\nread packages]
    PARSE --> SESSION[create session dir\nopen .log + .tsv writers]
    SESSION --> PIDS[rescan /proc\nbuild pid→package map]
    PIDS --> LOGCAT[spawn logcat -v threadtime]
    LOGCAT --> LOOP{read line}
    LOOP --> PID[extract PID]
    PID --> MATCH{PID in\npid map?}
    MATCH -- No --> LOOP
    MATCH -- Yes --> WRITE[write_entry to USB]
    WRITE --> FAIL{write\nfailed?}
    FAIL -- No --> RESCAN{2s elapsed?}
    RESCAN -- Yes --> PIDS
    RESCAN -- No --> LOOP
    FAIL -- Yes --> RECOVER[wait 10s for USB remount]
    RECOVER --> RESUMED{USB\nback?}
    RESUMED -- Yes --> LOOP
    RESUMED -- No --> ENDSESS[end session\nreset state]
    ENDSESS --> USBS

    PROFILE([Profile Switch]) -. FUSE torn down .-> FAIL
```

### Logv2 Feasibility

| Factor | Result |
|--------|--------|
| Owner profile logs | ✅ Works |
| User profile logs | ❌ logd filters by user — helper never sees them |
| Profile switch resilience | ❌ FUSE torn down → write fails → new session |
| USB hot-plug | ✅ UsbStateReceiver + hint file |
| Firmware needed | No |

**Verdict:** Works for owner only. Each profile switch creates a new session (log gaps).

---

## Approach 2 — Logv3: Service Streams via Abstract Socket

`LogDaemonService` (user 0, permanent) captures logcat for its own user,
filters by PID, and streams tagged lines to the helper over abstract Unix
socket `@logdaemon`. Helper writes to USB with 8 MB RAM ring buffer for
FUSE gaps.

```mermaid
flowchart TD
    BOOT([Device Boot]) --> SVC0[LogDaemonService\nuser 0 — permanent]
    SVC0 --> LAUNCH[Launch helper if not alive]
    LAUNCH --> HELPER

    subgraph HELPER [liblogdaemon_helper.so — reparented to init]
        HS[setup socket server\n@logdaemon] --> SEL{select loop\n1s timeout}
        SEL -- new client --> ACC[accept connection\ng_client_fd]
        ACC --> SEL
        SEL -- data from client --> RDL[read_line_fd\npkg\\traw_line]
        RDL --> SESS{session\nopen?}
        SESS -- Yes --> WR[write_entry to USB]
        WR -- fail --> RING2[ring_store\nend_session]
        WR -- ok --> SEL
        SESS -- No --> RING2
        RING2 --> SEL
        SEL -- timeout --> USBCK{USB\npresent?}
        USBCK -- No --> TRY[try_open_session\nfind_usb + parse_config]
        TRY -- success --> FLUSH[ring_flush\ndrain buffered lines]
        FLUSH --> SEL
        TRY -- fail --> SEL
        USBCK -- Yes --> SEL
    end

    subgraph SVC0LOOP [LogDaemonService captureLoop — user 0]
        WUSB[waitForUsb\npoll findUsbHint every 3s] --> PSINFO[parseLogSinfo\nread packages]
        PSINFO --> CONN[connectToHelper\n@logdaemon socket]
        CONN --> LOGCAT0[spawn logcat *:V\nthreadtime]
        LOGCAT0 --> SCAN0[rescanPids every 2s]
        SCAN0 --> FILT0{PID matches\npackage?}
        FILT0 -- Yes --> SEND0[write pkg\\tline\\n\nto socket]
        SEND0 --> FILT0
        FILT0 -- No --> FILT0
        SEND0 -- socket fail --> WUSB
    end

    SVC0 --> WUSB
    SEND0 --> RDL

    USB([USB Inserted]) --> MEDIA[MEDIA_MOUNTED\nUsbStateReceiver] --> HINT[writeHintFile] --> WUSB
```

### Logv3 Feasibility

| Factor | Result |
|--------|--------|
| Owner profile logs | ✅ Works |
| User profile logs | ⚠️ Needs multi-user fix below |
| Profile switch resilience | ✅ Ring buffer absorbs FUSE gap |
| USB hot-plug | ✅ |
| Firmware needed | No |
| Cross-user SELinux socket | ⚠️ Must verify on device |

**Verdict:** Owner logs work. User-profile logs need the multi-user service fix.

---

## Approach 3 — Logv3 Multi-User Fix

Remove `singleUser=true` from the service. Android OS automatically runs a
service instance in each user profile. Each instance captures its own user's
logcat and streams to the same `@logdaemon` socket. Abstract Unix sockets are
kernel-level — shared across all user profiles.

```mermaid
flowchart TD
    BOOT0([User 0 Boot]) --> SVC0A[LogDaemonService\nuser 0 instance]
    BOOT1([User 1 Start]) --> SVC1A[LogDaemonService\nuser 1 instance]

    SVC0A --> U0CHK{myUserId?}
    U0CHK -- 0 --> U0LAUNCH[launch helper\nwrite hint file]
    U0LAUNCH --> U0LOOP[captureLoop\nuser 0 logcat]

    SVC1A --> U1CHK{myUserId?}
    U1CHK -- 1 --> U1LOOP[captureLoop\nuser 1 logcat]
    U1CHK -- ≥2 --> SKIP[skip — SecureFolder\nnot wanted]

    U0LOOP --> SOCK0[stream to @logdaemon\nuser0pkg\\tline]
    U1LOOP --> SOCK1[stream to @logdaemon\nuser1pkg\\tline]

    subgraph KERNEL [Kernel — abstract socket namespace shared across ALL users]
        SOCK0 --> DAEMON[@logdaemon\nhelper process]
        SOCK1 --> DAEMON
    end

    DAEMON --> USB[(USB\nlog files)]

    SELINUX{SELinux\nMCS labels\nallow cross-user\nconnect?} -. must verify .-> SOCK1
```

### Multi-User Feasibility

| Factor | Result |
|--------|--------|
| Owner profile logs | ✅ |
| User profile (user 1) logs | ✅ if SELinux allows socket connect |
| SecureFolder (user ≥ 2) | ✅ Explicitly skipped |
| Single helper process | ✅ |
| Firmware needed | No |
| **Blocker** | ⚠️ Samsung SELinux MCS labels may deny cross-user socket `connectto` — verify with `avc: denied` in logcat |

**Verdict:** Best no-firmware-change option. SELinux is the only unknown — check `avc: denied` on device.

---

## Approach 4 — LogCapture Standalone Class

Self-contained Kotlin class. Caller invokes `start(packages)`, class spawns
logcat and writes filtered lines to `Downloads/`. No service, no helper.

```mermaid
flowchart TD
    CALLER[Your code\nLogCapture.start\npackages listOf...] --> SCAN[rescanPids\n/proc scan]
    SCAN --> PIDMAP[pid → package map]
    PIDMAP --> PROC[spawn logcat\n-v threadtime *:V]
    PROC --> READ{readLine}
    READ --> EXT[extractPid]
    EXT --> CHK{pid in\npidMap?}
    CHK -- No --> RESCAN2{2s elapsed?}
    RESCAN2 -- Yes --> SCAN
    RESCAN2 -- No --> READ
    CHK -- Yes --> FILE[write line to\nDownloads/capture.log]
    FILE --> READ
    CALLER2[Your code\nLogCapture.stop] --> CANCEL[cancel coroutine\ndestroy logcat proc]
```

### LogCapture Feasibility

| Factor | Result |
|--------|--------|
| Owner profile logs | ✅ |
| User profile logs | ❌ Same logd user isolation — class runs in caller's user |
| No service/helper needed | ✅ |
| USB dependency | ❌ Writes to internal Downloads only |
| Firmware needed | No |

**Verdict:** Good for quick one-off owner-profile captures. Not suitable for cross-user or persistent daemon use.

---

## Approach 5 — Write to Internal Storage

Helper (or service) writes logs to app internal storage instead of USB.
Avoids FUSE mount/unmount issues. Logs retrieved later (ADB pull or copy-to-USB step).

```mermaid
flowchart TD
    SVC0B[LogDaemonService\nuser 0] --> CAP0[capture logcat\nuser 0 logs]
    SVC1B[LogDaemonService\nuser 1] --> CAP1[capture logcat\nuser 1 logs]

    CAP0 --> INT0[/data/user/0/\ncom.sqa.logdaemon/\nfiles/logs/]
    CAP1 --> INT1[/data/user/1/\ncom.sqa.logdaemon/\nfiles/logs/]

    INT0 --> AGG{Aggregation\nhow?}
    INT1 --> AGG

    AGG -- USB inserted --> COPY[user 0 service\ncopy both users' logs\nto USB]
    AGG -- ADB --> ADB[adb pull\n/data/user/0/...\n/data/user/1/...]

    COPY --> USB2[(USB\nlog files)]

    FUSE_ISSUE{FUSE gap\nduring profile switch} -. no impact\non internal writes .-> INT0
    FUSE_ISSUE -. no impact .-> INT1
```

### Internal Storage Feasibility

| Factor | Result |
|--------|--------|
| FUSE gap problem | ✅ Solved — internal storage is raw ext4 |
| Ring buffer needed | ❌ Not needed |
| User profile logs | ⚠️ Still needs per-user service (same logd isolation) |
| Cross-user aggregation | ⚠️ User 0 can access `/data/user/1/` on a platform-signed app |
| Storage limit | ⚠️ Must manage log rotation |
| Real-time USB writing | ❌ Copy step needed |

**Verdict:** Solves stability, not the multi-user capture problem. Good complement to per-user services.

---

## Approach 6 — Firmware: Patch logd

Firmware team adds the app's UID to logd's privileged reader whitelist.
One process, one logcat, all users' logs. Smallest possible firmware change.

```mermaid
flowchart TD
    FW[Firmware team\npatches logd source] --> BUILD[rebuild firmware\nflash to device]

    BUILD --> DAEMON2[logdaemon process\nUID = app UID u0_aXX]
    DAEMON2 --> CONNECT[connect to /dev/socket/logdr]
    CONNECT --> LOGD{logd checks\nUID}
    LOGD -- UID in privileged list --> ALL[receives ALL users'\nlog entries]
    LOGD -- UID not in list --> FILTERED[filtered to user 0 only]

    ALL --> PKGFILT[filter by package name\nfrom log.sinfo]
    PKGFILT --> USB3[(USB\nlog files)]

    subgraph CHANGE [2-line change in LogReader.cpp]
        CODE["static const uid_t kTrustedReaders[] =
  AID_SYSTEM, AID_LOG, YOUR_APP_UID ;"]
    end
```

### logd Patch Feasibility

| Factor | Result |
|--------|--------|
| All users' logs | ✅ Single process |
| Firmware change | Yes — minimal (2 lines in logd) |
| App architecture change | Minor — remove per-user service split |
| SELinux change | Minimal — existing logd socket rules apply |
| Risk | Low — targeted, no system-wide impact |

**Verdict:** Cleanest firmware-assisted option. Minimal risk, maximum payoff.

---

## Approach 7 — Firmware: sharedUserId="android.uid.system"

APK built into the firmware image with `android:sharedUserId="android.uid.system"`.
App gets AID_SYSTEM (1000) identity — logd gives unfiltered access natively.

```mermaid
flowchart TD
    FW2[Firmware team\nbakes APK into /system/priv-app/] --> INST[App installed with\nUID = AID_SYSTEM 1000]

    INST --> SVC3[LogDaemonService\nUID = 1000 = AID_SYSTEM]
    SVC3 --> LOGCAT3[spawn logcat]
    LOGCAT3 --> LOGD2{logd checks UID}
    LOGD2 -- AID_SYSTEM = 1000 --> ALLLOG[all users' logs\nno filtering]
    ALLLOG --> WRITE3[(USB / internal\nlog files)]

    ANDROID13{Android 13+\nrestriction} -. blocks POST-INSTALL\ndeclaration of sharedUserId .-> X[❌ cannot add\nafter firmware built]
    ANDROID13 -. PRE-INSTALLED in firmware .-> OK[✅ works fine\nif in firmware from day 1]
```

### sharedUserId Feasibility

| Factor | Result |
|--------|--------|
| All users' logs | ✅ AID_SYSTEM gets unfiltered logd access |
| Android 13+ compat | ✅ Only if APK is pre-installed in firmware image |
| Android 13+ sideload | ❌ Blocked — INSTALL_FAILED_SHARED_USER_INCOMPATIBLE |
| Architecture simplicity | ✅ No per-user service split needed |
| Risk | ⚠️ AID_SYSTEM has broad system privileges — higher blast radius than logd patch |

**Verdict:** Works if baked into firmware. Higher privilege than needed (prefer logd patch instead).

---

## Approach 8 — init.rc Native Daemon

Binary placed in `/system/bin/` by firmware team. Started by Android `init`
at boot as `user log` (AID_LOG = 1007). Logd treats AID_LOG as a privileged
reader. No Android service layer needed at all.

```mermaid
flowchart TD
    BOOT2([Device Boot]) --> INIT[Android init process\nreads /system/etc/init/logdaemon.rc]
    INIT --> START[start logdaemon\nUID=log GID=log,sdcard_rw]
    START --> SCTX[SELinux context\nu:r:logdaemon:s0]

    SCTX --> USBS2{USB with\nlog.sinfo?}
    USBS2 -- No --> SLEEP[sleep 5s] --> USBS2
    USBS2 -- Yes --> PCONFIG[parse log.sinfo\nread packages]
    PCONFIG --> SESS2[create session dir\nopen writers]
    SESS2 --> LOGCAT4[read logcat\ndirectly — UID=log\nno user filter]
    LOGCAT4 --> ALLP{all profiles'\nlog entries}
    ALLP --> PKGF[filter by\npackage + PID]
    PKGF --> WUSB[write to USB]
    WUSB --> LOGCAT4

    PROFILE2([Profile Switch]) -. daemon unaffected\nnot an Android app .-> LOGCAT4

    subgraph RC [/system/etc/init/logdaemon.rc]
        RCTEXT["service logdaemon /system/bin/logdaemon
  class late_start
  user log
  group log sdcard_rw media_rw
  seclabel u:r:logdaemon:s0"]
    end

    subgraph SEPC [sepolicy/logdaemon.te]
        SETEXT["type logdaemon, domain;
init_daemon_domain(logdaemon)
allow logdaemon logdr_socket:sock_file rw;
allow logdaemon logd:unix_stream_socket connectto;
allow logdaemon sdcard_type:file create_file_perms;"]
    end
```

### init.rc Daemon Feasibility

| Factor | Result |
|--------|--------|
| All users' logs | ✅ user=log → privileged logd reader |
| Profile switch resilience | ✅ init daemon — not an Android app, unaffected |
| FUSE gap | ✅ Process never dies — ring buffer optional |
| Per-user service needed | ❌ Not needed |
| Android service layer | ❌ Not needed |
| Firmware needed | Yes — binary + `.rc` + SELinux policy |
| Code changes | Minor — remove daemonize(), keep everything else |

**Verdict:** Cleanest end-state architecture. Single binary, no Android framework dependency,
captures all profiles natively. Requires firmware team for deployment.

---

## Decision Tree

```mermaid
flowchart TD
    START2([What do you need?]) --> Q1{Firmware team\navailable?}

    Q1 -- Yes --> Q2{Prefer minimal\nfirmware change?}
    Q2 -- Yes --> LOGDPATCH[Approach 6\npatch logd\n2-line change]
    Q2 -- No --> Q3{Want zero Android\nframework dependency?}
    Q3 -- Yes --> INITRC[Approach 8\ninit.rc daemon\ncleanest long-term]
    Q3 -- No --> SUID[Approach 7\nsharedUserId=system\nin firmware]

    Q1 -- No --> Q4{Only need\nowner profile logs?}
    Q4 -- Yes --> Q5{One-off or\npersistent?}
    Q5 -- One-off --> LOGCAP[Approach 4\nLogCapture class]
    Q5 -- Persistent --> LOGV2[Approach 2\nLogv2 or Logv3]

    Q4 -- No, need user\nprofile logs too --> Q6{Can verify SELinux\ncross-user socket?}
    Q6 -- Yes / unknown --> LOGV3[Approach 3\nLogv3 multi-user\ntest avc:denied first]
    Q6 -- Blocked by SELinux --> Q7{Real-time USB\nor storage ok?}
    Q7 -- Storage ok --> INTSTOR[Approach 5\nInternal storage\nper-user service]
    Q7 -- Must be USB real-time --> STUCK[Need firmware\nteam involvement]
    STUCK --> LOGDPATCH
```

---

## What to Test First (No Firmware)

Before involving the firmware team, verify Approach 3 on the device:

```bash
# On device as adb shell — switch to user 1, check if user 1 service
# can connect to @logdaemon socket created by user 0 helper

# Look for SELinux denial:
adb logcat | grep "avc: denied"

# Specifically look for:
# avc: denied { connectto } for ... scontext=u:r:priv_app:s0:c<user1_range>
#   tcontext=u:r:priv_app:s0:c<user0_range> tclass=unix_stream_socket

# If NO denial appears and user 1 logs show up → Approach 3 works as-is
# If denial appears → need firmware team for Approach 6 or 8
```
