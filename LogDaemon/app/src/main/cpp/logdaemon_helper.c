// LogDaemon native helper — Logv4 (init.rc daemon)
//
// Deployed as /system/bin/logdaemon, started by init as user=root.
// No daemonize(), no instance guard — init manages both.
//
// Flow:
//   1. Scan /mnt/media_rw/ for a USB drive containing log.sinfo
//   2. Read package list from log.sinfo (file closed immediately after)
//   3. Write logs to /data/media/0/LogDaemon/logs/<timestamp>/
//      → internal storage, never affected by profile switches or vold
//      → visible in file manager as "Internal Storage/LogDaemon/"
//   4. Capture runs indefinitely — USB removal does NOT stop capture
//      (no open handles on USB during capture)
//   5. On daemon restart: repeat from step 1

#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <signal.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <dirent.h>
#include <time.h>
#include <errno.h>
#include <ctype.h>
#include <android/log.h>

#define TAG "LogDaemon"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)

#define MAX_PACKAGES             16
#define MAX_PIDS_PER_PKG          8
#define MAX_LINE               8192
#define PID_RESCAN_INTERVAL_SEC   2
#define USB_SCAN_INTERVAL_SEC     5

// Logs always go to owner internal storage regardless of USB state.
#define OUTPUT_ROOT "/data/media/0/LogDaemon"
#define RESUME_FILE "/data/media/0/LogDaemon/.last_ts"

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
    char    usb_root[512];    // path of USB drive containing log.sinfo
    char    session_dir[512]; // output dir on internal storage
    Package packages[MAX_PACKAGES];
    int     package_count;
    char    min_level;
    pid_t   logcat_pid;
    int     logcat_fd;
} State;

static State        g_state   = {0};
static volatile int g_running = 1;

static void sigterm_handler(int sig) {
    (void)sig;
    g_running = 0;
    // Kill logcat child immediately so fgets() gets EOF without waiting
    // for the next log line.
    if (g_state.logcat_pid > 0)
        kill(g_state.logcat_pid, SIGTERM);
}

// ── USB discovery ─────────────────────────────────────────────────────────────

// Scan /mnt/media_rw/ for a drive containing log.sinfo.
// As root we can opendir /mnt/media_rw/ directly (raw FAT, no FUSE).
static int find_usb(void) {
    DIR *d = opendir("/mnt/media_rw");
    if (!d) {
        LOGD("opendir /mnt/media_rw: %s", strerror(errno));
        return 0;
    }

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

// Read config from USB log.sinfo. File is opened and closed here —
// no USB file handle remains open after this call.
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
    fclose(f);  // USB handle closed here — no further USB dependency

    LOGI("Config: %d package(s), min_level=%c (from %s)",
         g_state.package_count, g_state.min_level, g_state.usb_root);
    return 0;
}

