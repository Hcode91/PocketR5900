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

## Status

- [x] Analyzer and recompiler build as Android ARM64 programs
  (`-DPS2X_ANDROID_TOOLS=ON`, CI: `pocket-android-tools.yml`).
- [x] Tested: ARM64 analyzer + recompiler (run under qemu) converted a PS2SDK
  homebrew test ELF; the generated C++, linked with the runtime, printed the
  correct results.
- [ ] Disc reader (bin/cue, ISO)
- [ ] App UI calling the tools
- [ ] Bundled clang + on-phone compile of the generated code
- [ ] Load and run the compiled game in the app
- [ ] Per-game APK packaging and signing on the phone

## Note on retail games

Upstream recommends a Ghidra function export for stripped retail games. Ghidra cannot
run on the phone, so PocketR5900 uses the analyzer's own path (heuristic scan plus the
embedded SDK symbol database). Some retail games may need per-game fixes.
