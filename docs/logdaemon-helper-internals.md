# logdaemon_helper.c — Internals Reference

**File:** `LogDaemon/app/src/main/cpp/logdaemon_helper.c`
**Deployed as:** `/system/bin/logdaemon`
**Started by:** Android `init` as `uid = 0` (root)

---

## Table of Contents

1. [Purpose and Constraints](#1-purpose-and-constraints)
2. [Two-Thread Architecture](#2-two-thread-architecture)
3. [Constants](#3-constants)
4. [Data Structures](#4-data-structures)
5. [Global State](#5-global-state)
6. [Signal Handling](#6-signal-handling)
7. [USB Detection](#7-usb-detection)
8. [Configuration Parsing](#8-configuration-parsing)
9. [Session Lifecycle](#9-session-lifecycle)
10. [PID Tracking](#10-pid-tracking)
11. [logcat Spawning and Resume](#11-logcat-spawning-and-resume)
12. [Log Line Parsing and Writing](#12-log-line-parsing-and-writing)
13. [Sync Thread](#13-sync-thread)
14. [Session Metadata and Summary](#14-session-metadata-and-summary)
15. [Main Capture Loop](#15-main-capture-loop)
16. [main() — Startup and Outer Loop](#16-main--startup-and-outer-loop)
17. [Decision Log](#17-decision-log)
18. [Edge Cases Handled](#18-edge-cases-handled)
19. [Known Limitations](#19-known-limitations)

---

## 1. Purpose and Constraints

`logdaemon` solves a problem no Android APK can solve: **capturing logs from every user profile simultaneously**. Android's `logd` runtime filters log entries by the caller's UID. Only root (UID 0) receives entries from all profiles. The `READ_LOGS` permission only grants socket access — it does not bypass the cross-user filter.

The daemon therefore must run as root, which requires being started by `init` rather than the Android framework. Running as root also gives:

- Direct read access to `/proc/<pid>/cmdline` for all users' processes (PID-to-package mapping)
- Direct write access to `/mnt/media_rw/` (the raw FAT USB mount, no FUSE layer)
- Immunity to Android user lifecycle events — profile switches never kill a root `init` service

The primary constraint driving every major design decision: **vold will `SIGTERM` any process that holds open file handles on `/mnt/media_rw/` before it unmounts or remounts that volume**. This means keeping USB handles open during capture is not an option.

---

## 2. Two-Thread Architecture

```
┌─────────────────────────────────────────────────────────┐
│                     main thread                          │
│  logcat pipe → parse → PID match → write internal .log  │
│  Never touches USB after initial config read.            │
└─────────────────────────────────────────────────────────┘
                           │  g_usb_gone (volatile)
                           ▼
┌─────────────────────────────────────────────────────────┐
│                     sync thread                          │
│  Every 5s: read chunk from internal .log → write USB    │
│  inotify on USB root → instant ejection detection       │
└─────────────────────────────────────────────────────────┘
```

The split exists for two reasons:

**Throughput.** USB file I/O (open, write, close, update offset file) takes a measurable amount of time. If this happened inline in the capture loop, a slow or temporarily busy USB drive would stall `fgets()` on the logcat pipe. The kernel pipe buffer is ~64 KB — at high log rates it can overflow in milliseconds, dropping entries that can never be recovered.

**USB handle safety.** The capture loop writes to internal storage (`/data/media/0/`) which vold never manages. The sync thread opens USB files for at most ~50 ms per 5-second cycle. vold never sees a persistent holder, so profile switches complete without targeting the daemon.

The two threads share only two `volatile int` globals (`g_running`, `g_usb_gone`) and the read-only `SyncArgs` struct. No mutex is needed anywhere.

---

## 3. Constants

```c
#define MAX_PACKAGES             16   // max entries in log.sinfo [packages]
#define MAX_PIDS_PER_PKG          8   // PIDs per package (main + services + :bg etc.)
#define MAX_LINE               8192   // max logcat line length
#define PID_RESCAN_INTERVAL_SEC   2   // how often to re-scan /proc for new PIDs
#define USB_SCAN_INTERVAL_SEC     5   // fallback poll sleep when inotify unavailable
#define SYNC_INTERVAL_SEC         5   // sync thread poll() timeout
#define USB_DEBOUNCE_SEC          5   // wait after IN_UNMOUNT before declaring gone
#define SYNC_CHUNK_SIZE       65536   // 64 KB per file per sync cycle
```

**`MAX_PIDS_PER_PKG = 8`** — Android apps routinely spawn multiple processes: the main process, `:remote`, `:background`, `:push`, isolated renderer, etc. All share the same package name but have distinct PIDs. Without tracking all of them, log lines from service processes would be missed. 8 covers the realistic maximum; going higher wastes scan time.

**`PID_RESCAN_INTERVAL_SEC = 2`** — Apps start and stop processes during normal use (Activity creation, Service binding, broadcast receivers). Rescanning `/proc` every 2 seconds catches new processes within one JVM startup latency. Once-per-line rescanning would be too expensive (`/proc` has thousands of entries on a real device).

**`SYNC_CHUNK_SIZE = 65536`** — 64 KB balances two concerns: large enough that a single write covers several seconds of log output at typical rates, small enough that the USB handle is held for well under the ~200 ms threshold where vold would consider it a persistent holder. The write path uses a stack buffer of this size, which is safe for a thread with the default 1 MB stack.

**`USB_DEBOUNCE_SEC = 5`** — vold typically completes a profile-switch remount within 1–2 seconds. 5 seconds provides margin on slower devices. The cost of a false positive (treating a profile switch as a real ejection) is a session restart with a small gap in logs, so the tradeoff favors a generous window.

---

## 4. Data Structures

### `Package`

```c
typedef struct {
    char name[128];              // e.g. "com.example.app"
    int  pids[MAX_PIDS_PER_PKG]; // current live PIDs for this package
    int  pid_count;
    FILE *raw;                   // .log writer (raw logcat lines)
    FILE *tsv;                   // .log.tsv writer (parsed, tab-separated)
    long entries[6];             // per-level counters: V D I W E F
    long total;                  // total lines written
} Package;
```

The `entries[6]` array is indexed by `level_idx()` which maps `V→0, D→1, I→2, W→3, E→4, F→5`. These counters feed `_summary.tsv` at session end.

### `State`

```c
typedef struct {
    char    usb_root[512];       // /mnt/media_rw/<uuid>
    char    session_dir[512];    // /data/media/0/LogDaemon/logs/<timestamp>
    Package packages[MAX_PACKAGES];
    int     package_count;
    char    min_level;           // 'D', 'I', 'W', etc.
    pid_t   logcat_pid;          // PID of the forked logcat child
    int     logcat_fd;           // read end of the logcat pipe (-1 once fdopen'd)
} State;
```

`State` is the main thread's working context. It is populated during session setup and cleared by `reset_state()` at session end. It is **not** safe to read from the sync thread — that thread uses `SyncArgs` instead.

### `SyncArgs`

```c
typedef struct {
    char usb_root[512];          // /mnt/media_rw/<uuid>
    char usb_session[512];       // /mnt/media_rw/<uuid>/logs/<ts>
    char session_dir[512];       // /data/media/0/LogDaemon/logs/<ts>
    char sync_dir[512];          // /data/media/0/LogDaemon/.sync/<ts>
    int  pkg_count;
    char pkg_names[MAX_PACKAGES][128];
} SyncArgs;
```

`SyncArgs` is populated once at session start and never modified. It is allocated on `run_session()`'s stack frame. The sync thread receives it as a `void *arg` pointer. This design is safe because `run_session()` calls `pthread_join()` before it returns — the stack frame is still live for the entire lifetime of the sync thread.

Making `SyncArgs` immutable eliminates the need for any mutex. The sync thread reads it freely.

### `LogEntry`

```c
typedef struct {
    char timestamp[32];  // "YYYY-MM-DD HH:MM:SS.mmm"  (for TSV output)
    char raw_ts[32];     // "MM-DD HH:MM:SS.mmm"        (for logcat -T resume)
    int  pid, tid;
    char level;
    char tag[256];
    char message[8192];
} LogEntry;
```

`timestamp` reconstructs the full year that logcat's `threadtime` format omits (it only emits `MM-DD`). `raw_ts` stores the original format exactly as logcat produces it so it can be saved to `.last_ts` and passed back to `logcat -T` on the next restart.

---

## 5. Global State

```c
static State        g_state    = {0};
static volatile int g_running  = 1;  // cleared by SIGTERM
static volatile int g_usb_gone = 0;  // set by sync thread on USB ejection
```

`g_state` is zero-initialized at startup. `reset_state()` returns it to this condition after each session.

`g_running` and `g_usb_gone` are `volatile` because they are written from signal handlers or a different thread and read from the main capture loop. `volatile` prevents the compiler from caching their values in a register across loop iterations. They do not need to be `_Atomic` or protected by a mutex because:

- They are only ever written to `0` or `1` (single aligned int write, atomic on every supported architecture)
- A transient visibility delay of one loop iteration is acceptable — the loop exits within milliseconds of the flag being set

---

## 6. Signal Handling

```c
static void sigterm_handler(int sig) {
    (void)sig;
    g_running = 0;
    if (g_state.logcat_pid > 0)
        kill(g_state.logcat_pid, SIGTERM);
}
```

Registered in `main()`:

```c
signal(SIGHUP,  SIG_IGN);
signal(SIGPIPE, SIG_IGN);
signal(SIGTERM, sigterm_handler);
umask(0);
```

**`SIGHUP` ignored** — the daemon has no controlling terminal. Without this, a terminal session ending while the daemon is attached (e.g. during debugging) would kill it unexpectedly.

**`SIGPIPE` ignored** — the logcat child's pipe read end may be closed if logcat exits unexpectedly. Without `SIG_IGN`, the next `fgets()` or `fprintf()` on that pipe would kill the daemon with SIGPIPE before it could clean up.

**`SIGTERM`** sets `g_running = 0` and forwards SIGTERM to the logcat child. The child's pipe will produce EOF, which unblocks `fgets()` in the capture loop. The loop then exits cleanly because `g_running == 0`.

**`umask(0)`** — ensures all files and directories created by the daemon have the permissions explicitly requested in `mkdir()` and `fopen()` calls, without the process-level mask silently masking bits off.

---

## 7. USB Detection

### `wait_for_usb_mount(int ifd)`

```c
static void wait_for_usb_mount(int ifd) {
    char buf[sizeof(struct inotify_event) + NAME_MAX + 1];
    struct pollfd pfd = { .fd = ifd, .events = POLLIN };
    while (g_running) {
        int r = poll(&pfd, 1, 1000);
        if (r > 0) { read(ifd, buf, sizeof(buf)); break; }
    }
}
```

Rather than blocking indefinitely on `read(ifd, ...)`, the function polls with a 1-second timeout. This keeps SIGTERM responsive: when `init` sends SIGTERM, `g_running` becomes 0 and the next poll timeout exits the loop within 1 second.

The `read()` drains one event from the kernel queue so the fd returns to a non-ready state. The event contents are discarded — the only relevant information is that *something* was created in `/mnt/media_rw/`.

### `find_usb()`

```c
static int find_usb(void) {
    DIR *d = opendir("/mnt/media_rw");
    ...
    while ((e = readdir(d))) {
        if (e->d_name[0] == '.') continue;
        char cfg[600];
        snprintf(cfg, sizeof(cfg), "/mnt/media_rw/%s/log.sinfo", e->d_name);
        if (access(cfg, R_OK) == 0) {
            snprintf(g_state.usb_root, ..., "/mnt/media_rw/%s", e->d_name);
            ...
            return 1;
        }
    }
    ...
    return 0;
}
```

`find_usb()` does two things: it discovers the UUID directory name that vold assigns to the volume, and it verifies that `log.sinfo` exists at the root. The presence of `log.sinfo` is the user's signal that this USB drive is configured for logging. Drives without the file are silently skipped, so the daemon ignores USB storage drives not intended for log capture.

### The Race Between `IN_CREATE` and FAT Mount

When vold mounts a USB drive it:
1. Creates `/mnt/media_rw/<uuid>/` — fires `IN_CREATE` on `/mnt/media_rw`
2. Mounts the FAT filesystem into that directory — takes 100–500 ms

If `find_usb()` runs immediately after step 1, the directory exists but the filesystem is not yet mounted so `log.sinfo` is not visible. The `IN_CREATE` event must not be silently dropped in this case — the daemon must retry.

The solution in `main()`:

```c
wait_for_usb_mount(usb_ifd);
if (!g_running) break;
for (int i = 0; i < 10 && g_running; i++) {
    sleep(1);
    if (find_usb()) goto session_ready;
}
LOGD("USB mounted but log.sinfo not found — waiting for next event");
```

After the event, retry `find_usb()` once per second for up to 10 seconds. If it succeeds at any point, `goto session_ready` bypasses the outer `if (!find_usb())` guard and proceeds directly to config parsing. If all 10 tries fail, the loop logs a message and re-blocks on inotify — which will fire again when the next drive is inserted.

The `goto` is the simplest way to break out of an inner retry loop into the middle of an outer loop without restructuring the entire loop or introducing a separate function.

---

## 8. Configuration Parsing

```c
static int parse_config(void) {
    char path[600];
    snprintf(path, sizeof(path), "%s/log.sinfo", g_state.usb_root);
    FILE *f = fopen(path, "r");
    ...
    while (fgets(line, sizeof(line), f)) {
        char *p = trim(line);
        if (!*p || *p == '#' || *p == ';') continue;
        if (*p == '[') {
            in_packages = (strstr(p, "[packages]") == p);
            in_options  = (strstr(p, "[options]")  == p);
            continue;
        }
        if (in_packages && g_state.package_count < MAX_PACKAGES) { ... }
        if (in_options && strncmp(p, "min_level=", 10) == 0) { ... }
    }
    fclose(f);
    ...
}
```

The config format is a minimal INI-like file. Two sections:

```ini
[packages]
com.example.app
com.other.package

[options]
min_level=D
```

The file is opened, read, and **closed before the function returns**. After `parse_config()`, no file handle to USB storage exists anywhere in the process. This is the mechanism that makes the daemon immune to vold's SIGTERM cascade.

`min_level` defaults to `'D'` (Debug) if absent. The value is passed directly to logcat as `*:D`, `*:I`, etc. `toupper()` is applied so `min_level=d` works correctly.

---

## 9. Session Lifecycle

### `makedirs()`

```c
static void makedirs(const char *path) {
    char tmp[512];
    ...
    for (char *p = tmp + 1; *p; p++) {
        if (*p == '/') {
            *p = 0;
            mkdir(tmp, 0775);  // EEXIST is ignored
            *p = '/';
        }
    }
    mkdir(tmp, 0775);
}
```

A simple recursive directory creator. It walks the path character by character, temporarily null-terminating at each `/` to call `mkdir()` on each prefix. `EEXIST` errors are logged at DEBUG level and ignored — the function is idempotent.

### `create_session_dir()`

Creates `/data/media/0/LogDaemon/logs/<YYYY-MM-DD_HH-MM-SS>/` using `localtime_r()` and `strftime()`. The timestamp is the session start time. If two sessions start within the same second (possible after a rapid eject-reinsert), the second `mkdir()` would fail with `EEXIST` and the function returns an error — causing `reset_state()` and a retry after 5 seconds.

`localtime_r()` is used instead of `localtime()` because it is thread-safe (writes to caller-provided storage rather than a static buffer). Even though session creation only happens on the main thread, the habit prevents subtle bugs if the code is ever restructured.

### `open_writers()`

Opens two `FILE *` handles per package:

- `<session>/<pkg>.log` — raw logcat lines exactly as received from the pipe
- `<session>/<pkg>.log.tsv` — parsed, tab-separated: `timestamp\tpid\ttid\tlevel\ttag\tmessage`

The TSV header row is written immediately on open so the file is valid even if zero entries are captured (e.g. the target package never launched during the session).

Both files are opened in `"w"` mode (create/truncate). They are never appended to — each session directory is unique, so truncation is the correct behavior.

### `close_writers()` and `reset_state()`

`close_writers()` calls `fflush()` before `fclose()` for each file. The explicit flush ensures that any buffered data in the C library's `FILE` buffer is written to the kernel before the file descriptor is released.

`reset_state()` calls `close_writers()`, then kills and waits for the logcat child if it is still running, then zeroes all of `g_state` and resets `g_usb_gone`. It is called after every session — whether the session ended cleanly, failed to start, or was interrupted by a signal.

---

## 10. PID Tracking

### Why `/proc` Instead of `logcat` PIDs?

logcat's `threadtime` format includes the PID of the logging process. The daemon could in principle just collect all PIDs that appear in log lines from target packages. The problem: a log line's PID tells you which process produced it, but you only know it belongs to a target package *after* you have already seen it in a log line tagged with the package name — which requires reading the line first.

The `/proc` scan approach inverts this: build a PID list upfront and on a rolling 2-second refresh. Any line whose PID is in the list is immediately written to the correct package's files without needing to look up the package from the tag.

### `rescan_pids()`

```c
static void rescan_pids(void) {
    for (int i = 0; i < g_state.package_count; i++)
        g_state.packages[i].pid_count = 0;

    DIR *d = opendir("/proc");
    while ((e = readdir(d))) {
        int pid = atoi(e->d_name);
        if (pid <= 0) continue;

        // read /proc/<pid>/cmdline
        // strip ":suffix" (e.g. com.app:background → com.app)
        // compare against each package name
    }
}
```

`/proc/<pid>/cmdline` contains the process name as a null-terminated string followed by null-separated arguments. For Android app processes, the process name is the package name, optionally followed by `:suffix` for named processes (e.g. `:remote`, `:push`).

The colon truncation ensures `com.example.app:background` matches a `[packages]` entry of `com.example.app`.

Running as root, this scan sees processes from every user profile — user 0, user 10 (work profile), user 11, etc. An APK running as a normal UID can only see its own user's processes.

### `pid_to_package()`

A simple O(n·m) linear scan through all packages and their PID lists. With `MAX_PACKAGES = 16` and `MAX_PIDS_PER_PKG = 8`, the worst case is 128 comparisons per log line. On a modern CPU this is negligible compared to the `fgets()` call.

---

## 11. logcat Spawning and Resume

### `spawn_logcat()`

```c
static int spawn_logcat(void) {
    char resume_ts[64] = {0};
    FILE *rf = fopen(RESUME_FILE, "r");
    if (rf) {
        if (fgets(resume_ts, sizeof(resume_ts), rf))
            resume_ts[strcspn(resume_ts, "\n\r")] = 0;
        fclose(rf);
    }

    int pipefd[2];
    pipe(pipefd);

    pid_t pid = fork();
    if (pid == 0) {
        // child
        close(pipefd[0]);
        dup2(pipefd[1], STDOUT_FILENO);
        dup2(pipefd[1], STDERR_FILENO);
        close(pipefd[1]);
        if (resume_ts[0])
            execl("/system/bin/logcat", "logcat", "-v", "threadtime",
                  "-T", resume_ts, level, NULL);
        else
            execl("/system/bin/logcat", "logcat", "-v", "threadtime", level, NULL);
        _exit(127);
    }

    close(pipefd[1]);
    g_state.logcat_pid = pid;
    g_state.logcat_fd  = pipefd[0];
    ...
}
```

**The pipe setup:**

- `pipe(pipefd)` creates a read end (`[0]`) and write end (`[1]`)
- In the child: `dup2(pipefd[1], STDOUT_FILENO)` redirects stdout to the pipe's write end; STDERR is also redirected so any logcat diagnostic output is captured rather than going to the terminal
- In the parent: `close(pipefd[1])` closes the write end — this is critical. If the parent holds the write end open, the pipe will never produce EOF when logcat exits, and `fgets()` in the capture loop would block forever

**`-v threadtime`** produces lines in the format:
```
MM-DD HH:MM:SS.mmm  PID  TID LEVEL TAG: message
```
This is the format `parse_line()` expects.

**`-T <timestamp>`** (resume): `logd` maintains an in-memory ring buffer of recent log entries. The `-T` flag asks logcat to replay all entries since a given timestamp before streaming live entries. This closes the gap between when the previous session ended and when this one started. The timestamp is read from `.last_ts`, which is written during capture every 64 lines.

**`_exit(127)` in the child** — `_exit()` rather than `exit()` is intentional. `exit()` would flush C library stdio buffers and run `atexit()` handlers, which in a forked process would corrupt shared state (e.g. double-flushing file buffers). `_exit()` terminates the process immediately without any library cleanup.

---

## 12. Log Line Parsing and Writing

### `parse_line()`

```c
static int parse_line(const char *line, LogEntry *e) {
    char date[16], time_s[16];
    int pid, tid, consumed = 0;
    char level;
    if (sscanf(line, "%15s %15s %d %d %c %n",
               date, time_s, &pid, &tid, &level, &consumed) < 5 || consumed == 0)
        return -1;
    ...
}
```

`sscanf` with `%n` captures the number of characters consumed up to that point. `consumed` becomes the offset to the remainder of the line (tag and message). If the format does not match (system messages, blank lines, etc.), `sscanf` returns fewer than 5 matches and the line is silently dropped.

**Year reconstruction:** logcat's `threadtime` format omits the year (`MM-DD` not `YYYY-MM-DD`). The daemon reconstructs the full timestamp by prepending the current year from `time(NULL)`:

```c
snprintf(e->timestamp, sizeof(e->timestamp), "%d-%s %s", tm.tm_year + 1900, date, time_s);
```

This is a best-effort reconstruction. Sessions that span a year boundary (Dec 31 → Jan 1) would have the wrong year for entries before midnight. This is an accepted limitation.

**Tag/message split:** after the level character, logcat formats the rest as `TAG: message`. The split is found by `strstr(rest, ": ")`. Tags are trimmed of trailing whitespace. If no `": "` separator exists (malformed line), the entire remainder is treated as the message with an empty tag.

### `tsv_clean()`

```c
static void tsv_clean(char *s) {
    for (char *p = s; *p; p++)
        if (*p == '\t' || *p == '\n' || *p == '\r') *p = ' ';
}
```

TSV files use tabs as field delimiters. A tag or message containing a literal tab would break the column structure. `tsv_clean()` replaces tabs, newlines, and carriage returns with spaces in the copies that go to the TSV file. The raw `.log` file is never modified — it receives the original line verbatim.

### `write_entry()` — The Checkpoint

```c
pkg->total++;
pkg->entries[level_idx(e->level)]++;
if ((pkg->total & 0x3F) == 0) {          // every 64 lines
    fflush(pkg->raw);
    fflush(pkg->tsv);
    FILE *cf = fopen(RESUME_FILE, "w");
    if (cf) { fprintf(cf, "%s\n", e->raw_ts); fclose(cf); }
}
```

`& 0x3F` is equivalent to `% 64` but branch-free on every architecture. Every 64 lines:
1. Both writers are flushed to the kernel (so the sync thread sees recent data)
2. `.last_ts` is updated with the current log entry's timestamp

The flush-before-checkpoint ordering is important: the sync thread reads from the internal files, not from the stdio buffer. Without `fflush()`, the sync thread could read a stale copy of the file while fresh data sits in the C library's buffer.

If the flush interval were 1 line, the overhead of `.last_ts` rewrites would be significant at high log rates. If it were 1000 lines, a crash would lose up to 1000 lines of resume precision. 64 is the sweet spot.

---

## 13. Sync Thread

### `sync_one_file()` — The Core Sync Primitive

```c
static void sync_one_file(const char *src_path, const char *dst_path,
                           const char *off_path) {
    long offset = 0;
    { FILE *f = fopen(off_path, "r"); if (f) { fscanf(f, "%ld", &offset); fclose(f); } }

    FILE *src = fopen(src_path, "r");
    if (fseek(src, offset, SEEK_SET) != 0) { fclose(src); return; }

    char buf[SYNC_CHUNK_SIZE];
    size_t n = fread(buf, 1, sizeof(buf), src);
    fclose(src);
    if (n == 0) return;

    FILE *dst = fopen(dst_path, "a");
    size_t written = fwrite(buf, 1, n, dst);
    fflush(dst);
    fclose(dst);

    if (written == n) {
        FILE *f = fopen(off_path, "w");
        if (f) { fprintf(f, "%ld\n", offset + (long)n); fclose(f); }
    }
}
```

The offset file (`*.off`) records how many bytes of the internal file have been copied to USB so far. This is the state that survives temporary USB absence: if the drive is missing for 30 seconds (profile switch, transient disconnect), the offset is unchanged, and when the drive returns the sync resumes from exactly where it left off without duplication.

**Partial write safety:** the offset is only advanced if `written == n`. If the USB drive runs out of space or the write fails mid-chunk, the offset stays at its previous value and the same chunk is retried on the next cycle. This means partial writes are retried rather than silently lost.

**`fopen(dst, "a")`** — append mode. The USB mirror file grows monotonically. Combined with the offset tracking, this guarantees that the USB file is always a prefix of the internal file — never out of order, never with gaps between what was written in previous cycles.

**`fclose(src)` before `fopen(dst)`** — the internal source file is closed before the USB destination file is opened. Only one file handle exists at a time. The internal file is always closed before any USB handle is opened, ensuring that vold can never simultaneously see both.

### `sync_thread_func()` — The Event Loop

```c
while (g_running && !g_usb_gone) {
    int r = (ifd >= 0)
            ? poll(&pfd, 1, SYNC_INTERVAL_SEC * 1000)
            : (sleep(SYNC_INTERVAL_SEC), 0);

    if (r > 0) {
        // inotify fired — drain event, debounce, check log.sinfo
        ...
    }

    if (!g_running) break;

    // r == 0 (timeout) or r < 0 (EINTR): run sync
    sync_all_packages(sa);
}
```

**The `poll()` call** serves double duty:
- When it returns 0 (timeout), it means `SYNC_INTERVAL_SEC` elapsed with no USB events — run the scheduled sync
- When it returns > 0 (readable), an inotify event arrived — handle the potential ejection

`EINTR` (signal interrupt, `r == -1, errno == EINTR`) falls through to `sync_all_packages()` — the sync runs, then the loop continues. This is correct: a signal arriving during the poll timeout is not a USB event.

**The inotify watch:**

```c
inotify_add_watch(ifd, sa->usb_root,
                  IN_UNMOUNT | IN_DELETE_SELF | IN_MOVE_SELF);
```

Three events are watched:
- `IN_UNMOUNT` — vold unmounted the filesystem (most common: user pressed "eject" or a profile switch triggered a remount)
- `IN_DELETE_SELF` — the directory itself was deleted (unusual but possible if vold removes the UUID dir)
- `IN_MOVE_SELF` — the directory was renamed (defensive; not expected in normal operation)

**The debounce:**

```c
sleep(USB_DEBOUNCE_SEC);
char sinfo[600];
snprintf(sinfo, sizeof(sinfo), "%s/log.sinfo", sa->usb_root);
if (access(sinfo, R_OK) == 0) {
    // profile switch — USB came back
    inotify_add_watch(ifd, sa->usb_root, IN_UNMOUNT | IN_DELETE_SELF | IN_MOVE_SELF);
    continue;
}
// real ejection
g_usb_gone = 1;
kill(g_state.logcat_pid, SIGTERM);
break;
```

`IN_UNMOUNT` fires on *any* unmount, including the temporary remount vold performs during an Android profile switch. Without debouncing, every profile switch would restart the session.

After `IN_UNMOUNT` fires, the inotify watch descriptor is **invalidated** by the kernel — watching an unmounted filesystem is meaningless. After the debounce, if `log.sinfo` is accessible, vold has completed its remount. The watch is **re-registered** (`inotify_add_watch()` is called again on the same `ifd`) to watch the newly mounted filesystem.

After a real ejection, `g_usb_gone = 1` is set and the logcat child is killed. The main thread's `fgets()` returns EOF (or `g_usb_gone` is checked in the loop condition), ending the capture loop.

**Final flush:**

```c
if (ifd >= 0) close(ifd);
sync_all_packages(sa);   // one final attempt
```

After the loop exits (USB gone or `g_running = 0`), `sync_all_packages()` runs once more. If USB is genuinely gone, the `fopen(dst, "a")` calls inside `sync_one_file()` will return NULL and the function exits silently — no crash, no error spam. If USB is still momentarily accessible (e.g. the thread exited because `g_running` went to 0), this final sync flushes any bytes written since the last cycle.

---

## 14. Session Metadata and Summary

### `_session.meta`

Written at session start to internal storage:

```
session_start=2026-06-08 14:30:00
logv4=1
uid=0
pid=1234
min_level=D
usb=/mnt/media_rw/ABCD-1234
packages:
  - com.example.app
  - com.other.package
```

The `logv4=1` field allows tooling to distinguish Logv4 sessions from earlier formats. `uid=0` and `pid` are informational — they confirm the daemon ran as root and identify the process in system logs.

### `_summary.tsv`

Written at session end:

```tsv
package         total   V   D   I   W   E   F
com.example.app 4821    0   3104 1203 412 98  4
duration_seconds=1842
```

One row per package. The level counters let developers immediately see if a package logged an unusual number of errors without reading through raw logs. Written after `pthread_join()` completes, so it reflects the final state of all counters after the last line was processed.

---

## 15. Main Capture Loop

Inside `run_session()`, after the sync thread is spawned:

```c
char line[MAX_LINE];
time_t last_rescan = time(NULL);
long   lines_total = 0, lines_matched = 0;

while (g_running && !g_usb_gone && fgets(line, sizeof(line), logf)) {
    // strip trailing newline
    // parse the line
    // look up PID → package index
    // write entry to .log and .tsv
    // every 2s: rescan_pids()
}
```

The loop has three exit conditions:

1. **`g_running == 0`** — SIGTERM received; init is stopping the daemon
2. **`g_usb_gone == 1`** — sync thread detected USB ejection and killed the logcat child
3. **`fgets()` returns NULL** — logcat child exited (pipe EOF); this happens when the sync thread kills it after setting `g_usb_gone`, or if logcat crashes

In cases 2 and 3, the flow is: sync thread kills logcat → pipe gets EOF → `fgets()` returns NULL → loop exits. The `g_usb_gone` check in the loop condition is technically redundant (the loop would exit anyway via the EOF path), but it makes the intent explicit and avoids processing one more line between the signal and the EOF.

After the loop:

```c
if (g_state.logcat_pid > 0) {
    kill(g_state.logcat_pid, SIGTERM);
    waitpid(g_state.logcat_pid, NULL, 0);
    g_state.logcat_pid = 0;
}
fclose(logf);
close_writers();
pthread_join(sync_tid, NULL);
write_summary(session_start, time(NULL));
```

`waitpid()` is called unconditionally (if the PID is still set) to reap the zombie child process. If the sync thread already killed it, `waitpid()` will immediately return because the child is already in zombie state. Without `waitpid()`, the child would remain as a zombie entry in the process table until init reaps it.

`close_writers()` is called **before** `pthread_join()`. This ensures the internal `.log` and `.tsv` files are fully flushed and closed before the sync thread's final `sync_all_packages()` runs. If the order were reversed, the sync thread might copy a truncated version of a file that was still buffered.

---

## 16. main() — Startup and Outer Loop

```c
int main(void) {
    signal(SIGHUP,  SIG_IGN);
    signal(SIGPIPE, SIG_IGN);
    signal(SIGTERM, sigterm_handler);
    umask(0);

    makedirs(OUTPUT_ROOT);

    int usb_ifd = inotify_init1(IN_CLOEXEC);
    if (usb_ifd >= 0)
        inotify_add_watch(usb_ifd, "/mnt/media_rw", IN_CREATE);

    while (g_running) {
        if (!find_usb()) {
            // wait for inotify event, then retry find_usb() up to 10 times
            ...
            continue;
        }
        session_ready:

        parse_config() → create_session_dir() → open_writers() → run_session() → reset_state()
    }

    close(usb_ifd);
}
```

`inotify_init1(IN_CLOEXEC)` — the `IN_CLOEXEC` flag marks the inotify fd to be closed automatically when the logcat child is `exec()`'d. Without it, the child would inherit an open fd pointing at the `/mnt/media_rw` inotify instance, which would prevent the daemon from detecting some inotify events correctly and would leak a fd into a process that has no use for it.

The outer `while (g_running)` loop represents the daemon's session restart behavior. After each session ends (USB ejected, clean shutdown, or error), `reset_state()` clears everything and the loop repeats from USB detection. This mirrors the behavior described in the `.rc` file's `restart_period 5` — when the process exits entirely, init restarts it after 5 seconds. When it stays alive but the session ends, this inner loop restarts immediately.

---

## 17. Decision Log

| Decision | Alternative Considered | Reason Chosen |
|---|---|---|
| Two-thread design | Single thread polling USB between log reads | Eliminates USB I/O from the hot path; prevents pipe overflow at high log rates |
| Write to internal storage, sync to USB | Write directly to USB | Open USB handles cause vold SIGTERM cascade on profile switches |
| inotify for USB mount/unmount | `stat()` polling every 5s | Instant detection; no wasted CPU; no 5s lag on plug-in |
| 5s debounce on `IN_UNMOUNT` | Re-check immediately | Profile-switch remounts trigger `IN_UNMOUNT` and take 1–3s to complete |
| Re-register inotify watch after debounce | Keep old watch descriptor | `IN_UNMOUNT` invalidates the watch; re-registration is mandatory |
| `/proc/<pid>/cmdline` for PID mapping | Parse package name from logcat tag field | Root access sees all-user processes; tag field is unreliable for some system log lines |
| 64-line flush/checkpoint interval | 1 line or 1000 lines | Balances write amplification against resume granularity |
| `SyncArgs` on `run_session()` stack | Heap-allocated `SyncArgs` | `pthread_join()` guarantees stack is live; avoids `malloc`/`free` |
| `goto session_ready` for retry path | Flag variable or loop restructure | Cleanest way to break an inner retry loop into the outer loop |
| `_exit(127)` in child after `exec` failure | `exit(127)` | Prevents double-flushing of parent's stdio buffers in the forked child |

---

## 18. Edge Cases Handled

**USB inserted with no `log.sinfo`** — `find_usb()` scans every UUID directory but only accepts one with `log.sinfo` present. Drives without the file are ignored. The daemon continues waiting for a qualifying drive.

**USB inserted but FAT not yet mounted** — `IN_CREATE` fires when vold creates the UUID directory, before the filesystem is accessible. The 10-second retry loop in `main()` handles the gap.

**Profile switch during active session** — vold sends `IN_UNMOUNT`, remounts, re-fires something on the path. The 5s debounce plus `access(log.sinfo)` check distinguishes this from a real ejection. Session continues without interruption.

**logcat child crashes** — pipe read end gets EOF, `fgets()` returns NULL, capture loop exits cleanly. `waitpid()` reaps the zombie. `reset_state()` clears the PID. The outer loop retries the session.

**`init` sends SIGTERM** — `sigterm_handler` sets `g_running = 0` and kills the logcat child. Both the capture loop and the sync thread's `poll()` exit within their respective timeouts (1 second for each). The daemon performs a clean shutdown.

**USB write error mid-chunk** — `sync_one_file()` does not advance the offset unless `written == n`. The failed chunk is retried next cycle. Error is silent (no log spam for transient USB errors).

**Partial read on logcat pipe** — `fgets()` handles this correctly — it reads until `\n` or the buffer is full. Logcat writes complete lines atomically to the pipe, so partial lines should not occur in practice, but `fgets()` is safe regardless.

**Package with zero log lines** — the TSV file still exists with only the header row. The summary row shows all zeros. This is intentional: the absence of log lines is itself useful information.

**`inotify_init1` fails** — both the mount watcher and the sync-thread watcher have explicit fallback paths. The mount watcher falls back to `sleep(USB_SCAN_INTERVAL_SEC)` polling. The sync thread falls back to `sleep(SYNC_INTERVAL_SEC)` with no ejection detection (session will continue until `g_running` is cleared by SIGTERM or the next `restart_period` expiry).

---

## 19. Known Limitations

**Year rollover in timestamps.** `parse_line()` prepends the current year to the `MM-DD` date from logcat. Sessions that cross midnight on December 31 will have the wrong year for pre-midnight entries. Fixing this would require parsing the year from the system clock at session start and applying it based on the date field, which adds complexity for a very rare edge case.

**Single active session.** The outer loop processes one session at a time. If a second USB drive with `log.sinfo` is inserted while a session is active, it is ignored until the current session ends. `find_usb()` returns the first matching drive found by `readdir()`, which is non-deterministic.

**No USB-to-USB session resume.** The `.last_ts` resume mechanism works across daemon restarts, but the sync offset files are keyed by session directory name. If the session directory is deleted from internal storage (e.g. storage was wiped), the sync offsets are orphaned and the corresponding USB files cannot be continued.

**Max 16 packages.** `MAX_PACKAGES = 16`. Increasing this requires recompilation. At values much larger than ~50, the `rescan_pids()` O(n·m) scan becomes a measurable cost in the capture loop.

**`SYNC_CHUNK_SIZE` is a stack allocation.** `sync_one_file()` declares `char buf[65536]` on the stack inside the sync thread. The default thread stack on Android is 1 MB — this is safe — but it prevents further nesting of large stack arrays in functions called from the sync thread.

**No log rotation on internal storage.** Session directories accumulate in `/data/media/0/LogDaemon/logs/`. There is no automatic cleanup of old sessions. On a device used heavily over weeks, this directory can grow large. Management is left to the user.
