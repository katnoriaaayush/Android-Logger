// LogDaemon native helper — Logv3
//
// Receives tagged log lines from LogDaemonService (user 0, always alive)
// over abstract Unix socket @logdaemon, writes them to USB.
//
// An 8 MB RAM ring buffer absorbs lines during FUSE mount gaps (profile
// switches) so no data is lost. The socket server is always up; the
// Android service reconnects automatically if needed.
//
// Flow:
//   setup_socket_server() -> accept client -> receive "pkg\tline\n"
//   -> write to USB files; on USB loss -> ring_store() -> on USB return
//   -> ring_flush() -> resume writing

#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <signal.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/select.h>
#include <sys/wait.h>
#include <dirent.h>
#include <time.h>
#include <errno.h>
#include <ctype.h>
#include <android/log.h>

#define TAG "LogDaemon.Helper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)

#define MAX_PACKAGES          16
#define MAX_LINE            8192
#define USB_CHECK_INTERVAL_SEC  5
#define USB_SCAN_INTERVAL_SEC   5
#define RING_SIZE          (8 * 1024 * 1024)
#define SOCKET_NAME        "logdaemon"

typedef struct {
    char name[128];
    FILE *raw;
    FILE *tsv;
    long entries[6];  // V D I W E F
    long total;
} Package;

typedef struct {
    char    usb_root[512];
    char    session_dir[1024];
    char    pid_file[1024];
    Package packages[MAX_PACKAGES];
    int     package_count;
    char    min_level;
} State;

static State           g_state          = {0};
static volatile int    g_running        = 1;
static char            g_hint_file[512] = "";

// Socket
static int             g_server_fd      = -1;
static int             g_client_fd      = -1;

// RAM ring buffer (BSS — only wired on first write)
static char            g_ring[RING_SIZE];
static size_t          g_ring_used      = 0;
static int             g_ring_overflow  = 0;

// Session state
static int             g_session_open   = 0;
static int             g_buffering      = 0;
static time_t          g_session_start  = 0;
static time_t          g_last_usb_check = 0;

static void sigterm_handler(int sig) { (void)sig; g_running = 0; }

// ── single-instance guard ─────────────────────────────────────────────────────

static int another_instance_running(void) {
    pid_t our_pid = getpid();
    DIR *d = opendir("/proc");
    if (!d) return 0;

    int found = 0;
    struct dirent *e;
    while ((e = readdir(d))) {
        if (e->d_type != DT_DIR) continue;
        pid_t pid = (pid_t)atoi(e->d_name);
        if (pid <= 0 || pid == our_pid) continue;

        char path[64], cmdline[256];
        snprintf(path, sizeof(path), "/proc/%d/cmdline", pid);
        int fd = open(path, O_RDONLY);
        if (fd < 0) continue;
        ssize_t n = read(fd, cmdline, sizeof(cmdline) - 1);
        close(fd);
        if (n <= 0) continue;
        cmdline[n] = 0;

        if (strstr(cmdline, "liblogdaemon_helper.so") != NULL) {
            found = 1;
            break;
        }
    }
    closedir(d);
    return found;
}

// ── daemonize ─────────────────────────────────────────────────────────────────

static void daemonize(void) {
    pid_t pid = fork();
    if (pid < 0) _exit(1);
    if (pid > 0) _exit(0);

    if (setsid() < 0) _exit(1);

    pid = fork();
    if (pid < 0) _exit(1);
    if (pid > 0) _exit(0);

    signal(SIGHUP,  SIG_IGN);
    signal(SIGPIPE, SIG_IGN);
    signal(SIGTERM, sigterm_handler);

    if (chdir("/") < 0) { /* ignore */ }
    umask(0);

    close(STDIN_FILENO);
    close(STDOUT_FILENO);
}

// ── USB discovery ─────────────────────────────────────────────────────────────

