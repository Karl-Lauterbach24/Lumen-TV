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
