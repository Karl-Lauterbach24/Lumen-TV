/*
 * mmshim: LD_PRELOAD library that gives a Linux program inside the app's proot tree a SCSI generic
 * device (/dev/sg0, also answering for /dev/sr0) backed by the USB drive the Android app opened.
 *
 * The app owns the USB connection (Android only grants it to the app). On the first open of the
 * device path the shim connects to the app's abstract unix socket "lumen.mmshim.usb" and receives
 * the usbfs file descriptor plus "<ep_in> <ep_out> <interface>". SG_IO requests are then carried
 * out with USB Mass Storage Bulk-Only Transport over that descriptor.
 *
 * MMSHIM_LOG=1 logs every request to stderr.
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <pthread.h>
#include <scsi/sg.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/sysmacros.h>
#include <sys/un.h>
#include <unistd.h>
#include <dirent.h>
#include <time.h>
#include <linux/usbdevice_fs.h>
#include <sys/file.h>
#include <poll.h>

#define SOCKET_NAME "lumen.mmshim.usb"
#define CHUNK (64 * 1024)

/* back-pressure (see write_all) */
static volatile int w_busy;
static volatile long long w_progress_ms;
static long long now_ms(void)
{
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return t.tv_sec * 1000LL + t.tv_nsec / 1000000;
}
static unsigned long long held_ms;

static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
static int usb_fd = -1, ep_in, ep_out, iface;
static uint32_t tag = 1;
static unsigned long long stat_bytes, stat_cmds, stat_last_bytes;
static struct timespec stat_start, stat_last;
static int logging = -1;

#define LOG(...) do { if (logging) { fprintf(stderr, "[mmshim] " __VA_ARGS__); fputc('\n', stderr); } } while (0)

static void init_log(void)
{
    if (logging < 0) { const char *l = getenv("MMSHIM_LOG"); logging = l ? atoi(l) : 0; }
}

static int probed(const char *p)
{
    return p && (!strncmp(p, "/dev", 4) || !strncmp(p, "/sys", 4) || !strncmp(p, "/proc", 5));
}

static int is_dev(const char *p)
{
    return p && (!strcmp(p, "/dev/sg0") || !strcmp(p, "/dev/sr0"));
}

static int (*real_open)(const char *, int, ...);
static int (*real_ioctl)(int, unsigned long, ...);
static int (*real_close)(int);

static void resolve(void)
{
    if (!real_open) real_open = dlsym(RTLD_NEXT, "open");
    if (!real_ioctl) real_ioctl = dlsym(RTLD_NEXT, "ioctl");
    if (!real_close) real_close = dlsym(RTLD_NEXT, "close");
}

/* get the usbfs descriptor from the app */
static int connect_app(void)
{
    if (usb_fd >= 0) return 0;
    int s = socket(AF_UNIX, SOCK_STREAM, 0);
    if (s < 0) return -1;
    struct sockaddr_un a;
    memset(&a, 0, sizeof(a));
    a.sun_family = AF_UNIX;
    memcpy(a.sun_path + 1, SOCKET_NAME, strlen(SOCKET_NAME));
    socklen_t len = offsetof(struct sockaddr_un, sun_path) + 1 + strlen(SOCKET_NAME);
    if (connect(s, (struct sockaddr *)&a, len) < 0) {
        LOG("connect to app failed: %s", strerror(errno));
        real_close(s);
        return -1;
    }
    char buf[64];
    char cbuf[CMSG_SPACE(sizeof(int))];
    struct iovec iov = { buf, sizeof(buf) - 1 };
    struct msghdr m;
    memset(&m, 0, sizeof(m));
    m.msg_iov = &iov; m.msg_iovlen = 1;
    m.msg_control = cbuf; m.msg_controllen = sizeof(cbuf);
    ssize_t n = recvmsg(s, &m, 0);
    real_close(s);
    if (n <= 0) { LOG("no answer from app"); return -1; }
    buf[n] = 0;
    struct cmsghdr *c = CMSG_FIRSTHDR(&m);
    if (!c || c->cmsg_type != SCM_RIGHTS) { LOG("app sent no descriptor: %s", buf); return -1; }
    memcpy(&usb_fd, CMSG_DATA(c), sizeof(int));
    if (sscanf(buf, "%d %d %d", &ep_in, &ep_out, &iface) != 3) { LOG("bad message %s", buf); return -1; }
    LOG("got usbfs fd %d ep_in 0x%x ep_out 0x%x interface %d", usb_fd, ep_in, ep_out, iface);
    return 0;
}