static int resolve_usb_path(const char *uuid) {
    char media_rw[512], media_rw_cfg[768];
    snprintf(media_rw, sizeof(media_rw), "/mnt/media_rw/%s", uuid);
    snprintf(media_rw_cfg, sizeof(media_rw_cfg), "%s/log.sinfo", media_rw);
    if (access(media_rw_cfg, R_OK) == 0) {
        strncpy(g_state.usb_root, media_rw, sizeof(g_state.usb_root) - 1);
        LOGI("USB path: %s [raw vold mount]", g_state.usb_root);
        return 1;
    }
    LOGI("  /mnt/media_rw/%s not accessible (errno=%d: %s), trying /storage/",
         uuid, errno, strerror(errno));
    char storage[512], storage_cfg[768];
    snprintf(storage, sizeof(storage), "/storage/%s", uuid);
    snprintf(storage_cfg, sizeof(storage_cfg), "%s/log.sinfo", storage);
    if (access(storage_cfg, R_OK) == 0) {
        strncpy(g_state.usb_root, storage, sizeof(g_state.usb_root) - 1);
        LOGI("USB path: %s [FUSE overlay]", g_state.usb_root);
        return 1;
    }
    LOGE("  /storage/%s not accessible either (errno=%d: %s)", uuid, errno, strerror(errno));
    return 0;
}

static int find_usb(void) {
    int found = 0;

    DIR *d = opendir("/storage");
    if (!d) {
        LOGE("opendir /storage: %s", strerror(errno));
    } else {
        struct dirent *e;
        while ((e = readdir(d)) && !found) {
            if (e->d_name[0] == '.') continue;
            if (strcmp(e->d_name, "self")     == 0) continue;
            if (strcmp(e->d_name, "emulated") == 0) continue;
            char cfg[768];
            snprintf(cfg, sizeof(cfg), "/storage/%s/log.sinfo", e->d_name);
            if (access(cfg, R_OK) == 0) {
                LOGI("log.sinfo found on volume %s", e->d_name);
                found = resolve_usb_path(e->d_name);
            }
        }
        closedir(d);
    }

    if (!found && g_hint_file[0]) {
        FILE *hf = fopen(g_hint_file, "r");
        if (hf) {
            char hint[512] = {0};
            if (fgets(hint, sizeof(hint), hf)) {
                char *nl = strchr(hint, '\n');
                if (nl) *nl = 0;
                char cfg[768];
                snprintf(cfg, sizeof(cfg), "%s/log.sinfo", hint);
                if (hint[0] && access(cfg, R_OK) == 0) {
                    strncpy(g_state.usb_root, hint, sizeof(g_state.usb_root) - 1);
                    found = 1;
                    LOGI("USB path: %s [hint file]", g_state.usb_root);
                }
            }
            fclose(hf);
        }
    }

    return found;
}

// ── helpers ───────────────────────────────────────────────────────────────────

static char *trim(char *s) {
    while (*s && isspace((unsigned char)*s)) s++;
    char *end = s + strlen(s);
    while (end > s && isspace((unsigned char)*(end - 1))) end--;
    *end = 0;
    return s;
}

static int parse_config(void) {
    char path[1024];
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
        if (in_options && strncmp(p, "min_level=", 10) == 0) {
            g_state.min_level = (char)toupper((unsigned char)p[10]);
        }
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

    char logs_dir[1024];
    snprintf(logs_dir, sizeof(logs_dir), "%s/logs", g_state.usb_root);
    mkdir(logs_dir, 0775);

    snprintf(g_state.session_dir, sizeof(g_state.session_dir),
             "%s/logs/%s", g_state.usb_root, ts);
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
        char path[1200];

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
        if (pkg->raw) { fclose(pkg->raw); pkg->raw = NULL; }
        if (pkg->tsv) { fclose(pkg->tsv); pkg->tsv = NULL; }
    }
}

// Clears session state. Does NOT touch socket or ring buffer.
static void reset_state(void) {
    close_writers();
    g_state.usb_root[0]    = 0;
    g_state.session_dir[0] = 0;
    g_state.pid_file[0]    = 0;
    g_state.package_count  = 0;
    for (int i = 0; i < MAX_PACKAGES; i++)
        memset(&g_state.packages[i], 0, sizeof(Package));
}

