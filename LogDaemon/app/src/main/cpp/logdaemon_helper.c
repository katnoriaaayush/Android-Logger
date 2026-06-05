// LogDaemon native helper — Logv4 (init.rc daemon)
//
// Deployed as /system/bin/logdaemon, started by init as user=root.
//
// Two-thread design:
//   Main thread  — reads logcat pipe, filters by PID/package, writes to
//                  internal storage. Never touches USB during capture.
//   Sync thread  — wakes every SYNC_INTERVAL_SEC: streams new bytes from
//                  internal storage to USB in 64 KB chunks. Uses inotify
//                  on the USB root path to detect ejection instantly —
//                  no polling, no miss counter.
//
// Flow:
//   1. Watch /mnt/media_rw/ via inotify for IN_CREATE (USB mount)
//   2. find_usb() checks for log.sinfo; if absent go back to step 1
//   3. Read config (file closed immediately — no persistent USB handle)
//   4. Create session dir on internal storage
//   5. Spawn sync thread (passes immutable session params + inotify fd)
//   6. Main thread: logcat → filter → write internal storage (uninterrupted)
//   7. Sync thread: every 5s sync chunks to USB; inotify fires instantly
//      on IN_UNMOUNT/IN_DELETE_SELF → sets g_usb_gone, kills logcat child
//   8. Main thread fgets() returns EOF → session ends
//   9. Join sync thread (final flush attempt) → reset → back to step 1

#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <signal.h>
#include <pthread.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <dirent.h>
#include <time.h>
#include <errno.h>
#include <ctype.h>
#include <poll.h>
#include <sys/inotify.h>
#include <android/log.h>

#define TAG "LogDaemon"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)

#define MAX_PACKAGES             16
#define MAX_PIDS_PER_PKG          8
#define MAX_LINE               8192
#define PID_RESCAN_INTERVAL_SEC   2
#define USB_SCAN_INTERVAL_SEC     5   // fallback poll interval if inotify unavailable
#define SYNC_INTERVAL_SEC         5   // sync thread wake interval (poll timeout)
#define SYNC_CHUNK_SIZE       65536   // max bytes per file per sync cycle (64 KB)

#define OUTPUT_ROOT "/data/media/0/LogDaemon"
#define RESUME_FILE "/data/media/0/LogDaemon/.last_ts"

// ── data structures ───────────────────────────────────────────────────────────

typedef struct {
    char name[128];
    int  pids[MAX_PIDS_PER_PKG];
    int  pid_count;
    FILE *raw;
    FILE *tsv;
    long entries[6];  // V D I W E F
    long total;
} Package;

typedef struct {
    char    usb_root[512];
    char    session_dir[512];
    Package packages[MAX_PACKAGES];
    int     package_count;
    char    min_level;
    pid_t   logcat_pid;
    int     logcat_fd;
} State;

// Immutable after session start — safe to read from sync thread without locks.
typedef struct {
    char usb_root[512];       // /mnt/media_rw/<uuid>
    char usb_session[512];    // /mnt/media_rw/<uuid>/logs/<ts>  (mirror on USB)
    char session_dir[512];    // /data/media/0/LogDaemon/logs/<ts>
    char sync_dir[512];       // /data/media/0/LogDaemon/.sync/<ts>
    int  pkg_count;
    char pkg_names[MAX_PACKAGES][128];
} SyncArgs;

static State        g_state    = {0};
static volatile int g_running  = 1;  // cleared by SIGTERM
static volatile int g_usb_gone = 0;  // set by sync thread when USB disappears

// ── signal handling ───────────────────────────────────────────────────────────

static void sigterm_handler(int sig) {
    (void)sig;
    g_running = 0;
    if (g_state.logcat_pid > 0)
        kill(g_state.logcat_pid, SIGTERM);
}

// ── USB discovery ─────────────────────────────────────────────────────────────

