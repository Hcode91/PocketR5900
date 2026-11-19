package com.hcode91.pocketr5900;

import android.content.Context;
import android.system.ErrnoException;
import android.system.Os;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The programs bundled in the APK (analyzer, recompiler, clang, lld) and the SDK
 * pack (Android sysroot, clang headers, PS2 runtime) they work with.
 */
final class Toolchain {
    interface Lines {
        void line(String line);
    }

    static final class Cancelled extends IOException {
        Cancelled() {
            super("Stopped");
        }
    }

    private static final String SDK_VERSION_FILE = "VERSION";

    final File nativeDir;
    final File sdk;
    final File bin;
    private final Set<Process> running = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private volatile boolean cancelled;

    Toolchain(Context context) {
        nativeDir = new File(context.getApplicationInfo().nativeLibraryDir);
        sdk = new File(context.getFilesDir(), "sdk");
        bin = new File(context.getFilesDir(), "bin");
    }

    File analyzer() {
        return new File(nativeDir, "libps2analyzer.so");
    }

    File recompiler() {
        return new File(nativeDir, "libps2recomp.so");
    }

    File clang() {
        return new File(nativeDir, "libclang.so");
    }

    /** lld picks its mode from its own name, so it is run through a link called ld.lld. */
    File ldLld() {
        return new File(bin, "ld.lld");
    }

    /** Checks the bundled programs exist and unpacks the SDK pack if it is new. */
    void prepare(Context context, Lines log) throws IOException {
        for (File f : new File[]{analyzer(), recompiler(), clang(), new File(nativeDir, "liblld.so")}) {
            if (!f.isFile()) {
                throw new IOException("This build of the app is missing " + f.getName()
                        + ". Install a full build from the PocketR5900 workflow.");
            }
        }

        if (!bin.isDirectory() && !bin.mkdirs()) {
            throw new IOException("Cannot create " + bin);
        }
        File link = ldLld();
        String target = new File(nativeDir, "liblld.so").getAbsolutePath();
        try {
            if (link.exists() || Files.isSymbolicLink(link.toPath())) {
                String current = Files.isSymbolicLink(link.toPath())
                        ? Files.readSymbolicLink(link.toPath()).toString() : "";
                if (!current.equals(target)) {
                    //noinspection ResultOfMethodCallIgnored
                    link.delete();
                }
            }
            if (!Files.isSymbolicLink(link.toPath())) {
                Os.symlink(target, link.getAbsolutePath());
            }
        } catch (ErrnoException e) {
            throw new IOException("Cannot set up the linker: " + e.getMessage());
        }

        String wanted = readAssetVersion(context);
        File version = new File(sdk, SDK_VERSION_FILE);
        String have = version.isFile() ? new String(Files.readAllBytes(version.toPath()), StandardCharsets.UTF_8).trim() : "";
        if (!wanted.isEmpty() && wanted.equals(have)) {
            return;
        }
        log.line("Unpacking the compiler files (first run only)...");
        File tmp = new File(context.getFilesDir(), "sdk.tmp");
        Game.deleteTree(tmp);
        unzip(context, tmp);
        File unpacked = new File(tmp, "sdk");
        if (!new File(unpacked, SDK_VERSION_FILE).isFile()) {
            throw new IOException("The compiler files in this build are damaged.");
        }
        Game.deleteTree(sdk);
        if (!unpacked.renameTo(sdk)) {
            throw new IOException("Cannot install the compiler files.");
        }
        Game.deleteTree(tmp);
        log.line("Compiler files ready.");
    }

    /** The SDK pack's version, stored next to it as assets/sdk.version. */
    private static String readAssetVersion(Context context) throws IOException {
        try (InputStream in = context.getAssets().open("sdk.version")) {
            return new String(readAll(in), StandardCharsets.UTF_8).trim();
        } catch (java.io.FileNotFoundException e) {
            throw new IOException("This build of the app has no compiler files (sdk.zip). Install a full build from the PocketR5900 workflow.");
        }
    }

    private static void unzip(Context context, File dest) throws IOException {
        String root = dest.getCanonicalPath() + File.separator;
        byte[] buffer = new byte[1 << 16];
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(context.getAssets().open("sdk.zip"), 1 << 16))) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                File out = new File(dest, e.getName());
                if (!out.getCanonicalPath().startsWith(root)) {
                    throw new IOException("Bad entry in sdk.zip: " + e.getName());
                }
                if (e.isDirectory()) {
                    //noinspection ResultOfMethodCallIgnored
                    out.mkdirs();
                    continue;
                }
                File parent = out.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                    throw new IOException("Cannot create " + parent);
                }
                try (OutputStream os = new FileOutputStream(out)) {
                    int n;
                    while ((n = zip.read(buffer)) > 0) {
                        os.write(buffer, 0, n);
                    }
                }
            }
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] b = new byte[4096];
        int n;
        while ((n = in.read(b)) > 0) {
            out.write(b, 0, n);
        }
        return out.toByteArray();
    }

    /** Runs a bundled program, passing each output line on; returns its exit code. */
    int run(List<String> command, File workDir, Lines out) throws IOException {
        if (cancelled) {
            throw new Cancelled();
        }
        ProcessBuilder pb = new ProcessBuilder(command).directory(workDir).redirectErrorStream(true);
        pb.environment().put("TMPDIR", workDir.getAbsolutePath());
        pb.environment().put("HOME", workDir.getAbsolutePath());
        Process p = pb.start();
        running.add(p);
        try {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    out.line(line);
                }
            }
            int code = p.waitFor();
            if (cancelled) {
                throw new Cancelled();
            }
            return code;
        } catch (InterruptedException e) {
            p.destroy();
            Thread.currentThread().interrupt();
            throw new Cancelled();
        } finally {
            running.remove(p);
        }
    }

    void cancel() {
        cancelled = true;
        for (Process p : new ArrayList<>(running)) {
            p.destroyForcibly();
        }
    }

    boolean isCancelled() {
        return cancelled;
    }
}