/*
 * One bulk transfer. As an asynchronous URB (submit, poll, reap) unless MMSHIM_URB=0: on the television the
 * synchronous USBDEVFS_BULK kept a core busy in the kernel for as long as the drive took to answer.
 */
static int use_urb = -1;
static int bulk(int ep, void *data, unsigned len, unsigned timeout_ms)
{
    if (use_urb < 0) { const char *e = getenv("MMSHIM_URB"); use_urb = !(e && *e == '0'); LOG("bulk transfers: %s", use_urb ? "URB" : "USBDEVFS_BULK"); }
    if (!use_urb) {
        struct usbdevfs_bulktransfer b = { ep, len, timeout_ms, data };
        return real_ioctl(usb_fd, USBDEVFS_BULK, &b);
    }
    struct usbdevfs_urb u;
    memset(&u, 0, sizeof(u));
    u.type = USBDEVFS_URB_TYPE_BULK;
    u.endpoint = ep;
    u.buffer = data;
    u.buffer_length = len;
    if (real_ioctl(usb_fd, USBDEVFS_SUBMITURB, &u) < 0) return -1;
    struct pollfd p = { usb_fd, POLLOUT | POLLWRNORM, 0 };
    int r;
    do r = poll(&p, 1, timeout_ms); while (r < 0 && errno == EINTR);
    if (r <= 0) real_ioctl(usb_fd, USBDEVFS_DISCARDURB, &u);
    struct usbdevfs_urb *got = NULL;
    int k;
    do k = real_ioctl(usb_fd, USBDEVFS_REAPURB, &got); while (k < 0 && errno == EINTR);
    if (k < 0) return -1;
    if (r <= 0) { errno = ETIMEDOUT; return -1; }
    if (u.status < 0) { errno = -u.status; return -1; }
    return u.actual_length;
}

static void clear_halt(int ep)
{
    unsigned int e = ep;
    real_ioctl(usb_fd, USBDEVFS_CLEAR_HALT, &e);
}

static void reset_recovery(void)
{
    struct usbdevfs_ctrltransfer c = { 0x21, 0xff, 0, (uint16_t)iface, 0, 5000, NULL };
    real_ioctl(usb_fd, USBDEVFS_CONTROL, &c);
    clear_halt(ep_in);
    clear_halt(ep_out);
}

/*
 * Several processes may use the drive at once (libmmbd's helper and the player of a menu): each command (the three bulk
 * transfers of a Bulk-Only Transport command) holds a file lock shared by all of them.
 */
static int xlock_fd = -2;
static void xlock(int on)
{
    if (xlock_fd == -2) {
        int (*real_open)(const char *, int, ...) = dlsym(RTLD_NEXT, "open");
        xlock_fd = real_open("/tmp/.mmshim.lock", O_RDWR | O_CREAT, 0600);
    }
    if (xlock_fd >= 0) flock(xlock_fd, on ? LOCK_EX : LOCK_UN);
}