// Block until inotify reports a new entry in /mnt/media_rw/ (USB mount).
// Polls in 1 s slices so SIGTERM (g_running=0) is noticed promptly.
static void wait_for_usb_mount(int ifd) {
    char buf[sizeof(struct inotify_event) + NAME_MAX + 1];
    struct pollfd pfd = { .fd = ifd, .events = POLLIN };
    while (g_running) {
        int r = poll(&pfd, 1, 1000);
        if (r > 0) { read(ifd, buf, sizeof(buf)); break; }
    }
}

static int find_usb(void) {
    DIR *d = opendir("/mnt/media_rw");
    if (!d) { LOGD("opendir /mnt/media_rw: %s", strerror(errno)); return 0; }

    struct dirent *e;
    while ((e = readdir(d))) {
        if (e->d_name[0] == '.') continue;
        char cfg[600];
        snprintf(cfg, sizeof(cfg), "/mnt/media_rw/%s/log.sinfo", e->d_name);
        if (access(cfg, R_OK) == 0) {
            snprintf(g_state.usb_root, sizeof(g_state.usb_root),
                     "/mnt/media_rw/%s", e->d_name);
            LOGI("USB found: %s", g_state.usb_root);
            closedir(d);
            return 1;
        }
    }
    closedir(d);
    return 0;
}

// ── helpers ───────────────────────────────────────────────────────────────────

static char *trim(char *s) {
    while (*s && isspace((unsigned char)*s)) s++;
    char *end = s + strlen(s);
    while (end > s && isspace((unsigned char)*(end - 1))) end--;
    *end = 0;
    return s;
}

static void makedirs(const char *path) {
    char tmp[512];
    strncpy(tmp, path, sizeof(tmp) - 1);
    tmp[sizeof(tmp) - 1] = 0;
    for (char *p = tmp + 1; *p; p++) {
        if (*p == '/') {
            *p = 0;
            if (mkdir(tmp, 0775) < 0 && errno != EEXIST)
                LOGD("makedirs: %s: %s", tmp, strerror(errno));
            *p = '/';
        }
    }
    if (mkdir(tmp, 0775) < 0 && errno != EEXIST)
        LOGD("makedirs: %s: %s", tmp, strerror(errno));
}

// Read config from USB. File opened and closed here — no USB handle persists.
static int parse_config(void) {
    char path[600];
    snprintf(path, sizeof(path), "%s/log.sinfo", g_state.usb_root);
    FILE *f = fopen(path, "r");
    if (!f) { LOGE("Cannot open config: %s", path); return -1; }

    char line[1024];
    int in_packages = 0, in_options = 0;
    g_state.min_level = 'D';

    while (fgets(line, sizeof(line), f)) {
        char *p = trim(line);
        if (!*p || *p == '#' || *p == ';') continue;
        if (*p == '[') {
            in_packages = (strstr(p, "[packages]") == p);
            in_options  = (strstr(p, "[options]")  == p);
            continue;
        }
        if (in_packages && g_state.package_count < MAX_PACKAGES) {
            strncpy(g_state.packages[g_state.package_count].name, p, 127);
            g_state.package_count++;
        }
        if (in_options && strncmp(p, "min_level=", 10) == 0)
            g_state.min_level = (char)toupper((unsigned char)p[10]);
    }
    fclose(f);
    LOGI("Config: %d package(s), min_level=%c", g_state.package_count, g_state.min_level);
    return 0;
}

static int create_session_dir(void) {
    time_t now = time(NULL);
    struct tm tm;
    localtime_r(&now, &tm);
    char ts[64];
    strftime(ts, sizeof(ts), "%Y-%m-%d_%H-%M-%S", &tm);

    char logs_dir[512];
    snprintf(logs_dir, sizeof(logs_dir), "%s/logs", OUTPUT_ROOT);
    makedirs(logs_dir);

    struct stat st;
    if (stat(logs_dir, &st) < 0 || !S_ISDIR(st.st_mode)) {
        LOGE("logs dir not usable: %s (%s)", logs_dir, strerror(errno));
        return -1;
    }

    snprintf(g_state.session_dir, sizeof(g_state.session_dir), "%s/%s", logs_dir, ts);
    if (mkdir(g_state.session_dir, 0775) < 0) {
        LOGE("mkdir session: %s", strerror(errno));
        return -1;
    }
    LOGI("Session dir: %s", g_state.session_dir);
    return 0;
}

