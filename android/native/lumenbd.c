/*
 * JNI bridge to libbluray for Lumen TV: the disc's files come from the app (UDF reader, decrypted
 * streams), the stream goes to the player as plain 188-byte transport packets, the menu overlay as ARGB.
 */
#include <jni.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <android/log.h>

#include "libbluray/bluray.h"
#include "libbluray/keys.h"
#include "libbluray/decoders/overlay.h"
#include "file/filesystem.h"
#include "util/log_control.h"

#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "LumenBD", __VA_ARGS__)

static JavaVM *g_vm;
static jobject g_fs;          /* Bd.Fs */
static jobject g_bd;          /* the Bd object (overlay callbacks) */
static jmethodID m_open, m_size, m_read, m_close, m_list, m_overlay;
static BLURAY *g_bluray;
static pthread_mutex_t g_ov_lock = PTHREAD_MUTEX_INITIALIZER;
static int g_ov_w, g_ov_h;
static int64_t g_off;            /* added to every time stamp, 90 kHz, modulo 2^33 */
static int64_t g_last_pcr;       /* last PCR after shifting, 90 kHz */
static int g_have_pcr;

static JNIEnv *env_get(int *attached)
{
    JNIEnv *env = NULL;
    *attached = 0;
    if ((*g_vm)->GetEnv(g_vm, (void **)&env, JNI_VERSION_1_6) == JNI_OK) return env;
    if ((*g_vm)->AttachCurrentThread(g_vm, &env, NULL) == JNI_OK) { *attached = 1; return env; }
    return NULL;
}
static void env_put(int attached) { if (attached) (*g_vm)->DetachCurrentThread(g_vm); }

/* ---- files ---- */
struct jfile { int handle; int64_t pos; int64_t size; };

static void jf_close(BD_FILE_H *f)
{
    struct jfile *j = f->internal;
    int a; JNIEnv *env = env_get(&a);
    if (env) { (*env)->CallVoidMethod(env, g_fs, m_close, j->handle); env_put(a); }
    free(j); free(f);
}
static int64_t jf_seek(BD_FILE_H *f, int64_t off, int32_t origin)
{
    struct jfile *j = f->internal;
    int64_t p = origin == 0 ? off : origin == 1 ? j->pos + off : j->size + off;
    if (p < 0) return -1;
    j->pos = p;
    return p;
}
static int64_t jf_tell(BD_FILE_H *f) { return ((struct jfile *)f->internal)->pos; }
static int64_t jf_read(BD_FILE_H *f, uint8_t *buf, int64_t size)
{
    struct jfile *j = f->internal;
    if (size <= 0) return 0;
    if (j->pos >= j->size) return 0;
    int a; JNIEnv *env = env_get(&a);
    if (!env) return -1;
    int64_t total = 0;
    int chunk = size > (1 << 20) ? (1 << 20) : (int)size;
    jbyteArray arr = (*env)->NewByteArray(env, chunk);
    while (total < size && j->pos < j->size) {
        int want = (size - total) > chunk ? chunk : (int)(size - total);
        int n = (*env)->CallIntMethod(env, g_fs, m_read, j->handle, (jlong)j->pos, arr, 0, want);
        if (n <= 0) break;
        (*env)->GetByteArrayRegion(env, arr, 0, n, (jbyte *)(buf + total));
        total += n; j->pos += n;
    }
    (*env)->DeleteLocalRef(env, arr);
    env_put(a);
    return total;
}
static int64_t jf_write(BD_FILE_H *f, const uint8_t *buf, int64_t size) { return -1; }

static BD_FILE_H *open_file(void *handle, const char *rel_path)
{
    int a; JNIEnv *env = env_get(&a);
    if (!env) return NULL;
    jstring p = (*env)->NewStringUTF(env, rel_path);
    jint h = (*env)->CallIntMethod(env, g_fs, m_open, p);
    (*env)->DeleteLocalRef(env, p);
    BD_FILE_H *f = NULL;
    if (h >= 0) {
        struct jfile *j = calloc(1, sizeof(*j));
        j->handle = h;
        j->size = (*env)->CallLongMethod(env, g_fs, m_size, h);
        f = calloc(1, sizeof(*f));
        f->internal = j; f->close = jf_close; f->seek = jf_seek; f->tell = jf_tell; f->read = jf_read; f->write = jf_write;
    }
    env_put(a);
    return f;
}

