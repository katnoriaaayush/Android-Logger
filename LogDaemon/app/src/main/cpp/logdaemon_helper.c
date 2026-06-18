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
#include <sys/socket.h>
#include <sys/time.h>
#include <netdb.h>
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
#define USB_DEBOUNCE_SEC          5   // wait after IN_UNMOUNT before declaring USB gone
#define SYNC_CHUNK_SIZE       65536   // max bytes per file per sync cycle (64 KB)
#define UPLOAD_TIMEOUT_SEC       10   // socket connect/send/recv timeout for server upload

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
    char    upload_url[512];   // from log.sinfo [options] upload_url= (empty = no upload)
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
    // server upload (optional — parsed from State.upload_url at session start)
    int  upload_enabled;
    char upload_host[256];
    int  upload_port;
    char upload_path[256];
    char session_id[64];      // session dir basename, sent as query param
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
        if (in_options && strncmp(p, "upload_url=", 11) == 0) {
            strncpy(g_state.upload_url, p + 11, sizeof(g_state.upload_url) - 1);
            g_state.upload_url[sizeof(g_state.upload_url) - 1] = 0;
        }
    }
    fclose(f);
    LOGI("Config: %d package(s), min_level=%c%s%s",
         g_state.package_count, g_state.min_level,
         g_state.upload_url[0] ? ", upload=" : "",
         g_state.upload_url[0] ? g_state.upload_url : "");
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
    g_state.upload_url[0]  = 0;
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

// ── server upload (plain HTTP POST over raw socket — no curl, no TLS) ──────────

// Parse "http://host[:port][/path]" into components. http only.
static int parse_upload_url(const char *url, char *host, size_t host_sz,
                            int *port, char *path, size_t path_sz) {
    if (strncmp(url, "http://", 7) != 0) return -1;
    const char *p = url + 7;

    const char *hs = p;
    while (*p && *p != ':' && *p != '/') p++;
    size_t hl = (size_t)(p - hs);
    if (hl == 0 || hl >= host_sz) return -1;
    memcpy(host, hs, hl); host[hl] = 0;

    *port = 80;
    if (*p == ':') {
        *port = atoi(++p);
        while (*p && *p != '/') p++;
    }
    if (*p == '/') { strncpy(path, p,   path_sz - 1); path[path_sz - 1] = 0; }
    else           { strncpy(path, "/", path_sz - 1); path[path_sz - 1] = 0; }

    return (*port > 0 && *port <= 65535) ? 0 : -1;
}

static int send_all(int fd, const char *buf, size_t len) {
    size_t sent = 0;
    while (sent < len) {
        ssize_t n = send(fd, buf + sent, len - sent, MSG_NOSIGNAL);
        if (n <= 0) return -1;
        sent += (size_t)n;
    }
    return 0;
}

// POST `body` to host:port + path_q. Returns 0 on HTTP 2xx, -1 otherwise.
static int http_post_chunk(const char *host, int port, const char *path_q,
                           const char *body, size_t body_len) {
    struct addrinfo hints, *res = NULL;
    memset(&hints, 0, sizeof(hints));
    hints.ai_family   = AF_UNSPEC;
    hints.ai_socktype = SOCK_STREAM;

    char portstr[16];
    snprintf(portstr, sizeof(portstr), "%d", port);
    if (getaddrinfo(host, portstr, &hints, &res) != 0) return -1;

    int fd = -1;
    for (struct addrinfo *ai = res; ai; ai = ai->ai_next) {
        fd = socket(ai->ai_family, ai->ai_socktype, ai->ai_protocol);
        if (fd < 0) continue;
        struct timeval tv = { .tv_sec = UPLOAD_TIMEOUT_SEC, .tv_usec = 0 };
        setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
        setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));
        if (connect(fd, ai->ai_addr, ai->ai_addrlen) == 0) break;
        close(fd); fd = -1;
    }
    freeaddrinfo(res);
    if (fd < 0) return -1;

    char header[1024];
    int hlen = snprintf(header, sizeof(header),
        "POST %s HTTP/1.1\r\n"
        "Host: %s:%d\r\n"
        "Content-Type: application/octet-stream\r\n"
        "Content-Length: %zu\r\n"
        "Connection: close\r\n\r\n",
        path_q, host, port, body_len);
    if (hlen <= 0 || hlen >= (int)sizeof(header)) { close(fd); return -1; }

    if (send_all(fd, header, (size_t)hlen) < 0)       { close(fd); return -1; }
    if (body_len && send_all(fd, body, body_len) < 0) { close(fd); return -1; }

    char resp[256];
    ssize_t n = recv(fd, resp, sizeof(resp) - 1, 0);
    close(fd);
    if (n <= 0) return -1;
    resp[n] = 0;

    int code = 0;
    if (sscanf(resp, "HTTP/%*s %d", &code) != 1) return -1;
    return (code >= 200 && code < 300) ? 0 : -1;
}

