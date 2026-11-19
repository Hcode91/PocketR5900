package com.hcode91.pocketr5900;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Reads PS2 disc images.
 *
 * <p>Handles plain ISOs (2048-byte sectors) and raw CD images such as the .bin of a
 * bin/cue pair (2352-byte sectors, MODE1 or MODE2 form 1), converts raw images to an
 * ISO, extracts the ISO 9660 file system and finds the boot program in SYSTEM.CNF.
 */
public final class Disc {
    static final int SECTOR = 2048;
    private static final int RAW_SECTOR = 2352;
    private static final byte[] SYNC = {0, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 0};

    interface Progress {
        void update(long done, long total) throws IOException;
    }

    /** Sector layout of an image file. */
    static final class Layout {
        final int sectorSize;
        final int dataOffset;
        final long sectors;

        Layout(int sectorSize, int dataOffset, long sectors) {
            this.sectorSize = sectorSize;
            this.dataOffset = dataOffset;
            this.sectors = sectors;
        }

        boolean isRaw() {
            return sectorSize == RAW_SECTOR;
        }

        @Override
        public String toString() {
            return isRaw()
                    ? String.format(Locale.US, "raw CD image, %d sectors, data at +%d", sectors, dataOffset)
                    : String.format(Locale.US, "ISO image, %d sectors", sectors);
        }
    }

    private Disc() {
    }

    /** Works out whether a file is a raw CD image or an ISO. */
    static Layout detect(File image) throws IOException {
        long length = image.length();
        try (RandomAccessFile f = new RandomAccessFile(image, "r")) {
            byte[] head = new byte[16];
            f.readFully(head);
            boolean sync = true;
            for (int i = 0; i < SYNC.length; i++) {
                if (head[i] != SYNC[i]) {
                    sync = false;
                    break;
                }
            }
            if (sync && length % RAW_SECTOR == 0) {
                int mode = head[15];
                int offset = mode == 1 ? 16 : 24; // MODE2 form 1 has an 8-byte subheader
                return new Layout(RAW_SECTOR, offset, length / RAW_SECTOR);
            }
        }
        if (length % SECTOR != 0) {
            throw new IOException("This file is not a disc image (its size is not a whole number of sectors).");
        }
        return new Layout(SECTOR, 0, length / SECTOR);
    }

    /** Copies the user data of every sector into a plain 2048-byte-sector ISO. */
    static void toIso(File image, Layout layout, File iso, Progress progress) throws IOException {
        byte[] raw = new byte[layout.sectorSize * 64];
        try (RandomAccessFile in = new RandomAccessFile(image, "r");
             OutputStream out = new BufferedOutputStream(new FileOutputStream(iso), 1 << 20)) {
            long done = 0;
            while (done < layout.sectors) {
                int count = (int) Math.min(64, layout.sectors - done);
                in.readFully(raw, 0, count * layout.sectorSize);
                for (int i = 0; i < count; i++) {
                    out.write(raw, i * layout.sectorSize + layout.dataOffset, SECTOR);
                }
                done += count;
                if ((done & 0x3FFF) == 0 || done == layout.sectors) {
                    progress.update(done, layout.sectors);
                }
            }
        }
    }

    /** Extracts every file of an ISO 9660 image; returns the number of files. */
    static int extract(File iso, File destination, Progress progress) throws IOException {
        try (RandomAccessFile f = new RandomAccessFile(iso, "r")) {
            byte[] pvd = readSectors(f, 16, 1);
            if (pvd[0] != 1 || !"CD001".equals(new String(pvd, 1, 5, StandardCharsets.US_ASCII))) {
                throw new IOException("No ISO 9660 file system found on this disc.");
            }
            long total = f.length();
            long[] done = {0};
            int[] files = {0};
            int rootLba = le32(pvd, 156 + 2);
            int rootSize = le32(pvd, 156 + 10);
            if (!destination.isDirectory() && !destination.mkdirs()) {
                throw new IOException("Cannot create " + destination);
            }
            walk(f, rootLba, rootSize, destination, 0, done, files, total, progress);
            return files[0];
        }
    }

