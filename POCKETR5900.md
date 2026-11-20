# PocketR5900

PocketR5900 is a fork of [PS2Recomp](https://github.com/ran-j/PS2Recomp) that does the
whole conversion on an Android phone: you pick a game you own (bin/cue, ISO or ELF) and
the phone turns it into a native ARM64 build. Nothing is uploaded anywhere, and no game
code is ever stored in this repository.

Licence: GPL-3.0, same as upstream.

## Pipeline (all on the phone)

1. **Disc reader** - read bin/cue (raw 2352-byte sectors) or ISO, open `SYSTEM.CNF`,
   extract the boot ELF.
2. **Analyzer** (`ps2_analyzer`) - writes the recompiler config for the ELF.
3. **Recompiler** (`ps2_recomp`) - turns the ELF into C++.
4. **Compiler** - a bundled clang, shipped in the APK's native library folder (the only
   place Android lets an app run programs from), compiles the C++ against the
   prebuilt runtime.
5. **Runner** - the compiled game is loaded by the app; later, packaged as its own
   signed APK on the phone.

## Using it

1. Install the APK from the **PocketR5900 - App** workflow.
2. Tap **Add a game** and pick your own dump: a `.bin` (pick its `.cue` too), an
   `.iso`, or a PS2 ELF.
3. Choose **Faster conversion** (`-O1`) or **Faster game** (`-O2`).
4. Leave it to run. It keeps going with the screen off, shows progress in a
   notification, and can be stopped and continued later without starting over.
5. Tap **Play**, or **Export as APK** to make the game its own installable app.

Each game has a **Convert log** and, once played, a **Game log** you can share.

## Converting on a Windows PC (PocketR5900-PC)

The slow part, compiling, can be done on a Windows PC instead (much faster with
more cores). Download **PocketR5900-PC** from the **PocketR5900 - PC converter**
workflow, unzip it, run `PocketR5900-PC.exe` and pick your dump. It writes one
`.pr5900` file (the compiled game library, plus the disc unless you untick it).
Open that file with **Add a game** on the phone and it is ready to play or export.
See `pocket/pc/README.md`.

## How it is built

`pocket-app.yml` builds everything into one APK:

- `pocket/scripts/build_toolchain.sh`: clang + lld (LLVM 19) as static Android
  ARM64 programs. Slow the first time (1-3 hours), then cached.
- The analyzer and recompiler as static Android ARM64 programs.
- The PS2 runtime for Android (without LTO, so it can be linked on the phone),
  packed with the Android sysroot and headers by `pocket/scripts/make_sdk_pack.sh`
  into `sdk.zip`.
- `pocket/player`: the template every exported game APK is made from.
- `pocket/app`: the app.

`pocket-pc.yml` builds the Windows converter: the same `sdk.zip`, the analyzer and
recompiler built with MSVC, and `pocket/pc/pocketr5900_pc.py` packed with
PyInstaller. On the PC it compiles with Google's Android NDK, downloaded on first
use.

Runtime changes for PocketR5900 (all opt-in through environment variables set by
the app): `POCKETR5900_BOOT_ELF`, `POCKETR5900_CD_IMAGE`, `POCKETR5900_LOG_FILE`.

## Tested so far

- Analyzer and recompiler as ARM64 programs (under qemu) on a PS2SDK homebrew ELF;
  the generated C++ linked with the runtime printed the correct results.
- Disc reader: raw MODE2/2352 `.bin` and `.iso` both extract identically to the
  original files, and the boot ELF is found from `SYSTEM.CNF`.
- The app's exact compile and link commands, with the SDK pack, build a working
  `libgame.so` that exports `ANativeActivity_onCreate`.
- clang and lld built as ARM64 Android programs (run under qemu): built the
  precompiled header, compiled the biggest generated files and linked a working
  `libgame.so` with the app's exact commands.
- PocketR5900-PC (Linux run of the same script with the NDK) converted a test
  `.bin`/`.cue` into a `.pr5900`, carries on after a stop, and the app's import of
  it gives the same library and disc.
- Exported APKs pass `apksigner verify` (v3) and `zipalign -c`, with the new
  package name and title.

## Note on retail games

Upstream recommends a Ghidra function export for stripped retail games. Ghidra cannot
run on the phone, so PocketR5900 uses the analyzer's own path (heuristic scan plus the
embedded SDK symbol database). Some retail games may need per-game fixes.
