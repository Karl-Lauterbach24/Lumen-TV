#!/usr/bin/env python3
"""Assembles the web-based TV apps (Samsung Tizen, LG webOS).

    python tools/build_web.py --lumen <Lumen checkout> [--out dist]

The receiver itself (index.html, receiver.js, receiver.css, icon.png, hls.light.min.js) is taken
from the Lumen repository, folder receiver/ - the same files Lumen serves to browsers. Each
platform adds its platform.js, icons and manifest.

Result:
    <out>/tizen/                              app folder (open it in Tizen Studio, or sign and package it)
    <out>/LumenTV-<version>-tizen-unsigned.wgt   the same as an archive, NOT signed
    <out>/webos/                              app folder for ares-package
"""
import argparse
import os
import shutil
import sys
import zipfile

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
# every file of Lumen's receiver folder except platform.js (that one is the platform's own)
def receiver_files(lumen):
    return sorted(n for n in os.listdir(os.path.join(lumen, "receiver"))
                  if n != "platform.js" and os.path.isfile(os.path.join(lumen, "receiver", n)))


def read(path):
    with open(path, encoding="utf-8") as f:
        return f.read()


def write(path, text):
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)


def assemble(lumen, out, platform, extra):
    target = os.path.join(out, platform)
    shutil.rmtree(target, ignore_errors=True)
    os.makedirs(target)
    for name in receiver_files(lumen):
        src = os.path.join(lumen, "receiver", name)
        if not os.path.exists(src):
            sys.exit("missing in the Lumen checkout: receiver/" + name)
        shutil.copy(src, target)
    for name in extra:
        shutil.copy(os.path.join(HERE, platform, name), target)
    return target


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--lumen", required=True, help="checkout of https://github.com/Karl-Lauterbach24/Lumen")
    ap.add_argument("--out", default=os.path.join(HERE, "dist"))
    a = ap.parse_args()
    version = read(os.path.join(HERE, "VERSION")).strip()
    os.makedirs(a.out, exist_ok=True)

    # ---- Samsung Tizen ----
    tizen = assemble(a.lumen, a.out, "tizen", ["platform.js", "appicon.png"])
    write(os.path.join(tizen, "config.xml"), read(os.path.join(HERE, "tizen", "config.xml")).replace("@VERSION@", version))
    # Samsung's device APIs (network address, model name) come from a script the TV provides
    page = read(os.path.join(tizen, "index.html"))
    marker = '<script src="platform.js"'
    if marker not in page:
        sys.exit("receiver/index.html has no platform.js script tag")
    page = page.replace(marker, '<script src="$WEBAPIS/webapis/webapis.js"></script>\n' + marker, 1)
    write(os.path.join(tizen, "index.html"), page)
    wgt = os.path.join(a.out, "LumenTV-%s-tizen-unsigned.wgt" % version)
    with zipfile.ZipFile(wgt, "w", zipfile.ZIP_DEFLATED) as z:
        for name in sorted(os.listdir(tizen)):
            z.write(os.path.join(tizen, name), name)

    # ---- LG webOS ----
    webos = assemble(a.lumen, a.out, "webos", ["platform.js", "icon80.png", "icon130.png"])
    write(os.path.join(webos, "appinfo.json"), read(os.path.join(HERE, "webos", "appinfo.json")).replace("@VERSION@", version))

    print("tizen:", tizen)
    print("      ", wgt, "(unsigned)")
    print("webos:", webos)


if __name__ == "__main__":
    main()
