package com.hcode91.pocketr5900;

import com.android.apksig.ApkSigner;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Locale;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Builds a standalone APK for one converted game from the player template:
 * gives it its own package name and the game's title, adds libgame.so and the
 * disc, and signs it.
 *
 * <p>The template's package name and label are fixed-length placeholders, so they
 * are replaced byte for byte inside the binary manifest and resource table; the
 * replacement always has exactly the same length.
 */
final class ApkExporter {
    static final String TEMPLATE_PACKAGE = "com.hcode91.pocketr5900.game.xxxxxxxxxxxxxxxxxxxxxxxx";
    static final String PACKAGE_PREFIX = "com.hcode91.pocketr5900.game.";
    static final String TEMPLATE_TITLE = "@PR5900_TITLE_PLACEHOLDER_000000000000000000000@";
    /** Android cannot install APKs over 4 GB. */
    static final long MAX_APK = 4000L * 1024 * 1024;

    interface Progress {
        void update(String what, long done, long total) throws IOException;
    }

    private ApkExporter() {
    }

    /** A package name unique to the game, the same length as the template's. */
    static String packageFor(String gameId) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-1").digest(gameId.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder("g");
            String alphabet = "abcdefghijklmnopqrstuvwxyz0123456789";
            int need = TEMPLATE_PACKAGE.length() - PACKAGE_PREFIX.length();
            for (int i = 0; sb.length() < need; i++) {
                sb.append(alphabet.charAt((h[i % h.length] & 0xFF) % alphabet.length()));
            }
            return PACKAGE_PREFIX + sb;
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The title as plain ASCII, cut or padded with spaces to the placeholder's length. */
    static String fitTitle(String title) {
        StringBuilder sb = new StringBuilder();
        for (char c : title.toCharArray()) {
            sb.append(c >= 0x20 && c < 0x7F ? c : ' ');
        }
        String t = sb.toString().trim().replaceAll(" +", " ");
        if (t.isEmpty()) {
            t = "PS2 game";
        }
        int len = TEMPLATE_TITLE.length();
        if (t.length() > len) {
            t = t.substring(0, len);
        }
        StringBuilder padded = new StringBuilder(t);
        while (padded.length() < len) {
            padded.append(' ');
        }
        return padded.toString();
    }