static int open_writers(void) {
    for (int i = 0; i < g_state.package_count; i++) {
        Package *pkg = &g_state.packages[i];
        char path[600];

        snprintf(path, sizeof(path), "%s/%s.log", g_state.session_dir, pkg->name);
        pkg->raw = fopen(path, "w");
        if (!pkg->raw) { LOGE("Open %s: %s", path, strerror(errno)); return -1; }

        snprintf(path, sizeof(path), "%s/%s.log.tsv", g_state.session_dir, pkg->name);
        pkg->tsv = fopen(path, "w");
        if (!pkg->tsv) { LOGE("Open %s: %s", path, strerror(errno)); return -1; }

        fprintf(pkg->tsv, "timestamp\tpid\ttid\tlevel\ttag\tmessage\n");
        fflush(pkg->tsv);
    }
    return 0;
}

static void close_writers(void) {
    for (int i = 0; i < g_state.package_count; i++) {
        Package *pkg = &g_state.packages[i];
        if (pkg->raw) { fflush(pkg->raw); fclose(pkg->raw); pkg->raw = NULL; }
        if (pkg->tsv) { fflush(pkg->tsv); fclose(pkg->tsv); pkg->tsv = NULL; }
    }
}

static void reset_state(void) {
    close_writers();
    if (g_state.logcat_pid > 0) {
        kill(g_state.logcat_pid, SIGTERM);
        waitpid(g_state.logcat_pid, NULL, 0);
        g_state.logcat_pid = 0;
    }
    if (g_state.logcat_fd >= 0) {
        close(g_state.logcat_fd);
        g_state.logcat_fd = -1;
    }
    g_state.usb_root[0]    = 0;
    g_state.session_dir[0] = 0;
    g_state.package_count  = 0;
    g_usb_gone             = 0;
    for (int i = 0; i < MAX_PACKAGES; i++)
        memset(&g_state.packages[i], 0, sizeof(Package));
}

// ── PID tracking ──────────────────────────────────────────────────────────────

static void rescan_pids(void) {
    for (int i = 0; i < g_state.package_count; i++)
        g_state.packages[i].pid_count = 0;

    DIR *d = opendir("/proc");
    if (!d) return;

    struct dirent *e;
    while ((e = readdir(d))) {
        if (e->d_type != DT_DIR) continue;
        int pid = atoi(e->d_name);
        if (pid <= 0) continue;

        char path[64], cmdline[256];
        snprintf(path, sizeof(path), "/proc/%d/cmdline", pid);
        int fd = open(path, O_RDONLY);
        if (fd < 0) continue;
        ssize_t n = read(fd, cmdline, sizeof(cmdline) - 1);
        close(fd);
        if (n <= 0) continue;
        cmdline[n] = 0;
        char *colon = strchr(cmdline, ':');
        if (colon) *colon = 0;

        for (int i = 0; i < g_state.package_count; i++) {
            Package *pkg = &g_state.packages[i];
            if (strcmp(cmdline, pkg->name) == 0 && pkg->pid_count < MAX_PIDS_PER_PKG)
                pkg->pids[pkg->pid_count++] = pid;
        }
    }
    closedir(d);
}

static int pid_to_package(int pid) {
    for (int i = 0; i < g_state.package_count; i++) {
        Package *pkg = &g_state.packages[i];
        for (int j = 0; j < pkg->pid_count; j++)
            if (pkg->pids[j] == pid) return i;
    }
    return -1;
}

// ── logcat ────────────────────────────────────────────────────────────────────

