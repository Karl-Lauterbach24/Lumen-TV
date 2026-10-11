#!/bin/sh
# Builds libbluray + the JNI bridge (android/native) for the Android ABIs into android/app/src/main/jniLibs.
# Needs the Android NDK and CMake (sdkmanager "ndk;26.3.11579264" "cmake;3.22.1").
set -e
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/opt/homebrew/share/android-commandlinetools}}"
NDK="${ANDROID_NDK:-$(ls -d "$SDK"/ndk/* | tail -1)}"
CM="$SDK/cmake/3.22.1/bin/cmake"; NINJA="$SDK/cmake/3.22.1/bin/ninja"
SRC="$(cd "$(dirname "$0")/../android/native" && pwd)"
OUT="$(cd "$(dirname "$0")/../android/app/src/main" && pwd)/jniLibs"
for abi in arm64-v8a armeabi-v7a x86_64; do
  B="$(mktemp -d)"
  "$CM" -S "$SRC" -B "$B" -G Ninja -DCMAKE_MAKE_PROGRAM="$NINJA" -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=$abi -DANDROID_PLATFORM=android-24 -DCMAKE_BUILD_TYPE=Release -DCMAKE_SHARED_LINKER_FLAGS="-Wl,-z,max-page-size=16384" >/dev/null
  "$CM" --build "$B" > "$B/build.log" 2>&1 || { grep -E "error|undefined" "$B/build.log" | head; exit 1; }
  mkdir -p "$OUT/$abi"
  "$NDK"/toolchains/llvm/prebuilt/*/bin/llvm-strip --strip-unneeded -o "$OUT/$abi/liblumenbd.so" "$B/liblumenbd.so"
  rm -rf "$B"
  echo "$abi: $(wc -c < "$OUT/$abi/liblumenbd.so") bytes"
done
