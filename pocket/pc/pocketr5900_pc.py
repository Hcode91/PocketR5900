"""PocketR5900-PC: convert a PS2 game you own on a PC, for PocketR5900 on Android.

It does the same steps as the phone app (read the disc, analyse, recompile,
compile, link) but uses the PC's processor, then writes one .pr5900 file
holding the compiled game (libgame.so, Android ARM64) and the disc. Open
that file with "Add a game" in PocketR5900 on the phone.

Layout next to the program:
    PocketR5900-PC.exe
    tools/ps2_analyzer.exe, tools/ps2_recomp.exe
    sdk.zip, sdk.version              (Android sysroot + PS2 runtime, same as the phone)
Google's Android NDK (for its clang) is downloaded on first use.

Run without arguments for the window, or from a terminal:
    PocketR5900-PC-cli --cli GAME [--fast-game] [--out FOLDER] [--no-disc] [--title TITLE]
"""

import concurrent.futures
import os
import re
import shutil
import struct
import subprocess
import sys
import threading
import time
import urllib.request
import zipfile

APP_VERSION = "0.1.0"
PACKAGE_FORMAT = "1"
NDK_REVISION = "r28c"
OPT_FAST_CONVERT = "-O1"
OPT_FAST_GAME = "-O2"
HUGE_SOURCE = 6 * 1024 * 1024
SECTOR = 2048
RAW_SECTOR = 2352
SYNC = bytes([0] + [0xFF] * 10 + [0])
IS_WINDOWS = os.name == "nt"
EXE = ".exe" if IS_WINDOWS else ""


class Cancelled(Exception):
    pass


class ConvertError(Exception):
    pass


# --------------------------------------------------------------------- places

def program_dir():
    if getattr(sys, "frozen", False):
        return os.path.dirname(os.path.abspath(sys.executable))
    return os.path.dirname(os.path.abspath(__file__))


def data_dir():
    if IS_WINDOWS:
        base = os.environ.get("LOCALAPPDATA") or os.path.expanduser("~")
        return os.path.join(base, "PocketR5900")
    return os.path.join(os.path.expanduser("~"), ".local", "share", "pocketr5900")


def ndk_host():
    if IS_WINDOWS:
        return "windows-x86_64"
    if sys.platform == "darwin":
        return "darwin-x86_64"
    return "linux-x86_64"


def ndk_url():
    name = {"windows-x86_64": "windows", "darwin-x86_64": "darwin", "linux-x86_64": "linux"}[ndk_host()]
    return "https://dl.google.com/android/repository/android-ndk-%s-%s.zip" % (NDK_REVISION, name)


# ----------------------------------------------------------------------- disc

def detect_layout(path):
    """(sector_size, data_offset, sectors) for a raw CD image or an ISO."""
    size = os.path.getsize(path)
    with open(path, "rb") as f:
        head = f.read(16)
    if head[:12] == SYNC and size % RAW_SECTOR == 0:
        offset = 16 if head[15] == 1 else 24  # MODE2 form 1 has an 8-byte subheader
        return RAW_SECTOR, offset, size // RAW_SECTOR
    if size % SECTOR != 0:
        raise ConvertError("This file is not a disc image (its size is not a whole number of sectors).")
    return SECTOR, 0, size // SECTOR


def to_iso(image, layout, iso, progress):
    sector_size, offset, sectors = layout
    done = 0
    with open(image, "rb") as src, open(iso, "wb") as dst:
        while done < sectors:
            count = min(512, sectors - done)
            raw = src.read(count * sector_size)
            out = bytearray()
            for i in range(count):
                s = i * sector_size + offset
                out += raw[s:s + SECTOR]
            dst.write(out)
            done += count
            progress("Reading the disc", done, sectors)


def _le32(b, o):
    return struct.unpack_from("<I", b, o)[0]


