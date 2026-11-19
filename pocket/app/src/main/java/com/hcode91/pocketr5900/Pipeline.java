package com.hcode91.pocketr5900;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a game into a native library, step by step:
 * disc -> analyze -> recompile -> compile -> link.
 *
 * <p>Every step records itself when it finishes, and compiled objects are kept, so a
 * conversion that was stopped carries on from where it stopped.
 */
final class Pipeline {
    interface Listener {
        /** A short status line and progress from 0 to 1 (or -1 when unknown). */
        void status(String text, float progress);
    }

    /** -O1 converts much faster; -O2 makes a faster game. */
    static final String OPT_FAST_CONVERT = "-O1";
    static final String OPT_FAST_GAME = "-O2";
    private static final long HUGE_SOURCE = 6L * 1024 * 1024;

    private final Game game;
    private final Toolchain tc;
    private final Listener listener;
    private final int jobs;
    private PrintWriter log;

    Pipeline(Game game, Toolchain tc, Listener listener) {
        this.game = game;
        this.tc = tc;
        this.listener = listener;
        int cores = Runtime.getRuntime().availableProcessors();
        this.jobs = Math.max(1, Math.min(6, cores - 1));
    }

    void run(android.content.Context context) throws IOException {
        if (!game.logs().isDirectory() && !game.logs().mkdirs()) {
            throw new IOException("Cannot create " + game.logs());
        }
        try (PrintWriter w = new PrintWriter(new FileWriter(game.convertLog(), true))) {
            log = w;
            log("=== " + new java.util.Date() + " - converting " + game.title());
            try {
                tc.prepare(context, this::log);
                Game.Step done = game.done();
                if (done == null) {
                    throw new IOException("This game was not imported properly. Delete it and add it again.");
                }
                if ("package".equals(game.props.getProperty("kind")) && done != Game.Step.READY) {
                    // Converted on a PC: unpack it and it is ready to play.
                    PcPackage.unpack(context, game, this::log, this::progress);
                    game.props.setProperty("step", Game.Step.READY.name());
                    game.props.remove("error");
                    game.save();
                    done = Game.Step.READY;
                }
                while (done != Game.Step.READY) {
                    Game.Step next = done.next();
                    switch (next) {
                        case DISC:
                            disc();
                            break;
                        case ANALYZED:
                            analyze();
                            break;
                        case RECOMPILED:
                            recompile();
                            break;
                        case COMPILED:
                            compile();
                            break;
                        case READY:
                            link();
                            break;
                        default:
                            throw new IOException("Unexpected step " + next);
                    }
                    game.props.setProperty("step", next.name());
                    game.props.remove("error");
                    game.save();
                    done = next;
                }
                log("=== Finished. The game is ready to play.");
                listener.status("Ready to play", 1f);
            } catch (IOException | RuntimeException e) {
                String message = e.getMessage() != null ? e.getMessage() : e.toString();
                if (!(e instanceof Toolchain.Cancelled)) {
                    game.props.setProperty("error", message);
                    game.save();
                    log("!!! " + message);
                    if (e instanceof RuntimeException) {
                        e.printStackTrace(log);
                    }
                } else {
                    log("=== Stopped. Converting again carries on from here.");
                }
                throw e;
            } finally {
                log.flush();
            }
        }
    }

    // ---------------------------------------------------------------- steps

    private void disc() throws IOException {
        String kind = game.props.getProperty("kind", "");
        if (kind.equals("elf")) {
            // A loose ELF: it is its own "disc"; its folder is the disc root.
            File elf = new File(game.source(), game.props.getProperty("sourceName", "game.elf"));
            File dir = game.disc();
            if (!dir.isDirectory() && !dir.mkdirs()) {
                throw new IOException("Cannot create " + dir);
            }
            File boot = new File(dir, elf.getName());
            if (!boot.isFile()) {
                Files.copy(elf.toPath(), boot.toPath());
            }
            game.props.setProperty("bootElf", boot.getAbsolutePath());
            log("Boot program: " + boot.getName());
            return;
        }

        File image = new File(game.source(), game.props.getProperty("sourceName", ""));
        File iso = game.iso();
        if (!iso.isFile() || iso.length() == 0) {
            Disc.Layout layout = Disc.detect(image);
            log("Disc: " + layout);
            if (layout.isRaw()) {
                File tmp = new File(game.dir, "disc.iso.tmp");
                Disc.toIso(image, layout, tmp, (d, t) -> progress("Reading the disc", d, t));
                if (!tmp.renameTo(iso)) {
                    throw new IOException("Cannot write " + iso);
                }
            } else if (!image.renameTo(iso)) {
                Files.copy(image.toPath(), iso.toPath());
            }
        }

        File out = game.disc();
        Game.deleteTree(out);
        int files = Disc.extract(iso, out, (d, t) -> progress("Extracting the disc", d, t));
        log("Extracted " + files + " files.");

        String bootPath = Disc.bootPath(out);
        File boot = Disc.findIgnoreCase(out, bootPath);
        if (boot == null || !boot.isFile()) {
            throw new IOException("The boot program " + bootPath + " named in SYSTEM.CNF is not on the disc.");
        }
        if (!Disc.isElf(boot)) {
            throw new IOException(boot.getName() + " is not an ELF program.");
        }
        game.props.setProperty("bootElf", boot.getAbsolutePath());
        game.props.setProperty("serial", boot.getName());
        log("Boot program: " + boot.getName());

        // The source images are no longer needed: the ISO and the files are kept.
        Game.deleteTree(game.source());
    }

