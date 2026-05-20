// LogDaemon native helper.
//
// Detached process that survives Android service death (profile switches, OOM
// kills) by double-forking and calling setsid() before doing any work. After
// detachment the process is reparented to init (PID 1) and is no longer in the
// parent Android app's process group, so signals targeting the parent do not
// reach it.
//
// Usage: liblogdaemon_helper.so <usb_root_path>
//
// The helper reads log.sinfo from <usb_root_path>, creates a session folder,
// spawns logcat as a subprocess, parses output, filters by PID, and writes
// per-package .log and .log.tsv files. It rescans /proc periodically so it
// picks up new process instances created by user-profile switches.

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

#define TAG "LogDaemon.Helper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)

#define MAX_PACKAGES 16
#define MAX_PIDS_PER_PKG 8
#define MAX_LINE 8192
#define PID_RESCAN_INTERVAL_SEC 2
#define USB_CHECK_INTERVAL_SEC 5
#define USB_MISSING_THRESHOLD 3  // ignore transient unmounts (profile switch)

typedef struct {
    char name[128];
    int pids[MAX_PIDS_PER_PKG];
    int pid_count;
    FILE *raw;
    FILE *tsv;
    long entries[6];  // V D I W E F
    long total;
} Package;

typedef struct {
    char usb_root[512];
    char session_dir[1024];
    char pid_file[1024];
    Package packages[MAX_PACKAGES];
    int package_count;
    char min_level;
    pid_t logcat_pid;
    int logcat_fd;
} State;

static State g_state = {0};
static volatile int g_running = 1;

static void sigterm_handler(int sig) { (void)sig; g_running = 0; }

static void daemonize(void) {
    pid_t pid = fork();
    if (pid < 0) exit(1);
    if (pid > 0) exit(0);

    if (setsid() < 0) exit(1);

    pid = fork();
    if (pid < 0) exit(1);
    if (pid > 0) exit(0);

    signal(SIGHUP, SIG_IGN);
    signal(SIGPIPE, SIG_IGN);
    signal(SIGTERM, sigterm_handler);

    if (chdir("/") < 0) { /* ignore */ }
    umask(0);

    close(STDIN_FILENO);
    close(STDOUT_FILENO);
}

static char *trim(char *s) {
    while (*s && isspace((unsigned char)*s)) s++;
    char *end = s + strlen(s);
    while (end > s && isspace((unsigned char)*(end - 1))) end--;
    *end = 0;
    return s;
}

static int parse_config(const char *usb_root) {
    char path[1024];
    snprintf(path, sizeof(path), "%s/log.sinfo", usb_root);

    FILE *f = fopen(path, "r");
    if (!f) {
        LOGE("Cannot open config: %s", path);
        return -1;
    }

    char line[1024];
    int in_packages = 0, in_options = 0;
    g_state.min_level = 'D';

    while (fgets(line, sizeof(line), f)) {
        char *p = trim(line);
        if (!*p || *p == '#' || *p == ';') continue;

        if (*p == '[') {
            in_packages = (strstr(p, "[packages]") == p);
            in_options = (strstr(p, "[options]") == p);
            continue;
        }

        if (in_packages && g_state.package_count < MAX_PACKAGES) {
            strncpy(g_state.packages[g_state.package_count].name, p, 127);
            g_state.package_count++;
        }
        if (in_options && strncmp(p, "min_level=", 10) == 0) {
            g_state.min_level = toupper((unsigned char)p[10]);
        }
    }
    fclose(f);

    LOGI("Parsed %d package(s), min_level=%c", g_state.package_count, g_state.min_level);
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
        LOGE("mkdir %s failed: %s", g_state.session_dir, strerror(errno));
        return -1;
    }

    LOGI("Session: %s", g_state.session_dir);
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

static int read_cmdline(int pid, char *buf, size_t buflen) {
    char path[64];
    snprintf(path, sizeof(path), "/proc/%d/cmdline", pid);
    int fd = open(path, O_RDONLY);
    if (fd < 0) return -1;
    ssize_t n = read(fd, buf, buflen - 1);
    close(fd);
    if (n <= 0) return -1;
    buf[n] = 0;
    char *colon = strchr(buf, ':');
    if (colon) *colon = 0;
    return 0;
}

static void rescan_pids(void) {
    DIR *d = opendir("/proc");
    if (!d) return;

    for (int i = 0; i < g_state.package_count; i++) {
        g_state.packages[i].pid_count = 0;
    }

    struct dirent *e;
    while ((e = readdir(d))) {
        if (e->d_type != DT_DIR) continue;
        int pid = atoi(e->d_name);
        if (pid <= 0) continue;

        char cmdline[256];
        if (read_cmdline(pid, cmdline, sizeof(cmdline)) < 0) continue;

        for (int i = 0; i < g_state.package_count; i++) {
            Package *pkg = &g_state.packages[i];
            if (strcmp(cmdline, pkg->name) == 0 && pkg->pid_count < MAX_PIDS_PER_PKG) {
                pkg->pids[pkg->pid_count++] = pid;
            }
        }
    }
    closedir(d);
}

static int pid_to_package(int pid) {
    for (int i = 0; i < g_state.package_count; i++) {
        Package *pkg = &g_state.packages[i];
        for (int j = 0; j < pkg->pid_count; j++) {
            if (pkg->pids[j] == pid) return i;
        }
    }
    return -1;
}

static int spawn_logcat(void) {
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
        execl("/system/bin/logcat", "logcat", "-v", "threadtime", level, NULL);
        _exit(127);
    }

    close(pipefd[1]);
    g_state.logcat_pid = pid;
    g_state.logcat_fd = pipefd[0];
    LOGI("Spawned logcat pid=%d level=*:%c", pid, g_state.min_level);
    return 0;
}

