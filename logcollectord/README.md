# logcollectord

A native C daemon that runs permanently in firmware, detects a USB drive
containing a `log.sinfo` config, and streams filtered logcat output to
`/data/media/0/Download/` on the device's internal storage.

Works seamlessly across Android user-profile switches because it runs as a
root `init` service (parented to PID 1), never restarts on profile transitions,
and re-resolves package PIDs from `/proc` every few seconds.

---

## Features

- **USB-triggered** — starts collecting only when a drive with `log.sinfo` is attached
- **Multi-user transparent** — single logcat stream, no UID filter; re-scans `/proc` for new PIDs after every profile switch (gap ≤ 3 s)
- **System + user apps** — any app whose package name is in `log.sinfo`, including system packages
- **Output in user 0 Downloads** — written to `/data/media/0/Download/logcollect_<timestamp>.txt`, visible in the Files app
- **Auto media scan** — triggers `am broadcast MEDIA_SCANNER_SCAN_FILE` on session close so the file appears without a reboot
- **Resumable** — if the daemon is killed/crashed, init restarts it and it picks up any re-inserted USB immediately

---

## Files

```
logcollectord/
├── logcollectord.c        # daemon source
├── CMakeLists.txt         # NDK cross-compile via cmake
├── build.sh               # one-command NDK build script
├── install.sh             # adb push + install script
├── logcollectord.rc       # init service definition
├── log.sinfo.example      # annotated USB config template
└── README.md
```

---

## Build

### Option A — build.sh (recommended)

```bash
cd logcollectord
NDK=/path/to/ndk ./build.sh          # arm64-v8a, API 34
# or
NDK=/path/to/ndk ABI=armeabi-v7a API=26 ./build.sh
```

Output: `out/<ABI>/logcollectord`

### Option B — CMake

```bash
cmake -B build \
  -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-34 \
  -DCMAKE_BUILD_TYPE=Release
cmake --build build
```

---

## Install

```bash
cd logcollectord
ABI=arm64-v8a ./install.sh
```

The script:
1. `adb root && adb remount`
2. Pushes binary to `/system/bin/logcollectord` (chmod 755)
3. Pushes `.rc` to `/system/etc/init/logcollectord.rc`
4. Reports SELinux state and prompts for reboot

For firmware inclusion (no ADB required at install time):

```
device/<vendor>/<board>/
└── system/
    ├── bin/logcollectord
    └── etc/init/logcollectord.rc
```

---

## USB config (`log.sinfo`)

Place a file named `log.sinfo` at the root of the USB drive:

```ini
[packages]
com.example.mainapp
com.example.launcher
com.android.settings

[options]
min_level=D
```

See `log.sinfo.example` for the full annotated version.

---

## Output

Each USB session creates one file:

```
/storage/emulated/0/Download/logcollect_2026-05-29_14-32-15.txt
```

Format:
```
=== logcollectord session start: 2026-05-29 14:32:15 ===
=== USB root: /mnt/media_rw/ABCD-1234 ===
=== Packages: com.example.mainapp com.android.settings ===

[com.example.mainapp] 05-29 14:32:16.001  1234  5678 D MyActivity: onCreate
[com.android.settings] 05-29 14:32:16.050  2000  2001 I SettingsActivity: onResume
...
=== session end: 2026-05-29 14:45:00  lines: 8432 ===
```

---

## Permissions & checklist

Go through this before deploying:

### 1. SELinux (most common blocker)

Check current mode:
```bash
adb shell getenforce
```

**Permissive** — works immediately with the `.rc` as written.

**Enforcing** — the daemon runs under `u:r:shell:s0` (workaround). Whether this
works depends on the device's policy. If the daemon fails to start:

```bash
adb shell setenforce 0       # temporary, until next reboot
```

For a permanent enforcing-mode solution, define a custom SELinux type. Minimal
rules needed:
- allow reading `logd_socket` (to read logcat output)
- allow reading `/proc/<pid>/cmdline` (`proc_cmdline`)
- allow reading `/mnt/media_rw/` (`mnt_media_rw_file`)
- allow writing `/data/media/` (`media_rw_data_file`)
- allow executing `/system/bin/logcat` (`logcat_exec`)
- allow executing `/system/bin/am` (for media scan broadcast)

### 2. Linux groups

The `.rc` declares `group root log media_rw sdcard_rw`:
- `log` (AID 1007) — read logd
- `media_rw` (AID 1023) — `/mnt/media_rw/<UUID>` and `/data/media`
- `sdcard_rw` (AID 1015) — fallback for FUSE paths

### 3. FBE / user CE storage

The daemon always writes to `/data/media/0/Download` (user 0's CE storage).
This is accessible as root as long as user 0 has unlocked the device at least
once since boot. If you need capture before first unlock, write to DE storage
instead (e.g. `/data/misc/logcollectord/`) and move files later.

### 4. USB drive filesystem

Android's vold handles FAT32 and exFAT natively. NTFS requires a kernel module
or vold plugin. The daemon mounts nothing itself — it relies on vold to mount
the drive and expose it under `/mnt/media_rw/<UUID>`.

### 5. Boot timing

`class late_start` starts after the framework boots. If you need pre-unlock
capture, change to `class core` and use DE output storage.

### 6. Buffer overflow

A single logcat stream for all UIDs can be high volume. Set a reasonable
`min_level` in `log.sinfo` (I or W for production, D for debugging) to reduce
throughput. The output file is unbounded — add size rotation if needed for
long sessions.

---

## Verify installation

After reboot:

```bash
# daemon running?
adb shell ps -A | grep logcollectord

# watching logs
adb logcat -s logcollectord

# insert USB drive with log.sinfo, then check Downloads
adb shell ls -lh /data/media/0/Download/logcollect_*
```
