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

int main(int argc, char **argv)
{
    if (argc < 3) { fprintf(stderr, "usage: bdmenu <device> <outdir>\n"); return 2; }
    outdir = argv[2];
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