/* one Bulk-Only Transport command; returns CSW status (0 ok, 1 failed, 2 phase error) or -1 */
static int bot_locked(const uint8_t *cdb, int cdb_len, int dir_in, uint8_t *data, unsigned len,
               unsigned timeout_ms, unsigned *done)
{
    uint8_t cbw[31];
    memset(cbw, 0, sizeof(cbw));
    uint32_t t = tag++;
    cbw[0] = 'U'; cbw[1] = 'S'; cbw[2] = 'B'; cbw[3] = 'C';
    memcpy(cbw + 4, &t, 4);
    memcpy(cbw + 8, &len, 4);
    cbw[12] = dir_in ? 0x80 : 0;
    cbw[13] = 0;
    cbw[14] = cdb_len;
    memcpy(cbw + 15, cdb, cdb_len > 16 ? 16 : cdb_len);
    if (bulk(ep_out, cbw, 31, 5000) != 31) {
        LOG("CBW failed: %s", strerror(errno));
        reset_recovery();
        return -1;
    }
    *done = 0;
    while (*done < len) {
        unsigned n = len - *done > CHUNK ? CHUNK : len - *done;
        int r = bulk(dir_in ? ep_in : ep_out, data + *done, n, timeout_ms);
        if (r < 0) {
            if (errno == EPIPE) clear_halt(dir_in ? ep_in : ep_out);
            else LOG("data phase failed: %s", strerror(errno));
            break;
        }
        *done += r;
        if ((unsigned)r < n) break; /* short packet ends the data phase */
    }
    uint8_t csw[13];
    int r = bulk(ep_in, csw, 13, timeout_ms);
    if (r < 0 && errno == EPIPE) {
        clear_halt(ep_in);
        r = bulk(ep_in, csw, 13, timeout_ms);
    }
    if (r != 13 || memcmp(csw, "USBS", 4)) {
        LOG("CSW failed (%d): %s", r, strerror(errno));
        reset_recovery();
        return -1;
    }
    if (csw[12] == 2) reset_recovery();
    return csw[12];
}

static int bot(const uint8_t *cdb, int cdb_len, int dir_in, uint8_t *data, unsigned len, unsigned timeout_ms, unsigned *done)
{
    xlock(1);
    int r = bot_locked(cdb, cdb_len, dir_in, data, len, timeout_ms, done);
    xlock(0);
    return r;
}

static int do_sg_io(struct sg_io_hdr *h)
{
    if (h->interface_id != 'S' || h->iovec_count) { errno = EINVAL; return -1; }
    if (connect_app() < 0) { errno = ENODEV; return -1; }
    int dir_in = h->dxfer_direction == SG_DXFER_FROM_DEV;
    unsigned len = h->dxfer_direction == SG_DXFER_NONE ? 0 : h->dxfer_len;
    unsigned timeout = h->timeout ? h->timeout : 60000;
    unsigned done = 0;

    /* a read of the disc waits while the stream's writer is stuck (see write_all) */
    uint8_t op = h->cmdp[0];
    if (op == 0x28 || op == 0xa8 || op == 0xbe) {
        long long w0 = now_ms();
        while (w_busy && now_ms() - w_progress_ms > 150) usleep(20000);
        held_ms += now_ms() - w0;
    }
    struct timespec c0;
    clock_gettime(CLOCK_MONOTONIC, &c0);
    uint8_t key = 0, asc = 0, ascq = 0;
    pthread_mutex_lock(&lock);
    int st = bot(h->cmdp, h->cmd_len, dir_in, h->dxferp, len, timeout, &done);
    h->status = 0; h->masked_status = 0; h->msg_status = 0; h->sb_len_wr = 0;
    h->host_status = 0; h->driver_status = 0; h->info = 0;
    h->resid = len - done;
    if (st < 0) {
        h->host_status = 0x07; /* DID_ERROR */
        h->info = SG_INFO_CHECK;
    } else if (st != 0) {
        uint8_t rs[6] = { 0x03, 0, 0, 0, 18, 0 }, sense[18];
        unsigned sd = 0;
        memset(sense, 0, sizeof(sense));
        bot(rs, 6, 1, sense, sizeof(sense), 5000, &sd);
        unsigned n = h->mx_sb_len < sd ? h->mx_sb_len : sd;
        if (h->sbp && n) memcpy(h->sbp, sense, n);
        key = sense[2] & 0x0f; asc = sense[12]; ascq = sense[13];
        h->sb_len_wr = n;
        h->status = 0x02;          /* CHECK CONDITION */
        h->masked_status = 0x01;
        h->driver_status = 0x08;   /* DRIVER_SENSE */
        h->info = SG_INFO_CHECK;
    }
    pthread_mutex_unlock(&lock);
    struct timespec c1;
    clock_gettime(CLOCK_MONOTONIC, &c1);
    long ms = (c1.tv_sec - c0.tv_sec) * 1000 + (c1.tv_nsec - c0.tv_nsec) / 1000000;
    const uint8_t *cd = h->cmdp;
    unsigned lba = (cd[0] == 0x28 || cd[0] == 0xa8) ? (unsigned)cd[2] << 24 | cd[3] << 16 | cd[4] << 8 | cd[5] : 0;
    if (logging >= 3 || (logging >= 1 && st != 0))
        LOG("SG_IO cdb %02x lba %u len %u dir %d -> csw %d done %u in %ld ms, sense %x/%02x/%02x", cd[0], lba, len, h->dxfer_direction,
            st, done, ms, key, asc, ascq);
    else if (logging >= 1 && ms >= 2000)
        LOG("SG_IO cdb %02x lba %u len %u slow: %ld ms", cd[0], lba, len, ms);
    stat_bytes += done; stat_cmds++;
    struct timespec now;
    clock_gettime(CLOCK_MONOTONIC, &now);
    if (!stat_start.tv_sec) stat_start = stat_last = now;
    double since = (now.tv_sec - stat_last.tv_sec) + (now.tv_nsec - stat_last.tv_nsec) / 1e9;
    if (logging >= 1 && since >= 5) {
        double total = (now.tv_sec - stat_start.tv_sec) + (now.tv_nsec - stat_start.tv_nsec) / 1e9;
        LOG("stats: %llu commands, %.1f MB read, now %.2f MB/s, average %.2f MB/s, reading held %.1f s", stat_cmds, stat_bytes / 1e6,
            (stat_bytes - stat_last_bytes) / 1e6 / since, stat_bytes / 1e6 / total, held_ms / 1000.0);
        stat_last = now; stat_last_bytes = stat_bytes;
    }
    return 0;
}