    private static void walk(RandomAccessFile f, int lba, int size, File dir, int depth,
                             long[] done, int[] files, long total, Progress progress) throws IOException {
        if (depth > 32) {
            throw new IOException("Directory tree on the disc is too deep.");
        }
        int sectors = (size + SECTOR - 1) / SECTOR;
        byte[] data = readSectors(f, lba, sectors);
        int pos = 0;
        while (pos < data.length) {
            int len = data[pos] & 0xFF;
            if (len == 0) {
                pos = (pos / SECTOR + 1) * SECTOR; // records never cross a sector
                continue;
            }
            if (pos + 33 > data.length) {
                break;
            }
            int extent = le32(data, pos + 2);
            int length = le32(data, pos + 10);
            int flags = data[pos + 25] & 0xFF;
            int nameLen = data[pos + 32] & 0xFF;
            if (pos + 33 + nameLen > data.length) {
                break;
            }
            String name = new String(data, pos + 33, nameLen, StandardCharsets.ISO_8859_1);
            pos += len;

            if (nameLen == 1 && (name.charAt(0) == 0 || name.charAt(0) == 1)) {
                continue; // "." and ".."
            }
            int semicolon = name.indexOf(';');
            if (semicolon >= 0) {
                name = name.substring(0, semicolon);
            }
            if (name.endsWith(".")) {
                name = name.substring(0, name.length() - 1);
            }
            if (name.isEmpty() || name.contains("/") || name.equals("..")) {
                continue;
            }
            File target = new File(dir, name);
            if ((flags & 2) != 0) {
                if (!target.isDirectory() && !target.mkdirs()) {
                    throw new IOException("Cannot create " + target);
                }
                walk(f, extent, length, target, depth + 1, done, files, total, progress);
            } else {
                copyExtent(f, extent, length, target);
                files[0]++;
                done[0] += length;
                progress.update(done[0], total);
            }
        }
    }

    private static void copyExtent(RandomAccessFile f, int lba, long length, File target) throws IOException {
        byte[] buffer = new byte[SECTOR * 256];
        f.seek((long) lba * SECTOR);
        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(target), 1 << 20)) {
            long left = length;
            while (left > 0) {
                int n = (int) Math.min(buffer.length, left);
                f.readFully(buffer, 0, n);
                out.write(buffer, 0, n);
                left -= n;
            }
        }
    }

    /**
     * Finds the boot program named by SYSTEM.CNF (BOOT2 = cdrom0:\SLES_123.45;1),
     * returned as a path relative to the extracted disc, e.g. "SLES_123.45".
     */
    static String bootPath(File extracted) throws IOException {
        File cnf = findIgnoreCase(extracted, "SYSTEM.CNF");
        if (cnf == null) {
            throw new IOException("SYSTEM.CNF is missing, so this does not look like a PS2 disc.");
        }
        String text = new String(java.nio.file.Files.readAllBytes(cnf.toPath()), StandardCharsets.ISO_8859_1);
        for (String line : text.split("[\\r\\n]+")) {
            String t = line.trim();
            if (!t.toUpperCase(Locale.US).startsWith("BOOT2")) {
                continue;
            }
            int eq = t.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String value = t.substring(eq + 1).trim();
            int colon = value.indexOf(':');
            if (colon >= 0) {
                value = value.substring(colon + 1);
            }
            int semicolon = value.indexOf(';');
            if (semicolon >= 0) {
                value = value.substring(0, semicolon);
            }
            value = value.replace('\\', '/');
            while (value.startsWith("/")) {
                value = value.substring(1);
            }
            if (!value.isEmpty()) {
                return value;
            }
        }
        throw new IOException("SYSTEM.CNF has no BOOT2 line, so this is not a PS2 game disc (it may be a PS1 disc).");
    }

    /** Resolves a disc path case-insensitively, as the PS2 does. */
    static File findIgnoreCase(File root, String relative) {
        File current = root;
        for (String part : relative.split("/")) {
            if (part.isEmpty()) {
                continue;
            }
            File exact = new File(current, part);
            if (exact.exists()) {
                current = exact;
                continue;
            }
            String[] names = current.list();
            File match = null;
            if (names != null) {
                for (String n : names) {
                    if (n.equalsIgnoreCase(part)) {
                        match = new File(current, n);
                        break;
                    }
                }
            }
            if (match == null) {
                return null;
            }
            current = match;
        }
        return current;
    }

    /** True if the file starts with the ELF magic. */
    static boolean isElf(File file) {
        try (RandomAccessFile f = new RandomAccessFile(file, "r")) {
            byte[] magic = new byte[4];
            f.readFully(magic);
            return magic[0] == 0x7F && magic[1] == 'E' && magic[2] == 'L' && magic[3] == 'F';
        } catch (IOException e) {
            return false;
        }
    }

    private static byte[] readSectors(RandomAccessFile f, long lba, int count) throws IOException {
        byte[] data = new byte[count * SECTOR];
        f.seek(lba * SECTOR);
        f.readFully(data);
        return data;
    }

    private static int le32(byte[] b, int o) {
        return (b[o] & 0xFF) | (b[o + 1] & 0xFF) << 8 | (b[o + 2] & 0xFF) << 16 | (b[o + 3] & 0xFF) << 24;
    }
}