    /**
     * @param template the player template APK
     * @param gameLib  the converted game's libgame.so
     * @param iso      the game's disc as an ISO, or null for a loose ELF
     * @param elf      the loose ELF (when iso is null)
     * @param bootPath the boot ELF's path inside the disc
     */
    static void export(File template, File gameLib, File iso, File elf, String bootPath,
                       String packageName, String title, PrivateKey key, X509Certificate cert,
                       File out, Progress progress) throws Exception {
        long size = gameLib.length() + template.length() + (iso != null ? iso.length() : elf.length());
        if (size > MAX_APK) {
            throw new IOException(String.format(Locale.US,
                    "This game is too big to export as an APK (%.1f GB; Android's limit is 4 GB). Play it in PocketR5900 instead.",
                    size / 1073741824.0));
        }
        if (packageName.length() != TEMPLATE_PACKAGE.length()) {
            throw new IllegalArgumentException("package name must be " + TEMPLATE_PACKAGE.length() + " characters");
        }
        String fittedTitle = fitTitle(title);

        File unsigned = new File(out.getPath() + ".unsigned");
        try (ZipFile in = new ZipFile(template);
             ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(unsigned), 1 << 20))) {
            int manifests = 0;
            int titles = 0;
            Enumeration<? extends ZipEntry> entries = in.entries();
            for (ZipEntry e : Collections.list(entries)) {
                String name = e.getName();
                if (name.startsWith("META-INF/")) {
                    continue; // old signature
                }
                byte[] data = readAll(in.getInputStream(e));
                if (name.equals("AndroidManifest.xml")) {
                    int n = replaceEverywhere(data, TEMPLATE_PACKAGE, packageName);
                    if (n == 0) {
                        throw new IOException("The player template's manifest has no package placeholder.");
                    }
                    manifests += n;
                } else if (name.equals("resources.arsc")) {
                    titles += replaceEverywhere(data, TEMPLATE_TITLE, fittedTitle);
                    replaceEverywhere(data, TEMPLATE_PACKAGE, packageName);
                }
                // resources.arsc must be stored uncompressed on Android 11+.
                boolean store = e.getMethod() == ZipEntry.STORED || name.equals("resources.arsc");
                putBytes(zip, name, data, store);
            }
            if (manifests == 0 || titles == 0) {
                throw new IOException("The player template is missing its placeholders.");
            }

            putFile(zip, "lib/arm64-v8a/libgame.so", gameLib, false, progress, "Adding the game");
            putBytes(zip, "assets/game/boot.txt", bootPath.getBytes(StandardCharsets.UTF_8), false);
            if (iso != null) {
                putFile(zip, "assets/game/disc.iso", iso, true, progress, "Adding the disc");
            } else {
                putFile(zip, "assets/game/elf/" + elf.getName(), elf, false, progress, "Adding the game");
            }
        }

        progress.update("Signing", 0, -1);
        ApkSigner.SignerConfig signer = new ApkSigner.SignerConfig.Builder(
                "pocketr5900", key, Collections.singletonList(cert)).build();
        new ApkSigner.Builder(Collections.singletonList(signer))
                .setInputApk(unsigned)
                .setOutputApk(out)
                .setMinSdkVersion(28)
                .setV1SigningEnabled(false)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .build()
                .sign();
        //noinspection ResultOfMethodCallIgnored
        unsigned.delete();
    }

    /** Replaces a same-length string in both UTF-8 and UTF-16LE form; returns the count. */
    static int replaceEverywhere(byte[] data, String from, String to) {
        if (from.length() != to.length()) {
            throw new IllegalArgumentException("replacement must be the same length");
        }
        int count = replaceBytes(data, from.getBytes(StandardCharsets.UTF_8), to.getBytes(StandardCharsets.UTF_8));
        count += replaceBytes(data, from.getBytes(StandardCharsets.UTF_16LE), to.getBytes(StandardCharsets.UTF_16LE));
        return count;
    }

    private static int replaceBytes(byte[] data, byte[] from, byte[] to) {
        int count = 0;
        outer:
        for (int i = 0; i + from.length <= data.length; i++) {
            for (int j = 0; j < from.length; j++) {
                if (data[i + j] != from[j]) {
                    continue outer;
                }
            }
            System.arraycopy(to, 0, data, i, to.length);
            count++;
            i += from.length - 1;
        }
        return count;
    }

    private static void putBytes(ZipOutputStream zip, String name, byte[] data, boolean store) throws IOException {
        ZipEntry e = new ZipEntry(name);
        if (store) {
            CRC32 crc = new CRC32();
            crc.update(data);
            e.setMethod(ZipEntry.STORED);
            e.setSize(data.length);
            e.setCompressedSize(data.length);
            e.setCrc(crc.getValue());
        } else {
            e.setMethod(ZipEntry.DEFLATED);
        }
        zip.putNextEntry(e);
        zip.write(data);
        zip.closeEntry();
    }

    private static void putFile(ZipOutputStream zip, String name, File file, boolean store,
                                Progress progress, String what) throws IOException {
        long total = file.length();
        byte[] buffer = new byte[1 << 20];
        ZipEntry e = new ZipEntry(name);
        if (store) {
            // A stored entry needs its CRC up front: one pass to work it out.
            CRC32 crc = new CRC32();
            try (InputStream in = new BufferedInputStream(new FileInputStream(file), 1 << 20)) {
                long done = 0;
                int n;
                while ((n = in.read(buffer)) > 0) {
                    crc.update(buffer, 0, n);
                    done += n;
                    progress.update(what + " (checking)", done, total);
                }
            }
            e.setMethod(ZipEntry.STORED);
            e.setSize(total);
            e.setCompressedSize(total);
            e.setCrc(crc.getValue());
        } else {
            e.setMethod(ZipEntry.DEFLATED);
        }
        zip.putNextEntry(e);
        try (InputStream in = new BufferedInputStream(new FileInputStream(file), 1 << 20)) {
            long done = 0;
            int n;
            while ((n = in.read(buffer)) > 0) {
                zip.write(buffer, 0, n);
                done += n;
                progress.update(what, done, total);
            }
        }
        zip.closeEntry();
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try (InputStream i = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[1 << 16];
            int n;
            while ((n = i.read(b)) > 0) {
                out.write(b, 0, n);
            }
            return out.toByteArray();
        }
    }

    @SuppressWarnings("unused")
    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] b = new byte[1 << 16];
        int n;
        while ((n = in.read(b)) > 0) {
            out.write(b, 0, n);
        }
    }
}
