/*
 * logdaemon.c — Native Android log capture daemon
 *
 * Started by init.rc as uid=root after sys.boot_completed=1.
 *
 * Boot-log capture strategy
 * ─────────────────────────
 * The daemon runs two steps at startup before entering the USB-watch loop:
 *
 *   1. logcat -G 16M  — expands the logd ring buffer so logs produced during
 *      boot (before the daemon itself starts) are not dropped.
 *
 *   2. Records the device boot epoch from /proc/uptime.  When a session
 *      starts, logcat is launched with -T <boot_epoch_ms> so it replays
 *      every entry buffered since boot, covering the init → USB-detection gap.
 *
 * The .last_ts checkpoint is used only for daemon-restart recovery within the
 * same boot: if .last_ts is newer than the boot epoch, the daemon was killed
 * mid-session and we resume from that point instead of replaying from boot.
 *
 * Architecture
 * ────────────
 *   Main thread  — reads from the logcat pipe, matches PIDs to configured
 *                  packages via /proc, writes .log / .log.tsv to internal
 *                  storage, checkpoints .last_ts every 64 lines.
 *
 *   Sync thread  — every 5 s: streams new bytes from internal storage to USB
 *                  in 64 KB chunks (open → write → close, no persistent handle).
 *                  Watches USB root via inotify(IN_UNMOUNT) with a 5-second
 *                  debounce to survive profile-switch remounts.
 */

#define _GNU_SOURCE
#include <ctype.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <poll.h>
#include <pthread.h>
#include <signal.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/inotify.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

/* ── constants ──────────────────────────────────────────────────────────── */

#define INTERNAL_BASE     "/data/media/0/LogDaemon"
#define LOGS_DIR          INTERNAL_BASE "/logs"
#define LAST_TS_FILE      INTERNAL_BASE "/.last_ts"
#define USB_WATCH_PATH    "/mnt/media_rw"
#define CONFIG_FILE       "log.sinfo"
#define LOGCAT_BIN        "/system/bin/logcat"

#define MAX_PACKAGES      16
#define MAX_PKG_LEN       256
#define MAX_LINE          8192
#define CHUNK_SIZE        (64 * 1024)
#define SYNC_INTERVAL_MS  5000
#define DEBOUNCE_SEC      5
#define CHECKPOINT_EVERY  64
#define LOGD_BUFFER_SIZE  "16M"

#define INOTIFY_BUFSZ \
    (16 * (sizeof(struct inotify_event) + NAME_MAX + 1))

/* ── types ──────────────────────────────────────────────────────────────── */

typedef struct {
    char          name[MAX_PKG_LEN];
    int           pid;
    FILE         *log_fp;
    FILE         *tsv_fp;
    off_t         log_synced;
    off_t         tsv_synced;
    unsigned long cnt[6];   /* V D I W E F */
} Pkg;

typedef struct {
    char    session_dir[PATH_MAX];
    char    usb_dir[PATH_MAX];
    char    usb_root[PATH_MAX];
    Pkg     pkgs[MAX_PACKAGES];
    int     npkgs;
    char    min_level;
    int     inotify_fd;
    int     usb_wd;
    pid_t   logcat_pid;
} Session;

typedef struct { Session *sess; } SyncArg;

/* ── globals ─────────────────────────────────────────────────────────────── */

static volatile int g_running  = 1;
static volatile int g_usb_gone = 0;
static long long    g_boot_ms  = 0;

/* ── logging ─────────────────────────────────────────────────────────────── */

static void dlog(const char *fmt, ...) {
    va_list ap;
    time_t t = time(NULL);
    struct tm *tm = localtime(&t);
    char tbuf[32];
    strftime(tbuf, sizeof(tbuf), "%m-%d %H:%M:%S", tm);
    fprintf(stderr, "[logdaemon %s] ", tbuf);
    va_start(ap, fmt);
    vfprintf(stderr, fmt, ap);
    va_end(ap);
    fputc('\n', stderr);
    fflush(stderr);
}

/* ── small helpers ───────────────────────────────────────────────────────── */

static void mkdirs(const char *path) {
    char tmp[PATH_MAX];
    snprintf(tmp, sizeof(tmp), "%s", path);
    for (char *p = tmp + 1; *p; p++) {
        if (*p == '/') { *p = '\0'; mkdir(tmp, 0755); *p = '/'; }
    }
    mkdir(tmp, 0755);
}