/* ---- the intercepted C library functions ---- */

#define MAXFD 1024
static unsigned char ours[MAXFD];

static int is_ours(int fd) { return fd >= 0 && fd < MAXFD && ours[fd]; }
static long long devpos[MAXFD];      /* the read position of the fake drive when read as a file */

static void fake_stat(struct stat64 *st)
{
    memset(st, 0, sizeof(*st));
    st->st_mode = S_IFCHR | 0660;
    st->st_rdev = makedev(21, 0);
    st->st_nlink = 1;
    st->st_blksize = 4096;
}

static unsigned char outfd[1024];
static unsigned char streamfd[1024];
static long long stream_pos[1024];
static int is_stream_path(const char *p)
{
    const char *o = getenv("MMSHIM_STREAM");
    return o && p && !strncmp(p, o, strlen(o));
}
static int is_out_path(const char *p)
{
    const char *o = getenv("MMSHIM_OUT");
    return o && p && !strncmp(p, o, strlen(o));
}

static int do_open(const char *path, int flags, mode_t mode)
{
    resolve(); init_log();
    if (is_stream_path(path)) {
        /* the app made the path a pipe: write-only, no truncation */
        int r = real_open(path, O_WRONLY, 0);
        if (r >= 0 && r < 1024) { streamfd[r] = 1; stream_pos[r] = 0; }
        LOG("STREAM open(%s) = %d", path, r);
        return r;
    }
    if (is_out_path(path)) {
        int r = real_open(path, flags, mode);
        if (r >= 0 && r < 1024) outfd[r] = 1;
        LOG("OUT open(%s, 0x%x) = %d", path, flags, r);
        return r;
    }
    if (is_dev(path)) {
        if (connect_app() < 0) { errno = ENOENT; LOG("open(%s): no drive from the app", path); return -1; }
        int fd = real_open("/dev/null", O_RDWR);
        if (fd >= 0 && fd < MAXFD) ours[fd] = 1;
        LOG("open(%s) -> %d", path, fd);
        return fd;
    }
    int r = real_open(path, flags, mode);
    if (logging > 1 && probed(path)) LOG("open(%s) = %d", path, r);
    return r;
}
int open(const char *path, int flags, ...)
{
    mode_t mode = 0;
    if (flags & O_CREAT) { va_list ap; va_start(ap, flags); mode = va_arg(ap, int); va_end(ap); }
    return do_open(path, flags, mode);
}

