/*
 * bdmenu: plays a Blu-ray's menu with libbluray (decrypting through libmmbd, as MakeMKV does) and writes what the menu
 * draws, so that the names in it can be read (by the app) and tied to the streams the buttons choose.
 *
 *   bdmenu <device> <outdir>          then commands on stdin, one per line:
 *     key <name>      up down left right enter root popup back   (a remote control key)
 *     select <x> <y>  the button at that point of the 1920x1080 menu
 *     snap            write the whole menu picture (s<N>.raw)
 *     quit
 *   and on stdout, one per line:
 *     DISC ...        what libbluray found
 *     EVENT <name> <value>
 *     FLUSH <seq> <x> <y> <w> <h>   the menu changed in that rectangle (f<seq>.raw holds it)
 *     SNAP <seq> <file>
 *
 * A .raw file: "BDIG <w> <h> <x> <y>\n" then w*h*4 bytes RGBA (not premultiplied).
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/select.h>
#include <time.h>
#include <unistd.h>
#include <libbluray/bluray.h>
#include <libbluray/keys.h>
#include <libbluray/overlay.h>
#include <libbluray/log_control.h>

#define W 1920
#define H 1080

static uint32_t *plane[2];                 /* RGBA, 1920x1080, per plane */
static int dirty = 0, dx0, dy0, dx1, dy1;  /* what changed since the last flush */
static int seq = 0;
static const char *outdir = ".";

static void mark(int x, int y, int w, int h)
{
    if (!dirty) { dx0 = x; dy0 = y; dx1 = x + w; dy1 = y + h; dirty = 1; return; }
    if (x < dx0) dx0 = x;
    if (y < dy0) dy0 = y;
    if (x + w > dx1) dx1 = x + w;
    if (y + h > dy1) dy1 = y + h;
}

static uint32_t rgba(const BD_PG_PALETTE_ENTRY *e)
{
    /* BT.709, limited range */
    double y = (e->Y - 16) * 1.164, cb = e->Cb - 128, cr = e->Cr - 128;
    double r = y + 1.793 * cr, g = y - 0.213 * cb - 0.533 * cr, b = y + 2.112 * cb;
    int ri = r < 0 ? 0 : r > 255 ? 255 : (int)r, gi = g < 0 ? 0 : g > 255 ? 255 : (int)g, bi = b < 0 ? 0 : b > 255 ? 255 : (int)b;
    return (uint32_t)ri | (uint32_t)gi << 8 | (uint32_t)bi << 16 | (uint32_t)e->T << 24;
}

static void write_region(const char *name, int x, int y, int w, int h, int composite)
{
    char path[512];
    snprintf(path, sizeof(path), "%s/%s", outdir, name);
    FILE *f = fopen(path, "wb");
    if (!f) return;
    fprintf(f, "BDIG %d %d %d %d\n", w, h, x, y);
    for (int j = y; j < y + h; ++j)
        for (int i = x; i < x + w; ++i) {
            uint32_t p = plane[1][j * W + i];
            if (composite && (p >> 24) < 255) {
                /* the IG over the PG */
                uint32_t q = plane[0][j * W + i];
                unsigned a = p >> 24, qa = q >> 24;
                if (a == 0) p = q;
                else if (qa) {
                    unsigned r = ((p & 255) * a + (q & 255) * (255 - a)) / 255, g = (((p >> 8) & 255) * a + ((q >> 8) & 255) * (255 - a)) / 255,
                             b = (((p >> 16) & 255) * a + ((q >> 16) & 255) * (255 - a)) / 255;
                    p = r | g << 8 | b << 16 | 255u << 24;
                }
            }
            fwrite(&p, 4, 1, f);
        }
    fclose(f);
}

static void overlay_cb(void *handle, const BD_OVERLAY *ov)
{
    (void)handle;
    if (!ov) return;
    int pl = ov->plane > 1 ? 1 : ov->plane;
    switch (ov->cmd) {
    case BD_OVERLAY_INIT:
        if (!plane[pl]) plane[pl] = calloc((size_t)W * H, 4);
        break;
    case BD_OVERLAY_CLOSE:
    case BD_OVERLAY_CLEAR:
    case BD_OVERLAY_HIDE:
        if (plane[pl]) { memset(plane[pl], 0, (size_t)W * H * 4); mark(0, 0, W, H); }
        break;
    case BD_OVERLAY_WIPE:
        if (plane[pl]) {
            for (int j = ov->y; j < ov->y + ov->h && j < H; ++j) memset(plane[pl] + j * W + ov->x, 0, (size_t)(ov->w) * 4);
            mark(ov->x, ov->y, ov->w, ov->h);
        }
        break;
    case BD_OVERLAY_DRAW:
        if (!plane[pl] || !ov->img || !ov->palette) break;
        {
            uint32_t pal[256];
            for (int i = 0; i < 256; ++i) pal[i] = rgba(&ov->palette[i]);
            if (ov->palette_update_flag) break;            /* (only the colours changed: the next DRAW has the picture) */
            int x = ov->x, y = ov->y, n = 0, total = ov->w * ov->h;
            const BD_PG_RLE_ELEM *e = ov->img;
            while (n < total) {
                int len = e->len, col = e->color;
                if (len == 0 && col == 0) { e++; continue; }
                while (len-- > 0 && n < total) {
                    int px = x + n % ov->w, py = y + n / ov->w;
                    if (px < W && py < H) plane[pl][py * W + px] = pal[col & 255];
                    n++;
                }
                e++;
            }
            mark(ov->x, ov->y, ov->w, ov->h);
        }
        break;
    case BD_OVERLAY_FLUSH:
        if (dirty && plane[1]) {
            if (dx0 < 0) dx0 = 0;
            if (dy0 < 0) dy0 = 0;
            if (dx1 > W) dx1 = W;
            if (dy1 > H) dy1 = H;
            int w = dx1 - dx0, h = dy1 - dy0;
            if (w > 0 && h > 0) {
                char name[64];
                snprintf(name, sizeof(name), "f%04d.raw", ++seq);
                write_region(name, dx0, dy0, w, h, 1);
                printf("FLUSH %d %d %d %d %d\n", seq, dx0, dy0, w, h);
                fflush(stdout);
            }
            dirty = 0;
        }
        break;
    }
}

