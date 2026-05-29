/*
 * logcollectord.c — Android native log-collection daemon
 *
 * Deploy:  /system/bin/logcollectord
 * Init:    /system/etc/init/logcollectord.rc
 * Runs as: root (group: log media_rw)
 *
 * Lifecycle:
 *   1. Boot → init starts daemon via .rc service (class late_start)
 *   2. Daemon polls /proc/mounts waiting for a USB drive that contains log.sinfo
 *   3. On USB attach: parse log.sinfo, open output file in /data/media/0/Download/,
 *      spawn one logcat (all UIDs, no filter), and filter lines in-process by
 *      PID→package-name mapping derived from /proc/<pid>/cmdline.
 *   4. /proc is rescanned every PID_RESCAN_SEC so new processes (and processes
 *      started in a different Android user after a profile switch) are picked up
 *      automatically without restarting logcat.
 *   5. On USB detach: flush, close output, trigger media scan, wait for USB to
 *      fully disappear, then go back to step 2.
 */

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
#include <sys/poll.h>
#include <dirent.h>
#include <time.h>
#include <errno.h>
#include <ctype.h>
#include <android/log.h>

/* ── log macros ─────────────────────────────────────────────────────────── */
#define TAG "logcollectord"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)

/* ── tunables ───────────────────────────────────────────────────────────── */
#define MAX_PACKAGES      32
#define MAX_LINE          8192
#define PID_CACHE_SIZE    1024
#define MOUNTS_POLL_MS    2000   /* how often to recheck mounts when idle   */
#define PID_RESCAN_SEC    3      /* re-walk /proc to pick up new PIDs       */
#define USB_CHECK_SEC     5      /* check whether USB is still mounted      */
#define FLUSH_EVERY       64     /* fwrite flush interval (lines written)   */

#define OUTPUT_DIR        "/data/media/0/Download"

/* ── types ──────────────────────────────────────────────────────────────── */

typedef struct {
    char  names[MAX_PACKAGES][128];
    int   count;
    char  min_level;   /* V / D / I / W / E */
} Config;

typedef struct {
    int pid;
    int pkg_idx;   /* index into Config.names, or -1 */
} PidEntry;

typedef struct {
    Config    cfg;
    char      usb_root[512];
    char      out_path[1024];
    FILE     *out;
    pid_t     logcat_pid;
    int       logcat_fd;
    long      lines_written;
    PidEntry  pid_cache[PID_CACHE_SIZE];
    int       pid_cache_n;
} Session;

/* ── globals ────────────────────────────────────────────────────────────── */

static volatile int g_running = 1;
static Session      g_sess;

/* ── signal handling ────────────────────────────────────────────────────── */

static void on_signal(int s) { (void)s; g_running = 0; }

/* ── string helpers ─────────────────────────────────────────────────────── */

static char *trim(char *s)
{
    while (*s && isspace((unsigned char)*s)) s++;
    char *e = s + strlen(s);
    while (e > s && isspace((unsigned char)*(e-1))) e--;
    *e = '\0';
    return s;
}

static void fmt_ts_file(char *buf, size_t n)
{
    time_t t = time(NULL); struct tm tm;
    localtime_r(&t, &tm);
    strftime(buf, n, "%Y-%m-%d_%H-%M-%S", &tm);
}

static void fmt_ts_human(char *buf, size_t n)
{
    time_t t = time(NULL); struct tm tm;
    localtime_r(&t, &tm);
    strftime(buf, n, "%Y-%m-%d %H:%M:%S", &tm);
}

/* ── config parser ──────────────────────────────────────────────────────── */

/*
 * Parse log.sinfo from <usb_root>/log.sinfo.
 * Format:
 *   [packages]
 *   com.example.app
 *   com.example.other
 *
 *   [options]
 *   min_level=D
 */
static int parse_config(const char *usb_root, Config *cfg)
{
    char path[600];
    snprintf(path, sizeof(path), "%s/log.sinfo", usb_root);

    FILE *f = fopen(path, "r");
    if (!f) { LOGE("Cannot open %s: %s", path, strerror(errno)); return -1; }

    cfg->count     = 0;
    cfg->min_level = 'D';

    char line[1024];
    int in_pkg = 0, in_opt = 0;
    while (fgets(line, sizeof(line), f)) {
        char *p = trim(line);
        if (!*p || *p == '#' || *p == ';') continue;
        if (*p == '[') {
            in_pkg = (strncmp(p, "[packages]", 10) == 0);
            in_opt = (strncmp(p, "[options]",   9) == 0);
            continue;
        }
        if (in_pkg && cfg->count < MAX_PACKAGES) {
            strncpy(cfg->names[cfg->count], p, 127);
            cfg->names[cfg->count][127] = '\0';
            cfg->count++;
            LOGI("  package[%d]: %s", cfg->count - 1, cfg->names[cfg->count - 1]);
        }
        if (in_opt && strncmp(p, "min_level=", 10) == 0)
            cfg->min_level = (char)toupper((unsigned char)p[10]);
    }
    fclose(f);

    LOGI("Config loaded: %d package(s), min_level=%c", cfg->count, cfg->min_level);
    return (cfg->count > 0) ? 0 : -1;
}