// Stream all new bytes of src_path to the server, resuming from off_path.
// Offset only advances on a confirmed 2xx, so failures are retried next cycle.
// Returns 0 if fully uploaded (or already up to date), -1 if a POST failed.
static int upload_one_file(const SyncArgs *sa, const char *src_path,
                           const char *off_path, const char *label) {
    long offset = 0;
    { FILE *f = fopen(off_path, "r"); if (f) { fscanf(f, "%ld", &offset); fclose(f); } }

    struct stat st;
    if (stat(src_path, &st) != 0) return 0;     // file not created yet
    long fsize = (long)st.st_size;
    if (offset >= fsize) return 0;              // nothing new

    FILE *src = fopen(src_path, "r");
    if (!src) return -1;

    char buf[SYNC_CHUNK_SIZE];
    int result = 0;
    while (offset < fsize) {
        if (fseek(src, offset, SEEK_SET) != 0) { result = -1; break; }
        size_t want = (size_t)(fsize - offset);
        if (want > sizeof(buf)) want = sizeof(buf);
        size_t n = fread(buf, 1, want, src);
        if (n == 0) break;

        char path_q[800];
        snprintf(path_q, sizeof(path_q), "%s?session=%s&file=%s&offset=%ld",
                 sa->upload_path, sa->session_id, label, offset);

        if (http_post_chunk(sa->upload_host, sa->upload_port, path_q, buf, n) != 0) {
            result = -1;            // leave offset unchanged — retried next cycle
            break;
        }
        offset += (long)n;
        FILE *of = fopen(off_path, "w");
        if (of) { fprintf(of, "%ld\n", offset); fclose(of); }
    }
    fclose(src);
    return result;
}

// Upload .log and .log.tsv for every package. Returns -1 if any file failed.
static int upload_all_to_server(const SyncArgs *sa) {
    int status = 0;
    for (int i = 0; i < sa->pkg_count; i++) {
        char src[600], off[600], label[300];

        snprintf(src,   sizeof(src),   "%s/%s.log",         sa->session_dir, sa->pkg_names[i]);
        snprintf(off,   sizeof(off),   "%s/%s_log.srv.off", sa->sync_dir,    sa->pkg_names[i]);
        snprintf(label, sizeof(label), "%s.log",            sa->pkg_names[i]);
        if (upload_one_file(sa, src, off, label) != 0) status = -1;

        snprintf(src,   sizeof(src),   "%s/%s.log.tsv",     sa->session_dir, sa->pkg_names[i]);
        snprintf(off,   sizeof(off),   "%s/%s_tsv.srv.off", sa->sync_dir,    sa->pkg_names[i]);
        snprintf(label, sizeof(label), "%s.log.tsv",        sa->pkg_names[i]);
        if (upload_one_file(sa, src, off, label) != 0) status = -1;
    }
    return status;
}