static long long now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_REALTIME, &ts);
    return (long long)ts.tv_sec * 1000LL + ts.tv_nsec / 1000000LL;
}

static void rtrim(char *s) {
    int n = (int)strlen(s);
    while (n > 0 && (s[n-1] == '\n' || s[n-1] == '\r' || s[n-1] == ' '))
        s[--n] = '\0';
}

static int level_idx(char c) {
    const char *lv = "VDIWEF";
    const char *p = strchr(lv, c);
    return p ? (int)(p - lv) : -1;
}

/* replace tabs and newlines with spaces — safe for TSV fields */
static void tsv_sanitize(char *s) {
    for (; *s; s++)
        if (*s == '\t' || *s == '\n' || *s == '\r') *s = ' ';
}

/* ── logd buffer expansion ───────────────────────────────────────────────── */

static void expand_logd_buffer(void) {
    char *argv[] = { LOGCAT_BIN, "-G", LOGD_BUFFER_SIZE, NULL };
    pid_t pid = fork();
    if (pid == 0) { execvp(argv[0], argv); _exit(1); }
    if (pid < 0)  { dlog("WARN: fork for logcat -G failed"); return; }
    int status;
    waitpid(pid, &status, 0);
    if (WIFEXITED(status) && WEXITSTATUS(status) == 0)
        dlog("logd buffer expanded to %s", LOGD_BUFFER_SIZE);
    else
        dlog("WARN: logcat -G failed (status=%d) — continuing with default buffer", status);
}

/* ── boot epoch ──────────────────────────────────────────────────────────── */

/*
 * Compute the wall-clock time of the last boot from /proc/uptime.
 * Returns milliseconds since Unix epoch.
 */
static long long boot_epoch_ms(void) {
    FILE *f = fopen("/proc/uptime", "r");
    if (!f) return now_ms();
    double uptime = 0.0;
    if (fscanf(f, "%lf", &uptime) != 1) { fclose(f); return now_ms(); }
    fclose(f);
    return now_ms() - (long long)(uptime * 1000.0);
}

/* ── .last_ts checkpoint ─────────────────────────────────────────────────── */

static void write_last_ts(long long ts_ms) {
    FILE *f = fopen(LAST_TS_FILE, "w");
    if (!f) return;
    fprintf(f, "%lld\n", ts_ms);
    fclose(f);
}

static long long read_last_ts(void) {
    FILE *f = fopen(LAST_TS_FILE, "r");
    if (!f) return 0;
    long long ts = 0;
    fscanf(f, "%lld", &ts);
    fclose(f);
    return ts;
}

/* ── config parser ───────────────────────────────────────────────────────── */

static int parse_config(const char *path, Session *s) {
    FILE *f = fopen(path, "r");
    if (!f) { dlog("cannot open %s: %s", path, strerror(errno)); return -1; }
    s->npkgs     = 0;
    s->min_level = 'D';
    char line[512];
    int in_pkg = 0, in_opt = 0;
    while (fgets(line, sizeof(line), f)) {
        rtrim(line);
        if (!line[0] || line[0] == '#') continue;
        if      (strcmp(line, "[packages]") == 0) { in_pkg = 1; in_opt = 0; continue; }
        else if (strcmp(line, "[options]")  == 0) { in_opt = 1; in_pkg = 0; continue; }
        if (in_pkg && s->npkgs < MAX_PACKAGES) {
            strncpy(s->pkgs[s->npkgs].name, line, MAX_PKG_LEN - 1);
            s->npkgs++;
        } else if (in_opt) {
            if (strncmp(line, "min_level=", 10) == 0 && line[10])
                s->min_level = (char)toupper((unsigned char)line[10]);
        }
    }
    fclose(f);
    dlog("config: %d package(s), min_level=%c", s->npkgs, s->min_level);
    return s->npkgs > 0 ? 0 : -1;
}

/* ── PID resolution ──────────────────────────────────────────────────────── */

/*
 * Returns the PID whose /proc/<pid>/cmdline (before the first ':') matches pkg,
 * or -1.  Scanning all of /proc on every line would be expensive; callers
 * refresh the cache at session start and every 200 lines.
 */