int open64(const char *path, int flags, ...)
{
    mode_t mode = 0;
    if (flags & O_CREAT) { va_list ap; va_start(ap, flags); mode = va_arg(ap, int); va_end(ap); }
    return do_open(path, flags | O_LARGEFILE, mode);
}

int close(int fd)
{
    resolve();
    if (is_ours(fd)) { ours[fd] = 0; devpos[fd] = 0; }
    if (fd >= 0 && fd < 1024 && outfd[fd]) { outfd[fd] = 0; LOG("OUT close(%d)", fd); }
    if (fd >= 0 && fd < 1024 && streamfd[fd]) { streamfd[fd] = 0; LOG("STREAM close(%d) after %lld bytes", fd, stream_pos[fd]); }
    return real_close(fd);
}

int ioctl(int fd, unsigned long req, ...)
{
    resolve(); init_log();
    va_list ap; va_start(ap, req); void *arg = va_arg(ap, void *); va_end(ap);
    if (!is_ours(fd)) return real_ioctl(fd, req, arg);
    switch (req) {
    case SG_IO:
        return do_sg_io(arg);
    case SG_GET_VERSION_NUM:
        *(int *)arg = 30536;
        return 0;
    case SG_GET_TIMEOUT:
        return 60 * 100;
    case SG_SET_TIMEOUT: case SG_SET_RESERVED_SIZE: case SG_SET_COMMAND_Q: case SG_SET_FORCE_PACK_ID:
        return 0;
    case SG_GET_RESERVED_SIZE:
        *(int *)arg = 256 * 1024;
        return 0;
    case SG_EMULATED_HOST:
        *(int *)arg = 1;
        return 0;
    case SG_GET_SCSI_ID: {
        struct sg_scsi_id *id = arg;
        memset(id, 0, sizeof(*id));
        id->scsi_type = 5; /* CD/DVD */
        id->h_cmd_per_lun = 1;
        id->d_queue_depth = 1;
        return 0;
    }
    default:
        LOG("ioctl(%d, 0x%lx) not handled", fd, req);
        errno = EINVAL;
        return -1;
    }
}

int stat64(const char *path, struct stat64 *st)
{
    static int (*real)(const char *, struct stat64 *);
    if (!real) real = dlsym(RTLD_NEXT, "stat64");
    init_log();
    if (is_dev(path)) { fake_stat(st); LOG("stat(%s) faked", path); return 0; }
    return real(path, st);
}

int fstat64(int fd, struct stat64 *st)
{
    static int (*real)(int, struct stat64 *);
    if (!real) real = dlsym(RTLD_NEXT, "fstat64");
    if (is_ours(fd)) { fake_stat(st); return 0; }
    return real(fd, st);
}

int access(const char *path, int mode)
{
    static int (*real)(const char *, int);
    if (!real) real = dlsym(RTLD_NEXT, "access");
    if (is_dev(path)) return 0;
    return real(path, mode);
}

DIR *opendir(const char *path)
{
    static DIR *(*real)(const char *);
    if (!real) real = dlsym(RTLD_NEXT, "opendir");
    init_log();
    DIR *d = real(path);
    if (logging > 1 && probed(path)) LOG("opendir(%s) = %p", path, (void *)d);
    return d;
}

ssize_t readlink(const char *path, char *buf, size_t n)
{
    static ssize_t (*real)(const char *, char *, size_t);
    if (!real) real = dlsym(RTLD_NEXT, "readlink");
    init_log();
    /* started through ld.so, the program would see the loader as itself and look for its data
       next to it: MMSHIM_EXE names the program instead */
    const char *exe = getenv("MMSHIM_EXE");
    if (exe && !strcmp(path, "/proc/self/exe")) {
        size_t l = strlen(exe);
        if (l > n) l = n;
        memcpy(buf, exe, l);
        return (ssize_t)l;
    }
    ssize_t r = real(path, buf, n);
    if (logging > 1 && probed(path) && strcmp(path, "/proc/self/exe")) LOG("readlink(%s) = %zd", path, r);
    return r;
}

