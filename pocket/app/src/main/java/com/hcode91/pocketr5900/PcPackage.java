package com.hcode91.pocketr5900;

import android.content.Context;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * A game converted on a PC by PocketR5900-PC: a .pr5900 file (a zip) holding
 * manifest.properties, libgame.so and the disc (disc.iso, or elf/&lt;name&gt; for a
 * loose ELF).
 */
final class PcPackage {
    interface Log {
        void line(String line);
    }

    interface Progress {
        void update(String what, long done, long total) throws IOException;
    }

    private PcPackage() {
    }

    static boolean isPackage(File f) {
        try (ZipFile z = new ZipFile(f)) {
            return z.getEntry("manifest.properties") != null && z.getEntry("libgame.so") != null;
        } catch (IOException e) {
            return false;
        }
    }

    static String title(File f) {
        try (ZipFile z = new ZipFile(f)) {
            return manifest(z).getProperty("title", "");
        } catch (IOException e) {
            return null;
        }
    }

    private static Properties manifest(ZipFile z) throws IOException {
        ZipEntry e = z.getEntry("manifest.properties");
        if (e == null) {
            throw new IOException("This .pr5900 file has no manifest; it may be damaged.");
        }
        Properties p = new Properties();
        try (InputStream in = z.getInputStream(e)) {
            p.load(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
        }
        return p;
    }

    /** Unpacks the game into the game's folders; afterwards it is an ordinary disc or ELF game. */
    static void unpack(Context context, Game game, Log log, Progress progress) throws IOException {
        File pkg = new File(game.source(), game.props.getProperty("sourceName", ""));
        try (ZipFile z = new ZipFile(pkg)) {
            Properties m = manifest(z);
            if (!"1".equals(m.getProperty("format"))) {
                throw new IOException("This .pr5900 file was made by a newer PocketR5900-PC. Update the app.");
            }
            String kind = m.getProperty("kind", "disc");
            String boot = m.getProperty("boot", "");
            log.line("Unpacking a game converted on a PC (" + m.getProperty("made_by", "PocketR5900-PC") + ")");

            File disc = game.disc();
            Game.deleteTree(disc);
            if (!disc.mkdirs()) {
                throw new IOException("Cannot create " + disc);
            }

            if ("elf".equals(kind)) {
                ZipEntry elf = null;
                java.util.Enumeration<? extends ZipEntry> all = z.entries();
                while (all.hasMoreElements()) {
                    ZipEntry e = all.nextElement();
                    if (e.getName().startsWith("elf/") && !e.isDirectory()) {
                        elf = e;
                    }
                }
                if (elf == null) {
                    throw new IOException("The .pr5900 file has no ELF in it.");
                }
                String name = new File(elf.getName()).getName();
                copy(z, elf, new File(disc, name), progress, "Unpacking the game");
                boot = name;
            } else {
                ZipEntry iso = z.getEntry("disc.iso");
                if (iso != null) {
                    copy(z, iso, game.iso(), progress, "Unpacking the disc");
                } else if (!borrowDisc(context, game, m.getProperty("serial", ""), log)) {
                    throw new IOException("This .pr5900 file has no disc in it and no game on the phone has the same disc ("
                            + m.getProperty("serial", "?") + "). Add the disc first, or make the file with the disc included.");
                }
                int files = Disc.extract(game.iso(), disc, (d, t) -> progress.update("Extracting the disc", d, t));
                log.line("Extracted " + files + " files.");
            }

            File bootFile = Disc.findIgnoreCase(disc, boot);
            if (bootFile == null || !bootFile.isFile()) {
                throw new IOException("The game's boot program " + boot + " is missing.");
            }

            ZipEntry lib = z.getEntry("libgame.so");
            if (!game.libDir.isDirectory() && !game.libDir.mkdirs()) {
                throw new IOException("Cannot create " + game.libDir);
            }
            File tmp = new File(game.gameLib().getPath() + ".tmp");
            copy(z, lib, tmp, progress, "Unpacking the game");
            if (!tmp.renameTo(game.gameLib())) {
                throw new IOException("Cannot write " + game.gameLib());
            }

            game.props.setProperty("kind", "elf".equals(kind) ? "elf" : "disc");
            game.props.setProperty("bootElf", bootFile.getAbsolutePath());
            String serial = m.getProperty("serial", "");
            if (!serial.isEmpty()) {
                game.props.setProperty("serial", serial);
            }
            String opt = m.getProperty("opt", "");
            if (!opt.isEmpty()) {
                game.props.setProperty("opt", opt);
            }
            game.props.setProperty("madeBy", m.getProperty("made_by", "PocketR5900-PC"));
            game.save();
            log.line(String.format(java.util.Locale.US, "Game library %.1f MB; ready to play.", game.gameLib().length() / 1048576.0));
        }
        // The package's contents are unpacked; it is no longer needed.
        Game.deleteTree(game.source());
    }

    /** Uses the disc of a game already on the phone with the same boot program. */
    private static boolean borrowDisc(Context context, Game game, String serial, Log log) throws IOException {
        if (serial.isEmpty()) {
            return false;
        }
        for (Game other : Game.all(context)) {
            if (other.id.equals(game.id) || !serial.equalsIgnoreCase(other.serial()) || !other.iso().isFile()) {
                continue;
            }
            log.line("Using the disc of \"" + other.title() + "\" already on the phone.");
            Files.copy(other.iso().toPath(), game.iso().toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return true;
        }
        return false;
    }

    private static void copy(ZipFile z, ZipEntry e, File dst, Progress progress, String what) throws IOException {
        long total = e.getSize();
        byte[] buffer = new byte[1 << 20];
        try (InputStream in = z.getInputStream(e);
             OutputStream out = new BufferedOutputStream(new FileOutputStream(dst), 1 << 20)) {
            long done = 0;
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
                done += n;
                progress.update(what, done, total);
            }
        }
    }
}
