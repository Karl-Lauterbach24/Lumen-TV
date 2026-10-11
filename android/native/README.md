# Native part of the Android app

`liblumenbd.so` = **libbluray 1.3.4** (LGPL-2.1-or-later, `libbluray-src/`, unchanged except that the
parts needing Java, libudfread, libxml2 and freetype are left out) + `lumenbd.c`, a JNI bridge.

- The disc's files are not read by libbluray: `open_file`/`open_dir` call back into the app
  (`Bd.kt`: `DiscFs`, which uses the app's UDF reader and decrypts AACS streams with the keys of the
  key database).
- The HDMV menus run in libbluray's virtual machine; the stream it returns is handed to Media3 as plain
  188-byte packets, re-timed so that the clock does not jump when a clip loops or the next one begins.
- The menu's pictures come as RLE/palette overlays; they are drawn into two ARGB planes and shown by the
  app on top of the video.
- No BD-J (Java), no BD+, no AACS inside libbluray.

Rebuild (needs the NDK and CMake from the Android SDK): `tools/build_native.sh`; the result goes to
`app/src/main/jniLibs/` and is committed, so the normal Gradle build (and CI) needs no NDK.