/* ---- output file tracing (MMSHIM_OUT) ---- */
static int is_outfd(int fd) { return fd >= 0 && fd < 1024 && outfd[fd]; }
static int is_streamfd(int fd) { return fd >= 0 && fd < 1024 && streamfd[fd]; }

/* all of buf into the pipe */
/*
 * Back-pressure. MakeMKV reads the disc as fast as the drive goes and keeps in memory what it cannot write
 * yet, without a limit: on a television with 1 GB it grew by 200 MB in ten seconds once the player's cache
 * was full. So: while the stream's writer has been waiting for the pipe for a while (nothing taken for
 * 150 ms), the drive is not read; reading goes on as soon as the player takes data again.
 */

static int write_all(int fd, const char *buf, size_t n)
{
    static ssize_t (*real)(int, const void *, size_t);
    if (!real) real = dlsym(RTLD_NEXT, "write");
    int stream = fd >= 0 && fd < 1024 && streamfd[fd];
    if (stream) { w_progress_ms = now_ms(); w_busy = 1; }
    while (n) {
        ssize_t w = real(fd, buf, n);
        if (w < 0) { if (errno == EINTR) continue; if (stream) w_busy = 0; return -1; }
        buf += w; n -= w;
        if (stream) w_progress_ms = now_ms();
    }
    if (stream) w_busy = 0;
    return 0;
}


/*
 * MakeMKV writes the file in order and finalizes it at the end: the first bytes are a placeholder
 * ("This file was not properly finalized"), the clusters at its 32 MB buffer boundaries carry size 0 and
 * the duration is 0 until then. For a stream: a real EBML header, an unknown-sized Segment and a Void up
 * to the first element (Info), clusters of unknown size, and the duration from MMSHIM_DURATION_MS.
 */
static const unsigned char ebml_head[] = {
    0x1a,0x45,0xdf,0xa3,0xa3,0x42,0x86,0x81,0x01,0x42,0xf7,0x81,0x01,0x42,0xf2,0x81,0x04,0x42,0xf3,0x81,0x08,
    0x42,0x82,0x88,0x6d,0x61,0x74,0x72,0x6f,0x73,0x6b,0x61,0x42,0x87,0x81,0x04,0x42,0x85,0x81,0x02,
    0x18,0x53,0x80,0x67,0x01,0xff,0xff,0xff,0xff,0xff,0xff,0xff };

static const unsigned char *find(const unsigned char *h, size_t hn, const unsigned char *nd, size_t nn)
{
    if (hn < nn) return NULL;
    for (size_t i = 0; i + nn <= hn; ++i)
        if (h[i] == nd[0] && !memcmp(h + i, nd, nn)) return h + i;
    return NULL;
}