static int find_pid(const char *pkg) {
    DIR *proc = opendir("/proc");
    if (!proc) return -1;
    char path[64], buf[MAX_PKG_LEN];
    struct dirent *de;
    int found = -1;
    while ((de = readdir(proc)) != NULL && found < 0) {
        for (const char *p = de->d_name; *p; p++)
            if (!isdigit((unsigned char)*p)) goto next;
        snprintf(path, sizeof(path), "/proc/%s/cmdline", de->d_name);
        {
            int fd = open(path, O_RDONLY);
            if (fd < 0) goto next;
            int n = (int)read(fd, buf, sizeof(buf) - 1);
            close(fd);
            if (n <= 0) goto next;
            buf[n] = '\0';
            char *colon = strchr(buf, ':');
            if (colon) *colon = '\0';
            if (strcmp(buf, pkg) == 0) found = atoi(de->d_name);
        }
        next:;
    }
    closedir(proc);
    return found;
}

static void refresh_pids(Session *s) {
    for (int i = 0; i < s->npkgs; i++)
        s->pkgs[i].pid = find_pid(s->pkgs[i].name);
}

/* ── logcat line parser ──────────────────────────────────────────────────── */

typedef struct {
    char date_time[24];  /* "MM-DD HH:MM:SS.mmm" */
    int  pid, tid;
    char level;
    char tag[128];
    char msg[MAX_LINE];
} LogLine;

/*
 * Parses threadtime format:
 *   MM-DD HH:MM:SS.mmm  PID  TID L Tag  : message
 */
static int parse_logcat_line(const char *line, LogLine *out) {
    char date[12], tyme[16], lev[4], tag[128], rest[MAX_LINE];
    int pid, tid;
    int n = sscanf(line,
                   "%11s %15s %d %d %1s %127[^:]: %[^\n]",
                   date, tyme, &pid, &tid, lev, tag, rest);
    if (n < 6 || level_idx(lev[0]) < 0) return -1;
    snprintf(out->date_time, sizeof(out->date_time), "%s %s", date, tyme);
    out->pid   = pid;
    out->tid   = tid;
    out->level = lev[0];
    /* strip trailing spaces from tag */
    char *te = tag + strlen(tag) - 1;
    while (te >= tag && *te == ' ') *te-- = '\0';
    strncpy(out->tag, tag, sizeof(out->tag) - 1);
    out->tag[sizeof(out->tag) - 1] = '\0';
    strncpy(out->msg, n >= 7 ? rest : "", sizeof(out->msg) - 1);
    out->msg[sizeof(out->msg) - 1] = '\0';
    return 0;
}

/* ── USB sync thread ─────────────────────────────────────────────────────── */

/*
 * Copy up to CHUNK_SIZE new bytes from src at *off into dst (append).
 * Opens and closes dst each call so vold never sees a persistent holder.
 */
static void sync_file(const char *src, const char *dst, off_t *off) {
    static char buf[CHUNK_SIZE];
    FILE *s = fopen(src, "rb");
    if (!s) return;
    if (fseeko(s, *off, SEEK_SET) != 0) { fclose(s); return; }
    size_t n = fread(buf, 1, sizeof(buf), s);
    fclose(s);
    if (!n) return;
    FILE *d = fopen(dst, "ab");
    if (!d) return;
    fwrite(buf, 1, n, d);
    fclose(d);
    *off += (off_t)n;
}

static void flush_and_sync(Session *sess) {
    char src[PATH_MAX], dst[PATH_MAX];
    for (int i = 0; i < sess->npkgs; i++) {
        Pkg *p = &sess->pkgs[i];
        if (!p->log_fp) continue;
        fflush(p->log_fp);
        fflush(p->tsv_fp);
        snprintf(src, PATH_MAX, "%s/%s.log",     sess->session_dir, p->name);
        snprintf(dst, PATH_MAX, "%s/%s.log",     sess->usb_dir,     p->name);
        sync_file(src, dst, &p->log_synced);
        snprintf(src, PATH_MAX, "%s/%s.log.tsv", sess->session_dir, p->name);
        snprintf(dst, PATH_MAX, "%s/%s.log.tsv", sess->usb_dir,     p->name);
        sync_file(src, dst, &p->tsv_synced);
    }
}

