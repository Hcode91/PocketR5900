#!/usr/bin/env bash
# Build clang and lld as static Android ARM64 programs, so the PocketR5900 app can
# compile recompiled games on the phone.
#
#   build_toolchain.sh <llvm-project source dir> <NDK dir> <output dir>
#
# Output: <out>/clang, <out>/lld (static, stripped) and <out>/resource/include
# (clang's own headers such as arm_neon.h).
set -euo pipefail

SRC="$(cd "$1" && pwd)"
NDK="$(cd "$2" && pwd)"
OUT="$(mkdir -p "$3" && cd "$3" && pwd)"
BUILD="${BUILD_DIR:-$OUT/../toolchain-build}"
JOBS="${JOBS:-$(nproc)}"

cmake -S "$SRC/llvm" -B "$BUILD" -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-28 \
  -DANDROID_STL=c++_static \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_EXE_LINKER_FLAGS="-static" \
  -DLLVM_ENABLE_PROJECTS="clang;lld" \
  -DLLVM_TARGETS_TO_BUILD=AArch64 \
  -DLLVM_HOST_TRIPLE=aarch64-linux-android28 \
  -DLLVM_DEFAULT_TARGET_TRIPLE=aarch64-linux-android28 \
  -DLLVM_ENABLE_ZLIB=OFF -DLLVM_ENABLE_ZSTD=OFF -DLLVM_ENABLE_LIBXML2=OFF \
  -DLLVM_ENABLE_TERMINFO=OFF -DLLVM_ENABLE_LIBEDIT=OFF -DLLVM_ENABLE_LIBPFM=OFF \
  -DLLVM_INCLUDE_TESTS=OFF -DLLVM_INCLUDE_EXAMPLES=OFF -DLLVM_INCLUDE_BENCHMARKS=OFF \
  -DLLVM_INCLUDE_DOCS=OFF -DLLVM_BUILD_TOOLS=OFF -DLLVM_INCLUDE_UTILS=OFF \
  -DCLANG_ENABLE_ARCMT=OFF -DCLANG_ENABLE_STATIC_ANALYZER=OFF \
  -DCLANG_PLUGIN_SUPPORT=OFF -DLLVM_ENABLE_PLUGINS=OFF \
  -DCLANG_DEFAULT_LINKER=lld -DCLANG_DEFAULT_CXX_STDLIB=libc++ \
  -DCLANG_DEFAULT_RTLIB=compiler-rt -DCLANG_DEFAULT_UNWINDLIB=libunwind

ninja -C "$BUILD" -j "$JOBS" clang lld clang-resource-headers

STRIP="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"
mkdir -p "$OUT"
cp "$BUILD/bin/clang" "$OUT/clang"
cp "$BUILD/bin/lld" "$OUT/lld"
"$STRIP" "$OUT/clang" "$OUT/lld"
rm -rf "$OUT/resource"
mkdir -p "$OUT/resource"
cp -r "$BUILD/lib/clang/19/include" "$OUT/resource/include"
ls -l "$OUT"