// Output goes to internal storage, not USB.
static int create_session_dir(void) {
    time_t now = time(NULL);
    struct tm tm;
    localtime_r(&now, &tm);
    char ts[64];
    strftime(ts, sizeof(ts), "%Y-%m-%d_%H-%M-%S", &tm);

    mkdir(OUTPUT_ROOT, 0775);
    char logs_dir[512];
    snprintf(logs_dir, sizeof(logs_dir), "%s/logs", OUTPUT_ROOT);
    mkdir(logs_dir, 0775);

    snprintf(g_state.session_dir, sizeof(g_state.session_dir),
             "%s/logs/%s", OUTPUT_ROOT, ts);
    if (mkdir(g_state.session_dir, 0775) < 0) {
        LOGE("mkdir %s: %s", g_state.session_dir, strerror(errno));
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
    for (int i = 0; i < MAX_PACKAGES; i++)
        memset(&g_state.packages[i], 0, sizeof(Package));
}

// ── PID tracking ─────────────────────────────────────────────────────────────

// Running as root: /proc scan sees ALL users' processes simultaneously.
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

        // Strip sub-process suffix: "com.pkg:service" -> "com.pkg"
        char *colon = strchr(cmdline, ':');
        if (colon) *colon = 0;

        for (int i = 0; i < g_state.package_count; i++) {
            Package *pkg = &g_state.packages[i];
            if (strcmp(cmdline, pkg->name) == 0 &&
                pkg->pid_count < MAX_PIDS_PER_PKG)
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

// Spawn logcat as root. logd sends ALL users' log entries to root readers.
// Reads RESUME_FILE and passes -T <timestamp> if present so logd replays
// any buffered lines since the last checkpoint.
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
    char timestamp[32];  // "YYYY-MM-DD HH:MM:SS.mmm" written to TSV
    char raw_ts[32];     // "MM-DD HH:MM:SS.mmm" for logcat -T resume
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
    snprintf(e->timestamp, sizeof(e->timestamp), "%d-%s %s",
             tm.tm_year + 1900, date, time_s);
    snprintf(e->raw_ts, sizeof(e->raw_ts), "%s %s", date, time_s);
    e->pid = pid; e->tid = tid; e->level = level;

    const char *rest  = line + consumed;
    const char *colon = strstr(rest, ": ");
    if (colon) {
        size_t tag_len = (size_t)(colon - rest);
        if (tag_len >= sizeof(e->tag)) tag_len = sizeof(e->tag) - 1;
        memcpy(e->tag, rest, tag_len);
        e->tag[tag_len] = 0;
        while (tag_len > 0 && isspace((unsigned char)e->tag[tag_len - 1]))
            e->tag[--tag_len] = 0;
        strncpy(e->message, colon + 2, sizeof(e->message) - 1);
        e->message[sizeof(e->message) - 1] = 0;
    } else {
        e->tag[0] = 0;
        strncpy(e->message, rest, sizeof(e->message) - 1);
        e->message[sizeof(e->message) - 1] = 0;
    }
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
    strncpy(tag_c, e->tag,     sizeof(tag_c) - 1); tag_c[sizeof(tag_c) - 1] = 0;
    strncpy(msg_c, e->message, sizeof(msg_c) - 1); msg_c[sizeof(msg_c) - 1] = 0;
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
    fprintf(f, "session_start=%s\nlogv4=1\nuid=%d\nhelper_pid=%d\nmin_level=%c\n"
               "usb_source=%s\n",
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

// Runs until SIGTERM or a write error. No USB handles open during this call —
// capture continues regardless of USB state (profile switches, vold, unplug).
static void run_session(void) {
    time_t session_start = time(NULL);
    write_session_meta(session_start);
    rescan_pids();

    if (spawn_logcat() < 0) { LOGE("spawn_logcat failed"); return; }

    FILE *logf = fdopen(g_state.logcat_fd, "r");
    if (!logf) { LOGE("fdopen failed"); return; }
    g_state.logcat_fd = -1;

    char line[MAX_LINE];
    time_t last_rescan = time(NULL);
    long   lines_total = 0, lines_matched = 0;

    while (g_running && fgets(line, sizeof(line), logf)) {
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

    LOGI("Session end: total=%ld matched=%ld", lines_total, lines_matched);

    if (g_state.logcat_pid > 0) {
        kill(g_state.logcat_pid, SIGTERM);
        waitpid(g_state.logcat_pid, NULL, 0);
        g_state.logcat_pid = 0;
    }
    fclose(logf);
    close_writers();
    write_summary(session_start, time(NULL));
}

// ── main ─────────────────────────────────────────────────────────────────────

int main(void) {
    signal(SIGHUP,  SIG_IGN);
    signal(SIGPIPE, SIG_IGN);
    signal(SIGTERM, sigterm_handler);
    umask(0);

    LOGI("logdaemon started pid=%d uid=%d (Logv4)", getpid(), (int)getuid());

    mkdir(OUTPUT_ROOT, 0775);

    while (g_running) {
        // Wait for USB drive with log.sinfo
        if (!find_usb()) {
            LOGD("No USB with log.sinfo — retrying in %ds", USB_SCAN_INTERVAL_SEC);
            sleep(USB_SCAN_INTERVAL_SEC);
            continue;
        }

        // Read config from USB. After this call, no USB handles remain open.
        if (parse_config() < 0 || g_state.package_count == 0) {
            LOGE("Config invalid or no packages");
            reset_state();
            sleep(USB_SCAN_INTERVAL_SEC);
            continue;
        }

        // Create output on internal storage
        if (create_session_dir() < 0) { reset_state(); sleep(USB_SCAN_INTERVAL_SEC); continue; }
        if (open_writers()       < 0) { reset_state(); sleep(USB_SCAN_INTERVAL_SEC); continue; }

        LOGI("Capture started — writing to internal storage (USB may be removed safely)");

        // Capture runs indefinitely. USB removal/profile switches do not stop this.
        run_session();

        reset_state();
    }

    LOGI("logdaemon exiting");
    return 0;
}
