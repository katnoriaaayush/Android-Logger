# LogBridge — Phase 1

Two-way communication between a **native daemon** (`logbridged`) and a
**singleUser persistent Android service** (`LogBridgeService`), with the daemon
starting the service when a USB volume carrying `log.sinfo` is detected.

This phase only **proves the channel works**. Log capture, encryption, and
USB write-back come in later phases.

## Architecture

```
┌─ logbridged (native, /system/bin) ─────────────────────────┐
│  • binds /dev/socket/logbridge  (server)                    │
│  • polls /mnt/media_rw/<vol>/log.sinfo  (USB)               │
│       or /data/local/tmp/log.sinfo      (emulator test)     │
│  • on detect: reads sinfo → `am start-foreground-service`   │
│  • handshake over socket (see protocol)                     │
└───────────────────────────┬────────────────────────────────┘
            Unix domain socket  (/dev/socket/logbridge, 0660 system:system)
┌───────────────────────────┴────────────────────────────────┐
│  LogBridgeService (com.sqa.logtest, UID 1000, user 0)       │
│  • singleUser + persistent + directBootAware                │
│  • connects back (RESERVED LocalSocket namespace)           │
│  • answers SESSION_CONFIG→ACK, PING→PONG                    │
└─────────────────────────────────────────────────────────────┘
```

## Wire protocol

Length-prefixed binary frames, all integers **big-endian**:

```
[4 bytes: type][4 bytes: payload length][payload]
```

| Type | Name           | Direction        | Payload                       |
|------|----------------|------------------|-------------------------------|
| 0x01 | SESSION_CONFIG | daemon → service | raw sinfo file content        |
| 0x02 | ACK            | service → daemon | status string                 |
| 0x03 | PING           | daemon → service | text                          |
| 0x04 | PONG           | service → daemon | `pong:<original text>`        |
| 0x05 | BYE            | either           | (none)                        |

Handshake sequence on connect:
`SESSION_CONFIG → ACK → PING → PONG`, then the daemon keepalive-PINGs every 10s.
This exercises **both directions twice** before settling into keepalive.

## Files

| Path | Purpose |
|------|---------|
| `native/logbridged.c`   | daemon source (libc only, no Android deps) |
| `native/logbridged.rc`  | init service + socket declaration |
| `native/Android.bp`     | Soong build (`cc_binary`) |
| `../CrossUserLogTest/app/src/main/java/com/sqa/logtest/LogBridgeService.kt` | Android service |

## Build

### Daemon (AOSP / Soong)

1. Copy `native/` into an AOSP tree, e.g. `device/<oem>/<board>/logbridge/`.
2. Add to the device makefile:
   ```makefile
   PRODUCT_PACKAGES += logbridged
   ```
3. `m logbridged` (or a full build). Output: `/system/bin/logbridged` +
   `/system/etc/init/logbridged.rc`.

### Daemon (standalone NDK, for quick testing)

```bash
$NDK/toolchains/llvm/prebuilt/<host>/bin/aarch64-linux-android30-clang \
    native/logbridged.c -o logbridged -static
adb push logbridged /data/local/tmp/
adb shell chmod 755 /data/local/tmp/logbridged
```

### Service

Built as part of the `CrossUserLogTest` app. Use the `systemSigned` flavor and
platform-sign the APK so it runs as UID 1000 (required for `singleUser`).

## Test (emulator, no real USB)

```bash
# 1. Start the daemon (run as root so it can bind /dev/socket and exec am)
adb shell su 0 /data/local/tmp/logbridged &

# 2. Drop a sinfo file to trigger detection
adb shell "echo '[packages]
com.company.mainapp
[options]
min_level=D' > /data/local/tmp/log.sinfo"

# 3. Watch both sides
adb logcat -s logbridged LogBridgeService
#   daemon: "sinfo detected" → "starting Android service"
#   daemon: "TWO-WAY HANDSHAKE COMPLETE — bidirectional comms verified"
#   service log file: /sdcard/CrossUserLogTest/logbridge_<ts>.txt
```

To test on real hardware, format a USB drive, place `log.sinfo` at its root,
and attach it — the daemon picks it up under `/mnt/media_rw/<vol>/`.

## Manual service start (without the daemon)

```bash
adb shell am start-foreground-service --user 0 \
    -n com.sqa.logtest/.LogBridgeService --es session manual
```
(The service will then try to connect to `/dev/socket/logbridge`, so the daemon
must already be running for the handshake to complete.)

## SELinux / cert notes (read before shipping)

The Phase-1 `.rc` uses `seclabel u:r:shell:s0` as a **testing shortcut**. For an
EDLA / GMS-certified image:

- Give the daemon a **dedicated domain** (`logbridge_daemon`), not `shell`.
- Keep `LogBridgeService` out of the shared `platform_app` domain — assign a
  dedicated `seinfo` domain via `mac_permissions.xml` so socket-connect grants
  don't widen `platform_app` (avoids `neverallow` failures).
- Allowlist every privileged permission the app requests in
  `privapp-permissions-*.xml` (`PrivappPermissionsTest` fails the build otherwise).
- Ship **fully enforcing**, zero AVC denials.
- Gate activation behind enterprise provisioning so the log-capture/USB-export
  reads as a disclosed managed capability during privacy review.