typedef struct {
    char timestamp[32];
    int pid, tid;
    char level;
    char tag[256];
    char message[8192];
} LogEntry;

static int parse_line(const char *line, LogEntry *e) {
    // threadtime: MM-DD HH:MM:SS.MMM PID TID L TAG: MSG
    char date[16], time_s[16];
    int pid, tid;
    char level;
    int consumed = 0;
    if (sscanf(line, "%15s %15s %d %d %c %n",
               date, time_s, &pid, &tid, &level, &consumed) < 5) {
        return -1;
    }
    if (consumed == 0) return -1;

    time_t now = time(NULL);
    struct tm tm;
    localtime_r(&now, &tm);
    snprintf(e->timestamp, sizeof(e->timestamp), "%d-%s %s",
             tm.tm_year + 1900, date, time_s);
    e->pid = pid;
    e->tid = tid;
    e->level = level;

    const char *rest = line + consumed;
    const char *colon = strstr(rest, ": ");
    if (colon) {
        size_t tag_len = colon - rest;
        if (tag_len >= sizeof(e->tag)) tag_len = sizeof(e->tag) - 1;
        memcpy(e->tag, rest, tag_len);
        e->tag[tag_len] = 0;
        while (tag_len > 0 && isspace((unsigned char)e->tag[tag_len - 1])) {
            e->tag[--tag_len] = 0;
        }
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
        if (*p == '\t') *p = ' ';
        if (*p == '\n' || *p == '\r') *p = ' ';
    }
}

static int level_idx(char l) {
    switch (l) {
        case 'V': return 0;
        case 'D': return 1;
        case 'I': return 2;
        case 'W': return 3;
        case 'E': return 4;
        case 'F': return 5;
    }
    return 1;
}

static int write_entry(int pkg_idx, const LogEntry *e, const char *raw_line) {
    Package *pkg = &g_state.packages[pkg_idx];
    if (fprintf(pkg->raw, "%s\n", raw_line) < 0) return -1;

    char tag_c[256], msg_c[8192];
    strncpy(tag_c, e->tag, sizeof(tag_c) - 1); tag_c[sizeof(tag_c) - 1] = 0;
    strncpy(msg_c, e->message, sizeof(msg_c) - 1); msg_c[sizeof(msg_c) - 1] = 0;
    tsv_clean(tag_c);
    tsv_clean(msg_c);

    if (fprintf(pkg->tsv, "%s\t%d\t%d\t%c\t%s\t%s\n",
                e->timestamp, e->pid, e->tid, e->level, tag_c, msg_c) < 0) return -1;

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
    return (stat(g_state.session_dir, &st) == 0);
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
    fprintf(f, "packages:\n");
    for (int i = 0; i < g_state.package_count; i++) {
        fprintf(f, "  - %s\n", g_state.packages[i].name);
    }
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

static void close_writers(void) {
    for (int i = 0; i < g_state.package_count; i++) {
        Package *pkg = &g_state.packages[i];
        if (pkg->raw) { fclose(pkg->raw); pkg->raw = NULL; }
        if (pkg->tsv) { fclose(pkg->tsv); pkg->tsv = NULL; }
    }
}

static void write_pid_file(void) {
    snprintf(g_state.pid_file, sizeof(g_state.pid_file),
             "%s/.logdaemon.pid", g_state.usb_root);
    FILE *f = fopen(g_state.pid_file, "w");
    if (f) { fprintf(f, "%d\n", getpid()); fclose(f); }
}

int main(int argc, char **argv) {
    if (argc < 2) {
        fprintf(stderr, "Usage: %s <usb_root>\n", argv[0]);
        return 1;
    }
    strncpy(g_state.usb_root, argv[1], sizeof(g_state.usb_root) - 1);

    daemonize();
    LOGI("Helper detached, pid=%d, usb=%s", getpid(), g_state.usb_root);

    if (parse_config(g_state.usb_root) < 0 || g_state.package_count == 0) {
        LOGE("Config invalid or empty");
        return 1;
    }
    if (create_session_dir() < 0) return 1;
    if (open_writers() < 0) return 1;

    write_pid_file();

    time_t session_start = time(NULL);
    write_session_meta(session_start);

    rescan_pids();
    if (spawn_logcat() < 0) return 1;

    FILE *logf = fdopen(g_state.logcat_fd, "r");
    if (!logf) return 1;

    char line[MAX_LINE];
    time_t last_rescan = time(NULL);
    time_t last_usb_check = time(NULL);
    int usb_missing = 0;
    long lines_total = 0, lines_matched = 0;

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
                    LOGE("Write failed, USB pulled?");
                    g_running = 0;
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
        if (now - last_usb_check >= USB_CHECK_INTERVAL_SEC) {
            if (!usb_present()) {
                usb_missing++;
                LOGI("USB session dir missing (%d/%d)", usb_missing, USB_MISSING_THRESHOLD);
                if (usb_missing >= USB_MISSING_THRESHOLD) {
                    LOGI("USB confirmed gone");
                    g_running = 0;
                }
            } else {
                usb_missing = 0;
            }
            last_usb_check = now;
        }
    }

    LOGI("Loop exit, total=%ld matched=%ld", lines_total, lines_matched);

    if (g_state.logcat_pid > 0) {
        kill(g_state.logcat_pid, SIGTERM);
        int status;
        waitpid(g_state.logcat_pid, &status, 0);
    }
    fclose(logf);

    close_writers();
    write_summary(session_start, time(NULL));
    unlink(g_state.pid_file);

    LOGI("Helper done");
    return 0;
}