/* ---- directories ---- */
struct jdir { char **names; int count, at; };
static void jd_close(BD_DIR_H *d)
{
    struct jdir *j = d->internal;
    for (int i = 0; i < j->count; i++) free(j->names[i]);
    free(j->names); free(j); free(d);
}
static int jd_read(BD_DIR_H *d, BD_DIRENT *e)
{
    struct jdir *j = d->internal;
    if (j->at >= j->count) return 1;                  /* end */
    const char *n = j->names[j->at++];
    strncpy(e->d_name, n, sizeof(e->d_name) - 1);
    e->d_name[sizeof(e->d_name) - 1] = 0;
    return 0;
}
static BD_DIR_H *open_dir(void *handle, const char *rel_path)
{
    int a; JNIEnv *env = env_get(&a);
    if (!env) return NULL;
    jstring p = (*env)->NewStringUTF(env, rel_path);
    jobjectArray arr = (jobjectArray)(*env)->CallObjectMethod(env, g_fs, m_list, p);
    (*env)->DeleteLocalRef(env, p);
    BD_DIR_H *d = NULL;
    if (arr) {
        int n = (*env)->GetArrayLength(env, arr);
        struct jdir *j = calloc(1, sizeof(*j));
        j->names = calloc(n ? n : 1, sizeof(char *));
        j->count = n;
        for (int i = 0; i < n; i++) {
            jstring s = (*env)->GetObjectArrayElement(env, arr, i);
            const char *u = (*env)->GetStringUTFChars(env, s, NULL);
            j->names[i] = strdup(u);
            (*env)->ReleaseStringUTFChars(env, s, u);
            (*env)->DeleteLocalRef(env, s);
        }
        (*env)->DeleteLocalRef(env, arr);
        d = calloc(1, sizeof(*d));
        d->internal = j; d->close = jd_close; d->read = jd_read;
    }
    env_put(a);
    return d;
}

/* ---- overlay: libbluray's compressed (RLE, palette) HDMV graphics, drawn into two ARGB planes ---- */
static uint32_t *g_plane[2];
static int g_dirty[2][4];     /* x0, y0, x1, y1; x1 < x0: nothing */

static void dirty_reset(int p) { g_dirty[p][0] = g_ov_w; g_dirty[p][1] = g_ov_h; g_dirty[p][2] = -1; g_dirty[p][3] = -1; }
static void dirty_add(int p, int x, int y, int w, int h)
{
    if (x < g_dirty[p][0]) g_dirty[p][0] = x;
    if (y < g_dirty[p][1]) g_dirty[p][1] = y;
    if (x + w - 1 > g_dirty[p][2]) g_dirty[p][2] = x + w - 1;
    if (y + h - 1 > g_dirty[p][3]) g_dirty[p][3] = y + h - 1;
}
static inline uint8_t clamp8(int v) { return v < 0 ? 0 : v > 255 ? 255 : (uint8_t)v; }
static uint32_t ycc(const BD_PG_PALETTE_ENTRY *e)
{
    int y = e->Y - 16, cr = e->Cr - 128, cb = e->Cb - 128;
    int r = (298 * y + 459 * cr + 128) >> 8;
    int g = (298 * y - 55 * cb - 136 * cr + 128) >> 8;
    int b = (298 * y + 541 * cb + 128) >> 8;
    return ((uint32_t)e->T << 24) | ((uint32_t)clamp8(r) << 16) | ((uint32_t)clamp8(g) << 8) | clamp8(b);
}

