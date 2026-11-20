package com.hcode91.pocketr5900;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

/**
 * One game in the library and its folders.
 *
 * <pre>
 * external files/games/&lt;id&gt;/          (visible to the user, holds the big files)
 *     game.properties                    state of the conversion
 *     source/                            the files the user picked
 *     disc.iso                           the disc as a plain ISO (disc games)
 *     disc/                              the extracted disc, with the boot ELF
 *     work/config.toml, work/output/     analyzer and recompiler output
 *     work/obj/                          compiled objects (kept, so a stopped
 *                                        conversion carries on where it stopped)
 *     logs/convert.log, logs/game.log
 * internal files/games/&lt;id&gt;/libgame.so  the finished game (app storage is the
 *                                        only place a library may be loaded from)
 * </pre>
 */
final class Game {
    enum Step {
        IMPORTED, DISC, ANALYZED, RECOMPILED, COMPILED, READY;

        Step next() {
            return values()[Math.min(ordinal() + 1, values().length - 1)];
        }
    }

    final String id;
    final File dir;
    final File libDir;
    final Properties props = new Properties();

    private Game(Context context, String id) {
        this.id = id;
        this.dir = new File(gamesRoot(context), id);
        this.libDir = new File(new File(context.getFilesDir(), "games"), id);
    }

    static File gamesRoot(Context context) {
        File ext = context.getExternalFilesDir(null);
        File base = ext != null ? ext : context.getFilesDir();
        return new File(base, "games");
    }

    static Game create(Context context, String title) throws IOException {
        String slug = title.replaceAll("[^A-Za-z0-9]+", "-").replaceAll("^-|-$", "");
        if (slug.isEmpty()) {
            slug = "game";
        }
        if (slug.length() > 40) {
            slug = slug.substring(0, 40);
        }
        String id = slug + "-" + Long.toString(System.currentTimeMillis(), 36);
        Game g = new Game(context, id);
        if (!g.dir.mkdirs() || !(g.libDir.isDirectory() || g.libDir.mkdirs())) {
            throw new IOException("Cannot create the game folder " + g.dir);
        }
        g.props.setProperty("title", title);
        g.props.setProperty("step", "NEW");
        g.save();
        return g;
    }

    static Game load(Context context, String id) {
        Game g = new Game(context, id);
        File f = g.file();
        if (!f.isFile()) {
            return null;
        }
        try (InputStream in = new FileInputStream(f)) {
            g.props.load(in);
        } catch (IOException e) {
            return null;
        }
        return g;
    }

    static List<Game> all(Context context) {
        List<Game> list = new ArrayList<>();
        String[] ids = gamesRoot(context).list();
        if (ids != null) {
            for (String id : ids) {
                Game g = load(context, id);
                if (g != null) {
                    list.add(g);
                }
            }
        }
        Collections.sort(list, (a, b) -> a.title().compareToIgnoreCase(b.title()));
        return list;
    }

    synchronized void save() throws IOException {
        File tmp = new File(dir, "game.properties.tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            props.store(out, "PocketR5900 game");
        }
        if (!tmp.renameTo(file())) {
            throw new IOException("Cannot save " + file());
        }
    }

    private File file() {
        return new File(dir, "game.properties");
    }

    String title() {
        return props.getProperty("title", id);
    }

    String serial() {
        return props.getProperty("serial", "");
    }

    /** The last step that finished, or null if none has. */
    Step done() {
        try {
            return Step.valueOf(props.getProperty("step", ""));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    boolean ready() {
        return done() == Step.READY && gameLib().isFile();
    }

    String error() {
        return props.getProperty("error", "");
    }

    File source() {
        return new File(dir, "source");
    }

    File iso() {
        return new File(dir, "disc.iso");
    }

    File disc() {
        return new File(dir, "disc");
    }

    File work() {
        return new File(dir, "work");
    }

    File output() {
        return new File(work(), "output");
    }

    File objects() {
        return new File(work(), "obj");
    }

    File logs() {
        return new File(dir, "logs");
    }

    File convertLog() {
        return new File(logs(), "convert.log");
    }

    File gameLog() {
        return new File(logs(), "game.log");
    }

    File gameLib() {
        return new File(libDir, "libgame.so");
    }

    /** The boot ELF: inside the extracted disc, or the ELF the user picked. */
    File bootElf() {
        String path = props.getProperty("bootElf", "");
        return path.isEmpty() ? null : new File(path);
    }

    void delete() {
        deleteTree(dir);
        deleteTree(libDir);
    }

    static void deleteTree(File f) {
        if (f == null || !f.exists()) {
            return;
        }
        // Do not follow links out of the tree.
        if (f.isDirectory() && !java.nio.file.Files.isSymbolicLink(f.toPath())) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) {
                    deleteTree(c);
                }
            }
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