static int spawn_logcat(void) {
    char resume_ts[64] = {0};
    FILE *rf = fopen(RESUME_FILE, "r");
    if (rf) {
        if (fgets(resume_ts, sizeof(resume_ts), rf))
            resume_ts[strcspn(resume_ts, "\n\r")] = 0;
        fclose(rf);
    }

    int pipefd[2];
    if (pipe(pipefd) < 0) return -1;

    pid_t pid = fork();
    if (pid < 0) { close(pipefd[0]); close(pipefd[1]); return -1; }

    if (pid == 0) {
        close(pipefd[0]);
        dup2(pipefd[1], STDOUT_FILENO);
        dup2(pipefd[1], STDERR_FILENO);
        close(pipefd[1]);
        char level[8];
        snprintf(level, sizeof(level), "*:%c", g_state.min_level);
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
    if (resume_ts[0])
        LOGI("logcat pid=%d — resuming from %s", pid, resume_ts);
    else
        LOGI("logcat pid=%d level=*:%c (root — all user profiles)", pid, g_state.min_level);
    return 0;
}

// ── log entry parsing & writing ───────────────────────────────────────────────

typedef struct {
    char timestamp[32];  // "YYYY-MM-DD HH:MM:SS.mmm"
    char raw_ts[32];     // "MM-DD HH:MM:SS.mmm" for logcat -T
    int  pid, tid;
    char level;
    char tag[256];
    char message[8192];
} LogEntry;

static int parse_line(const char *line, LogEntry *e) {
    char date[16], time_s[16];
    int pid, tid, consumed = 0;
    char level;
    if (sscanf(line, "%15s %15s %d %d %c %n",
               date, time_s, &pid, &tid, &level, &consumed) < 5 || consumed == 0)
        return -1;

    time_t now = time(NULL);
    struct tm tm;
    localtime_r(&now, &tm);
    snprintf(e->timestamp, sizeof(e->timestamp), "%d-%s %s", tm.tm_year + 1900, date, time_s);
    snprintf(e->raw_ts,    sizeof(e->raw_ts),    "%s %s", date, time_s);
    e->pid = pid; e->tid = tid; e->level = level;

    const char *rest  = line + consumed;
    const char *colon = strstr(rest, ": ");
    if (colon) {
        size_t tl = (size_t)(colon - rest);
        if (tl >= sizeof(e->tag)) tl = sizeof(e->tag) - 1;
        memcpy(e->tag, rest, tl); e->tag[tl] = 0;
        while (tl > 0 && isspace((unsigned char)e->tag[tl - 1])) e->tag[--tl] = 0;
        strncpy(e->message, colon + 2, sizeof(e->message) - 1);
    } else {
        e->tag[0] = 0;
        strncpy(e->message, rest, sizeof(e->message) - 1);
    }
    e->message[sizeof(e->message) - 1] = 0;
    return 0;
}

static void tsv_clean(char *s) {
    for (char *p = s; *p; p++)
        if (*p == '\t' || *p == '\n' || *p == '\r') *p = ' ';
}

static int level_idx(char l) {
    switch (l) {
        case 'V': return 0; case 'D': return 1; case 'I': return 2;
        case 'W': return 3; case 'E': return 4; case 'F': return 5;
    }
    return 1;
}

static int write_entry(int pkg_idx, const LogEntry *e, const char *raw_line) {
    Package *pkg = &g_state.packages[pkg_idx];
    if (fprintf(pkg->raw, "%s\n", raw_line) < 0) return -1;

    char tag_c[256], msg_c[8192];
    strncpy(tag_c, e->tag,     sizeof(tag_c) - 1); tag_c[sizeof(tag_c)-1] = 0;
    strncpy(msg_c, e->message, sizeof(msg_c) - 1); msg_c[sizeof(msg_c)-1] = 0;
    tsv_clean(tag_c); tsv_clean(msg_c);

    if (fprintf(pkg->tsv, "%s\t%d\t%d\t%c\t%s\t%s\n",
                e->timestamp, e->pid, e->tid, e->level, tag_c, msg_c) < 0)
        return -1;

    pkg->total++;
    pkg->entries[level_idx(e->level)]++;
    if ((pkg->total & 0x3F) == 0) {
        fflush(pkg->raw);
        fflush(pkg->tsv);
        FILE *cf = fopen(RESUME_FILE, "w");
        if (cf) { fprintf(cf, "%s\n", e->raw_ts); fclose(cf); }
    }
    return 0;
}

// ── USB sync thread ───────────────────────────────────────────────────────────

// Stream new bytes from src_path (internal) to dst_path (USB), tracking
// position in off_path. Open-write-close per call: USB handle open <50ms.
static void sync_one_file(const char *src_path, const char *dst_path,
                           const char *off_path) {
    long offset = 0;
    {
        FILE *f = fopen(off_path, "r");
        if (f) { fscanf(f, "%ld", &offset); fclose(f); }
    }

    FILE *src = fopen(src_path, "r");
    if (!src) return;
    if (fseek(src, offset, SEEK_SET) != 0) { fclose(src); return; }

    char buf[SYNC_CHUNK_SIZE];
    size_t n = fread(buf, 1, sizeof(buf), src);
    fclose(src);
    if (n == 0) return;

    FILE *dst = fopen(dst_path, "a");
    if (!dst) return;
    size_t written = fwrite(buf, 1, n, dst);
    fflush(dst);
    fclose(dst);

    // Only advance offset if the full chunk was written — partial write retried next cycle
    if (written == n) {
        FILE *f = fopen(off_path, "w");
        if (f) { fprintf(f, "%ld\n", offset + (long)n); fclose(f); }
    }
}

static void sync_all_packages(const SyncArgs *sa) {
    for (int i = 0; i < sa->pkg_count; i++) {
        char src[600], dst[600], off[600];

        snprintf(src, sizeof(src), "%s/%s.log",     sa->session_dir,  sa->pkg_names[i]);
        snprintf(dst, sizeof(dst), "%s/%s.log",     sa->usb_session,  sa->pkg_names[i]);
        snprintf(off, sizeof(off), "%s/%s_log.off", sa->sync_dir,     sa->pkg_names[i]);
        sync_one_file(src, dst, off);

        snprintf(src, sizeof(src), "%s/%s.log.tsv", sa->session_dir,  sa->pkg_names[i]);
        snprintf(dst, sizeof(dst), "%s/%s.log.tsv", sa->usb_session,  sa->pkg_names[i]);
        snprintf(off, sizeof(off), "%s/%s_tsv.off", sa->sync_dir,     sa->pkg_names[i]);
        sync_one_file(src, dst, off);
    }
}

static void *sync_thread_func(void *arg) {
    SyncArgs *sa = (SyncArgs *)arg;

    makedirs(sa->usb_session);
    LOGI("Sync thread started → USB: %s", sa->usb_session);

    // Watch the USB root for unmount or deletion — fires the instant vold
    // removes the volume, with no polling or miss counter needed.
    int ifd = inotify_init1(IN_CLOEXEC);
    if (ifd >= 0) {
        inotify_add_watch(ifd, sa->usb_root,
                          IN_UNMOUNT | IN_DELETE_SELF | IN_MOVE_SELF);
        LOGD("Sync: inotify watching %s", sa->usb_root);
    } else {
        LOGE("Sync: inotify_init failed (%s) — falling back to stat polling",
             strerror(errno));
    }

    struct pollfd pfd = { .fd = ifd, .events = POLLIN };

    while (g_running && !g_usb_gone) {
        // Block for up to SYNC_INTERVAL_SEC; wake early on USB event.
        int r = (ifd >= 0)
                ? poll(&pfd, 1, SYNC_INTERVAL_SEC * 1000)
                : (sleep(SYNC_INTERVAL_SEC), 0);

        if (r > 0) {
            // inotify fired — USB unmounted, deleted, or moved.
            LOGI("Sync: USB event on %s — stopping capture", sa->usb_root);
            g_usb_gone = 1;
            if (g_state.logcat_pid > 0)
                kill(g_state.logcat_pid, SIGTERM);
            break;
        }

        if (!g_running) break;

        // r == 0: timeout — SYNC_INTERVAL_SEC elapsed, stream next chunk.
        // r <  0: poll interrupted (EINTR from signal) — still sync then loop.
        sync_all_packages(sa);
    }

    if (ifd >= 0) close(ifd);

    // Final flush: sync any bytes written after the last cycle.
    // fopen on a gone USB path fails silently — no-op if already ejected.
    sync_all_packages(sa);
    LOGI("Sync thread exiting");
    return NULL;
}

// ── session metadata ──────────────────────────────────────────────────────────

static void write_session_meta(time_t start_time) {
    char path[600];
    snprintf(path, sizeof(path), "%s/_session.meta", g_state.session_dir);
    FILE *f = fopen(path, "w");
    if (!f) return;
    char ts[64];
    struct tm tm;
    localtime_r(&start_time, &tm);
    strftime(ts, sizeof(ts), "%Y-%m-%d %H:%M:%S", &tm);
    fprintf(f, "session_start=%s\nlogv4=1\nuid=%d\npid=%d\nmin_level=%c\nusb=%s\n",
            ts, getuid(), getpid(), g_state.min_level, g_state.usb_root);
    fprintf(f, "packages:\n");
    for (int i = 0; i < g_state.package_count; i++)
        fprintf(f, "  - %s\n", g_state.packages[i].name);
    fclose(f);
}

static void write_summary(time_t start_t, time_t end_t) {
    char path[600];
    snprintf(path, sizeof(path), "%s/_summary.tsv", g_state.session_dir);
    FILE *f = fopen(path, "w");
    if (!f) return;
    fprintf(f, "package\ttotal\tV\tD\tI\tW\tE\tF\n");
    for (int i = 0; i < g_state.package_count; i++) {
        Package *pkg = &g_state.packages[i];
        fprintf(f, "%s\t%ld\t%ld\t%ld\t%ld\t%ld\t%ld\t%ld\n",
                pkg->name, pkg->total,
                pkg->entries[0], pkg->entries[1], pkg->entries[2],
                pkg->entries[3], pkg->entries[4], pkg->entries[5]);
    }
    fprintf(f, "\nduration_seconds=%ld\n", (long)(end_t - start_t));
    fclose(f);
}

// ── capture session ───────────────────────────────────────────────────────────

static void run_session(void) {
    time_t session_start = time(NULL);
    write_session_meta(session_start);
    rescan_pids();

    if (spawn_logcat() < 0) { LOGE("spawn_logcat failed"); return; }

    FILE *logf = fdopen(g_state.logcat_fd, "r");
    if (!logf) { LOGE("fdopen failed"); return; }
    g_state.logcat_fd = -1;

    // Build SyncArgs — immutable session params for the sync thread.
    // Lives on this stack frame; safe because we pthread_join before returning.
    SyncArgs sa = {0};
    strncpy(sa.usb_root,    g_state.usb_root,    sizeof(sa.usb_root)    - 1);
    strncpy(sa.session_dir, g_state.session_dir, sizeof(sa.session_dir) - 1);
    sa.pkg_count = g_state.package_count;
    for (int i = 0; i < sa.pkg_count; i++)
        strncpy(sa.pkg_names[i], g_state.packages[i].name, 127);

    // USB mirror:  /mnt/media_rw/<uuid>/logs/<session-basename>
    const char *sbase = strrchr(g_state.session_dir, '/');
    sbase = sbase ? sbase + 1 : g_state.session_dir;
    snprintf(sa.usb_session, sizeof(sa.usb_session),
             "%s/logs/%s", g_state.usb_root, sbase);

    // Offset tracking dir on internal storage
    snprintf(sa.sync_dir, sizeof(sa.sync_dir),
             "%s/.sync/%s", OUTPUT_ROOT, sbase);
    makedirs(sa.sync_dir);

    pthread_t sync_tid;
    if (pthread_create(&sync_tid, NULL, sync_thread_func, &sa) != 0) {
        LOGE("pthread_create failed: %s", strerror(errno));
        fclose(logf);
        return;
    }

    // ── main capture loop ─────────────────────────────────────────────────────
    // Pure capture: read logcat, write to internal storage.
    // USB presence and sync are fully handled by the sync thread.

    char line[MAX_LINE];
    time_t last_rescan = time(NULL);
    long   lines_total = 0, lines_matched = 0;

    while (g_running && !g_usb_gone && fgets(line, sizeof(line), logf)) {
        size_t l = strlen(line);
        while (l > 0 && (line[l-1] == '\n' || line[l-1] == '\r')) line[--l] = 0;
        if (l == 0) continue;
        lines_total++;

        LogEntry e;
        if (parse_line(line, &e) == 0) {
            int idx = pid_to_package(e.pid);
            if (idx >= 0) {
                if (write_entry(idx, &e, line) < 0) {
                    LOGE("Write error: %s", strerror(errno));
                    break;
                }
                lines_matched++;
            }
        }

        time_t now = time(NULL);
        if (now - last_rescan >= PID_RESCAN_INTERVAL_SEC) {
            rescan_pids();
            last_rescan = now;
        }
    }

    LOGI("Capture loop ended: total=%ld matched=%ld", lines_total, lines_matched);

    // Stop logcat child if still running (e.g. loop ended due to g_usb_gone)
    if (g_state.logcat_pid > 0) {
        kill(g_state.logcat_pid, SIGTERM);
        waitpid(g_state.logcat_pid, NULL, 0);
        g_state.logcat_pid = 0;
    }
    fclose(logf);

    // Flush internal storage writers before sync thread does its final pass
    close_writers();

    // Wait for sync thread — it will attempt one final flush to USB
    pthread_join(sync_tid, NULL);

    write_summary(session_start, time(NULL));
}

// ── main ──────────────────────────────────────────────────────────────────────

int main(void) {
    signal(SIGHUP,  SIG_IGN);
    signal(SIGPIPE, SIG_IGN);
    signal(SIGTERM, sigterm_handler);
    umask(0);

    LOGI("logdaemon started pid=%d uid=%d (Logv4)", getpid(), (int)getuid());

    makedirs(OUTPUT_ROOT);
    LOGI("Output root: %s", OUTPUT_ROOT);

    // Watch /mnt/media_rw/ for new subdirectories (= USB volume mounted by vold).
    // Falls back to sleep-poll if inotify is unavailable.
    int usb_ifd = inotify_init1(IN_CLOEXEC);
    if (usb_ifd >= 0) {
        inotify_add_watch(usb_ifd, "/mnt/media_rw", IN_CREATE);
        LOGI("Watching /mnt/media_rw for USB mount events");
    } else {
        LOGE("inotify_init for /mnt/media_rw failed (%s) — using polling",
             strerror(errno));
    }

    while (g_running) {
        if (!find_usb()) {
            if (usb_ifd >= 0) {
                LOGD("Waiting for USB mount event on /mnt/media_rw ...");
                wait_for_usb_mount(usb_ifd);
            } else {
                sleep(USB_SCAN_INTERVAL_SEC);
            }
            continue;
        }

        if (parse_config() < 0 || g_state.package_count == 0) {
            LOGE("Config invalid or no packages");
            reset_state();
            sleep(USB_SCAN_INTERVAL_SEC);
            continue;
        }

        if (create_session_dir() < 0) { reset_state(); sleep(USB_SCAN_INTERVAL_SEC); continue; }
        if (open_writers()       < 0) { reset_state(); sleep(USB_SCAN_INTERVAL_SEC); continue; }

        LOGI("Session started — capture: internal storage | sync: USB");
        run_session();
        reset_state();
    }

    if (usb_ifd >= 0) close(usb_ifd);
    LOGI("logdaemon exiting");
    return 0;
}