static int stream_chunk(int fd, const void *vbuf, size_t n, long long off)
{
    const unsigned char *buf = vbuf;
    static const unsigned char info_id[] = { 0x15, 0x49, 0xa9, 0x66 };
    static const unsigned char zero_cluster[] = { 0x1f, 0x43, 0xb6, 0x75, 0x10, 0x00, 0x00, 0x00 };
    static const unsigned char unknown_cluster[] = { 0x1f, 0x43, 0xb6, 0x75, 0x1f, 0xff, 0xff, 0xff };
    size_t done = 0;
    if (off == 0) {
        const unsigned char *info = find(buf, n < 65536 ? n : 65536, info_id, 4);
        size_t first = info ? (size_t)(info - buf) : 0;
        if (first >= sizeof(ebml_head) + 9) {
            unsigned char *head = calloc(1, first + 512);
            if (!head) return -1;
            memcpy(head, ebml_head, sizeof(ebml_head));
            size_t pay = first - sizeof(ebml_head) - 9;
            unsigned char *v = head + sizeof(ebml_head);
            v[0] = 0xec; v[1] = 0x01;
            for (int i = 0; i < 7; ++i) v[2 + i] = (unsigned char)(pay >> (8 * (6 - i)));
            /* Info up to its Duration (0x4489, 4 bytes) with the real length */
            size_t tail = n - first < 400 ? n - first : 400;
            memcpy(head + first, buf + first, tail);
            const char *dur = getenv("MMSHIM_DURATION_MS");
            const unsigned char dur_id[] = { 0x44, 0x89, 0x84 };
            unsigned char *d = (unsigned char *)find(head + first, tail, dur_id, 3);
            if (dur && d) {
                float f = (float)atof(dur);
                uint32_t u; memcpy(&u, &f, 4);
                d[3] = u >> 24; d[4] = u >> 16; d[5] = u >> 8; d[6] = u;
            }
            if (write_all(fd, (const char *)head, first + tail) < 0) { free(head); return -1; }
            free(head);
            LOG("STREAM header made: %zu bytes before Info, duration %s", first, dur ? dur : "unknown");
            done = first + tail;
        }
    }
    while (done < n) {
        const unsigned char *z = find(buf + done, n - done, zero_cluster, sizeof(zero_cluster));
        size_t upto = z ? (size_t)(z - buf) : n;
        if (write_all(fd, (const char *)buf + done, upto - done) < 0) return -1;
        done = upto;
        if (z) {
            if (write_all(fd, (const char *)unknown_cluster, sizeof(unknown_cluster)) < 0) return -1;
            done += sizeof(zero_cluster);
            LOG("STREAM cluster at %lld made unknown-sized", off + (long long)upto);
        }
    }
    return 0;
}

ssize_t pwrite64(int fd, const void *buf, size_t n, off64_t off)
{
    static ssize_t (*real)(int, const void *, size_t, off64_t);
    if (!real) real = dlsym(RTLD_NEXT, "pwrite64");
    if (is_streamfd(fd)) {
        if (off == stream_pos[fd]) {
            if (stream_chunk(fd, buf, n, off) < 0) { LOG("STREAM pipe closed: %s", strerror(errno)); return -1; }
            stream_pos[fd] += n;
        } else if (off < stream_pos[fd]) {
            LOG("STREAM fix-up of %zu bytes at %lld dropped", n, (long long)off);
        } else {
            LOG("STREAM gap: write at %lld, stream at %lld", (long long)off, stream_pos[fd]);
            errno = EIO; return -1;
        }
        return n;
    }
    ssize_t r = real(fd, buf, n, off);
    if (is_outfd(fd)) LOG("OUT pwrite64(%d, %zu, %lld) = %zd", fd, n, (long long)off, r);
    return r;
}

ssize_t write(int fd, const void *buf, size_t n)
{
    static ssize_t (*real)(int, const void *, size_t);
    if (!real) real = dlsym(RTLD_NEXT, "write");
    if (is_streamfd(fd)) {
        if (write_all(fd, buf, n) < 0) return -1;
        stream_pos[fd] += n;
        return n;
    }
    ssize_t r = real(fd, buf, n);
    if (is_outfd(fd)) LOG("OUT write(%d, %zu) = %zd", fd, n, r);
    return r;
}

/*
 * The fake drive as a file: reading the disc's sectors (libbluray and libudfread open the device and read it like a file).
 * A read is READ(10) over the same bulk transfers as SG_IO.
 */
static long long devsize = -1;

static long long dev_size(void)
{
    if (devsize >= 0) return devsize;
    uint8_t cdb[10] = { 0x25 }, d[8] = { 0 };
    unsigned done = 0;
    pthread_mutex_lock(&lock);
    int st = bot(cdb, 10, 1, d, 8, 5000, &done);
    pthread_mutex_unlock(&lock);
    if (st != 0 || done < 8) return 0;
    uint32_t last = (uint32_t)d[0] << 24 | d[1] << 16 | d[2] << 8 | d[3];
    uint32_t bs = (uint32_t)d[4] << 24 | d[5] << 16 | d[6] << 8 | d[7];
    devsize = ((long long)last + 1) * bs;
    LOG("device size %lld bytes", devsize);
    return devsize;
}