/* ── USB / mounts detection ─────────────────────────────────────────────── */

static int has_sinfo(const char *mnt)
{
    char p[600];
    snprintf(p, sizeof(p), "%s/log.sinfo", mnt);
    struct stat st;
    return stat(p, &st) == 0;
}

/*
 * Scan /proc/mounts for a vold-managed USB mount under /mnt/media_rw/
 * (the raw, root-accessible path) that contains log.sinfo.
 * Returns 1 and fills usb_root on success.
 */
static int find_usb_mount(char *usb_root, size_t n)
{
    FILE *f = fopen("/proc/mounts", "r");
    if (!f) return 0;
    char dev[256], mnt[256], fstype[64], opts[512];
    int  dummy, found = 0;
    while (fscanf(f, "%255s %255s %63s %511s %d %d\n",
                  dev, mnt, fstype, opts, &dummy, &dummy) == 6) {
        if ((strncmp(mnt, "/mnt/media_rw/", 14) == 0 ||
             strncmp(mnt, "/mnt/usb/",       9) == 0) &&
            has_sinfo(mnt)) {
            strncpy(usb_root, mnt, n - 1);
            usb_root[n - 1] = '\0';
            found = 1;
            break;
        }
    }
    fclose(f);
    return found;
}

static int usb_still_present(const char *usb_root)
{
    return has_sinfo(usb_root);
}

/* ── PID → package resolution ───────────────────────────────────────────── */

/*
 * Read /proc/<pid>/cmdline.  Android sets cmdline to the package name
 * (or "package:process" for named processes — we strip the colon suffix).
 * Returns the Config index on match, -1 otherwise.
 */
static int cmdline_to_pkg(int pid, const Config *cfg)
{
    char path[64];
    snprintf(path, sizeof(path), "/proc/%d/cmdline", pid);
    int fd = open(path, O_RDONLY);
    if (fd < 0) return -1;
    char buf[256];
    ssize_t n = read(fd, buf, sizeof(buf) - 1);
    close(fd);
    if (n <= 0) return -1;
    buf[n] = '\0';
    char *colon = strchr(buf, ':');
    if (colon) *colon = '\0';
    for (int i = 0; i < cfg->count; i++)
        if (strcmp(buf, cfg->names[i]) == 0) return i;
    return -1;
}

/* Walk /proc and build the full PID→package cache for all current processes. */
static void rescan_pids(Session *s)
{
    s->pid_cache_n = 0;
    DIR *d = opendir("/proc");
    if (!d) return;
    struct dirent *e;
    while ((e = readdir(d)) && s->pid_cache_n < PID_CACHE_SIZE) {
        if (e->d_type != DT_DIR) continue;
        int pid = atoi(e->d_name);
        if (pid <= 0) continue;
        int idx = cmdline_to_pkg(pid, &s->cfg);
        if (idx >= 0) {
            s->pid_cache[s->pid_cache_n].pid     = pid;
            s->pid_cache[s->pid_cache_n].pkg_idx = idx;
            s->pid_cache_n++;
        }
    }
    closedir(d);
    LOGD("PID rescan: %d matching process(es)", s->pid_cache_n);
}

/* Lookup from cache. Returns pkg index or -1. */
static int cache_lookup(const Session *s, int pid)
{
    for (int i = 0; i < s->pid_cache_n; i++)
        if (s->pid_cache[i].pid == pid) return s->pid_cache[i].pkg_idx;
    return -1;
}

/* Add a new entry to the cache (called when we resolve inline). */
static void cache_insert(Session *s, int pid, int pkg_idx)
{
    if (s->pid_cache_n >= PID_CACHE_SIZE) return;
    s->pid_cache[s->pid_cache_n].pid     = pid;
    s->pid_cache[s->pid_cache_n].pkg_idx = pkg_idx;
    s->pid_cache_n++;
}

/* ── logcat spawning ────────────────────────────────────────────────────── */

/*
 * Fork-exec /system/bin/logcat with all buffers, no UID filter.
 * Returns a readable fd connected to logcat's stdout, or -1 on error.
 */