typedef struct {
    char timestamp[32];
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
    for (char *p = s; *p; p++) {
        if (*p == '\t' || *p == '\n' || *p == '\r') *p = ' ';
    }
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
    tsv_clean(tag_c);
    tsv_clean(msg_c);

    if (fprintf(pkg->tsv, "%s\t%d\t%d\t%c\t%s\t%s\n",
                e->timestamp, e->pid, e->tid, e->level, tag_c, msg_c) < 0)
        return -1;

    pkg->total++;
    pkg->entries[level_idx(e->level)]++;
    if ((pkg->total & 0x3F) == 0) {
        fflush(pkg->raw);
        fflush(pkg->tsv);
    }
    return 0;
}

static int usb_present(void) {
    struct stat st;
    return (g_state.session_dir[0] && stat(g_state.session_dir, &st) == 0);
}

static void write_pid_file(void) {
    snprintf(g_state.pid_file, sizeof(g_state.pid_file),
             "%s/.logdaemon.pid", g_state.usb_root);
    FILE *f = fopen(g_state.pid_file, "w");
    if (f) { fprintf(f, "%d\n", getpid()); fclose(f); }
}

static void write_session_meta(time_t start_time) {
    char path[1100];
    snprintf(path, sizeof(path), "%s/_session.meta", g_state.session_dir);
    FILE *f = fopen(path, "w");
    if (!f) return;
    char ts[64];
    struct tm tm;
    localtime_r(&start_time, &tm);
    strftime(ts, sizeof(ts), "%Y-%m-%d %H:%M:%S", &tm);
    fprintf(f, "session_start=%s\n", ts);
    fprintf(f, "min_level=%c\n", g_state.min_level);
    fprintf(f, "helper_pid=%d\n", getpid());
    fprintf(f, "logv3=1\n");
    fprintf(f, "packages:\n");
    for (int i = 0; i < g_state.package_count; i++)
        fprintf(f, "  - %s\n", g_state.packages[i].name);
    fclose(f);
}