static ssize_t dev_read(void *vbuf, size_t n, long long pos)
{
    uint8_t *buf = vbuf;
    size_t got = 0;
    long long size = dev_size();
    if (pos >= size) return 0;
    if ((long long)n > size - pos) n = (size_t)(size - pos);
    static uint8_t blocks[32 * 2048];
    while (got < n) {
        long long lba = (pos + got) / 2048;
        size_t skip = (size_t)((pos + got) % 2048);
        unsigned count = (unsigned)((skip + (n - got) + 2047) / 2048);
        if (count > 32) count = 32;
        uint8_t cdb[10] = { 0x28, 0, (uint8_t)(lba >> 24), (uint8_t)(lba >> 16), (uint8_t)(lba >> 8), (uint8_t)lba, 0, (uint8_t)(count >> 8), (uint8_t)count, 0 };
        unsigned done = 0;
        pthread_mutex_lock(&lock);
        int st = bot(cdb, 10, 1, blocks, count * 2048, 30000, &done);
        pthread_mutex_unlock(&lock);
        if (st != 0 && done == 0) { if (got) break; errno = EIO; return -1; }
        size_t avail = done > skip ? done - skip : 0;
        size_t take = avail < n - got ? avail : n - got;
        if (!take) break;
        memcpy(buf + got, blocks + skip, take);
        got += take;
        if (done < count * 2048) break;
    }
    return (ssize_t)got;
}

ssize_t read(int fd, void *buf, size_t n)
{
    static ssize_t (*real)(int, void *, size_t);
    if (!real) real = dlsym(RTLD_NEXT, "read");
    if (is_ours(fd)) {
        if (connect_app() < 0) { errno = ENODEV; return -1; }
        ssize_t r = dev_read(buf, n, devpos[fd]);
        if (r > 0) devpos[fd] += r;
        return r;
    }
    return real(fd, buf, n);
}

ssize_t pread64(int fd, void *buf, size_t n, off64_t off);
ssize_t pread64(int fd, void *buf, size_t n, off64_t off)
{
    static ssize_t (*real)(int, void *, size_t, off64_t);
    if (!real) real = dlsym(RTLD_NEXT, "pread64");
    if (is_ours(fd)) return dev_read(buf, n, off);
    return real(fd, buf, n, off);
}

off64_t lseek64(int fd, off64_t off, int whence)
{
    static off64_t (*real)(int, off64_t, int);
    if (!real) real = dlsym(RTLD_NEXT, "lseek64");
    if (is_ours(fd)) {
        long long np = whence == SEEK_CUR ? devpos[fd] + off : whence == SEEK_END ? dev_size() + off : off;
        if (np < 0) { errno = EINVAL; return -1; }
        return devpos[fd] = np;
    }
    if (is_streamfd(fd)) return whence == SEEK_CUR ? stream_pos[fd] + off : whence == SEEK_SET ? off : stream_pos[fd];
    off64_t r = real(fd, off, whence);
    if (is_outfd(fd)) LOG("OUT lseek64(%d, %lld, %d) = %lld", fd, (long long)off, whence, (long long)r);
    return r;
}

int ftruncate64(int fd, off64_t len)
{
    static int (*real)(int, off64_t);
    if (!real) real = dlsym(RTLD_NEXT, "ftruncate64");
    if (is_streamfd(fd)) return 0;
    int r = real(fd, len);
    if (is_outfd(fd)) LOG("OUT ftruncate64(%d, %lld) = %d", fd, (long long)len, r);
    return r;
}

int fsync(int fd)
{
    static int (*real)(int);
    if (!real) real = dlsym(RTLD_NEXT, "fsync");
    if (is_streamfd(fd)) return 0;
    return real(fd);
}

int ftruncate(int fd, off_t len)
{
    static int (*real)(int, off_t);
    if (!real) real = dlsym(RTLD_NEXT, "ftruncate");
    if (is_streamfd(fd)) return 0;
    return real(fd, len);
}

off_t lseek(int fd, off_t off, int whence)
{
    static off_t (*real)(int, off_t, int);
    if (!real) real = dlsym(RTLD_NEXT, "lseek");
    if (is_streamfd(fd) || is_ours(fd)) return (off_t)lseek64(fd, off, whence);
    return real(fd, off, whence);
}