static void *sync_thread_func(void *arg) {
    Session *sess = ((SyncArg *)arg)->sess;
    char ibuf[INOTIFY_BUFSZ];
    struct pollfd pfd = { .fd = sess->inotify_fd, .events = POLLIN };

    while (g_running && !g_usb_gone) {
        int r = poll(&pfd, 1, SYNC_INTERVAL_MS);
        if (r < 0) { if (errno == EINTR) continue; break; }

        if (r > 0 && (pfd.revents & POLLIN)) {
            int len = (int)read(sess->inotify_fd, ibuf, sizeof(ibuf));
            int unmount = 0;
            for (int i = 0; i < len; ) {
                struct inotify_event *ev = (struct inotify_event *)(ibuf + i);
                if (ev->wd == sess->usb_wd && (ev->mask & IN_UNMOUNT))
                    unmount = 1;
                i += (int)(sizeof(*ev) + ev->len);
            }
            if (unmount) {
                dlog("IN_UNMOUNT — debouncing %ds", DEBOUNCE_SEC);
                sleep(DEBOUNCE_SEC);
                char probe[PATH_MAX];
                snprintf(probe, PATH_MAX, "%s/%s", sess->usb_root, CONFIG_FILE);
                if (access(probe, F_OK) == 0) {
                    /* profile-switch remount — re-register and continue */
                    inotify_rm_watch(sess->inotify_fd, sess->usb_wd);
                    sess->usb_wd = inotify_add_watch(sess->inotify_fd,
                                                     sess->usb_root, IN_UNMOUNT);
                    dlog("USB remounted (profile switch) — session continues");
                } else {
                    dlog("USB ejected — stopping capture");
                    g_usb_gone = 1;
                    if (sess->logcat_pid > 0) kill(sess->logcat_pid, SIGTERM);
                    break;
                }
            }
        }

        flush_and_sync(sess);
    }

    flush_and_sync(sess); /* final drain */
    dlog("sync thread done");
    return NULL;
}

/* ── summary ─────────────────────────────────────────────────────────────── */

static void write_summary(Session *sess, long long start_ms, long long end_ms) {
    char path[PATH_MAX];
    snprintf(path, PATH_MAX, "%s/_summary.tsv", sess->session_dir);
    FILE *f = fopen(path, "w");
    if (!f) return;
    fprintf(f, "package\tV\tD\tI\tW\tE\tF\tstart_ms\tend_ms\n");
    for (int i = 0; i < sess->npkgs; i++) {
        Pkg *p = &sess->pkgs[i];
        fprintf(f, "%s\t%lu\t%lu\t%lu\t%lu\t%lu\t%lu\t%lld\t%lld\n",
                p->name, p->cnt[0], p->cnt[1], p->cnt[2],
                p->cnt[3], p->cnt[4], p->cnt[5], start_ms, end_ms);
    }
    fclose(f);
    /* copy to USB */
    char usb[PATH_MAX];
    snprintf(usb, PATH_MAX, "%s/_summary.tsv", sess->usb_dir);
    FILE *src = fopen(path, "rb"), *dst = fopen(usb, "wb");
    if (src && dst) {
        char buf[4096]; size_t n;
        while ((n = fread(buf, 1, sizeof(buf), src)) > 0) fwrite(buf, 1, n, dst);
    }
    if (src) fclose(src);
    if (dst) fclose(dst);
}

/* ── USB detection ───────────────────────────────────────────────────────── */

/* Returns 0 and fills out_path if a USB volume with log.sinfo is mounted. */
static int find_usb(char *out_path) {
    DIR *d = opendir(USB_WATCH_PATH);
    if (!d) return -1;
    struct dirent *de;
    int found = -1;
    while ((de = readdir(d)) != NULL && found < 0) {
        if (de->d_name[0] == '.') continue;
        char cfg[PATH_MAX];
        snprintf(cfg, PATH_MAX, "%s/%s/%s", USB_WATCH_PATH, de->d_name, CONFIG_FILE);
        if (access(cfg, F_OK) == 0) {
            snprintf(out_path, PATH_MAX, "%s/%s", USB_WATCH_PATH, de->d_name);
            found = 0;
        }
    }
    closedir(d);
    return found;
}

/* ── session ─────────────────────────────────────────────────────────────── */

