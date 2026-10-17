#!/usr/bin/env python3
"""All icons of Lumen TV from the round Lumen emblem (branding/lumen-logo-circle-light.webp).

    python tools/make_icons.py          (needs Pillow)

Writes the Android banners (16:9, every density) and launcher icons, the Tizen and webOS icons and
the loose set in branding/ (16:9, 4:3, 1:1, 2:1; with and without the "TV").
"""
import os
from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
EMBLEM = Image.open(os.path.join(ROOT, "branding", "lumen-logo-circle-light.webp")).convert("RGBA")
NAVY = (16, 31, 54)
DARK = (8, 9, 12)
FONT = "/System/Library/Fonts/Avenir Next Condensed.ttc"


def emblem(h):
    box = EMBLEM.getbbox()
    e = EMBLEM.crop(box)
    return e.resize((round(e.width * h / e.height), h), Image.LANCZOS)


def background(w, h):
    """Navy glow in the middle, nearly black at the edges."""
    s = 4
    g = Image.new("RGB", (w // s + 1, h // s + 1), DARK)
    px = g.load()
    cx, cy = g.width * 0.38, g.height * 0.5
    r = max(g.width, g.height) * 0.75
    for y in range(g.height):
        for x in range(g.width):
            d = min(1.0, (((x - cx) ** 2 + ((y - cy) * 1.3) ** 2) ** 0.5) / r)
            t = (1 - d) ** 1.6
            px[x, y] = tuple(round(DARK[i] + (NAVY[i] + 6 - DARK[i]) * t) for i in range(3))
    return g.resize((w, h), Image.BICUBIC)


def text(img, s, x, y, size, spacing, fill=(232, 232, 232), anchor_right=False):
    f = ImageFont.truetype(FONT, size, index=0)         # Avenir Next Condensed Bold
    d = ImageDraw.Draw(img)
    widths = [d.textlength(c, font=f) for c in s]
    total = sum(widths) + spacing * (len(s) - 1)
    cx = x - total if anchor_right else x
    for c, wd in zip(s, widths):
        d.text((cx, y), c, font=f, fill=fill)
        cx += wd + spacing
    return total


def banner(w, h, tv=True):
    img = background(w, h)
    if tv:
        eh = round(h * 0.70)
        # (the emblem and "TV" side by side, made smaller until they fit with a margin of a seventh)
        while True:
            e = emblem(eh)
            gap = round(eh * 0.15)
            fs = round(eh * 0.57)
            f = ImageFont.truetype(FONT, fs, index=0)
            sp = round(fs * 0.08)
            d = ImageDraw.Draw(img)
            tw = sum(d.textlength(c, font=f) for c in "TV") + sp
            if e.width + gap + tw <= w * 0.76:
                break
            eh = round(eh * 0.97)
        x0 = round((w - (e.width + gap + tw)) / 2)
        img.paste(e, (x0, (h - eh) // 2), e)
        asc, desc = f.getmetrics()
        cap = f.getbbox("T")
        text(img, "TV", x0 + e.width + gap, round(h / 2 - (cap[1] + cap[3]) / 2), fs, sp)
    else:
        eh = round(min(h * 0.78, w * 0.5))
        e = emblem(eh)
        img.paste(e, ((w - e.width) // 2, (h - eh) // 2), e)
    return img


def tile(n, radius=0.22, fill=0.80):
    """The launcher symbol: the emblem on a rounded navy square."""
    img = Image.new("RGBA", (n * 4, n * 4), (0, 0, 0, 0))
    ImageDraw.Draw(img).rounded_rectangle((0, 0, n * 4 - 1, n * 4 - 1), radius=round(n * 4 * radius), fill=NAVY + (255,))
    e = emblem(round(n * 4 * fill))
    img.alpha_composite(e, ((n * 4 - e.width) // 2, (n * 4 - e.height) // 2))
    return img.resize((n, n), Image.LANCZOS)


def save(img, *path):
    p = os.path.join(ROOT, *path)
    os.makedirs(os.path.dirname(p), exist_ok=True)
    img.save(p, optimize=True)


res = os.path.join("android", "app", "src", "main", "res")
for d, w in (("mdpi", 160), ("hdpi", 240), ("xhdpi", 320), ("xxhdpi", 480), ("xxxhdpi", 640)):
    save(banner(w, w * 9 // 16), res, "drawable-" + d, "banner.png")
for d, n in (("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)):
    save(tile(n), res, "mipmap-" + d, "ic_launcher.png")
# adaptive icon: the emblem inside the inner 66 of 108 units
fg = Image.new("RGBA", (432, 432), (0, 0, 0, 0))
e = emblem(round(432 * 0.58))
fg.alpha_composite(e, ((432 - e.width) // 2, (432 - e.height) // 2))
save(fg, res, "mipmap-xxxhdpi", "ic_launcher_foreground.png")
save(tile(117, 0.0, 0.84).convert("RGB"), "tizen", "appicon.png")
save(tile(80, 0.0, 0.84).convert("RGB"), "webos", "icon80.png")
save(tile(130, 0.0, 0.84).convert("RGB"), "webos", "icon130.png")

for name, w, h, tv in (("lumen-tv-16x9", 1920, 1080, True), ("lumen-tv-16x9-1280x720", 1280, 720, True), ("lumen-tv-16x9-640x360", 640, 360, True),
                       ("lumen-emblem-16x9", 1920, 1080, False), ("lumen-tv-4x3", 1600, 1200, True), ("lumen-emblem-4x3", 1600, 1200, False),
                       ("lumen-tv-2x1", 1200, 600, True), ("lumen-emblem-2x1", 1200, 600, False)):
    save(banner(w, h, tv), "branding", name + ".png")
save(tile(1024), "branding", "lumen-tv-1x1-1024.png")
save(tile(512), "branding", "lumen-tv-1x1-512.png")
print("done")