    private void analyze() throws IOException {
        File work = game.work();
        if (!work.isDirectory() && !work.mkdirs()) {
            throw new IOException("Cannot create " + work);
        }
        File elf = requireElf();
        File config = new File(work, "config.toml");
        status("Analysing the game's code (this can take a few minutes)", -1);
        int code = tc.run(Arrays.asList(tc.analyzer().getAbsolutePath(), elf.getAbsolutePath(), config.getAbsolutePath()),
                work, this::tool);
        if (code != 0 || !config.isFile()) {
            throw new IOException("The analyzer failed (exit code " + code + "). See the log.");
        }

        // Point the output at our folder and keep memory use sensible on a phone.
        String toml = new String(Files.readAllBytes(config.toPath()), StandardCharsets.UTF_8);
        toml = setTomlValue(toml, "output", quote(game.output().getAbsolutePath() + "/"));
        toml = setTomlValue(toml, "single_file_output", "false");
        Files.write(config.toPath(), toml.getBytes(StandardCharsets.UTF_8));
    }

    private void recompile() throws IOException {
        File out = game.output();
        Game.deleteTree(out);
        if (!out.mkdirs()) {
            throw new IOException("Cannot create " + out);
        }
        // Objects from an earlier recompile no longer match.
        Game.deleteTree(game.objects());
        status("Recompiling the game into C++", -1);
        File config = new File(game.work(), "config.toml");
        int code = tc.run(Arrays.asList(tc.recompiler().getAbsolutePath(), config.getAbsolutePath()),
                game.work(), this::tool);
        String[] sources = out.list((d, n) -> n.endsWith(".cpp"));
        if (code != 0 || sources == null || sources.length == 0) {
            throw new IOException("The recompiler failed (exit code " + code + "). See the log.");
        }
        log("Recompiled into " + sources.length + " C++ files.");
    }

    private void compile() throws IOException {
        File out = game.output();
        File obj = game.objects();
        if (!obj.isDirectory() && !obj.mkdirs()) {
            throw new IOException("Cannot create " + obj);
        }
        File[] sources = out.listFiles((d, n) -> n.endsWith(".cpp"));
        if (sources == null || sources.length == 0) {
            throw new IOException("There is nothing to compile; convert the game again from the start.");
        }
        Arrays.sort(sources, (a, b) -> Long.compare(b.length(), a.length())); // big files first

        String opt = game.props.getProperty("opt", OPT_FAST_CONVERT);
        File pch = precompileHeader(opt);

        List<File> todo = new ArrayList<>();
        for (File src : sources) {
            File o = objectFor(src);
            if (!(o.isFile() && o.length() > 0 && o.lastModified() >= src.lastModified())) {
                todo.add(src);
            }
        }
        int total = sources.length;
        AtomicInteger finished = new AtomicInteger(total - todo.size());
        log(String.format(Locale.US, "Compiling %d files with %d jobs (%s), %d already done.",
                todo.size(), jobs, opt, finished.get()));
        progress("Compiling", finished.get(), total);

        ExecutorService pool = Executors.newFixedThreadPool(jobs);
        List<Future<?>> futures = new ArrayList<>();
        final IOException[] failure = {null};
        try {
            for (File src : todo) {
                futures.add(pool.submit(() -> {
                    if (failure[0] != null || tc.isCancelled()) {
                        return null;
                    }
                    File o = objectFor(src);
                    File tmp = new File(o.getPath() + ".tmp");
                    // A huge generated file (usually a function the recompiler could
                    // not split) can take hours and gigabytes to optimise on a phone:
                    // build those without optimisation, and without the header made
                    // for the optimised build.
                    boolean huge = src.length() > HUGE_SOURCE;
                    List<String> cmd = huge ? compileCommand("-O0", null) : compileCommand(opt, pch);
                    cmd.add("-c");
                    cmd.add(src.getAbsolutePath());
                    cmd.add("-o");
                    cmd.add(tmp.getAbsolutePath());
                    StringBuilder errors = new StringBuilder();
                    int code = tc.run(cmd, game.work(), line -> {
                        if (errors.length() < 8000) {
                            errors.append(line).append('\n');
                        }
                    });
                    if (code != 0 || !tmp.renameTo(o)) {
                        synchronized (failure) {
                            if (failure[0] == null) {
                                log("Compiling " + src.getName() + " failed:\n" + errors);
                                failure[0] = new IOException("Compiling " + src.getName() + " failed. See the log.");
                                tc.cancel();
                            }
                        }
                        return null;
                    }
                    progress("Compiling", finished.incrementAndGet(), total);
                    return null;
                }));
            }
            IOException other = null;
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (java.util.concurrent.ExecutionException e) {
                    // Once one file has failed the other jobs are stopped, and the
                    // stop itself shows up as errors here: keep the real failure.
                    Throwable c = e.getCause();
                    if (other == null && !(c instanceof Toolchain.Cancelled)) {
                        other = c instanceof IOException ? (IOException) c : new IOException(String.valueOf(c));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new Toolchain.Cancelled();
                }
            }
            if (failure[0] != null) {
                throw failure[0];
            }
            if (other != null && !tc.isCancelled()) {
                throw other;
            }
        } finally {
            pool.shutdownNow();
        }
        if (failure[0] != null) {
            throw failure[0];
        }
        if (tc.isCancelled()) {
            throw new Toolchain.Cancelled();
        }
    }