static void run_session(const char *usb_root) {
    Session sess;
    memset(&sess, 0, sizeof(sess));
    strncpy(sess.usb_root, usb_root, PATH_MAX - 1);
    sess.inotify_fd = -1;
    sess.logcat_pid = -1;
    g_usb_gone = 0;

    /* parse config — closes the file before anything else opens */
    char cfg_path[PATH_MAX];
    snprintf(cfg_path, PATH_MAX, "%s/%s", usb_root, CONFIG_FILE);
    if (parse_config(cfg_path, &sess) != 0) {
        dlog("no valid packages in config — skipping");
        return;
    }

    /* create session directories */
    time_t t = time(NULL);
    struct tm *tm = localtime(&t);
    char ts[32];
    strftime(ts, sizeof(ts), "%Y-%m-%d_%H-%M-%S", tm);
    snprintf(sess.session_dir, PATH_MAX, "%s/%s",       LOGS_DIR, ts);
    snprintf(sess.usb_dir,     PATH_MAX, "%s/logs/%s",  usb_root, ts);
    mkdirs(sess.session_dir);
    mkdirs(sess.usb_dir);

    /* open per-package writers */
    for (int i = 0; i < sess.npkgs; i++) {
        Pkg *p = &sess.pkgs[i];
        char path[PATH_MAX];
        snprintf(path, PATH_MAX, "%s/%s.log",     sess.session_dir, p->name);
        p->log_fp = fopen(path, "w");
        snprintf(path, PATH_MAX, "%s/%s.log.tsv", sess.session_dir, p->name);
        p->tsv_fp = fopen(path, "w");
        if (p->tsv_fp) fprintf(p->tsv_fp, "time\tpid\ttid\tlevel\ttag\tmessage\n");
    }

    /*
     * Choose logcat start timestamp:
     *   - .last_ts > boot epoch  →  daemon restarted mid-session; resume from
     *                               last checkpoint to avoid replaying old data
     *   - otherwise              →  new session; replay from boot so no early
     *                               logs are missed even if USB was pre-connected
     */
    long long last_ts = read_last_ts();
    long long start_ts;
    if (last_ts > 0 && last_ts > g_boot_ms) {
        start_ts = last_ts;
        dlog("restart recovery: resuming from .last_ts=%lld", last_ts);
    } else {
        start_ts = g_boot_ms;
        dlog("new session: replaying from boot epoch=%lld", start_ts);
    }

    /* inotify on USB root for ejection detection */
    sess.inotify_fd = inotify_init1(IN_CLOEXEC);
    if (sess.inotify_fd < 0) { dlog("inotify_init failed"); goto cleanup; }
    sess.usb_wd = inotify_add_watch(sess.inotify_fd, usb_root, IN_UNMOUNT);

    /* start sync thread */
    pthread_t sync_tid;
    SyncArg sync_arg = { &sess };
    pthread_create(&sync_tid, NULL, sync_thread_func, &sync_arg);

    /* fork logcat with -T <start_ts> */
    int pipefd[2];
    if (pipe(pipefd) != 0) { dlog("pipe failed"); goto join; }

    char ts_arg[32], level_filter[8];
    snprintf(ts_arg,       sizeof(ts_arg),       "%lld",   start_ts);
    snprintf(level_filter, sizeof(level_filter), "*:%c",   sess.min_level);
    char *argv[] = {
        LOGCAT_BIN, "-v", "threadtime",
        "-T", ts_arg,
        level_filter,
        NULL
    };

    pid_t pid = fork();
    if (pid == 0) {
        close(pipefd[0]);
        dup2(pipefd[1], STDOUT_FILENO);
        dup2(pipefd[1], STDERR_FILENO);
        close(pipefd[1]);
        execvp(argv[0], argv);
        _exit(1);
    }
    if (pid < 0) { dlog("fork failed"); close(pipefd[0]); close(pipefd[1]); goto join; }
    close(pipefd[1]);
    sess.logcat_pid = pid;
    dlog("logcat pid=%d  -T %s  %s", pid, ts_arg, level_filter);

    /* capture loop */
    long long wall_start = now_ms();
    FILE *pipe_fp = fdopen(pipefd[0], "r");
    char line[MAX_LINE];
    long long n = 0;

    refresh_pids(&sess);

    while (g_running && !g_usb_gone && fgets(line, sizeof(line), pipe_fp)) {
        rtrim(line);
        LogLine ll;
        if (parse_logcat_line(line, &ll) != 0) continue;
        n++;
        if (n % 200 == 0) refresh_pids(&sess);

        /* match PID to a package */
        Pkg *pk = NULL;
        for (int i = 0; i < sess.npkgs; i++)
            if (sess.pkgs[i].pid == ll.pid) { pk = &sess.pkgs[i]; break; }
        if (!pk) continue;

        if (pk->log_fp) fprintf(pk->log_fp, "%s\n", line);
        if (pk->tsv_fp) {
            char tag[128], msg[MAX_LINE];
            strncpy(tag, ll.tag, sizeof(tag) - 1); tag[sizeof(tag)-1] = '\0';
            strncpy(msg, ll.msg, sizeof(msg) - 1); msg[sizeof(msg)-1] = '\0';
            tsv_sanitize(tag); tsv_sanitize(msg);
            fprintf(pk->tsv_fp, "%s\t%d\t%d\t%c\t%s\t%s\n",
                    ll.date_time, ll.pid, ll.tid, ll.level, tag, msg);
        }
        int li = level_idx(ll.level);
        if (li >= 0) pk->cnt[li]++;

        if (n % CHECKPOINT_EVERY == 0) {
            for (int i = 0; i < sess.npkgs; i++) {
                if (sess.pkgs[i].log_fp) fflush(sess.pkgs[i].log_fp);
                if (sess.pkgs[i].tsv_fp) fflush(sess.pkgs[i].tsv_fp);
            }
            write_last_ts(now_ms());
        }
    }

    fclose(pipe_fp);
    waitpid(pid, NULL, 0);
    sess.logcat_pid = -1;
    dlog("logcat exited — %lld lines written", n);

join:
    pthread_join(sync_tid, NULL);
    write_summary(&sess, wall_start, now_ms());
    write_last_ts(now_ms());
    dlog("session done");

cleanup:
    for (int i = 0; i < sess.npkgs; i++) {
        if (sess.pkgs[i].log_fp) fclose(sess.pkgs[i].log_fp);
        if (sess.pkgs[i].tsv_fp) fclose(sess.pkgs[i].tsv_fp);
    }
    if (sess.inotify_fd >= 0) close(sess.inotify_fd);
}