// Post an Android notification via `cmd notification post` (root, no APK).
static void notify(const char *title, const char *message) {
    pid_t pid = fork();
    if (pid == 0) {
        execl("/system/bin/cmd", "cmd", "notification", "post",
              "-S", "bigtext", "-n", "4", "-t", title,
              "logdaemon", message, NULL);
        _exit(127);
    }
    if (pid > 0) waitpid(pid, NULL, 0);
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
            // inotify fired — could be a real ejection or a temporary vold
            // remount during an Android profile switch. Drain the event buffer,
            // wait USB_DEBOUNCE_SEC, then check whether log.sinfo is accessible.
            char evbuf[sizeof(struct inotify_event) + NAME_MAX + 1];
            read(ifd, evbuf, sizeof(evbuf));

            LOGI("Sync: USB event on %s — debouncing %ds",
                 sa->usb_root, USB_DEBOUNCE_SEC);
            sleep(USB_DEBOUNCE_SEC);

            char sinfo[600];
            snprintf(sinfo, sizeof(sinfo), "%s/log.sinfo", sa->usb_root);
            if (access(sinfo, R_OK) == 0) {
                // USB is back — profile switch remounted it. Re-register the
                // inotify watch (IN_UNMOUNT invalidates the old descriptor)
                // and continue the session without interruption.
                LOGI("Sync: USB remounted after profile switch — continuing session");
                inotify_add_watch(ifd, sa->usb_root,
                                  IN_UNMOUNT | IN_DELETE_SELF | IN_MOVE_SELF);
                continue;
            }

            // Still gone after debounce — real USB ejection.
            LOGI("Sync: USB gone after %ds debounce — stopping capture",
                 USB_DEBOUNCE_SEC);
            g_usb_gone = 1;
            if (g_state.logcat_pid > 0)
                kill(g_state.logcat_pid, SIGTERM);
            break;
        }

        if (!g_running) break;

        // r == 0: timeout — SYNC_INTERVAL_SEC elapsed, stream next chunk.
        // r <  0: poll interrupted (EINTR from signal) — still sync then loop.
        sync_all_packages(sa);
        if (sa->upload_enabled) upload_all_to_server(sa);  // stream to server too
    }

    if (ifd >= 0) close(ifd);

    // Final flush: sync any bytes written after the last cycle.
    // fopen on a gone USB path fails silently — no-op if already ejected.
    // The server stays reachable over the network after USB ejection, so this
    // best-effort pass drains most remaining bytes; run_session() does the
    // authoritative final pass (post close_writers) that drives the notification.
    sync_all_packages(sa);
    if (sa->upload_enabled) upload_all_to_server(sa);
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
    if (g_state.upload_url[0])
        fprintf(f, "upload_url=%s\n", g_state.upload_url);
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

    // Server upload config (optional — from log.sinfo upload_url=)
    strncpy(sa.session_id, sbase, sizeof(sa.session_id) - 1);
    sa.upload_enabled = 0;
    if (g_state.upload_url[0]) {
        if (parse_upload_url(g_state.upload_url,
                             sa.upload_host, sizeof(sa.upload_host),
                             &sa.upload_port,
                             sa.upload_path, sizeof(sa.upload_path)) == 0) {
            sa.upload_enabled = 1;
            LOGI("Upload target: %s:%d%s (session %s)",
                 sa.upload_host, sa.upload_port, sa.upload_path, sa.session_id);
        } else {
            LOGE("Invalid upload_url, skipping upload: %s", g_state.upload_url);
        }
    }

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

    // Authoritative final upload pass — runs after close_writers() has flushed
    // every byte (including the last sub-64-line buffer) and after the sync
    // thread has joined, so there is no concurrency on the offset files. Its
    // result is the true "did the whole session reach the server" status.
    if (sa.upload_enabled) {
        int up = upload_all_to_server(&sa);
        LOGI("Final upload result: %s", up == 0 ? "OK" : "FAILED");
        if (up == 0)
            notify("LogDaemon \xe2\x80\x94 Upload Complete",
                   "Session logs uploaded to server successfully.");
        else
            notify("LogDaemon \xe2\x80\x94 Upload Failed",
                   "Logs saved locally; server upload did not complete.");
    }
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
                if (!g_running) break;
                // IN_CREATE fires when vold creates the UUID directory, but the
                // FAT filesystem may not be accessible yet. Poll for up to 10 s
                // so find_usb() does not fail and re-block on inotify.
                for (int i = 0; i < 10 && g_running; i++) {
                    sleep(1);
                    if (find_usb()) goto session_ready;
                }
                LOGD("USB mounted but log.sinfo not found — waiting for next event");
            } else {
                sleep(USB_SCAN_INTERVAL_SEC);
            }
            continue;
        }
        session_ready:

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