static void write_summary(time_t start_t, time_t end_t) {
    char path[1100];
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

// ── socket server ─────────────────────────────────────────────────────────────

static int setup_socket_server(void) {
    g_server_fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (g_server_fd < 0) {
        LOGE("socket: %s", strerror(errno));
        return -1;
    }

    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    // Abstract socket: sun_path[0] = '\0', name at sun_path[1]
    memcpy(addr.sun_path + 1, SOCKET_NAME, strlen(SOCKET_NAME));
    socklen_t addrlen = (socklen_t)(sizeof(sa_family_t) + 1 + strlen(SOCKET_NAME));

    if (bind(g_server_fd, (struct sockaddr *)&addr, addrlen) < 0) {
        LOGE("bind @%s: %s", SOCKET_NAME, strerror(errno));
        close(g_server_fd);
        g_server_fd = -1;
        return -1;
    }
    if (listen(g_server_fd, 1) < 0) {
        LOGE("listen: %s", strerror(errno));
        close(g_server_fd);
        g_server_fd = -1;
        return -1;
    }
    LOGI("Socket server ready @%s", SOCKET_NAME);
    return 0;
}

// Read one newline-terminated line from fd. Returns length (without '\n'),
// 0 on EOF, -1 on error. Result is always null-terminated.
static int read_line_fd(int fd, char *buf, size_t bufsize) {
    size_t n = 0;
    while (n < bufsize - 1) {
        char c;
        ssize_t r = read(fd, &c, 1);
        if (r < 0) return (errno == EINTR) ? 0 : -1;
        if (r == 0) return (int)n;  // EOF
        if (c == '\n') break;
        buf[n++] = c;
    }
    buf[n] = '\0';
    return (int)n;
}

// ── ring buffer ───────────────────────────────────────────────────────────────

static void ring_store(const char *line, size_t len) {
    if (g_ring_used + len + 1 > RING_SIZE) {
        if (!g_ring_overflow) {
            LOGE("Ring buffer full (%d MB), dropping oldest data", RING_SIZE / (1024 * 1024));
            g_ring_overflow = 1;
        }
        return;
    }
    memcpy(g_ring + g_ring_used, line, len);
    g_ring[g_ring_used + len] = '\0';
    g_ring_used += len + 1;
}

static int find_package_idx(const char *name) {
    for (int i = 0; i < g_state.package_count; i++) {
        if (strcmp(g_state.packages[i].name, name) == 0) return i;
    }
    return -1;
}

// Flush all buffered lines to open writers. Clears the ring on success or
// on write failure (data loss accepted to avoid infinite retry loops).
static void ring_flush(void) {
    if (g_ring_used == 0) return;
    LOGI("Flushing ring: %zu bytes, overflow=%d", g_ring_used, g_ring_overflow);
    size_t pos = 0;
    long flushed = 0, skipped = 0;
    while (pos < g_ring_used) {
        const char *entry = g_ring + pos;
        size_t entry_len = strlen(entry);
        pos += entry_len + 1;

        // entry format: "pkg_name\traw_logcat_line"
        const char *tab = memchr(entry, '\t', entry_len);
        if (!tab) { skipped++; continue; }

        char pkg_name[128];
        size_t pkg_len = (size_t)(tab - entry);
        if (pkg_len >= sizeof(pkg_name)) pkg_len = sizeof(pkg_name) - 1;
        memcpy(pkg_name, entry, pkg_len);
        pkg_name[pkg_len] = '\0';

        int idx = find_package_idx(pkg_name);
        if (idx < 0) { skipped++; continue; }

        const char *raw_line = tab + 1;
        LogEntry e;
        if (parse_line(raw_line, &e) < 0) { skipped++; continue; }

        if (write_entry(idx, &e, raw_line) < 0) {
            LOGE("ring_flush: write failed at entry %ld, discarding ring", flushed);
            break;
        }
        flushed++;
    }
    LOGI("Ring flush: %ld written, %ld skipped", flushed, skipped);
    g_ring_used = 0;
    g_ring_overflow = 0;
}

// ── session lifecycle ─────────────────────────────────────────────────────────

// Called when USB goes away or a write fails. Writes summary, tears down
// writers, enters buffering mode, and resets state for fresh USB scan.
static void end_session(void) {
    if (g_session_open) {
        write_summary(g_session_start, time(NULL));
        if (g_state.pid_file[0]) unlink(g_state.pid_file);
        g_session_open = 0;
        g_buffering    = 1;
    }
    reset_state();
    g_last_usb_check = 0;  // force immediate USB rescan on next loop tick
}

// Attempt to find USB, parse config, and open a new write session.
// On success, flushes the ring buffer. Returns 1 on success, 0 on failure.
static int try_open_session(void) {
    if (g_state.usb_root[0] == '\0' && !find_usb()) return 0;

    if (g_state.package_count == 0) {
        if (parse_config() < 0 || g_state.package_count == 0) {
            LOGE("No valid packages in config, rescanning");
            reset_state();
            return 0;
        }
    }

    if (create_session_dir() < 0) { reset_state(); return 0; }
    if (open_writers() < 0)       { reset_state(); return 0; }

    write_pid_file();
    g_session_start = time(NULL);
    write_session_meta(g_session_start);
    g_session_open = 1;
    g_buffering    = 0;

    LOGI("Session open: %s | ring=%zu bytes buffered", g_state.session_dir, g_ring_used);
    if (g_ring_used > 0) ring_flush();
    return 1;
}

// ── tagged line processing ────────────────────────────────────────────────────

// Process one "pkg_name\traw_logcat_line" from the socket.
// Writes directly to USB if session is open; otherwise buffers to ring.
static void process_tagged_line(const char *line) {
    size_t len = strlen(line);
    if (len == 0) return;

    if (!g_session_open) {
        ring_store(line, len);
        return;
    }

    const char *tab = memchr(line, '\t', len);
    if (!tab) return;

    char pkg_name[128];
    size_t pkg_len = (size_t)(tab - line);
    if (pkg_len >= sizeof(pkg_name)) pkg_len = sizeof(pkg_name) - 1;
    memcpy(pkg_name, line, pkg_len);
    pkg_name[pkg_len] = '\0';

    int idx = find_package_idx(pkg_name);
    if (idx < 0) return;

    const char *raw_line = tab + 1;
    LogEntry e;
    if (parse_line(raw_line, &e) < 0) return;

    if (write_entry(idx, &e, raw_line) < 0) {
        LOGI("Write failed, buffering line and entering buffer mode");
        ring_store(line, len);
        end_session();
    }
}

// ── main ─────────────────────────────────────────────────────────────────────

int main(int argc, char **argv) {
    if (another_instance_running()) {
        LOGI("Another helper instance running, exiting");
        return 0;
    }

    // Hint file path from env var set by LogDaemonService at launch
    const char *env_hint = getenv("LOGDAEMON_HINT_FILE");
    if (env_hint && env_hint[0]) {
        strncpy(g_hint_file, env_hint, sizeof(g_hint_file) - 1);
        LOGI("Hint file: %s", g_hint_file);
    }

    // Optional argv[1]: USB root pre-validated by LogDaemonService
    if (argc >= 2 && argv[1] && argv[1][0]) {
        char cfg[768];
        snprintf(cfg, sizeof(cfg), "%s/log.sinfo", argv[1]);
        if (access(cfg, R_OK) == 0) {
            strncpy(g_state.usb_root, argv[1], sizeof(g_state.usb_root) - 1);
            LOGI("USB hint from launcher: %s", g_state.usb_root);
        } else {
            LOGI("USB hint invalid (%s), will scan", argv[1]);
        }
    }

    daemonize();

    if (another_instance_running()) {
        LOGI("Another instance after daemonize, exiting");
        return 0;
    }

    LOGI("Helper started pid=%d (Logv3)", getpid());

    if (setup_socket_server() < 0) {
        LOGE("Socket setup failed, exiting");
        return 1;
    }

    while (g_running) {
        // Periodically try to open a session when none is active
        time_t now = time(NULL);
        if (!g_session_open && (now - g_last_usb_check >= USB_SCAN_INTERVAL_SEC)) {
            g_last_usb_check = now;
            try_open_session();
        }

        fd_set rfds;
        FD_ZERO(&rfds);
        if (g_server_fd >= 0) FD_SET(g_server_fd, &rfds);
        if (g_client_fd >= 0) FD_SET(g_client_fd, &rfds);

        int maxfd = (g_server_fd > g_client_fd) ? g_server_fd : g_client_fd;
        if (maxfd < 0) { sleep(1); continue; }

        struct timeval tv = {1, 0};
        int ret = select(maxfd + 1, &rfds, NULL, NULL, &tv);

        if (ret < 0) {
            if (errno == EINTR) continue;
            LOGE("select: %s", strerror(errno));
            break;
        }

        if (ret == 0) {
            // 1s tick: periodic USB health check when session is open
            if (g_session_open) {
                now = time(NULL);
                if (now - g_last_usb_check >= USB_CHECK_INTERVAL_SEC) {
                    g_last_usb_check = now;
                    if (!usb_present()) {
                        LOGI("USB session dir gone, ending session");
                        end_session();
                    }
                }
            }
            continue;
        }

        // New client connection
        if (g_server_fd >= 0 && FD_ISSET(g_server_fd, &rfds)) {
            int new_fd = accept(g_server_fd, NULL, NULL);
            if (new_fd >= 0) {
                if (g_client_fd >= 0) {
                    LOGI("Replacing existing client connection");
                    close(g_client_fd);
                }
                g_client_fd = new_fd;
                LOGI("Client connected fd=%d", g_client_fd);
            }
        }

        // Data from client
        if (g_client_fd >= 0 && FD_ISSET(g_client_fd, &rfds)) {
            char line[MAX_LINE];
            int n = read_line_fd(g_client_fd, line, sizeof(line));
            if (n <= 0) {
                LOGI("Client disconnected (n=%d)", n);
                close(g_client_fd);
                g_client_fd = -1;
            } else {
                process_tagged_line(line);
            }
        }
    }

    if (g_session_open) end_session();
    if (g_client_fd >= 0) close(g_client_fd);
    if (g_server_fd >= 0) close(g_server_fd);
    LOGI("Helper exiting");
    return 0;
}