static void overlay_cb(void *handle, const struct bd_overlay_s *const ev)
{
    if (!ev) return;                              /* NULL: the overlay is closed */
    int p = ev->plane < 2 ? ev->plane : 0;
    switch (ev->cmd) {
    case BD_OVERLAY_INIT:
        pthread_mutex_lock(&g_ov_lock);
        if (!g_plane[0]) {
            g_ov_w = ev->w ? ev->w : 1920; g_ov_h = ev->h ? ev->h : 1080;
            g_plane[0] = calloc((size_t)g_ov_w * g_ov_h, 4);
            g_plane[1] = calloc((size_t)g_ov_w * g_ov_h, 4);
            dirty_reset(0); dirty_reset(1);
        }
        pthread_mutex_unlock(&g_ov_lock);
        break;
    case BD_OVERLAY_CLOSE:
    case BD_OVERLAY_CLEAR:
    case BD_OVERLAY_HIDE: {
        pthread_mutex_lock(&g_ov_lock);
        if (g_plane[p]) memset(g_plane[p], 0, (size_t)g_ov_w * g_ov_h * 4);
        dirty_reset(p);
        pthread_mutex_unlock(&g_ov_lock);
        int a; JNIEnv *env = env_get(&a);
        if (env) { (*env)->CallVoidMethod(env, g_bd, m_overlay, (jint)p, -1, -1, -1, -1); env_put(a); }
        break; }
    case BD_OVERLAY_WIPE:
        pthread_mutex_lock(&g_ov_lock);
        if (g_plane[p]) {
            for (int y = ev->y; y < ev->y + ev->h && y < g_ov_h; y++)
                for (int x = ev->x; x < ev->x + ev->w && x < g_ov_w; x++) g_plane[p][(size_t)y * g_ov_w + x] = 0;
            dirty_add(p, ev->x, ev->y, ev->w, ev->h);
        }
        pthread_mutex_unlock(&g_ov_lock);
        break;
    case BD_OVERLAY_DRAW:
        if (!ev->img || !ev->palette) break;
        pthread_mutex_lock(&g_ov_lock);
        if (g_plane[p]) {
            uint32_t pal[256];
            for (int i = 0; i < 256; i++) pal[i] = ycc(&ev->palette[i]);
            int x = 0, y = 0;
            for (const BD_PG_RLE_ELEM *r = ev->img; y < ev->h; r++) {
                uint32_t c = pal[r->color & 0xff];
                for (int n = 0; n < r->len && y < ev->h; n++) {
                    int dx = ev->x + x, dy = ev->y + y;
                    if (dx < g_ov_w && dy < g_ov_h) g_plane[p][(size_t)dy * g_ov_w + dx] = c;
                    if (++x >= ev->w) { x = 0; y++; }
                }
            }
            dirty_add(p, ev->x, ev->y, ev->w, ev->h);
        }
        pthread_mutex_unlock(&g_ov_lock);
        break;
    case BD_OVERLAY_FLUSH: {
        int a; JNIEnv *env = env_get(&a);
        for (int q = 0; q < 2 && env; q++) {
            pthread_mutex_lock(&g_ov_lock);
            int x0 = g_dirty[q][0], y0 = g_dirty[q][1], x1 = g_dirty[q][2], y1 = g_dirty[q][3];
            dirty_reset(q);
            pthread_mutex_unlock(&g_ov_lock);
            if (x1 >= x0 && y1 >= y0) (*env)->CallVoidMethod(env, g_bd, m_overlay, (jint)q, x0, y0, x1, y1);
        }
        if (env) env_put(a);
        break; }
    default: break;
    }
}

static void dbg(const char *msg)
{
    size_t n = strlen(msg);
    char line[512];
    strncpy(line, msg, sizeof(line) - 1); line[sizeof(line) - 1] = 0;
    if (n && line[strlen(line) - 1] == '\n') line[strlen(line) - 1] = 0;
    __android_log_print(ANDROID_LOG_DEBUG, "libbluray", "%s", line);
}

/* ---- JNI ---- */
JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) { g_vm = vm; return JNI_VERSION_1_6; }