/* what libbluray says about the player's registers: PSR1 is the audio stream, PSR2 the subtitle stream (bit 31: shown) */
static void debug_cb(const char *msg)
{
    const char *p = strstr(msg, "bd_psr_write(): PSR");
    if (p && !strstr(msg, "no change")) { printf("PSRW %s", p + 15); if (!strchr(p, '\n')) printf("\n"); fflush(stdout); }
}

static uint32_t key_of(const char *n)
{
    if (!strcmp(n, "up")) return BD_VK_UP;
    if (!strcmp(n, "down")) return BD_VK_DOWN;
    if (!strcmp(n, "left")) return BD_VK_LEFT;
    if (!strcmp(n, "right")) return BD_VK_RIGHT;
    if (!strcmp(n, "enter")) return BD_VK_ENTER;
    if (!strcmp(n, "root")) return BD_VK_ROOT_MENU;
    if (!strcmp(n, "popup")) return BD_VK_POPUP;
    if (!strcmp(n, "back")) return BD_VK_POPUP;
    return 0xffffffffu;
}

static void event_name(const BD_EVENT *ev)
{
    const char *n = NULL;
    switch (ev->event) {
    case BD_EVENT_ERROR: n = "error"; break;
    case BD_EVENT_ENCRYPTED: n = "encrypted"; break;
    case BD_EVENT_SEEK: n = "seek"; break;
    case BD_EVENT_STILL: n = "still"; break;
    case BD_EVENT_STILL_TIME: n = "still_time"; break;
    case BD_EVENT_PLAYLIST: n = "playlist"; break;
    case BD_EVENT_PLAYITEM: n = "playitem"; break;
    case BD_EVENT_TITLE: n = "title"; break;
    case BD_EVENT_CHAPTER: n = "chapter"; break;
    case BD_EVENT_END_OF_TITLE: n = "end_of_title"; break;
    case BD_EVENT_AUDIO_STREAM: n = "audio_stream"; break;
    case BD_EVENT_IG_STREAM: n = "ig_stream"; break;
    case BD_EVENT_PG_TEXTST_STREAM: n = "pg_stream"; break;
    case BD_EVENT_PG_TEXTST: n = "pg_enabled"; break;
    case BD_EVENT_MENU: n = "menu"; break;
    case BD_EVENT_SOUND_EFFECT: n = "sound"; break;
    case BD_EVENT_IDLE: case BD_EVENT_NONE: return;
    default: break;
    }
    if (n) printf("EVENT %s %u\n", n, ev->param);
    else printf("EVENT %d %u\n", ev->event, ev->param);
    fflush(stdout);
}

static void command(BLURAY *bd, char *line)
{
    char a[64] = "";
    int x = 0, y = 0;
    if (sscanf(line, "key %63s", a) == 1) {
        uint32_t k = key_of(a);
        if (k != 0xffffffffu) { bd_user_input(bd, -1, k); bd_user_input(bd, -1, k | BD_VK_KEY_RELEASED); }
    } else if (sscanf(line, "select %d %d", &x, &y) == 2) {
        int r = bd_mouse_select(bd, -1, (uint16_t)x, (uint16_t)y);
        printf("SELECT %d %d %d\n", x, y, r);
        fflush(stdout);
    } else if (!strncmp(line, "snap", 4) && plane[1]) {
        char name[64];
        snprintf(name, sizeof(name), "s%04d.raw", ++seq);
        write_region(name, 0, 0, W, H, 1);
        printf("SNAP %d %s\n", seq, name);
        fflush(stdout);
    } else if (sscanf(line, "title %d", &x) == 1) {
        int r = bd_play_title(bd, (unsigned)x);
        printf("TITLE %d %d\n", x, r);
        fflush(stdout);
    } else if (!strncmp(line, "quit", 4)) {
        exit(0);
    }
}


/* ---------------------------------------------------------------------------------------------------------------
 * serve mode:  bdmenu <device> <outdir> serve <ts-fifo> <menu-lang>
 * The disc plays as a whole: the stream (plain transport packets, time stamps made continuous) goes into the fifo, the menu's
 * pictures are written per plane (g<seq>_<plane>.raw, "BDIG w h x y" + RGBA) and announced on stdout:
 *     READY | EVENT <name> <value> | FLUSH <seq> <plane> <x> <y> <w> <h> | CLEAR <plane> | SOUND <id> <channels> <frames> <file>
 * Commands on stdin: key <BD_VK number> | menu | title <n> | quit.
 * --------------------------------------------------------------------------------------------------------------- */
