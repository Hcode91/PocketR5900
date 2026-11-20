#!/usr/bin/env bash
# Pack everything the phone needs to compile and link a recompiled game:
# Android sysroot (arm64, API 28), clang resource files, PS2 runtime headers,
# the prebuilt runtime libraries and the runner's main.o.
#
#   make_sdk_pack.sh <NDK dir> <runtime build dir> <repo dir> <toolchain out dir> <out.zip>
set -euo pipefail

NDK="$(cd "$1" && pwd)"
RT="$(cd "$2" && pwd)"
REPO="$(cd "$3" && pwd)"
TC="$(cd "$4" && pwd)"
OUTZIP="$(realpath -m "$5")"
API=28

PRE="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
CLANG_VER_DIR="$(ls -d "$PRE"/lib/clang/* | head -1)"
STAGE="$(mktemp -d)"
S="$STAGE/sdk"
mkdir -p "$S"

# --- Android sysroot (arm64 only) ---
mkdir -p "$S/sysroot/usr/lib/aarch64-linux-android"
cp -r "$PRE/sysroot/usr/include" "$S/sysroot/usr/include"
for a in arm-linux-androideabi i686-linux-android x86_64-linux-android riscv64-linux-android; do
  rm -rf "$S/sysroot/usr/include/$a"
done
L="$PRE/sysroot/usr/lib/aarch64-linux-android"
cp "$L/libc++_static.a" "$L/libc++abi.a" "$S/sysroot/usr/lib/aarch64-linux-android/"
cp -r "$L/$API" "$S/sysroot/usr/lib/aarch64-linux-android/$API"

# --- clang resource dir: headers from our own clang, runtime libs from the NDK ---
mkdir -p "$S/resource/lib/linux/aarch64"
cp -r "$TC/resource/include" "$S/resource/include"
cp "$CLANG_VER_DIR/lib/linux/libclang_rt.builtins-aarch64-android.a" "$S/resource/lib/linux/"
cp "$CLANG_VER_DIR/lib/linux/aarch64/libunwind.a" "$CLANG_VER_DIR/lib/linux/aarch64/libatomic.a" \
   "$S/resource/lib/linux/aarch64/"

# --- PS2 runtime headers ---
mkdir -p "$S/include/runtime" "$S/include/kernel" "$S/include/iop" "$S/include/raylib" "$S/include/sse2neon"
cp -r "$REPO/ps2xRuntime/include/." "$S/include/runtime/"
(cd "$REPO/ps2xRuntime/src/lib/Kernel" && find . -name '*.h' -exec cp --parents {} "$S/include/kernel/" \;)
cp -r "$REPO/ps2xIOP/include/." "$S/include/iop/"
cp "$RT"/_deps/raylib-src/src/*.h "$S/include/raylib/"
cp "$RT"/_deps/sse2neon-src/sse2neon.h "$S/include/sse2neon/"
# The repo's placeholder must not shadow each game's generated headers.
rm -f "$S/include/runtime/ps2_recompiled_functions.h" "$S/include/runtime/ps2_recompiled_stubs.h"

# --- prebuilt runtime ---
mkdir -p "$S/lib"
cp "$RT/ps2xRuntime/libps2_runtime.a" "$RT/ps2xIOP/libps2_iop.a" "$RT/_deps/raylib-build/raylib/libraylib.a" "$S/lib/"
cp "$RT/ps2xRuntime/CMakeFiles/ps2EntryRunner.dir/src/main.cpp.o" "$S/lib/main.o"
STRIP="$PRE/bin/llvm-strip"
"$STRIP" --strip-debug "$S"/lib/*.a "$S/lib/main.o"

echo "pocketr5900-sdk-2" > "$S/VERSION"
mkdir -p "$(dirname "$OUTZIP")"
rm -f "$OUTZIP"
cp "$S/VERSION" "$(dirname "$OUTZIP")/sdk.version"
(cd "$STAGE" && zip -q -r -9 "$OUTZIP" sdk)
du -sh "$S" "$OUTZIP"
rm -rf "$STAGE"