def extract_iso(iso, dest, progress):
    """Extracts an ISO 9660 file system; returns the number of files."""
    total = os.path.getsize(iso)
    state = {"done": 0, "files": 0}
    with open(iso, "rb") as f:
        f.seek(16 * SECTOR)
        pvd = f.read(SECTOR)
        if pvd[0] != 1 or pvd[1:6] != b"CD001":
            raise ConvertError("No ISO 9660 file system found on this disc.")
        os.makedirs(dest, exist_ok=True)

        def walk(lba, size, folder, depth):
            if depth > 32:
                raise ConvertError("Directory tree on the disc is too deep.")
            f.seek(lba * SECTOR)
            data = f.read(((size + SECTOR - 1) // SECTOR) * SECTOR)
            pos = 0
            while pos < len(data):
                length = data[pos]
                if length == 0:
                    pos = (pos // SECTOR + 1) * SECTOR
                    continue
                if pos + 33 > len(data):
                    break
                extent = _le32(data, pos + 2)
                size_ = _le32(data, pos + 10)
                flags = data[pos + 25]
                name_len = data[pos + 32]
                raw = data[pos + 33:pos + 33 + name_len]
                pos += length
                if name_len == 1 and raw[0] in (0, 1):
                    continue
                name = raw.decode("latin-1").split(";")[0].rstrip(".")
                if not name or "/" in name or "\\" in name or name == "..":
                    continue
                target = os.path.join(folder, name)
                if flags & 2:
                    os.makedirs(target, exist_ok=True)
                    here = f.tell()
                    walk(extent, size_, target, depth + 1)
                    f.seek(here)
                else:
                    here = f.tell()
                    f.seek(extent * SECTOR)
                    left = size_
                    with open(target, "wb") as out:
                        while left > 0:
                            chunk = f.read(min(1 << 20, left))
                            if not chunk:
                                break
                            out.write(chunk)
                            left -= len(chunk)
                    f.seek(here)
                    state["files"] += 1
                    state["done"] += size_
                    progress("Extracting the disc", state["done"], total)

        walk(_le32(pvd, 158), _le32(pvd, 166), dest, 0)
    return state["files"]


def find_ignore_case(root, relative):
    current = root
    for part in relative.split("/"):
        if not part:
            continue
        exact = os.path.join(current, part)
        if os.path.exists(exact):
            current = exact
            continue
        match = None
        if os.path.isdir(current):
            for n in os.listdir(current):
                if n.lower() == part.lower():
                    match = os.path.join(current, n)
                    break
        if match is None:
            return None
        current = match
    return current


def boot_path(extracted):
    cnf = find_ignore_case(extracted, "SYSTEM.CNF")
    if not cnf:
        raise ConvertError("SYSTEM.CNF is missing, so this does not look like a PS2 disc.")
    with open(cnf, "rb") as f:
        text = f.read().decode("latin-1")
    for line in re.split(r"[\r\n]+", text):
        t = line.strip()
        if not t.upper().startswith("BOOT2"):
            continue
        value = t.split("=", 1)[1].strip() if "=" in t else ""
        if ":" in value:
            value = value.split(":", 1)[1]
        value = value.split(";")[0].replace("\\", "/").lstrip("/")
        if value:
            return value
    raise ConvertError("SYSTEM.CNF has no BOOT2 line, so this is not a PS2 game disc (it may be a PS1 disc).")


def is_elf(path):
    try:
        with open(path, "rb") as f:
            return f.read(4) == b"\x7fELF"
    except OSError:
        return False


def bin_from_cue(cue):
    with open(cue, "rb") as f:
        text = f.read().decode("latin-1")
    for line in text.splitlines():
        t = line.strip()
        if t.upper().startswith("FILE"):
            m = re.search(r'"([^"]+)"', t)
            if m:
                return os.path.join(os.path.dirname(cue), re.split(r"[\\/]", m.group(1))[-1])
    return None


# ------------------------------------------------------------------ converter

class Converter:
    def __init__(self, source, title=None, opt=OPT_FAST_CONVERT, out_dir=None, include_disc=True,
                 log=print, status=None, jobs=None):
        self.source = os.path.abspath(source)
        self.title = title or re.sub(r"\.[A-Za-z0-9]{1,4}$", "", os.path.basename(source))
        self.opt = opt
        self.out_dir = out_dir or os.path.dirname(self.source)
        self.include_disc = include_disc
        self._log = log
        self._status = status or (lambda text, progress: None)
        self.jobs = jobs or max(1, (os.cpu_count() or 2))
        self.cancelled = False
        self._procs = set()
        self._lock = threading.Lock()
        slug = re.sub(r"[^A-Za-z0-9]+", "-", self.title).strip("-") or "game"
        self.work = os.path.join(data_dir(), "work", slug[:40])
        self.sdk = os.path.join(data_dir(), "sdk")
        self.ndk = os.path.join(data_dir(), "ndk", "android-ndk-" + NDK_REVISION)
        self._last_progress = 0.0

    # -- reporting
    def log(self, line):
        self._log(line)

    def status(self, text, progress=-1.0):
        self._log("--- " + text)
        self._status(text, progress)

    def progress(self, what, done, total):
        if self.cancelled:
            raise Cancelled()
        now = time.time()
        if now - self._last_progress < 0.25 and done < total:
            return
        self._last_progress = now
        frac = done / total if total else -1.0
        self._status("%s - %d%%" % (what, int(frac * 100)), frac)

    def cancel(self):
        self.cancelled = True
        with self._lock:
            for p in list(self._procs):
                try:
                    p.kill()
                except OSError:
                    pass

    # -- running programs
    def run(self, cmd, cwd, on_line=None):
        if self.cancelled:
            raise Cancelled()
        flags = 0x08000000 if IS_WINDOWS else 0  # CREATE_NO_WINDOW
        p = subprocess.Popen(cmd, cwd=cwd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                             creationflags=flags)
        with self._lock:
            self._procs.add(p)
        try:
            for raw in p.stdout:
                line = raw.decode("utf-8", "replace").rstrip("\r\n")
                if on_line:
                    on_line(line)
            code = p.wait()
        finally:
            with self._lock:
                self._procs.discard(p)
        if self.cancelled:
            raise Cancelled()
        return code

    # -- setup
    def tool(self, name):
        path = os.path.join(program_dir(), "tools", name + EXE)
        if not os.path.isfile(path):
            raise ConvertError("Missing %s next to the program. Use the full PocketR5900-PC download." % path)
        return path

    def prepare(self):
        pack = os.path.join(program_dir(), "sdk.zip")
        version_file = os.path.join(program_dir(), "sdk.version")
        if not os.path.isfile(pack):
            raise ConvertError("sdk.zip is missing next to the program. Use the full PocketR5900-PC download.")
        wanted = open(version_file).read().strip() if os.path.isfile(version_file) else ""
        have_file = os.path.join(self.sdk, "VERSION")
        have = open(have_file).read().strip() if os.path.isfile(have_file) else ""
        if not wanted or wanted != have:
            self.status("Unpacking the compiler files (first run only)")
            tmp = self.sdk + ".tmp"
            shutil.rmtree(tmp, ignore_errors=True)
            with zipfile.ZipFile(pack) as z:
                z.extractall(tmp)
            shutil.rmtree(self.sdk, ignore_errors=True)
            os.makedirs(os.path.dirname(self.sdk), exist_ok=True)
            os.replace(os.path.join(tmp, "sdk"), self.sdk)
            shutil.rmtree(tmp, ignore_errors=True)

        env_ndk = os.environ.get("POCKETR5900_NDK")
        if env_ndk and os.path.isdir(env_ndk):
            self.ndk = env_ndk
        if not os.path.isfile(self.clangxx()):
            self.download_ndk()

    def download_ndk(self):
        url = ndk_url()
        target = os.path.dirname(self.ndk)
        os.makedirs(target, exist_ok=True)
        tmp_zip = os.path.join(target, "ndk-download.zip")
        self.status("Downloading Google's Android NDK (first run only, about 700 MB)")
        with urllib.request.urlopen(url) as r, open(tmp_zip, "wb") as f:
            total = int(r.headers.get("Content-Length") or 0)
            done = 0
            while True:
                chunk = r.read(1 << 20)
                if not chunk:
                    break
                f.write(chunk)
                done += len(chunk)
                self.progress("Downloading the Android NDK", done, total or done)
        self.status("Unpacking the Android NDK")
        with zipfile.ZipFile(tmp_zip) as z:
            names = z.infolist()
            for i, info in enumerate(names):
                z.extract(info, target)
                if not IS_WINDOWS:
                    mode = (info.external_attr >> 16) & 0o777
                    if mode:
                        os.chmod(os.path.join(target, info.filename), mode)
                if i % 500 == 0:
                    self.progress("Unpacking the Android NDK", i, len(names))
        os.remove(tmp_zip)
        if not os.path.isfile(self.clangxx()):
            raise ConvertError("The Android NDK download did not contain clang.")

    def clangxx(self):
        return os.path.join(self.ndk, "toolchains", "llvm", "prebuilt", ndk_host(), "bin", "clang++" + EXE)

    # -- steps
    def convert(self):
        start = time.time()
        os.makedirs(self.work, exist_ok=True)
        self.log("=== PocketR5900-PC %s converting %s (%s, %d jobs)" % (APP_VERSION, self.title, self.opt, self.jobs))
        self.prepare()
        kind, boot_abs, boot_rel, iso = self.disc()
        # Carry on from an earlier, stopped conversion of the same game with the
        # same settings: keep the recompiled C++ and the objects already built.
        marker = os.path.join(self.work, "recompiled.ok")
        key = "%s|%d|%s|%s" % (self.source, os.path.getsize(self.source), APP_VERSION, self.opt)
        if os.path.isfile(marker) and open(marker).read() == key and os.path.isdir(os.path.join(self.work, "output")):
            self.log("Using the C++ from the earlier conversion; carrying on with compiling.")
        else:
            if os.path.exists(marker):
                os.remove(marker)
            self.analyze(boot_abs)
            self.recompile()
            with open(marker, "w") as f:
                f.write(key)
        self.compile()
        lib = self.link()
        out = self.package(kind, lib, iso, boot_abs, boot_rel)
        mins = (time.time() - start) / 60
        self.log("=== Finished in %.1f minutes: %s" % (mins, out))
        self._status("Done: " + os.path.basename(out), 1.0)
        return out

    def disc(self):
        disc_dir = os.path.join(self.work, "disc")
        done_marker = os.path.join(self.work, "disc.ok")
        key = "%s|%d" % (self.source, os.path.getsize(self.source))
        if os.path.isfile(done_marker):
            saved = open(done_marker).read().split("\n")
            if len(saved) == 4 and saved[0] == key and os.path.isfile(saved[2]):
                self.log("Using the disc read earlier.")
                return saved[1], saved[2], saved[3], (saved[1] == "disc" and self._iso_path()) or None
        result = self._read_disc(disc_dir)
        with open(done_marker, "w") as f:
            f.write("\n".join([key, result[0], result[1], result[2]]))
        return result

    def _iso_path(self):
        src = self.source
        if src.lower().endswith(".cue"):
            src = bin_from_cue(src) or src
        iso = os.path.join(self.work, "disc.iso")
        return iso if os.path.isfile(iso) else src

    def _read_disc(self, disc_dir):
        src = self.source
        if src.lower().endswith(".cue"):
            b = bin_from_cue(src)
            if not b or not os.path.isfile(b):
                raise ConvertError("The .bin named in the .cue was not found next to it.")
            src = b
        if is_elf(src):
            shutil.rmtree(disc_dir, ignore_errors=True)
            os.makedirs(disc_dir)
            boot = os.path.join(disc_dir, os.path.basename(src))
            shutil.copyfile(src, boot)
            self.log("Boot program: " + os.path.basename(src))
            return "elf", boot, os.path.basename(src), None

        iso = os.path.join(self.work, "disc.iso")
        layout = detect_layout(src)
        if layout[0] == RAW_SECTOR:
            self.log("Disc: raw CD image, %d sectors" % layout[2])
            to_iso(src, layout, iso + ".tmp", self.progress)
            os.replace(iso + ".tmp", iso)
        else:
            self.log("Disc: ISO image, %d sectors" % layout[2])
            iso = src  # used as it is; never modified
        shutil.rmtree(disc_dir, ignore_errors=True)
        files = extract_iso(iso, disc_dir, self.progress)
        self.log("Extracted %d files." % files)
        rel = boot_path(disc_dir)
        boot = find_ignore_case(disc_dir, rel)
        if not boot or not os.path.isfile(boot):
            raise ConvertError("The boot program %s named in SYSTEM.CNF is not on the disc." % rel)
        if not is_elf(boot):
            raise ConvertError(os.path.basename(boot) + " is not an ELF program.")
        self.log("Boot program: " + os.path.basename(boot))
        return "disc", boot, os.path.relpath(boot, disc_dir).replace("\\", "/"), iso

    def analyze(self, boot):
        config = os.path.join(self.work, "config.toml")
        self.status("Analysing the game's code")
        code = self.run([self.tool("ps2_analyzer"), boot, config], self.work, self.log)
        if code != 0 or not os.path.isfile(config):
            raise ConvertError("The analyzer failed (exit code %d). See the log." % code)
        with open(config, encoding="utf-8") as f:
            toml = f.read()
        output = os.path.join(self.work, "output").replace("\\", "/") + "/"
        toml = set_toml_value(toml, "output", '"%s"' % output)
        toml = set_toml_value(toml, "single_file_output", "false")
        toml = set_toml_value(toml, "input", '"%s"' % boot.replace("\\", "/"))
        with open(config, "w", encoding="utf-8") as f:
            f.write(toml)

    def recompile(self):
        out = os.path.join(self.work, "output")
        shutil.rmtree(out, ignore_errors=True)
        shutil.rmtree(os.path.join(self.work, "obj"), ignore_errors=True)
        os.makedirs(out)
        self.status("Recompiling the game into C++")
        code = self.run([self.tool("ps2_recomp"), os.path.join(self.work, "config.toml")], self.work, self.log)
        sources = [n for n in os.listdir(out) if n.endswith(".cpp")]
        if code != 0 or not sources:
            raise ConvertError("The recompiler failed (exit code %d). See the log." % code)
        self.log("Recompiled into %d C++ files." % len(sources))

    def base_command(self):
        return [self.clangxx(), "--target=aarch64-linux-android28",
                "--sysroot=" + os.path.join(self.sdk, "sysroot")]

    def compile_command(self, opt, pch):
        inc = os.path.join(self.sdk, "include")
        out = os.path.join(self.work, "output")
        cmd = self.base_command() + [
            "-std=gnu++20", opt, "-fPIC", "-frtti", "-fexceptions",
            "-ffunction-sections", "-fdata-sections", "-funwind-tables",
            "-march=armv8-a+fp+simd+crypto+crc", "-w",
            "-DANDROID", "-DNDEBUG", "-DPLATFORM_ANDROID", "-DGRAPHICS_API_OPENGL_ES2",
            "-DUSE_SSE2NEON", "-DPS2_RUNTIME_LOGS=1", "-DAGRESSIVE_LOGS=1", "-DPS2X_ENABLE_IOP_RPC_TRACE=1",
            "-I" + out, "-I" + os.path.join(inc, "sse2neon"), "-I" + os.path.join(inc, "runtime"),
            "-I" + os.path.join(inc, "kernel"), "-I" + os.path.join(inc, "raylib"), "-I" + os.path.join(inc, "iop")]
        if pch:
            cmd += ["-include-pch", pch]
        return cmd

    def precompile_header(self):
        header = os.path.join(self.work, "pocket_pch.h")
        pch = os.path.join(self.work, "pocket_pch-%s.pch" % self.opt[1:])
        text = ('#pragma once\n#include <stdexcept>\n#include "ps2_runtime_macros.h"\n#include "ps2_runtime.h"\n'
                '#include "ps2_syscalls.h"\n#include "ps2_stubs.h"\n')
        if not os.path.isfile(header) or open(header).read() != text:
            with open(header, "w") as f:
                f.write(text)
        if os.path.exists(pch):
            os.remove(pch)
        self.status("Preparing the compiler")
        lines = []
        code = self.run(self.compile_command(self.opt, None) + ["-x", "c++-header", header, "-o", pch],
                        self.work, lines.append)
        if code != 0 or not os.path.isfile(pch):
            self.log("Precompiled header not used:\n" + "\n".join(lines))
            return None
        return pch

    def compile(self):
        out = os.path.join(self.work, "output")
        obj = os.path.join(self.work, "obj")
        os.makedirs(obj, exist_ok=True)
        sources = sorted((n for n in os.listdir(out) if n.endswith(".cpp")),
                         key=lambda n: -os.path.getsize(os.path.join(out, n)))
        pch = self.precompile_header()
        todo = []
        for n in sources:
            src = os.path.join(out, n)
            o = os.path.join(obj, n[:-4] + ".o")
            if not (os.path.isfile(o) and os.path.getsize(o) > 0 and os.path.getmtime(o) >= os.path.getmtime(src)):
                todo.append(n)
        total = len(sources)
        done = [total - len(todo)]
        self.log("Compiling %d files with %d jobs (%s), %d already done." % (len(todo), self.jobs, self.opt, done[0]))
        failure = []

        def one(n):
            if failure or self.cancelled:
                return
            src = os.path.join(out, n)
            o = os.path.join(obj, n[:-4] + ".o")
            huge = os.path.getsize(src) > HUGE_SOURCE
            cmd = self.compile_command("-O0", None) if huge else self.compile_command(self.opt, pch)
            lines = []
            code = self.run(cmd + ["-c", src, "-o", o + ".tmp"], self.work,
                            lambda l: lines.append(l) if len(lines) < 200 else None)
            if code != 0:
                with self._lock:
                    if not failure:
                        failure.append("Compiling %s failed:\n%s" % (n, "\n".join(lines)))
                self.cancel()
                return
            os.replace(o + ".tmp", o)
            with self._lock:
                done[0] += 1
                d = done[0]
            self.progress("Compiling", d, total)

        with concurrent.futures.ThreadPoolExecutor(self.jobs) as pool:
            futures = [pool.submit(one, n) for n in todo]
            for f in futures:
                try:
                    f.result()
                except Cancelled:
                    pass
        if failure:
            self.log(failure[0])
            raise ConvertError(failure[0].splitlines()[0] + " See the log.")
        if self.cancelled:
            raise Cancelled()

    def link(self):
        obj = os.path.join(self.work, "obj")
        objects = [os.path.join(obj, n) for n in os.listdir(obj) if n.endswith(".o")]
        rsp = os.path.join(self.work, "objects.rsp")
        with open(rsp, "w") as f:
            for o in objects:
                f.write('"%s"\n' % o.replace("\\", "/"))
        lib = os.path.join(self.work, "libgame.so")
        sl = os.path.join(self.sdk, "lib")
        cmd = self.base_command() + [
            "-shared", "-static-libstdc++", "-rtlib=compiler-rt", "-unwindlib=libunwind", "-fuse-ld=lld",
            "-Wl,-soname,libgame.so", "-Wl,--build-id=sha1", "-Wl,--no-undefined",
            "-Wl,--gc-sections", "-Wl,--undefined=ANativeActivity_onCreate",
            "-o", lib + ".tmp", "@" + rsp, os.path.join(sl, "main.o"),
            "-Wl,--start-group", os.path.join(sl, "libps2_runtime.a"), os.path.join(sl, "libps2_iop.a"),
            os.path.join(sl, "libraylib.a"), "-Wl,--end-group",
            "-lm", "-lEGL", "-lGLESv2", "-lOpenSLES", "-latomic", "-llog", "-landroid"]
        self.status("Linking the game")
        code = self.run(cmd, self.work, self.log)
        if code != 0 or not os.path.isfile(lib + ".tmp"):
            raise ConvertError("Linking failed (exit code %d). See the log." % code)
        os.replace(lib + ".tmp", lib)
        self.log("Built libgame.so (%.1f MB)." % (os.path.getsize(lib) / 1048576))
        return lib

    def package(self, kind, lib, iso, boot_abs, boot_rel):
        os.makedirs(self.out_dir, exist_ok=True)
        safe = re.sub(r'[\\/:*?"<>|]+', "_", self.title).strip() or "game"
        out = os.path.join(self.out_dir, safe + ".pr5900")
        serial = os.path.basename(boot_abs) if kind == "disc" else ""
        manifest = "\n".join([
            "format=" + PACKAGE_FORMAT,
            "title=" + self.title.replace("\n", " "),
            "kind=" + kind,
            "boot=" + boot_rel,
            "serial=" + serial,
            "opt=" + self.opt,
            "disc=" + ("yes" if (self.include_disc or kind == "elf") else "no"),
            "made_by=PocketR5900-PC " + APP_VERSION,
        ]) + "\n"
        self.status("Writing " + os.path.basename(out))
        tmp = out + ".tmp"
        with zipfile.ZipFile(tmp, "w", allowZip64=True) as z:
            z.writestr("manifest.properties", manifest)
            z.write(lib, "libgame.so", compress_type=zipfile.ZIP_DEFLATED)
            if kind == "elf":
                z.write(boot_abs, "elf/" + os.path.basename(boot_abs), compress_type=zipfile.ZIP_DEFLATED)
            elif self.include_disc:
                z.write(iso, "disc.iso", compress_type=zipfile.ZIP_STORED)
        os.replace(tmp, out)
        self.log("Wrote %s (%.1f MB)." % (out, os.path.getsize(out) / 1048576))
        return out


def set_toml_value(toml, key, value):
    pattern = re.compile(r"(?m)^(\s*" + re.escape(key) + r"\s*=\s*).*$")
    if pattern.search(toml):
        return pattern.sub(lambda m: m.group(1) + value, toml, count=1)
    i = toml.find("[general]")
    if i >= 0:
        eol = toml.find("\n", i)
        return toml[:eol + 1] + "%s = %s\n" % (key, value) + toml[eol + 1:]
    return toml


# ------------------------------------------------------------------------ CLI

def main_cli(argv):
    import argparse
    ap = argparse.ArgumentParser(prog="PocketR5900-PC", description="Convert a PS2 game for PocketR5900.")
    ap.add_argument("--cli", action="store_true")
    ap.add_argument("game", help=".bin, .cue, .iso or PS2 ELF")
    ap.add_argument("--title")
    ap.add_argument("--fast-game", action="store_true", help="-O2: faster game, slower conversion")
    ap.add_argument("--out", help="folder for the .pr5900 file (default: next to the game)")
    ap.add_argument("--no-disc", action="store_true", help="leave the disc out (it is already on the phone)")
    ap.add_argument("--jobs", type=int)
    a = ap.parse_args(argv)

    def status(text, progress):
        if progress >= 0:
            sys.stdout.write("\r%-70s" % text[:70])
            sys.stdout.flush()

    conv = Converter(a.game, a.title, OPT_FAST_GAME if a.fast_game else OPT_FAST_CONVERT, a.out,
                     not a.no_disc, log=lambda l: print("\n" + l if l.startswith("---") else l),
                     status=status, jobs=a.jobs)
    try:
        conv.convert()
        return 0
    except (ConvertError, Cancelled) as e:
        print("\nError: %s" % e)
        return 1


# ------------------------------------------------------------------------ GUI

def main_gui():
    import tkinter as tk
    from tkinter import filedialog, messagebox, ttk

    root = tk.Tk()
    root.title("PocketR5900-PC %s" % APP_VERSION)
    root.geometry("760x560")
    frm = ttk.Frame(root, padding=12)
    frm.pack(fill="both", expand=True)

    game_var = tk.StringVar()
    title_var = tk.StringVar()
    out_var = tk.StringVar()
    opt_var = tk.StringVar(value=OPT_FAST_CONVERT)
    disc_var = tk.BooleanVar(value=True)
    status_var = tk.StringVar(value="Pick your own dump of a PS2 game.")

    def pick_game():
        p = filedialog.askopenfilename(title="Your PS2 game",
                                       filetypes=[("PS2 game", "*.cue *.bin *.iso *.elf *.ELF"), ("All files", "*.*")])
        if p:
            game_var.set(p)
            title_var.set(re.sub(r"\.[A-Za-z0-9]{1,4}$", "", os.path.basename(p)))
            if not out_var.get():
                out_var.set(os.path.dirname(p))

    def pick_out():
        p = filedialog.askdirectory(title="Where to save the .pr5900 file")
        if p:
            out_var.set(p)

    r = 0
    ttk.Label(frm, text="Game (.cue / .bin / .iso / ELF):").grid(row=r, column=0, sticky="w")
    ttk.Entry(frm, textvariable=game_var, width=70).grid(row=r, column=1, sticky="we")
    ttk.Button(frm, text="Browse...", command=pick_game).grid(row=r, column=2, padx=4)
    r += 1
    ttk.Label(frm, text="Title:").grid(row=r, column=0, sticky="w")
    ttk.Entry(frm, textvariable=title_var, width=70).grid(row=r, column=1, sticky="we")
    r += 1
    ttk.Label(frm, text="Save to:").grid(row=r, column=0, sticky="w")
    ttk.Entry(frm, textvariable=out_var, width=70).grid(row=r, column=1, sticky="we")
    ttk.Button(frm, text="Browse...", command=pick_out).grid(row=r, column=2, padx=4)
    r += 1
    opts = ttk.Frame(frm)
    opts.grid(row=r, column=0, columnspan=3, sticky="w", pady=6)
    ttk.Radiobutton(opts, text="Faster conversion (-O1)", variable=opt_var, value=OPT_FAST_CONVERT).pack(side="left")
    ttk.Radiobutton(opts, text="Faster game (-O2)", variable=opt_var, value=OPT_FAST_GAME).pack(side="left", padx=12)
    ttk.Checkbutton(opts, text="Include the disc (untick if it is already on the phone)",
                    variable=disc_var).pack(side="left", padx=12)
    r += 1
    bar = ttk.Progressbar(frm, maximum=1000)
    bar.grid(row=r, column=0, columnspan=3, sticky="we", pady=4)
    r += 1
    ttk.Label(frm, textvariable=status_var).grid(row=r, column=0, columnspan=3, sticky="w")
    r += 1
    logbox = tk.Text(frm, height=18, wrap="none", font=("Consolas", 9))
    logbox.grid(row=r, column=0, columnspan=3, sticky="nsew", pady=6)
    frm.rowconfigure(r, weight=1)
    frm.columnconfigure(1, weight=1)
    r += 1
    buttons = ttk.Frame(frm)
    buttons.grid(row=r, column=0, columnspan=3, sticky="e")
    go = ttk.Button(buttons, text="Convert")
    stop = ttk.Button(buttons, text="Stop", state="disabled")
    go.pack(side="left", padx=4)
    stop.pack(side="left")

    state = {"conv": None}
    log_file = {"f": None}

    def ui(fn):
        root.after(0, fn)

    def log(line):
        if log_file["f"]:
            log_file["f"].write(line + "\n")
            log_file["f"].flush()
        if line.startswith("  [warning]"):
            return  # in the log file only; there are thousands

        def add():
            logbox.insert("end", line + "\n")
            if int(logbox.index("end-1c").split(".")[0]) > 3000:
                logbox.delete("1.0", "500.0")
            logbox.see("end")
        ui(add)

    def status(text, progress):
        def upd():
            status_var.set(text)
            if progress >= 0:
                bar.configure(mode="determinate")
                bar["value"] = progress * 1000
        ui(upd)

    def finish(ok, message, out=None):
        def done():
            go.configure(state="normal")
            stop.configure(state="disabled")
            status_var.set(message)
            if ok and out:
                if messagebox.askyesno("PocketR5900-PC", "Done.\n\n%s\n\nCopy this file to your phone and open it with "
                                       "\"Add a game\" in PocketR5900.\n\nOpen the folder?" % out):
                    if IS_WINDOWS:
                        subprocess.Popen(["explorer", "/select,", os.path.normpath(out)])
            elif not ok:
                messagebox.showerror("PocketR5900-PC", message)
        ui(done)

    def start():
        game = game_var.get().strip()
        if not os.path.isfile(game):
            messagebox.showerror("PocketR5900-PC", "Pick your game file first.")
            return
        conv = Converter(game, title_var.get().strip() or None, opt_var.get(), out_var.get().strip() or None,
                         disc_var.get(), log=log, status=status)
        state["conv"] = conv
        os.makedirs(conv.work, exist_ok=True)
        log_file["f"] = open(os.path.join(conv.work, "convert.log"), "a", encoding="utf-8")
        logbox.delete("1.0", "end")
        go.configure(state="disabled")
        stop.configure(state="normal")

        def worker():
            try:
                out = conv.convert()
                finish(True, "Done: " + out, out)
            except Cancelled:
                finish(False, "Stopped. Convert again carries on where it stopped.")
            except Exception as e:  # noqa: BLE001 - shown to the user
                log("!!! %s" % e)
                finish(False, "Error: %s\n\nThe full log is in %s" % (e, os.path.join(conv.work, "convert.log")))
        threading.Thread(target=worker, daemon=True).start()

    go.configure(command=start)
    stop.configure(command=lambda: state["conv"] and state["conv"].cancel())
    root.mainloop()


if __name__ == "__main__":
    if "--cli" in sys.argv[1:]:
        sys.exit(main_cli([a for a in sys.argv[1:] if a != "--cli"]))
    main_gui()