static int spawn_logcat(char min_level, pid_t *out_pid)
{
    int pipefd[2];
    if (pipe(pipefd) < 0) return -1;

    pid_t pid = fork();
    if (pid < 0) { close(pipefd[0]); close(pipefd[1]); return -1; }

    if (pid == 0) {
        close(pipefd[0]);
        dup2(pipefd[1], STDOUT_FILENO);
        close(pipefd[1]);
        /* silence logcat's own stderr so it doesn't corrupt the pipe reader */
        int null = open("/dev/null", O_WRONLY);
        if (null >= 0) { dup2(null, STDERR_FILENO); close(null); }

        char filter[16];
        snprintf(filter, sizeof(filter), "*:%c", min_level);
        execl("/system/bin/logcat", "logcat",
              "-v", "threadtime",
              "-b", "main,system,crash,events",
              filter, (char *)NULL);
        _exit(127);
    }

    close(pipefd[1]);
    *out_pid = pid;
    LOGI("logcat spawned: pid=%d level=*:%c", pid, min_level);
    return pipefd[0];
}

/* ── line parser ────────────────────────────────────────────────────────── */

/*
 * Parse a logcat threadtime line.
 * Format: "MM-DD HH:MM:SS.mmm  PID  TID L tag  : message"
 * We only need pid and level for filtering; raw line goes to the file as-is.
 */
static int parse_line(const char *line, int *out_pid, char *out_level)
{
    char date[16], ts[24];
    int  pid, tid, consumed = 0;
    char level;
    if (sscanf(line, "%15s %23s %d %d %c %n",
               date, ts, &pid, &tid, &level, &consumed) < 5) return -1;
    *out_pid   = pid;
    *out_level = level;
    return 0;
}

/* ── output file ────────────────────────────────────────────────────────── */

static int open_output(Session *s)
{
    /* ensure target directory exists */
    mkdir(OUTPUT_DIR, 0775);

    char ts[32];
    fmt_ts_file(ts, sizeof(ts));
    snprintf(s->out_path, sizeof(s->out_path),
             "%s/logcollect_%s.txt", OUTPUT_DIR, ts);

    s->out = fopen(s->out_path, "w");
    if (!s->out) {
        LOGE("Cannot create %s: %s", s->out_path, strerror(errno));
        return -1;
    }
    s->lines_written = 0;

    /* give the file the correct SELinux label so FUSE/MediaProvider sees it */
    char cmd[1100];
    snprintf(cmd, sizeof(cmd),
             "/system/bin/chcon u:object_r:media_rw_data_file:s0 '%s' 2>/dev/null",
             s->out_path);
    system(cmd);

    char hts[32];
    fmt_ts_human(hts, sizeof(hts));
    fprintf(s->out, "=== logcollectord session start: %s ===\n", hts);
    fprintf(s->out, "=== USB root: %s ===\n", s->usb_root);
    fprintf(s->out, "=== Packages:");
    for (int i = 0; i < s->cfg.count; i++)
        fprintf(s->out, " %s", s->cfg.names[i]);
    fprintf(s->out, " ===\n\n");
    fflush(s->out);

    LOGI("Output: %s", s->out_path);
    return 0;
}

static void write_line(Session *s, int pkg_idx, const char *raw_line)
{
    fprintf(s->out, "[%s] %s\n", s->cfg.names[pkg_idx], raw_line);
    s->lines_written++;
    if ((s->lines_written & (FLUSH_EVERY - 1)) == 0) fflush(s->out);
}

static void close_output(Session *s)
{
    if (!s->out) return;
    char hts[32];
    fmt_ts_human(hts, sizeof(hts));
    fprintf(s->out, "\n=== session end: %s  lines: %ld ===\n",
            hts, s->lines_written);
    fflush(s->out);
    fclose(s->out);
    s->out = NULL;
    LOGI("Output closed: %ld lines written to %s", s->lines_written, s->out_path);
}

/*
 * Trigger a MediaScanner broadcast so the file appears in the Files app.
 * Converts /data/media/0/... → /storage/emulated/0/... for the FUSE path.
 */
static void trigger_media_scan(const char *data_media_path)
{
    const char *rest = data_media_path;
    if (strncmp(rest, "/data/media/", 12) == 0) rest += 12;
    char fuse_path[1024];
    snprintf(fuse_path, sizeof(fuse_path), "/storage/emulated/%s", rest);

    char cmd[1200];
    snprintf(cmd, sizeof(cmd),
             "/system/bin/am broadcast"
             " -a android.intent.action.MEDIA_SCANNER_SCAN_FILE"
             " -d 'file://%s'"
             " > /dev/null 2>&1",
             fuse_path);
    system(cmd);
    LOGI("Media scan: %s", fuse_path);
}

/* ── capture loop ───────────────────────────────────────────────────────── */

/*
 * Run one complete collection session:
 *   - usb_root is already set in s->cfg and s->usb_root
 *   - Returns when USB is detached, logcat dies, or g_running == 0
 */