JNIEXPORT jboolean JNICALL Java_io_github_lumen_tv_Bd_nOpen(JNIEnv *env, jobject self, jobject fs)
{
    g_fs = (*env)->NewGlobalRef(env, fs);
    g_bd = (*env)->NewGlobalRef(env, self);
    jclass c = (*env)->GetObjectClass(env, fs);
    m_open  = (*env)->GetMethodID(env, c, "openFile", "(Ljava/lang/String;)I");
    m_size  = (*env)->GetMethodID(env, c, "size", "(I)J");
    m_read  = (*env)->GetMethodID(env, c, "read", "(IJ[BII)I");
    m_close = (*env)->GetMethodID(env, c, "close", "(I)V");
    m_list  = (*env)->GetMethodID(env, c, "list", "(Ljava/lang/String;)[Ljava/lang/String;");
    jclass bc = (*env)->GetObjectClass(env, self);
    m_overlay = (*env)->GetMethodID(env, bc, "onOverlay", "(IIIII)V");
    g_off = 0; g_have_pcr = 0;
    bd_set_debug_handler(dbg);
    bd_set_debug_mask(DBG_CRIT);
    g_bluray = bd_init();
    if (!g_bluray) return JNI_FALSE;
    if (!bd_open_files(g_bluray, NULL, open_dir, open_file)) { bd_close(g_bluray); g_bluray = NULL; return JNI_FALSE; }
    bd_register_overlay_proc(g_bluray, NULL, overlay_cb);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL Java_io_github_lumen_tv_Bd_nClose(JNIEnv *env, jobject self)
{
    if (g_bluray) { bd_register_overlay_proc(g_bluray, NULL, NULL); bd_close(g_bluray); g_bluray = NULL; }
    pthread_mutex_lock(&g_ov_lock);
    free(g_plane[0]); free(g_plane[1]); g_plane[0] = g_plane[1] = NULL;
    pthread_mutex_unlock(&g_ov_lock);
    if (g_fs) { (*env)->DeleteGlobalRef(env, g_fs); g_fs = NULL; }
    if (g_bd) { (*env)->DeleteGlobalRef(env, g_bd); g_bd = NULL; }
}

JNIEXPORT jstring JNICALL Java_io_github_lumen_tv_Bd_nInfo(JNIEnv *env, jobject self)
{
    char out[1024];
    if (!g_bluray) return (*env)->NewStringUTF(env, "{}");
    const BLURAY_DISC_INFO *di = bd_get_disc_info(g_bluray);
    if (!di) return (*env)->NewStringUTF(env, "{}");
    snprintf(out, sizeof(out),
        "{\"titles\":%u,\"hdmv\":%u,\"bdj\":%u,\"firstPlaySupported\":%d,\"topMenuSupported\":%d,"
        "\"aacs\":%d,\"aacsHandled\":%d,\"bdplus\":%d,\"bdplusHandled\":%d,\"bdjDetected\":%d}",
        di->num_titles, di->num_hdmv_titles, di->num_bdj_titles, di->first_play_supported, di->top_menu_supported,
        di->aacs_detected, di->aacs_handled, di->bdplus_detected, di->bdplus_handled, di->bdj_detected);
    return (*env)->NewStringUTF(env, out);
}

JNIEXPORT jboolean JNICALL Java_io_github_lumen_tv_Bd_nPlay(JNIEnv *env, jobject self) { return g_bluray && bd_play(g_bluray); }
JNIEXPORT jboolean JNICALL Java_io_github_lumen_tv_Bd_nPlayTitle(JNIEnv *env, jobject self, jint t) { return g_bluray && bd_play_title(g_bluray, (unsigned)t); }
JNIEXPORT jboolean JNICALL Java_io_github_lumen_tv_Bd_nMenu(JNIEnv *env, jobject self) { return g_bluray && bd_menu_call(g_bluray, -1); }
JNIEXPORT jint JNICALL Java_io_github_lumen_tv_Bd_nKey(JNIEnv *env, jobject self, jint key)
{
    if (!g_bluray) return 0;
    bd_user_input(g_bluray, -1, (uint32_t)key);
    return 1;
}
JNIEXPORT jlong JNICALL Java_io_github_lumen_tv_Bd_nTime(JNIEnv *env, jobject self) { return g_bluray ? (jlong)bd_tell_time(g_bluray) : 0; }
JNIEXPORT jlong JNICALL Java_io_github_lumen_tv_Bd_nSeekTime(JNIEnv *env, jobject self, jlong t) { return g_bluray ? (jlong)bd_seek_time(g_bluray, (uint64_t)t) : -1; }
JNIEXPORT jint JNICALL Java_io_github_lumen_tv_Bd_nSkipStill(JNIEnv *env, jobject self) { return g_bluray ? bd_read_skip_still(g_bluray) : 0; }

/* events as (type << 32 | value); 0 = none */
JNIEXPORT jlong JNICALL Java_io_github_lumen_tv_Bd_nEvent(JNIEnv *env, jobject self)
{
    BD_EVENT ev;
    if (!g_bluray || !bd_get_event(g_bluray, &ev) || ev.event == BD_EVENT_NONE) return 0;
    return ((jlong)ev.event << 32) | (uint32_t)ev.param;
}

/* ---- continuous time: the clock of each clip starts anew (a menu loop, the next play item); the player
 * wants one timeline, so PCR and PTS/DTS are shifted by an offset that is re-computed at every jump ---- */
#define MOD33 ((int64_t)1 << 33)

static int64_t ts_read33(const uint8_t *p)        /* 5-byte PES time stamp */
{
    return ((int64_t)(p[0] & 0x0e) << 29) | ((int64_t)p[1] << 22) | ((int64_t)(p[2] & 0xfe) << 14) |
           ((int64_t)p[3] << 7) | (p[4] >> 1);
}
static void ts_write33(uint8_t *p, int64_t v, int prefix)
{
    p[0] = (uint8_t)((prefix << 4) | (((v >> 30) & 7) << 1) | 1);
    p[1] = (uint8_t)(v >> 22);
    p[2] = (uint8_t)((((v >> 15) & 0x7f) << 1) | 1);
    p[3] = (uint8_t)(v >> 7);
    p[4] = (uint8_t)(((v & 0x7f) << 1) | 1);
}

static void retime(uint8_t *buf, int len)
{
    for (int o = 0; o + 188 <= len; o += 188) {
        uint8_t *p = buf + o;
        if (p[0] != 0x47) continue;
        int afc = (p[3] >> 4) & 3;
        int at = 4;
        if (afc & 2) {
            int alen = p[4];
            if ((afc & 2) && alen >= 7 && (p[5] & 0x10)) {                 /* PCR */
                int64_t base = ((int64_t)p[6] << 25) | ((int64_t)p[7] << 17) | ((int64_t)p[8] << 9) | ((int64_t)p[9] << 1) | (p[10] >> 7);
                int64_t adj = (base + g_off) % MOD33;
                if (g_have_pcr) {
                    int64_t d = adj - g_last_pcr;
                    if (d > MOD33 / 2) d -= MOD33;
                    if (d < -MOD33 / 2) d += MOD33;
                    if (d < -45000 || d > 270000) {                            /* a jump: carry on from where we were */
                        g_off = ((g_last_pcr + 1800 - base) % MOD33 + MOD33) % MOD33;
                        adj = (base + g_off) % MOD33;
                    }
                }
                g_last_pcr = adj; g_have_pcr = 1;
                p[6] = (uint8_t)(adj >> 25); p[7] = (uint8_t)(adj >> 17); p[8] = (uint8_t)(adj >> 9); p[9] = (uint8_t)(adj >> 1);
                p[10] = (uint8_t)((p[10] & 0x7f) | ((adj & 1) << 7));
            }
            at = 5 + alen;
        }
        if ((p[1] & 0x40) && (afc & 1) && at + 14 <= 188 && p[at] == 0 && p[at + 1] == 0 && p[at + 2] == 1) {
            int flags = p[at + 7] >> 6;
            if (flags >= 2 && at + 14 <= 188) {
                int64_t pts = (ts_read33(p + at + 9) + g_off) % MOD33;
                ts_write33(p + at + 9, pts, flags == 3 ? 3 : 2);
                if (flags == 3 && at + 19 <= 188) {
                    int64_t dts = (ts_read33(p + at + 14) + g_off) % MOD33;
                    ts_write33(p + at + 14, dts, 1);
                }
            }
        }
    }
}

/* plain transport stream: the 192-byte source packets of a Blu-ray lose their 4-byte time stamps.
 * Returns the bytes written to [buf], 0 if there is nothing now (a still picture, a wait), -1 on error. */
JNIEXPORT jint JNICALL Java_io_github_lumen_tv_Bd_nRead(JNIEnv *env, jobject self, jbyteArray buf, jint off, jint len)
{
    static unsigned char tmp[192 * 32];
    static unsigned char out[188 * 32];
    BD_EVENT ev;
    if (!g_bluray) return -1;
    int packets = len / 188;
    if (packets > 32) packets = 32;
    if (packets < 1) return 0;
    /* libbluray returns whole aligned units (6144 bytes) at least */
    int n = bd_read_ext(g_bluray, tmp, 6144 > packets * 192 ? 6144 : packets * 192, &ev);
    if (n < 0) return -1;
    if (n == 0) return 0;
    int w = 0;
    for (int o = 0; o + 192 <= n && w + 188 <= (int)sizeof(out); o += 192) { memcpy(out + w, tmp + o + 4, 188); w += 188; }
    retime(out, w);
    (*env)->SetByteArrayRegion(env, buf, off, w, (jbyte *)out);
    return w;
}

/* the overlay: pixels of a rectangle of plane [p] as ints (A,R,G,B) */
JNIEXPORT jboolean JNICALL Java_io_github_lumen_tv_Bd_nOverlayPixels(JNIEnv *env, jobject self, jint p, jint x, jint y, jint w, jint h, jintArray out)
{
    if (p < 0 || p > 1 || !g_plane[p] || w <= 0 || h <= 0) return JNI_FALSE;
    pthread_mutex_lock(&g_ov_lock);
    for (int r = 0; r < h; r++)
        (*env)->SetIntArrayRegion(env, out, r * w, w, (const jint *)(g_plane[p] + (size_t)(y + r) * g_ov_w + x));
    pthread_mutex_unlock(&g_ov_lock);
    return JNI_TRUE;
}
JNIEXPORT jint JNICALL Java_io_github_lumen_tv_Bd_nOverlayWidth(JNIEnv *env, jobject self) { return g_ov_w; }
JNIEXPORT jint JNICALL Java_io_github_lumen_tv_Bd_nOverlayHeight(JNIEnv *env, jobject self) { return g_ov_h; }
