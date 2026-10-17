#!/bin/bash
# Builds app/libs/media3-decoder-ffmpeg-1.5.1.aar: Media3's FFmpeg audio decoder with the decoders a
# Blu-ray needs. Needs the Android SDK (sdk.dir in android/local.properties) with NDK 26.3 installed;
# Gradle fetches the NDK version Media3 itself asks for.
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
work="${1:-$HOME/ffmpeg-build}"
sdk="$(sed -n 's/^sdk.dir=//p' "$here/android/local.properties")"
ndk="$sdk/ndk/26.3.11579264"
host="$(ls "$ndk/toolchains/llvm/prebuilt/" | head -1)"
mkdir -p "$work" && cd "$work"
[ -d media3 ] || git clone -q --depth 1 --branch 1.5.1 https://github.com/androidx/media.git media3
[ -d ffmpeg ] || git clone -q --depth 1 --branch release/6.0 https://git.ffmpeg.org/ffmpeg.git ffmpeg
module="$work/media3/libraries/decoder_ffmpeg/src/main"
ln -sfn "$work/ffmpeg" "$module/jni/ffmpeg"
# API 21: the level Media3's own native library is linked for
# no hand-written assembly: the NEON conversion of swresample (fltp to s16) crashed on a 32-bit television with 6 channels
sed -i.bak "s/    --disable-symver/    --disable-symver\n    --disable-asm/" "$module/jni/build_ffmpeg.sh"
# the resampler is built from each frame (DTS changes layout; the stock code used the first one and crashed on a null plane)
patch -N -p0 "$module/jni/ffmpeg_jni.cc" < "$here/tools/ffmpeg_jni.patch" || true
bash "$module/jni/build_ffmpeg.sh" "$module" "$ndk" "$host" 21 ac3 eac3 dca truehd mlp
cp "$here/android/local.properties" media3/
(cd media3 && ./gradlew -q :lib-decoder-ffmpeg:assembleRelease)
cp media3/libraries/decoder_ffmpeg/buildout/outputs/aar/lib-decoder-ffmpeg-release.aar "$here/android/app/libs/media3-decoder-ffmpeg-1.5.1.aar"
