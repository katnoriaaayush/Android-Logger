/*
 * logbridged.c — Phase-1 LogBridge native daemon
 *
 * Responsibility split (vs the monolithic logdaemon.c):
 *   • This daemon does NOT collect logs itself.
 *   • It detects a USB volume carrying a "log.sinfo" file, then hands the
 *     session over to the Android LogBridgeService, which has the framework
 *     APIs (logcat, ActivityManager) the daemon lacks.
 *   • Communication is a length-prefixed binary protocol over a Unix domain
 *     socket. The daemon is the server; the service is the client.
 *
 * Phase-1 goal: prove bidirectional comms.
 *
 *   1. Daemon binds/listens on the "logbridge" Unix socket.
 *   2. Daemon polls for a USB volume containing log.sinfo
 *      (or /data/local/tmp/log.sinfo for emulator testing).
 *   3. On detection it reads sinfo into memory and starts the Android service
 *      via `am start-foreground-service`.
 *   4. The service connects back to the socket. Handshake:
 *        daemon → SESSION_CONFIG(sinfo)   service → ACK
 *        daemon → PING                    service → PONG
 *      Both directions are exercised twice, then the daemon keepalive-PINGs.
 *
 * Wire format (all integers big-endian):
 *      [4 bytes type][4 bytes payload-length][payload]
 */

#define _GNU_SOURCE
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <pthread.h>
#include <signal.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

/* ── constants ──────────────────────────────────────────────────────────── */

#define SOCKET_NAME        "logbridge"            /* RESERVED ns → /dev/socket/logbridge */
#define USB_WATCH_PATH     "/mnt/media_rw"
#define TEST_SINFO_PATH    "/data/local/tmp/log.sinfo"   /* emulator / manual test */
#define CONFIG_FILE        "log.sinfo"
#define AM_BIN             "/system/bin/am"
#define SERVICE_COMPONENT  "com.sqa.logtest/.LogBridgeService"

#define MAX_SINFO          (64 * 1024)
#define BUFSZ              4096
#define POLL_INTERVAL_SEC  2
#define KEEPALIVE_SEC      10

/* frame types — must match LogBridgeService.kt */
#define T_SESSION_CONFIG   0x01
#define T_ACK              0x02
#define T_PING             0x03
#define T_PONG             0x04
#define T_BYE              0x05

/* ── globals ────────────────────────────────────────────────────────────── */

static volatile int     g_running    = 1;
static volatile int     g_have_sinfo = 0;
static char             g_sinfo[MAX_SINFO];
static size_t           g_sinfo_len  = 0;
static pthread_mutex_t  g_lock       = PTHREAD_MUTEX_INITIALIZER;

/* ── logging ────────────────────────────────────────────────────────────── */

static void dlog(const char *fmt, ...) {
    va_list ap;
    time_t t = time(NULL);
    struct tm *tm = localtime(&t);
    char tbuf[32];
    strftime(tbuf, sizeof(tbuf), "%m-%d %H:%M:%S", tm);
    fprintf(stderr, "[logbridged %s] ", tbuf);
    va_start(ap, fmt);
    vfprintf(stderr, fmt, ap);
    va_end(ap);
    fputc('\n', stderr);
    fflush(stderr);
}

/* ── full read/write helpers (handle partial transfers) ─────────────────── */

static int write_all(int fd, const void *p, size_t n) {
    const char *c = (const char *)p;
    while (n) {
        ssize_t w = write(fd, c, n);
        if (w < 0) { if (errno == EINTR) continue; return -1; }
        c += w; n -= (size_t)w;
    }
    return 0;
}

static int read_all(int fd, void *p, size_t n) {
    char *c = (char *)p;
    while (n) {
        ssize_t r = read(fd, c, n);
        if (r < 0) { if (errno == EINTR) continue; return -1; }
        if (r == 0) return -1;   /* peer closed */
        c += r; n -= (size_t)r;
    }
    return 0;
}

/* ── framing ────────────────────────────────────────────────────────────── */

static int write_frame(int fd, uint32_t type, const char *payload, uint32_t len) {
    unsigned char hdr[8];
    hdr[0] = (type >> 24) & 0xff; hdr[1] = (type >> 16) & 0xff;
    hdr[2] = (type >>  8) & 0xff; hdr[3] =  type        & 0xff;
    hdr[4] = (len  >> 24) & 0xff; hdr[5] = (len  >> 16) & 0xff;
    hdr[6] = (len  >>  8) & 0xff; hdr[7] =  len         & 0xff;
    if (write_all(fd, hdr, 8) < 0) return -1;
    if (len && write_all(fd, payload, len) < 0) return -1;
    return 0;
}