/* ── signal handling ─────────────────────────────────────────────────────── */

static void sig_handler(int sig) { (void)sig; g_running = 0; g_usb_gone = 1; }

/* ── main ────────────────────────────────────────────────────────────────── */

int main(void) {
    signal(SIGTERM, sig_handler);
    signal(SIGINT,  sig_handler);
    signal(SIGPIPE, SIG_IGN);

    dlog("starting (uid=%d)", (int)getuid());

    mkdirs(LOGS_DIR);

    /* expand logd ring buffer before anything else — running as root guarantees
     * this succeeds; larger buffer means boot logs survive until we attach */
    expand_logd_buffer();

    /* record boot epoch once; every session uses it as the logcat -T baseline */
    g_boot_ms = boot_epoch_ms();
    dlog("boot epoch: %lld ms", g_boot_ms);

    /* watch /mnt/media_rw/ for new USB volume directories */
    int ifd = inotify_init1(IN_CLOEXEC);
    if (ifd < 0) { dlog("inotify_init failed: %s", strerror(errno)); return 1; }
    inotify_add_watch(ifd, USB_WATCH_PATH, IN_CREATE | IN_ONLYDIR);

    /* handle USB already mounted at boot before the first inotify event */
    char usb_root[PATH_MAX];
    if (find_usb(usb_root) == 0) {
        dlog("USB already mounted: %s", usb_root);
        run_session(usb_root);
        inotify_add_watch(ifd, USB_WATCH_PATH, IN_CREATE | IN_ONLYDIR);
    }

    struct pollfd pfd = { .fd = ifd, .events = POLLIN };
    char ibuf[INOTIFY_BUFSZ];

    while (g_running) {
        /* 10-second fallback poll handles any missed inotify events */
        int r = poll(&pfd, 1, 10000);
        if (r < 0) { if (errno == EINTR) continue; break; }
        if (r > 0 && (pfd.revents & POLLIN))
            read(ifd, ibuf, sizeof(ibuf));  /* drain */

        if (find_usb(usb_root) == 0) {
            dlog("USB mounted: %s", usb_root);
            run_session(usb_root);
            inotify_add_watch(ifd, USB_WATCH_PATH, IN_CREATE | IN_ONLYDIR);
        }
    }

    close(ifd);
    dlog("exiting");
    return 0;
}