    private void link() throws IOException {
        File[] objects = game.objects().listFiles((d, n) -> n.endsWith(".o"));
        if (objects == null || objects.length == 0) {
            throw new IOException("There is nothing to link; convert the game again from the start.");
        }
        File rsp = new File(game.work(), "objects.rsp");
        StringBuilder sb = new StringBuilder();
        for (File o : objects) {
            sb.append('"').append(o.getAbsolutePath()).append("\"\n");
        }
        Files.write(rsp.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));

        File lib = game.gameLib();
        File tmp = new File(lib.getPath() + ".tmp");
        File sdkLib = new File(tc.sdk, "lib");
        List<String> cmd = baseCommand();
        cmd.addAll(Arrays.asList(
                "-shared", "-static-libstdc++", "-rtlib=compiler-rt", "-unwindlib=libunwind",
                "-fuse-ld=lld", "--ld-path=" + tc.ldLld().getAbsolutePath(),
                "-Wl,-soname,libgame.so", "-Wl,--build-id=sha1", "-Wl,--no-undefined",
                "-Wl,--gc-sections", "-Wl,--undefined=ANativeActivity_onCreate",
                "-o", tmp.getAbsolutePath(),
                "@" + rsp.getAbsolutePath(),
                new File(sdkLib, "main.o").getAbsolutePath(),
                "-Wl,--start-group",
                new File(sdkLib, "libps2_runtime.a").getAbsolutePath(),
                new File(sdkLib, "libps2_iop.a").getAbsolutePath(),
                new File(sdkLib, "libraylib.a").getAbsolutePath(),
                "-Wl,--end-group",
                "-lm", "-lEGL", "-lGLESv2", "-lOpenSLES", "-latomic", "-llog", "-landroid"));
        status("Linking the game", -1);
        int code = tc.run(cmd, game.work(), this::tool);
        if (code != 0 || !tmp.isFile()) {
            throw new IOException("Linking failed (exit code " + code + "). See the log.");
        }
        if (!tmp.renameTo(lib)) {
            throw new IOException("Cannot write " + lib);
        }
        log(String.format(Locale.US, "Built %s (%.1f MB).", lib.getName(), lib.length() / 1048576.0));
    }

    // ------------------------------------------------------------- compiling

    private List<String> baseCommand() {
        File s = tc.sdk;
        return new ArrayList<>(Arrays.asList(
                tc.clang().getAbsolutePath(),
                "--driver-mode=g++",
                "--target=aarch64-linux-android28",
                "--sysroot=" + new File(s, "sysroot").getAbsolutePath(),
                "-resource-dir=" + new File(s, "resource").getAbsolutePath()));
    }

    private List<String> compileCommand(String opt, File pch) {
        File inc = new File(tc.sdk, "include");
        List<String> cmd = baseCommand();
        cmd.addAll(Arrays.asList(
                "-std=gnu++20", opt, "-fPIC", "-frtti", "-fexceptions",
                "-ffunction-sections", "-fdata-sections", "-funwind-tables",
                "-march=armv8-a+fp+simd+crypto+crc", "-w",
                "-DANDROID", "-DNDEBUG", "-DPLATFORM_ANDROID", "-DGRAPHICS_API_OPENGL_ES2",
                "-DUSE_SSE2NEON", "-DPS2_RUNTIME_LOGS=1", "-DAGRESSIVE_LOGS=1", "-DPS2X_ENABLE_IOP_RPC_TRACE=1",
                "-I" + game.output().getAbsolutePath(),
                "-I" + new File(inc, "sse2neon").getAbsolutePath(),
                "-I" + new File(inc, "runtime").getAbsolutePath(),
                "-I" + new File(inc, "kernel").getAbsolutePath(),
                "-I" + new File(inc, "raylib").getAbsolutePath(),
                "-I" + new File(inc, "iop").getAbsolutePath()));
        if (pch != null) {
            cmd.add("-include-pch");
            cmd.add(pch.getAbsolutePath());
        }
        return cmd;
    }

    /**
     * Every generated file starts with the same heavy runtime headers; parsing them once
     * into a precompiled header makes each file compile much faster.
     */
    private File precompileHeader(String opt) throws IOException {
        File header = new File(game.work(), "pocket_pch.h");
        File pch = new File(game.work(), "pocket_pch-" + opt.substring(1) + ".pch");
        String text = "#pragma once\n#include <stdexcept>\n#include \"ps2_runtime_macros.h\"\n#include \"ps2_runtime.h\"\n"
                + "#include \"ps2_syscalls.h\"\n#include \"ps2_stubs.h\"\n";
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        // Only rewrite the header when it changes: clang rejects a precompiled
        // header whose source file has a newer date than it.
        if (!header.isFile() || !java.util.Arrays.equals(Files.readAllBytes(header.toPath()), bytes)) {
            Files.write(header.toPath(), bytes);
        }
        // Always build it fresh for this compile step; an old one (from an earlier
        // conversion or app version) may no longer match the header or the SDK.
        //noinspection ResultOfMethodCallIgnored
        pch.delete();
        List<String> cmd = compileCommand(opt, null);
        cmd.addAll(Arrays.asList("-x", "c++-header", header.getAbsolutePath(), "-o", pch.getAbsolutePath()));
        status("Preparing the compiler", -1);
        StringBuilder out = new StringBuilder();
        int code = tc.run(cmd, game.work(), line -> out.append(line).append('\n'));
        if (code != 0 || !pch.isFile()) {
            // Not fatal: compiling still works, only slower.
            log("Precompiled header not used:\n" + out);
            //noinspection ResultOfMethodCallIgnored
            pch.delete();
            return null;
        }
        return pch;
    }

    private File objectFor(File src) {
        String n = src.getName();
        return new File(game.objects(), n.substring(0, n.length() - 4) + ".o");
    }

    // --------------------------------------------------------------- helpers

    private File requireElf() throws IOException {
        File elf = game.bootElf();
        if (elf == null || !elf.isFile()) {
            throw new IOException("The game's boot program is missing; delete the game and add it again.");
        }
        return elf;
    }

    static String setTomlValue(String toml, String key, String value) {
        Pattern p = Pattern.compile("(?m)^(\\s*" + Pattern.quote(key) + "\\s*=\\s*).*$");
        Matcher m = p.matcher(toml);
        if (m.find()) {
            return m.replaceFirst(Matcher.quoteReplacement(m.group(1) + value));
        }
        int general = toml.indexOf("[general]");
        if (general >= 0) {
            int eol = toml.indexOf('\n', general);
            return toml.substring(0, eol + 1) + key + " = " + value + "\n" + toml.substring(eol + 1);
        }
        return toml;
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    synchronized void log(String line) {
        if (log != null) {
            log.println(line);
            log.flush();
        }
    }

    private long lastToolStatus;

    private void tool(String line) {
        log(line);
        long now = System.currentTimeMillis();
        if (now - lastToolStatus > 500 && !line.trim().isEmpty()) {
            lastToolStatus = now;
            listener.status(line.length() > 120 ? line.substring(0, 120) + "..." : line, -1);
        }
    }

    private void status(String text, float progress) {
        log("--- " + text);
        listener.status(text, progress);
    }

    private long lastProgress;

    void progress(String what, long done, long total) throws IOException {
        if (tc.isCancelled()) {
            throw new Toolchain.Cancelled();
        }
        long now = System.currentTimeMillis();
        if (now - lastProgress < 300 && done < total) {
            return;
        }
        lastProgress = now;
        float f = total > 0 ? (float) done / total : -1;
        listener.status(String.format(Locale.US, "%s - %d%%", what, (int) (f * 100)), f);
    }
}