#include <signal.h>
#include <sys/stat.h>

#define MOD33 ((int64_t)1 << 33)
static int64_t g_off, g_last_pcr;
static int g_have_pcr, g_pend;
static int64_t g_last_pts[2];       /* the last picture / sound time given out */

static int64_t ts_read33(const uint8_t *p)
{
    return ((int64_t)(p[0] & 0x0e) << 29) | ((int64_t)p[1] << 22) | ((int64_t)(p[2] & 0xfe) << 14) | ((int64_t)p[3] << 7) | (p[4] >> 1);
}
static void ts_write33(uint8_t *p, int64_t v, int prefix)
{
    p[0] = (uint8_t)((prefix << 4) | (((v >> 30) & 7) << 1) | 1);
    p[1] = (uint8_t)(v >> 22);
    p[2] = (uint8_t)((((v >> 15) & 0x7f) << 1) | 1);
    p[3] = (uint8_t)(v >> 7);
    p[4] = (uint8_t)(((v & 0x7f) << 1) | 1);
}

/* continuous time: the clock of each clip starts anew (a menu loop, the next play item); the player wants one timeline */
static void retime(uint8_t *buf, int len)
{
    for (int o = 0; o + 188 <= len; o += 188) {
        uint8_t *p = buf + o;
        if (p[0] != 0x47) continue;
        int afc = (p[3] >> 4) & 3;
        int at = 4;
        if (afc & 2) {
            int alen = p[4];
            if (alen >= 7 && (p[5] & 0x10)) {
                int64_t base = ((int64_t)p[6] << 25) | ((int64_t)p[7] << 17) | ((int64_t)p[8] << 9) | ((int64_t)p[9] << 1) | (p[10] >> 7);
                int64_t adj = (base + g_off) % MOD33;
                if (g_have_pcr) {
                    int64_t d = adj - g_last_pcr;
                    if (d > MOD33 / 2) d -= MOD33;
                    if (d < -MOD33 / 2) d += MOD33;
                    if (d < -45000 || d > 270000) {
                        g_off = ((g_last_pcr + 1800 - base) % MOD33 + MOD33) % MOD33;
                        adj = (base + g_off) % MOD33;
                        g_pend = 1;         /* the first picture/sound time of the new clip sets the offset exactly (below) */
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
            if (flags >= 2) {
                int sid = p[at + 3], cls = (sid >= 0xe0 && sid <= 0xef) ? 0 : 1;
                int64_t raw = ts_read33(p + at + 9);
                if (g_pend && (g_last_pts[0] > 0 || g_last_pts[1] > 0)) {
                    /* the clip's pictures start further ahead of its clock than the last clip's ended: without this the player sees a hole of half a second in sound and picture (and its audio sink stops on it).
                       The new clip begins after the later end of picture and sound: set from the sound's end alone its pictures fell before the last clip's last ones, and the player dropped them as late */
                    int64_t end0 = g_last_pts[0] + 3750, end1 = g_last_pts[1] + 1000;
                    int64_t target = end0 > end1 ? end0 : end1;
                    g_off = ((target - raw) % MOD33 + MOD33) % MOD33;
                    g_pend = 0;
                }
                int64_t pts = (raw + g_off) % MOD33;
                if (pts > g_last_pts[cls] || g_last_pts[cls] - pts > MOD33 / 2) g_last_pts[cls] = pts;
                ts_write33(p + at + 9, pts, flags == 3 ? 3 : 2);
                if (flags == 3 && at + 19 <= 188) {
                    int64_t dts = (ts_read33(p + at + 14) + g_off) % MOD33;
                    ts_write33(p + at + 14, dts, 1);
                }
            }
        }
    }
}

/*
 * The player's transport stream reader knows the stream types of broadcast TV, not all those of a Blu-ray: DTS-HD (0x85, 0x86,
 * 0xa2) is passed as DTS (0x82: the core inside it is what plays), secondary Dolby Digital Plus (0xa1) and 0x84 as 0x87. The
 * PMT is changed in the stream and its CRC made again.
 */
static uint32_t crc32_mpeg(const uint8_t *d, int n)
{
    uint32_t c = 0xffffffffu;
    for (int i = 0; i < n; ++i) {
        c ^= (uint32_t)d[i] << 24;
        for (int k = 0; k < 8; ++k) c = (c & 0x80000000u) ? (c << 1) ^ 0x04c11db7u : (c << 1);
    }
    return c;
}
/* the audio PIDs of the PMT and the one the disc chose (see update_audio_pid) */
static int g_audio_pids[16], g_naudio, g_audio_pid;
static int is_audio_type(int st) { return st == 0x0f || st == 0x03 || st == 0x04 || (st >= 0x80 && st <= 0x87) || st == 0xa1 || st == 0xa2; }
static int is_audio_pid(int pid) { for (int i = 0; i < g_naudio; ++i) if (g_audio_pids[i] == pid) return 1; return 0; }
static int g_pmt_pid = -1, g_pmt_dn = -1;
static char g_pmt_desc[160];
static uint8_t g_pat[188];
static int g_have_pat;
static int map_type(int st) { return (st == 0x85 || st == 0x86 || st == 0xa2) ? 0x82 : (st == 0x84 || st == 0xa1) ? 0x87 : st; }
/* returns the offset of a PMT whose streams differ from the last one (the player cannot change a track's format: the stream is
   started anew there), or -1 */
static int fix_pmt(uint8_t *buf, int len)
{
    int cut = -1;
    for (int o = 0; o + 188 <= len; o += 188) {
        uint8_t *p = buf + o;
        if (p[0] != 0x47) continue;
        int pid = ((p[1] & 0x1f) << 8) | p[2];
        int pusi = p[1] & 0x40;
        if (pid == 0 && pusi && (p[3] & 0x10)) {
            memcpy(g_pat, p, 188); g_have_pat = 1;
            uint8_t *s = p + 5 + p[4];
            int sl = ((s[1] & 0xf) << 8) | s[2];
            if (s[0] == 0) for (int j = 8; j + 4 <= 3 + sl - 4 && j + 4 < 180; j += 4) if (((s[j] << 8) | s[j + 1]) != 0) { g_pmt_pid = ((s[j + 2] & 0x1f) << 8) | s[j + 3]; break; }
        } else if (pid == g_pmt_pid && pusi && (p[3] & 0x10)) {
            uint8_t *s = p + 5 + p[4];
            if (s[0] != 2) continue;
            int sl = ((s[1] & 0xf) << 8) | s[2];
            if (5 + p[4] + 3 + sl > 188) continue;                      /* (a section over several packets is left alone) */
            int pil = ((s[10] & 0xf) << 8) | s[11];
            int i = 12 + pil, end = 3 + sl - 4, changed = 0;
            if (g_audio_pid) {
                /* the entries of the audio PIDs left out go from the PMT too */
                int w = i, j = i;
                while (j + 5 <= end) {
                    int l = 5 + (((s[j + 3] & 0xf) << 8) | s[j + 4]);
                    int st = s[j], pid = ((s[j + 1] & 0x1f) << 8) | s[j + 2];
                    if (!(is_audio_type(st) && pid != g_audio_pid)) { if (w != j) memmove(s + w, s + j, (size_t)l); w += l; }
                    else changed = 1;
                    j += l;
                }
                if (changed) {
                    int removed = end - w;
                    sl -= removed;
                    s[1] = (uint8_t)((s[1] & 0xf0) | ((sl >> 8) & 0x0f)); s[2] = (uint8_t)sl;
                    end = w;
                    memset(s + end + 4, 0xff, (size_t)removed);     /* (stuffing after the new CRC) */
                }
            }
            char desc[160]; int dn = 0;
            for (int j = i; j + 5 <= end && dn < 140; j += 5 + (((s[j + 3] & 0xf) << 8) | s[j + 4]))
                dn += snprintf(desc + dn, sizeof(desc) - (size_t)dn, " %02x:%04x", map_type(s[j]), ((s[j + 1] & 0x1f) << 8) | s[j + 2]);
            if (dn != g_pmt_dn || memcmp(desc, g_pmt_desc, (size_t)dn)) {
                if (g_pmt_dn >= 0 && cut < 0) cut = o;
                memcpy(g_pmt_desc, desc, (size_t)dn); g_pmt_dn = dn; fprintf(stderr, "PMT%.*s\n", dn, desc);
            }
            while (i + 5 <= end) {
                int st = s[i], l = ((s[i + 3] & 0xf) << 8) | s[i + 4];
                int to = st;
                if (st == 0x85 || st == 0x86 || st == 0xa2) to = 0x82;
                else if (st == 0x84 || st == 0xa1) to = 0x87;
                if (to != st) { s[i] = (uint8_t)to; changed = 1; }
                i += 5 + l;
            }
            if (changed) {
                uint32_t c = crc32_mpeg(s, 3 + sl - 4);
                s[end] = (uint8_t)(c >> 24); s[end + 1] = (uint8_t)(c >> 16); s[end + 2] = (uint8_t)(c >> 8); s[end + 3] = (uint8_t)c;
            }
        }
    }    return cut;
}


/*
 * DTS-HD (Master Audio, High Resolution): each PES holds a DTS core frame and an extension substream. The player's DTS reader takes
 * parts of the extension for "DTS Express" and its decoder fails on them; the core alone is plain DTS. So the PES of these PIDs are
 * collected, cut after the core frame and packed into transport packets again.
 */
#define MAXDTS 4
static int g_dts_pid[MAXDTS], g_dts_n;
static uint8_t g_pes[MAXDTS][65536];
static int g_pes_len[MAXDTS], g_cc[MAXDTS];

static int dts_index(int pid) { for (int i = 0; i < g_dts_n; ++i) if (g_dts_pid[i] == pid) return i; return -1; }

/*
 * The sound the disc chose (PSR 1, its "audio_stream"): a transport stream says nothing about languages, so the player took the track
 * with the most channels (an English one, where the disc plays German). Only the chosen audio PID is passed on; when the disc's menu
 * changes the language, the PMT changes and the stream goes on in a new one (see RESTART).
 */
static int g_playlist = -1, g_item, g_audio_stream;

static void update_audio_pid(BLURAY *bd)
{
    int pid = 0;
    if (g_audio_stream > 0 && g_audio_stream < 0xff && g_playlist >= 0) {
        BLURAY_TITLE_INFO *ti = bd_get_playlist_info(bd, (uint32_t)g_playlist, 0);
        if (ti) {
            if ((unsigned)g_item < ti->clip_count && g_audio_stream <= ti->clips[g_item].audio_stream_count)
                pid = ti->clips[g_item].audio_streams[g_audio_stream - 1].pid;
            bd_free_title_info(ti);
        }
    }
    if (pid != g_audio_pid) { g_audio_pid = pid; fprintf(stderr, "AUDIO stream %d pid %04x\n", g_audio_stream, pid); }
}

static void follow_event(BLURAY *bd, const BD_EVENT *ev)
{
    if (ev->event == BD_EVENT_PLAYLIST) { g_playlist = (int)ev->param; g_item = 0; update_audio_pid(bd); }
    else if (ev->event == BD_EVENT_PLAYITEM) { g_item = (int)ev->param; update_audio_pid(bd); }
    else if (ev->event == BD_EVENT_AUDIO_STREAM) { g_audio_stream = (int)ev->param; update_audio_pid(bd); }
}

/* the packets of the audio PIDs the disc did not choose are left out (in place; returns the new length) */
static int drop_audio(uint8_t *b, int n)
{
    if (!g_audio_pid) return n;
    int w = 0;
    for (int o = 0; o + 188 <= n; o += 188) {
        int pid = ((b[o + 1] & 0x1f) << 8) | b[o + 2];
        if (pid != g_audio_pid && is_audio_pid(pid)) continue;
        if (w != o) memmove(b + w, b + o, 188);
        w += 188;
    }
    return w;
}

static void learn_dts(const uint8_t *p)
{
    /* (a PMT: which PIDs carry DTS-HD; taken before fix_pmt rewrites the types) */
    const uint8_t *s = p + 5 + p[4];
    if (s[0] != 2) return;
    int sl = ((s[1] & 0xf) << 8) | s[2];
    if (5 + p[4] + 3 + sl > 188) return;
    int pil = ((s[10] & 0xf) << 8) | s[11];
    int i = 12 + pil, end = 3 + sl - 4, n = 0, pids[MAXDTS];
    static char last[200];
    char d[200]; int dn = 0;
    for (int j = i; j + 5 <= end && dn < 180; j += 5 + (((s[j + 3] & 0xf) << 8) | s[j + 4]))
        dn += snprintf(d + dn, sizeof(d) - (size_t)dn, " %02x:%04x", s[j], ((s[j + 1] & 0x1f) << 8) | s[j + 2]);
    d[dn] = 0;
    if (strcmp(d, last)) { strcpy(last, d); fprintf(stderr, "PMT on the disc:%s\n", d); }
    while (i + 5 <= end) {
        int st = s[i], pid = ((s[i + 1] & 0x1f) << 8) | s[i + 2], l = ((s[i + 3] & 0xf) << 8) | s[i + 4];
        if ((st == 0x85 || st == 0x86) && n < MAXDTS) pids[n++] = pid;
        if (is_audio_type(st) && !is_audio_pid(pid) && g_naudio < 16) g_audio_pids[g_naudio++] = pid;
        i += 5 + l;
    }
    for (int k = 0; k < n; ++k) if (dts_index(pids[k]) < 0 && g_dts_n < MAXDTS) { g_dts_pid[g_dts_n] = pids[k]; g_pes_len[g_dts_n] = 0; g_dts_n++; }
}

/* packs the collected PES of entry [k] (cut after its core frame) into transport packets at [out]; returns the bytes written */
static int flush_dts(int k, uint8_t *out)
{
    uint8_t *e = g_pes[k];
    int n = g_pes_len[k];
    g_pes_len[k] = 0;
    if (n < 9 || e[0] || e[1] || e[2] != 1) return 0;
    int hl = 9 + e[8];
    /* a PES of the extension substream alone (some discs put core and extension in PES of their own): left out */
    if (hl + 4 <= n && e[hl] == 0x64 && e[hl + 1] == 0x58 && e[hl + 2] == 0x20 && e[hl + 3] == 0x25) return 0;
    if (hl + 10 <= n && e[hl] == 0x7f && e[hl + 1] == 0xfe && e[hl + 2] == 0x80 && e[hl + 3] == 0x01) {
        int fsize = (((e[hl + 5] & 3) << 12) | (e[hl + 6] << 4) | (e[hl + 7] >> 4)) + 1;
        if (fsize > 95 && hl + fsize < n) {
            n = hl + fsize;
            int plen = n - 6;
            e[4] = (uint8_t)(plen >> 8); e[5] = (uint8_t)plen;
        }
    }
    int w = 0, at = 0, pid = g_dts_pid[k];
    while (at < n) {
        uint8_t *p = out + w;
        int left = n - at, take = left < 184 ? left : 184;
        p[0] = 0x47; p[1] = (uint8_t)(((at == 0) ? 0x40 : 0) | (pid >> 8)); p[2] = (uint8_t)pid;
        if (take == 184) { p[3] = (uint8_t)(0x10 | (g_cc[k] & 0xf)); memcpy(p + 4, e + at, 184); }
        else {
            int af = 183 - take;                 /* adaptation field length; stuffing fills the packet */
            p[3] = (uint8_t)(0x30 | (g_cc[k] & 0xf));
            p[4] = (uint8_t)af;
            if (af > 0) { p[5] = 0; memset(p + 6, 0xff, (size_t)(af - 1)); }
            memcpy(p + 5 + af, e + at, (size_t)take);
        }
        g_cc[k]++; at += take; w += 188;
    }
    return w;
}

/* [in] (n bytes of 188-byte packets) to [out]: DTS-HD PES reduced to their core; returns the bytes in [out] */
static int strip_dtshd(const uint8_t *in, int n, uint8_t *out)
{
    int w = 0;
    for (int o = 0; o + 188 <= n; o += 188) {
        const uint8_t *p = in + o;
        int pid = ((p[1] & 0x1f) << 8) | p[2];
        if (pid == 0 && (p[1] & 0x40) && (p[3] & 0x10)) {
            const uint8_t *s = p + 5 + p[4];
            int sl = ((s[1] & 0xf) << 8) | s[2];
            if (s[0] == 0) for (int j = 8; j + 4 <= 3 + sl - 4 && j + 4 < 180; j += 4) if (((s[j] << 8) | s[j + 1]) != 0) { g_pmt_pid = ((s[j + 2] & 0x1f) << 8) | s[j + 3]; break; }
        } else if (pid == g_pmt_pid && (p[1] & 0x40) && (p[3] & 0x10)) learn_dts(p);
        int k = dts_index(pid);
        if (k < 0 || !(p[3] & 0x10)) { memcpy(out + w, p, 188); w += 188; continue; }
        int at = 4;
        if (p[3] & 0x20) at = 5 + p[4];
        if (at > 188) continue;
        if (p[1] & 0x40) w += flush_dts(k, out + w);
        int len = 188 - at;
        if (g_pes_len[k] + len <= (int)sizeof(g_pes[k])) { memcpy(g_pes[k] + g_pes_len[k], p + at, (size_t)len); g_pes_len[k] += len; }
    }
    return w;
}

static int sv_dirty[2], sv_x0[2], sv_y0[2], sv_x1[2], sv_y1[2];

static void sv_mark(int pl, int x, int y, int w, int h)
{
    if (!sv_dirty[pl]) { sv_x0[pl] = x; sv_y0[pl] = y; sv_x1[pl] = x + w; sv_y1[pl] = y + h; sv_dirty[pl] = 1; return; }
    if (x < sv_x0[pl]) sv_x0[pl] = x;
    if (y < sv_y0[pl]) sv_y0[pl] = y;
    if (x + w > sv_x1[pl]) sv_x1[pl] = x + w;
    if (y + h > sv_y1[pl]) sv_y1[pl] = y + h;
}

static void sv_overlay(void *handle, const BD_OVERLAY *ov)
{
    (void)handle;
    if (!ov) return;
    int pl = ov->plane > 1 ? 1 : ov->plane;
    switch (ov->cmd) {
    case BD_OVERLAY_INIT:
        break;
    case BD_OVERLAY_CLOSE: case BD_OVERLAY_CLEAR: case BD_OVERLAY_HIDE:
        memset(plane[pl], 0, (size_t)W * H * 4);
        sv_dirty[pl] = 0;
        printf("CLEAR %d\n", pl); fflush(stdout);
        break;
    case BD_OVERLAY_WIPE:
        for (int j = ov->y; j < ov->y + ov->h && j < H; ++j) memset(plane[pl] + j * W + ov->x, 0, (size_t)(ov->w) * 4);
        sv_mark(pl, ov->x, ov->y, ov->w, ov->h);
        break;
    case BD_OVERLAY_DRAW:
        if (!ov->img || !ov->palette || ov->palette_update_flag) break;
        {
            uint32_t pal[256];
            for (int i = 0; i < 256; ++i) pal[i] = rgba(&ov->palette[i]);
            int n = 0, total = ov->w * ov->h;
            const BD_PG_RLE_ELEM *e = ov->img;
            while (n < total) {
                int len = e->len, col = e->color;
                if (len == 0 && col == 0) { e++; continue; }
                while (len-- > 0 && n < total) {
                    int px = ov->x + n % ov->w, py = ov->y + n / ov->w;
                    if (px < W && py < H) plane[pl][py * W + px] = pal[col & 255];
                    n++;
                }
                e++;
            }
            sv_mark(pl, ov->x, ov->y, ov->w, ov->h);
        }
        break;
    case BD_OVERLAY_FLUSH:
        for (int q = 0; q < 2; ++q) {
            if (!sv_dirty[q]) continue;
            int x0 = sv_x0[q] < 0 ? 0 : sv_x0[q], y0 = sv_y0[q] < 0 ? 0 : sv_y0[q], x1 = sv_x1[q] > W ? W : sv_x1[q], y1 = sv_y1[q] > H ? H : sv_y1[q];
            sv_dirty[q] = 0;
            if (x1 <= x0 || y1 <= y0) continue;
            char path[512];
            snprintf(path, sizeof(path), "%s/g%04d_%d.raw", outdir, ++seq, q);
            FILE *f = fopen(path, "wb");
            if (!f) continue;
            fprintf(f, "BDIG %d %d %d %d\n", x1 - x0, y1 - y0, x0, y0);
            for (int j = y0; j < y1; ++j) fwrite(plane[q] + j * W + x0, 4, (size_t)(x1 - x0), f);
            fclose(f);
            printf("FLUSH %d %d %d %d %d %d\n", seq, q, x0, y0, x1 - x0, y1 - y0);
            fflush(stdout);
        }
        break;
    }
}

/* opens the writing end of the fifo once the player has opened its end; -1 when told to quit meanwhile */
static int open_fifo(const char *fifo)
{
    int fd = -1;
    while (fd < 0) {
        fd = open(fifo, O_WRONLY | O_NONBLOCK);
        if (fd < 0) { usleep(50000); fd_set fs; struct timeval tv = { 0, 0 }; FD_ZERO(&fs); FD_SET(0, &fs); if (select(1, &fs, NULL, NULL, &tv) > 0) { char l[64]; if (!fgets(l, sizeof(l), stdin) || !strncmp(l, "quit", 4)) return -1; } }
    }
    fcntl(fd, F_SETFL, fcntl(fd, F_GETFL) & ~O_NONBLOCK);
    return fd;
}

static int write_all(int fd, const uint8_t *b, int n)
{
    int done = 0;
    while (done < n) {
        ssize_t r = write(fd, b + done, (size_t)(n - done));
        if (r < 0) { if (errno == EINTR) continue; fprintf(stderr, "END writing the stream: %s\n", strerror(errno)); return -1; }
        done += (int)r;
    }
    return 0;
}

static int serve(const char *device, const char *fifo, const char *lang)
{
    signal(SIGPIPE, SIG_IGN);
    if (getenv("BDM_PRELOAD")) setenv("LD_PRELOAD", getenv("BDM_PRELOAD"), 1);
    plane[0] = calloc((size_t)W * H, 4);
    plane[1] = calloc((size_t)W * H, 4);
    BLURAY *bd = bd_open(device, NULL);
    if (!bd) { printf("ERROR open\n"); return 1; }
    const BLURAY_DISC_INFO *di = bd_get_disc_info(bd);
    if (di) printf("DISC aacs_handled=%d hdmv=%d bdj=%d\n", di->aacs_handled, di->num_hdmv_titles, di->bdj_detected);
    bd_set_player_setting_str(bd, BLURAY_PLAYER_SETTING_MENU_LANG, lang);
    bd_set_player_setting_str(bd, BLURAY_PLAYER_SETTING_AUDIO_LANG, lang);
    bd_set_player_setting_str(bd, BLURAY_PLAYER_SETTING_PG_LANG, lang);
    bd_set_player_setting_str(bd, BLURAY_PLAYER_SETTING_COUNTRY_CODE, "DE");
    bd_set_player_setting(bd, BLURAY_PLAYER_SETTING_REGION_CODE, 2);
    bd_set_player_setting(bd, BLURAY_PLAYER_SETTING_DECODE_PG, 1);
    bd_register_overlay_proc(bd, NULL, sv_overlay);
    if (!bd_play(bd)) { printf("ERROR play\n"); return 1; }
    printf("READY\n"); fflush(stdout);
    /* the stream goes into the fifo once the player has opened its end */
    int fd = open_fifo(fifo);
    if (fd < 0) { bd_close(bd); return 0; }
    static unsigned char tmp[6144 * 8], out[188 * 32 * 8], out2[188 * 32 * 8 + 65536 * 2];
    for (;;) {
        BD_EVENT ev;
        ev.event = BD_EVENT_NONE;
        int n = bd_read_ext(bd, tmp, sizeof(tmp), &ev);
        if (ev.event != BD_EVENT_NONE) {
            event_name(&ev);
            follow_event(bd, &ev);
            if (ev.event == BD_EVENT_SOUND_EFFECT) {
                BLURAY_SOUND_EFFECT fx;
                if (bd_get_sound_effect(bd, ev.param, &fx) > 0 && fx.samples && fx.num_frames) {
                    char path[512];
                    snprintf(path, sizeof(path), "%s/snd%u.pcm", outdir, ev.param);
                    FILE *f = fopen(path, "wb");
                    if (f) { fwrite(fx.samples, 2, (size_t)fx.num_frames * fx.num_channels, f); fclose(f); printf("SOUND %u %d %u %s\n", ev.param, fx.num_channels, fx.num_frames, path); fflush(stdout); }
                }
            }
        }
        while (bd_get_event(bd, &ev) && ev.event != BD_EVENT_NONE) { event_name(&ev); follow_event(bd, &ev); }
        if (n < 0) { fprintf(stderr, "END bd_read_ext returned %d\n", n); break; }
        if (n > 0) {
            int w = 0;
            for (int o = 0; o + 192 <= n && w + 188 <= (int)sizeof(out); o += 192) { memcpy(out + w, tmp + o + 4, 188); w += 188; }
            retime(out, w);
            w = strip_dtshd(out, w, out2);
            uint8_t *buf = out2;
            w = drop_audio(buf, w);
            int cut = fix_pmt(buf, w);
            if (cut < 0) { if (write_all(fd, buf, w) < 0) goto end; }
            else {
                /* the streams changed (another audio format on the same PID: menu and film): the player's stream ends here and
                   a new one starts with the new PMT (preceded by the PAT); the app plays the old one out and opens the new one */
                if (write_all(fd, buf, cut) < 0) goto end;
                close(fd);
                /* a fifo of its own for the new stream: the old one is still open for the player's old reader */
                static char next[512];
                static int seg;
                snprintf(next, sizeof(next), "%s.%d", fifo, ++seg);
                unlink(next);
                if (mkfifo(next, 0666) < 0) { fprintf(stderr, "END mkfifo %s: %s\n", next, strerror(errno)); goto end; }
                printf("RESTART %s\n", next); fflush(stdout);
                fd = open_fifo(next);
                if (fd < 0) goto end;
                if (g_have_pat && write_all(fd, g_pat, 188) < 0) goto end;
                if (write_all(fd, buf + cut, w - cut) < 0) goto end;
            }
        } else usleep(15000);
        fd_set fs;
        struct timeval tv = { 0, 0 };
        FD_ZERO(&fs); FD_SET(0, &fs);
        while (select(1, &fs, NULL, NULL, &tv) > 0) {
            char line[128]; int a = 0;
            if (!fgets(line, sizeof(line), stdin)) { fprintf(stderr, "END stdin closed\n"); goto end; }
            if (sscanf(line, "key %d", &a) == 1) { bd_user_input(bd, -1, (uint32_t)a); }
            else if (!strncmp(line, "menu", 4)) bd_menu_call(bd, -1);
            else if (sscanf(line, "title %d", &a) == 1) bd_play_title(bd, (unsigned)a);
            else if (!strncmp(line, "quit", 4)) { fprintf(stderr, "END quit\n"); goto end; }
            FD_ZERO(&fs); FD_SET(0, &fs);
        }
    }
end:
    close(fd);
    bd_close(bd);
    return 0;
}

int main(int argc, char **argv)
{
    if (argc < 3) { fprintf(stderr, "usage: bdmenu <device> <outdir>\n"); return 2; }
    outdir = argv[2];
    if (argc >= 6 && !strcmp(argv[3], "serve")) return serve(argv[1], argv[4], argv[5]);
    /* libmmbd starts MakeMKV's helper (makemkvcon) itself: it must find the drive shim, too (set here: for the loader of the host the variable would be wrong) */
    if (getenv("BDM_PRELOAD")) setenv("LD_PRELOAD", getenv("BDM_PRELOAD"), 1);
    setvbuf(stdout, NULL, _IOLBF, 0);
    {
        void *h = dlopen("libmmbd.so.0", RTLD_NOW);
        printf("DLOPEN libmmbd.so.0: %s\n", h ? "ok" : dlerror());
        if (!h) { h = dlopen("/usr/lib/libmmbd.so.0", RTLD_NOW); printf("DLOPEN /usr/lib/libmmbd.so.0: %s\n", h ? "ok" : dlerror()); }
    }
    BLURAY *bd = bd_open(argv[1], NULL);
    if (!bd) { printf("ERROR open\n"); return 1; }
    const BLURAY_DISC_INFO *di = bd_get_disc_info(bd);
    if (di) printf("DISC name=%s aacs_detected=%d libaacs=%d aacs_handled=%d bdplus_detected=%d bdj=%d hdmv=%d first_play=%d top_menu=%d titles=%u\n",
                   di->disc_name ? di->disc_name : "", di->aacs_detected, di->libaacs_detected, di->aacs_handled, di->bdplus_detected,
                   di->bdj_detected, di->num_hdmv_titles, di->first_play_supported, di->top_menu_supported, di->num_titles);
    bd_set_player_setting_str(bd, BLURAY_PLAYER_SETTING_MENU_LANG, "eng");
    bd_set_player_setting_str(bd, BLURAY_PLAYER_SETTING_AUDIO_LANG, "eng");
    bd_set_player_setting_str(bd, BLURAY_PLAYER_SETTING_PG_LANG, "eng");
    bd_set_player_setting_str(bd, BLURAY_PLAYER_SETTING_COUNTRY_CODE, "US");
    bd_set_player_setting(bd, BLURAY_PLAYER_SETTING_REGION_CODE, 2);
    bd_set_player_setting(bd, BLURAY_PLAYER_SETTING_DECODE_PG, 1);
    plane[0] = calloc((size_t)W * H, 4);       /* (both planes exist from the start: the menu may draw on one only) */
    plane[1] = calloc((size_t)W * H, 4);
    bd_set_debug_handler(debug_cb);
    bd_set_debug_mask(DBG_BLURAY | DBG_CRIT);
    bd_register_overlay_proc(bd, NULL, overlay_cb);
    if (!bd_play(bd)) { printf("ERROR play\n"); return 1; }
    printf("PLAYING\n");
    static unsigned char buf[6144 * 16];
    long long total = 0;
    int idle = 0;
    for (;;) {
        BD_EVENT ev;
        int n = bd_read_ext(bd, buf, sizeof(buf), &ev);
        if (n > 0) { total += n; idle = 0; }
        else idle++;
        while (bd_get_event(bd, &ev)) { event_name(&ev); if (ev.event == BD_EVENT_NONE) break; }
        fd_set fds;
        struct timeval tv = { 0, n > 0 ? 5000 : 40000 };
        FD_ZERO(&fds); FD_SET(0, &fds);
        if (select(1, &fds, NULL, NULL, &tv) > 0) {
            char line[256];
            if (!fgets(line, sizeof(line), stdin)) break;
            command(bd, line);
        }
        /* a menu loops a short clip: read it about as fast as it plays, not as fast as the drive goes (that only heats the drive and the television) */
        if (n > 0) usleep((useconds_t)((long long)n * 1000000 / (2L << 20)));
        if (total > (1LL << 31)) { printf("EVENT read_limit 0\n"); break; }
    }
    bd_close(bd);
    return 0;
}