static int read_frame(int fd, uint32_t *type, char *buf, uint32_t bufsz, uint32_t *out_len) {
    unsigned char hdr[8];
    if (read_all(fd, hdr, 8) < 0) return -1;
    *type     = ((uint32_t)hdr[0] << 24) | ((uint32_t)hdr[1] << 16) |
                ((uint32_t)hdr[2] <<  8) |  (uint32_t)hdr[3];
    uint32_t len = ((uint32_t)hdr[4] << 24) | ((uint32_t)hdr[5] << 16) |
                   ((uint32_t)hdr[6] <<  8) |  (uint32_t)hdr[7];
    if (len > bufsz) { dlog("frame too large: %u > %u", len, bufsz); return -1; }
    if (len && read_all(fd, buf, len) < 0) return -1;
    *out_len = len;
    return 0;
}

/* ── socket setup ───────────────────────────────────────────────────────── */

/*
 * init creates the socket declared in logbridged.rc and passes the fd via the
 * ANDROID_SOCKET_<name> env var (this is what libcutils' android_get_control_socket
 * does internally — replicated here to keep the daemon dependency-free).
 * Falls back to self-binding /dev/socket/logbridge when run manually (no init).
 */
static int make_listen_socket(void) {
    char key[64];
    snprintf(key, sizeof(key), "ANDROID_SOCKET_%s", SOCKET_NAME);
    const char *val = getenv(key);

    int fd;
    if (val) {
        fd = atoi(val);
        dlog("using init-created socket fd=%d", fd);
    } else {
        fd = socket(AF_UNIX, SOCK_STREAM, 0);
        if (fd < 0) { dlog("socket() failed: %s", strerror(errno)); return -1; }
        struct sockaddr_un addr;
        memset(&addr, 0, sizeof(addr));
        addr.sun_family = AF_UNIX;
        snprintf(addr.sun_path, sizeof(addr.sun_path), "/dev/socket/%s", SOCKET_NAME);
        unlink(addr.sun_path);
        if (bind(fd, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
            dlog("bind %s failed: %s", addr.sun_path, strerror(errno));
            close(fd);
            return -1;
        }
        chmod(addr.sun_path, 0660);
        dlog("self-bound socket at %s", addr.sun_path);
    }

    if (listen(fd, 4) < 0) { dlog("listen failed: %s", strerror(errno)); close(fd); return -1; }
    return fd;
}

/* ── service launch ─────────────────────────────────────────────────────── */

/* Native daemon cannot start an Android service directly (no Context);
 * shell out to `am`, which talks to ActivityManager over Binder. */
static void start_service(const char *session_id) {
    pid_t pid = fork();
    if (pid == 0) {
        execl(AM_BIN, "am", "start-foreground-service",
              "--user", "0",
              "-n", SERVICE_COMPONENT,
              "--es", "session", session_id,
              (char *)NULL);
        _exit(127);
    }
    if (pid < 0) { dlog("fork for am failed: %s", strerror(errno)); return; }
    int status;
    waitpid(pid, &status, 0);
    dlog("am start-foreground-service exited status=%d", status);
}

/* ── sinfo detection ────────────────────────────────────────────────────── */

static int read_file(const char *path, char *buf, size_t bufsz, size_t *out_len) {
    int fd = open(path, O_RDONLY);
    if (fd < 0) return -1;
    ssize_t n = read(fd, buf, bufsz);
    close(fd);
    if (n < 0) return -1;
    *out_len = (size_t)n;
    return 0;
}

/* Fills out_path with the sinfo file path if found, returns 0. */
static int find_sinfo(char *out_path) {
    if (access(TEST_SINFO_PATH, F_OK) == 0) {
        snprintf(out_path, PATH_MAX, "%s", TEST_SINFO_PATH);
        return 0;
    }
    DIR *d = opendir(USB_WATCH_PATH);
    if (!d) return -1;
    struct dirent *de;
    int found = -1;
    while ((de = readdir(d)) != NULL && found < 0) {
        if (de->d_name[0] == '.') continue;
        char cfg[PATH_MAX];
        snprintf(cfg, sizeof(cfg), "%s/%s/%s", USB_WATCH_PATH, de->d_name, CONFIG_FILE);
        if (access(cfg, F_OK) == 0) {
            snprintf(out_path, PATH_MAX, "%s", cfg);
            found = 0;
        }
    }
    closedir(d);
    return found;
}

/* ── client handshake ───────────────────────────────────────────────────── */

static void handle_client(int cfd) {
    char buf[BUFSZ];
    uint32_t type, len;

    /* 1. push SESSION_CONFIG (the sinfo content) */
    pthread_mutex_lock(&g_lock);
    char sinfo[MAX_SINFO];
    size_t slen = g_sinfo_len;
    memcpy(sinfo, g_sinfo, slen);
    pthread_mutex_unlock(&g_lock);

    dlog("→ SESSION_CONFIG (%zu bytes)", slen);
    if (write_frame(cfd, T_SESSION_CONFIG, sinfo, (uint32_t)slen) < 0) {
        dlog("write SESSION_CONFIG failed"); return;
    }

    /* 2. expect ACK */
    if (read_frame(cfd, &type, buf, sizeof(buf), &len) < 0) { dlog("no ACK from service"); return; }
    dlog("← type=0x%02x (%u bytes): %.*s", type, len, (int)len, buf);
    if (type != T_ACK) dlog("WARN: expected ACK, got 0x%02x", type);

    /* 3. push PING */
    const char *ping = "ping-1 from logbridged";
    dlog("→ PING");
    if (write_frame(cfd, T_PING, ping, (uint32_t)strlen(ping)) < 0) { dlog("write PING failed"); return; }

    /* 4. expect PONG */
    if (read_frame(cfd, &type, buf, sizeof(buf), &len) < 0) { dlog("no PONG from service"); return; }
    dlog("← type=0x%02x (%u bytes): %.*s", type, len, (int)len, buf);
    if (type == T_PONG)
        dlog("==== TWO-WAY HANDSHAKE COMPLETE — bidirectional comms verified ====");
    else
        dlog("WARN: expected PONG, got 0x%02x", type);

    /* 5. keepalive — PING every KEEPALIVE_SEC, prove the channel stays bidirectional */
    int seq = 2;
    while (g_running) {
        sleep(KEEPALIVE_SEC);
        char pmsg[64];
        snprintf(pmsg, sizeof(pmsg), "ping-%d from logbridged", seq++);
        if (write_frame(cfd, T_PING, pmsg, (uint32_t)strlen(pmsg)) < 0) { dlog("keepalive write failed"); break; }
        if (read_frame(cfd, &type, buf, sizeof(buf), &len) < 0) { dlog("keepalive read failed"); break; }
        dlog("← keepalive type=0x%02x: %.*s", type, (int)len, buf);
    }
}

static void *accept_thread(void *arg) {
    int srv = *(int *)arg;
    while (g_running) {
        int cfd = accept(srv, NULL, NULL);
        if (cfd < 0) { if (errno == EINTR) continue; dlog("accept failed: %s", strerror(errno)); break; }
        dlog("service connected (fd=%d)", cfd);
        handle_client(cfd);
        close(cfd);
        dlog("service disconnected");
    }
    return NULL;
}

/* ── signals ────────────────────────────────────────────────────────────── */

static void on_sig(int sig) { (void)sig; g_running = 0; }

/* ── main ───────────────────────────────────────────────────────────────── */

int main(void) {
    signal(SIGTERM, on_sig);
    signal(SIGINT,  on_sig);
    signal(SIGPIPE, SIG_IGN);

    dlog("starting (uid=%d)", (int)getuid());

    int srv = make_listen_socket();
    if (srv < 0) { dlog("cannot create listen socket — exiting"); return 1; }

    pthread_t atid;
    if (pthread_create(&atid, NULL, accept_thread, &srv) != 0) {
        dlog("pthread_create failed"); return 1;
    }

    dlog("watching for %s (USB %s or %s)", CONFIG_FILE, USB_WATCH_PATH, TEST_SINFO_PATH);

    char path[PATH_MAX];
    while (g_running) {
        if (!g_have_sinfo && find_sinfo(path) == 0) {
            dlog("sinfo detected: %s", path);
            char tmp[MAX_SINFO];
            size_t len = 0;
            if (read_file(path, tmp, sizeof(tmp), &len) == 0) {
                pthread_mutex_lock(&g_lock);
                memcpy(g_sinfo, tmp, len);
                g_sinfo_len  = len;
                g_have_sinfo = 1;
                pthread_mutex_unlock(&g_lock);
                dlog("read %zu bytes of sinfo — starting Android service", len);
                start_service("phase1");
            } else {
                dlog("failed to read sinfo: %s", strerror(errno));
            }
        }
        sleep(POLL_INTERVAL_SEC);
    }

    close(srv);
    dlog("exiting");
    return 0;
}