static void run_session(Session *s)
{
    if (open_output(s) < 0) return;

    pid_t logcat_pid;
    int   logcat_fd = spawn_logcat(s->cfg.min_level, &logcat_pid);
    if (logcat_fd < 0) { close_output(s); return; }
    s->logcat_pid = logcat_pid;
    s->logcat_fd  = logcat_fd;

    rescan_pids(s);

    FILE *logf = fdopen(logcat_fd, "r");
    if (!logf) {
        LOGE("fdopen: %s", strerror(errno));
        goto cleanup;
    }

    char     line[MAX_LINE];
    time_t   last_rescan   = time(NULL);
    time_t   last_usb_chk  = time(NULL);

    while (g_running && fgets(line, sizeof(line), logf)) {
        /* strip trailing newline */
        size_t l = strlen(line);
        while (l > 0 && (line[l-1] == '\n' || line[l-1] == '\r')) line[--l] = '\0';
        if (l == 0) continue;

        int  pid;
        char level;
        if (parse_line(line, &pid, &level) < 0) continue;

        /* cache lookup first; inline resolve for cache misses (new processes) */
        int idx = cache_lookup(s, pid);
        if (idx < 0) {
            idx = cmdline_to_pkg(pid, &s->cfg);
            if (idx >= 0) cache_insert(s, pid, idx);
        }
        if (idx >= 0) write_line(s, idx, line);

        time_t now = time(NULL);

        /* periodic /proc rescan — picks up processes from profile switches */
        if (now - last_rescan >= PID_RESCAN_SEC) {
            rescan_pids(s);
            last_rescan = now;
        }

        /* periodic USB presence check */
        if (now - last_usb_chk >= USB_CHECK_SEC) {
            if (!usb_still_present(s->usb_root)) {
                LOGI("USB removed, ending session");
                break;
            }
            last_usb_chk = now;
        }
    }

    fclose(logf);
    s->logcat_fd = -1;

cleanup:
    if (s->logcat_pid > 0) {
        kill(s->logcat_pid, SIGTERM);
        int st; waitpid(s->logcat_pid, &st, WNOHANG);
        s->logcat_pid = 0;
    }
    close_output(s);
    trigger_media_scan(s->out_path);
}

/* ── daemonize ──────────────────────────────────────────────────────────── */

static void daemonize(void)
{
    /* first fork */
    pid_t pid = fork();
    if (pid < 0) _exit(1);
    if (pid > 0) _exit(0);

    setsid();

    /* second fork — can never re-acquire a controlling terminal */
    pid = fork();
    if (pid < 0) _exit(1);
    if (pid > 0) _exit(0);

    umask(0);
    chdir("/");

    close(STDIN_FILENO);
    /* keep stdout/stderr for early-boot logging before logd is up, then close */
    int devnull = open("/dev/null", O_RDWR);
    if (devnull >= 0) {
        dup2(devnull, STDIN_FILENO);
        dup2(devnull, STDOUT_FILENO);
        if (devnull > STDERR_FILENO) close(devnull);
    }

    signal(SIGHUP,  SIG_IGN);
    signal(SIGPIPE, SIG_IGN);
    signal(SIGTERM, on_signal);
    signal(SIGINT,  on_signal);
}

/* ── main ───────────────────────────────────────────────────────────────── */

int main(int argc, char **argv)
{
    (void)argc; (void)argv;

    daemonize();
    LOGI("started, pid=%d", getpid());

    int mounts_fd = open("/proc/mounts", O_RDONLY);
    if (mounts_fd < 0) {
        LOGE("open /proc/mounts: %s", strerror(errno));
        return 1;
    }

    while (g_running) {
        char usb_root[512];

        /* check immediately (handles USB already mounted on daemon start) */
        if (find_usb_mount(usb_root, sizeof(usb_root))) {
            LOGI("USB found at %s, starting session", usb_root);

            memset(&g_sess, 0, sizeof(g_sess));
            strncpy(g_sess.usb_root, usb_root, sizeof(g_sess.usb_root) - 1);

            if (parse_config(usb_root, &g_sess.cfg) == 0) {
                run_session(&g_sess);
            } else {
                LOGE("Config parse failed, ignoring this drive");
            }

            /* wait until USB is fully gone before scanning again */
            LOGI("Waiting for USB to unmount...");
            while (g_running && find_usb_mount(usb_root, sizeof(usb_root)))
                sleep(1);
            LOGI("USB gone, back to idle");
            continue;   /* immediately re-check without blocking on poll */
        }

        /* block until /proc/mounts changes (new mount/unmount event) */
        struct pollfd pfd = { .fd = mounts_fd, .events = POLLERR | POLLPRI };
        int r = poll(&pfd, 1, MOUNTS_POLL_MS);
        (void)r;
        /* seek back to 0 so the kernel re-arms the poll event */
        lseek(mounts_fd, 0, SEEK_SET);
    }

    close(mounts_fd);
    LOGI("exiting");
    return 0;
}
