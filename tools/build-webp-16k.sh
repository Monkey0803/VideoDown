#!/bin/bash
# Rebuild the five 4 KiB-aligned dependencies in youtubedl-android 0.18.1.
# Usage: ANDROID_NDK_ROOT=/path/to/ndk CMAKE=/path/to/cmake bash tools/build-webp-16k.sh
set -euo pipefail
project_dir="$(cd "$(dirname "$0")/.." && pwd)"
: "${ANDROID_NDK_ROOT:?Set ANDROID_NDK_ROOT to Android NDK r27c or newer}"
cmake_bin="${CMAKE:-cmake}"
work_dir="$project_dir/.tooling/webp-16k"
mkdir -p "$work_dir"
archive="$work_dir/libwebp-1.5.0.tar.gz"
if [ ! -f "$archive" ]; then
  curl -fL --retry 2 'https://github.com/webmproject/libwebp/archive/refs/tags/v1.5.0.tar.gz' -o "$archive"
fi
# Source checksum pinned with the vendored outputs below.
expected='668c9aba45565e24c27e17f7aaf7060a399f7f31dba6c97a044e1feacb930f37'
actual="$(shasum -a 256 "$archive" | cut -d ' ' -f 1)"
[ "$actual" = "$expected" ] || { echo 'libwebp source checksum mismatch' >&2; exit 1; }
tar -xzf "$archive" -C "$work_dir"
for abi in arm64-v8a x86_64; do
  build_dir="$work_dir/build-$abi"
  "$cmake_bin" -S "$work_dir/libwebp-1.5.0" -B "$build_dir" \
    -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_ROOT/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$abi" -DANDROID_PLATFORM=android-26 -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON \
    -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=ON -DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384 \
    -DWEBP_BUILD_ANIM_UTILS=OFF -DWEBP_BUILD_CWEBP=OFF -DWEBP_BUILD_DWEBP=OFF \
    -DWEBP_BUILD_GIF2WEBP=OFF -DWEBP_BUILD_IMG2WEBP=OFF -DWEBP_BUILD_VWEBP=OFF \
    -DWEBP_BUILD_WEBPINFO=OFF -DWEBP_BUILD_WEBPMUX=OFF -DWEBP_BUILD_EXTRAS=OFF
  "$cmake_bin" --build "$build_dir" --parallel 6
  dest="$project_dir/app/src/main/assets/engine/native/$abi"
  mkdir -p "$dest"
  for lib in webp webpdecoder webpmux webpdemux sharpyuv; do
    cp "$build_dir/lib$lib.so" "$dest/lib$lib.so"
  done
done
cp "$work_dir/libwebp-1.5.0/COPYING" "$project_dir/app/src/main/assets/engine/native/COPYING.libwebp"
