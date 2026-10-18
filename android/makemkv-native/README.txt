32-bit ARM proot for the test app (jniLibs/armeabi-v7a/libproot.so, libproot-loader.so)

Source: https://github.com/termux/proot (commit of 2026-10-12), built with NDK 26.3:
  env CC=<ndk>/armv7a-linux-androideabi24-clang CFLAGS="-I<talloc>/include -O2" LDFLAGS="-L<talloc>/lib" \
      make -C src PROOT_UNBUNDLE_LOADER=/unused
  (add #include <string.h> to src/extension/ashmem_memfd/ashmem_memfd.c)
libproot.so: NEEDED libtalloc.so.2 patched to libtalloc.so (same length, NUL padded).
proot-event.c.patched: src/tracee/event.c with a crash report (registers, faulting address, maps) on SIGSEGV/SIGBUS/SIGILL.

The loader is relinked: on the Changhong AI PONT (Android 11, kernel 4.9 armv7l) the kernel does not map an
ET_EXEC segment at 0x20000000 (lands at 0x26400000 etc.), so proot's stock loader jumps into nothing (signal 11
for every program). Linked at 0x0e000000 it works:
  clang -o loader loader/loader.o loader/assembly.o -static -nostdlib -Wl,--build-id=none,--image-base=0x0e000000,-z,noexecstack

bdmenu (2026-10-17): the disc's own menu through libbluray, decrypted by libmmbd
  libudfread 1.1.2 and libbluray 1.3.4 cross-built for armhf in the Lima VM (arm-linux-gnueabihf-gcc, the same
  -U_TIME_BITS -U_FILE_OFFSET_BITS -D_FILE_OFFSET_BITS=32 -D_TIME_BITS=32 flags as the shim):
    libudfread: ./configure --host=arm-linux-gnueabihf --prefix=$P --disable-static
    libbluray:  ./configure --host=arm-linux-gnueabihf --prefix=$P --disable-static --disable-bdjava-jar --without-freetype
                --without-fontconfig --without-libxml2 --disable-examples --disable-doxygen-doc
    bdmenu:     arm-linux-gnueabihf-gcc -O2 <flags> -I$P/include -o bdmenu bdmenu/bdmenu.c -L$P/lib -lbluray -ludfread -ldl
  shipped as jniLibs libbdm001.so (bdmenu), libbdm002.so (libbluray.so.2), libbdm003.so (libudfread.so.0), mapped in
  assets/makemkv/libmap-armeabi-v7a.txt. libbluray finds libmmbd by LIBAACS_PATH=libmmbd (it appends .so.0); libmmbd
  starts makemkvcon itself (MAKEMKVCON) and that child needs the drive shim: bdmenu sets LD_PRELOAD from BDM_PRELOAD
  (the host's own loader must not see it). The shim serves the fake /dev/sg0 as a file (read/pread/lseek via READ(10))
  and takes a file lock around each command (two processes use the drive).

libcurl (2026-10-18): makemkvcon loads libcurl.so.4 at run time for everything it does on the net – above all the download of its
  hashed key database ("HK", hkd_*.bin in ~/.MakeMKV/_private_data.tar), without which it says "the volume key is unknown" for
  4K discs. Without the library it takes the net as off and says nothing. Built small in the Lima VM against Debian's armhf OpenSSL:
    curl 8.14.1: ./configure --host=arm-linux-gnueabihf --prefix=/usr --with-openssl --with-zlib --without-libpsl --disable-ldap
      --disable-ldaps --without-libidn2 --without-nghttp2 --without-brotli --without-zstd --without-libssh2 --disable-rtsp
      --disable-dict --disable-telnet --disable-tftp --disable-pop3 --disable-imap --disable-smtp --disable-gopher --disable-mqtt
      --disable-manual --disable-docs --enable-shared --disable-static --with-ca-bundle=/etc/ssl/certs/ca-certificates.crt
  shipped as libmk103.so (/lib/arm-linux-gnueabihf/libcurl.so.4) with libmk102.so (libssl.so.3, Debian 3.5.7, the same version as
  the libcrypto.so.3 already there). The CA bundle is the one MakeMkv.ensureTree writes from the system's certificates.
