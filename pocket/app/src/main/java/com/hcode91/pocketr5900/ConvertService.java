package com.hcode91.pocketr5900;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.OpenableColumns;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Imports and converts games in the background, as a foreground service with a
 * wake lock, so a long conversion keeps going with the screen off.
 */
public class ConvertService extends Service {
    static final String ACTION_IMPORT = "com.hcode91.pocketr5900.IMPORT";
    static final String ACTION_CONVERT = "com.hcode91.pocketr5900.CONVERT";
    static final String ACTION_STOP = "com.hcode91.pocketr5900.STOP";
    static final String ACTION_EXPORT = "com.hcode91.pocketr5900.EXPORT";
    static final String EXTRA_GAME = "game";
    static final String EXTRA_OPT = "opt";
    private static final String CHANNEL = "convert";
    private static final int NOTIFICATION = 1;

    /** What the screen shows while something is running. */
    interface Watcher {
        void changed();
    }

    static volatile String activeGame;
    static volatile String activeStatus = "";
    static volatile float activeProgress = -1;
    /** The last problem that is not tied to one game (e.g. a bad pick), shown until the next job. */
    static volatile String lastMessage = "";
    private static Watcher watcher;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private final LinkedBlockingQueue<Intent> queue = new LinkedBlockingQueue<>();
    private Thread worker;
    private volatile Toolchain current;
    private PowerManager.WakeLock wakeLock;
    private long lastNotify;

    static void setWatcher(Watcher w) {
        watcher = w;
    }

    private static void notifyWatcher() {
        MAIN.post(() -> {
            Watcher w = watcher;
            if (w != null) {
                w.changed();
            }
        });
    }

    static boolean isBusy(String gameId) {
        return gameId != null && gameId.equals(activeGame);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Converting games", NotificationManager.IMPORTANCE_LOW));
        PowerManager pm = getSystemService(PowerManager.class);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PocketR5900:convert");
        wakeLock.setReferenceCounted(false);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startAsForeground("Starting...", -1);
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            queue.clear();
            Toolchain t = current;
            if (t != null) {
                t.cancel();
            }
            if (worker == null || !worker.isAlive()) {
                stopSelf();
            }
            return START_NOT_STICKY;
        }
        if (intent != null) {
            lastMessage = "";
            queue.add(intent);
        }
        if (worker == null || !worker.isAlive()) {
            worker = new Thread(this::work, "PocketR5900-convert");
            worker.start();
        }
        return START_NOT_STICKY;
    }

    private void work() {
        wakeLock.acquire();
        try {
            Intent job;
            while ((job = queue.poll()) != null) {
                try {
                    if (ACTION_IMPORT.equals(job.getAction())) {
                        Game g = importGame(job);
                        if (g != null) {
                            convert(g);
                        }
                    } else if (ACTION_EXPORT.equals(job.getAction())) {
                        Game g = Game.load(this, job.getStringExtra(EXTRA_GAME));
                        if (g != null) {
                            export(g);
                        }
                    } else if (ACTION_CONVERT.equals(job.getAction())) {
                        Game g = Game.load(this, job.getStringExtra(EXTRA_GAME));
                        if (g != null) {
                            String opt = job.getStringExtra(EXTRA_OPT);
                            if (opt != null) {
                                g.props.setProperty("opt", opt);
                                g.save();
                            }
                            convert(g);
                        }
                    }
                } catch (Throwable t) {
                    android.util.Log.e("PocketR5900", "job failed", t);
                }
            }
        } finally {
            activeGame = null;
            activeStatus = "";
            activeProgress = -1;
            notifyWatcher();
            wakeLock.release();
            MAIN.post(() -> {
                if (queue.isEmpty()) {
                    stopForeground(STOP_FOREGROUND_REMOVE);
                    stopSelf();
                }
            });
        }
    }

    private void convert(Game g) {
        activeGame = g.id;
        Toolchain tc = new Toolchain(this);
        current = tc;
        try {
            new Pipeline(g, tc, (text, progress) -> show(g, text, progress)).run(this);
            finishedNotification(g.title() + " is ready to play.");
        } catch (Toolchain.Cancelled e) {
            finishedNotification("Stopped converting " + g.title() + ".");
        } catch (IOException | RuntimeException e) {
            finishedNotification(g.title() + ": " + e.getMessage());
        } finally {
            current = null;
            activeGame = null;
            notifyWatcher();
        }
    }

    // ---------------------------------------------------------------- export

    /** Builds the game's own APK and hands it to Android's installer. */
    private void export(Game g) {
        activeGame = g.id;
        try {
            if (!g.ready()) {
                throw new IOException("Convert the game first.");
            }
            File exports = new File(g.dir, "export");
            if (!exports.isDirectory() && !exports.mkdirs()) {
                throw new IOException("Cannot create " + exports);
            }
            File template = new File(exports, "template.apk");
            try (InputStream in = getAssets().open("player-template.apk");
                 OutputStream out = new FileOutputStream(template)) {
                byte[] b = new byte[1 << 16];
                int n;
                while ((n = in.read(b)) > 0) {
                    out.write(b, 0, n);
                }
            }
            File iso = g.iso().isFile() ? g.iso() : null;
            File elf = g.bootElf();
            String boot = iso != null ? g.disc().toPath().relativize(elf.toPath()).toString() : elf.getName();
            File apk = new File(exports, g.title().replaceAll("[^A-Za-z0-9]+", "-") + ".apk");
            java.security.KeyStore.PrivateKeyEntry key = Installer.key();
            ApkExporter.export(template, g.gameLib(), iso, elf, boot,
                    ApkExporter.packageFor(g.id), g.title(), key.getPrivateKey(),
                    (java.security.cert.X509Certificate) key.getCertificate(), apk,
                    (what, done, total) -> show(g, total > 0
                            ? String.format(Locale.US, "%s - %d%%", what, (int) (done * 100 / total)) : what + "...",
                            total > 0 ? (float) done / total : -1));
            //noinspection ResultOfMethodCallIgnored
            template.delete();
            show(g, "Installing...", -1);
            Installer.install(this, apk);
            finishedNotification(g.title() + " APK made: " + apk.getAbsolutePath());
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.toString();
            android.util.Log.e("PocketR5900", "export failed", e);
            finishedNotification("Export failed: " + msg);
            lastMessage = "Export failed: " + msg;
        } finally {
            activeGame = null;
            notifyWatcher();
        }
    }

    // ---------------------------------------------------------------- import

    /** Copies the picked files into a new game folder and works out what they are. */
    private Game importGame(Intent job) throws IOException {
        List<Uri> uris = new ArrayList<>();
        if (job.getClipData() != null) {
            for (int i = 0; i < job.getClipData().getItemCount(); i++) {
                uris.add(job.getClipData().getItemAt(i).getUri());
            }
        } else if (job.getData() != null) {
            uris.add(job.getData());
        }
        if (uris.isEmpty()) {
            return null;
        }

        ContentResolver cr = getContentResolver();
        List<String> names = new ArrayList<>();
        for (Uri u : uris) {
            names.add(displayName(cr, u));
        }

        // Choose the main file: an ISO, the .bin of a bin/cue, or an ELF.
        int main = -1;
        String kind = null;
        String cueBin = null;
        for (int i = 0; i < uris.size(); i++) {
            String n = names.get(i).toLowerCase(Locale.US);
            if (n.endsWith(".cue")) {
                cueBin = binFromCue(cr, uris.get(i));
            }
        }
        for (int i = 0; i < uris.size() && main < 0; i++) {
            if (names.get(i).toLowerCase(Locale.US).endsWith(".pr5900")) {
                main = i;
                kind = "package";
            }
        }
        for (int i = 0; i < uris.size() && main < 0; i++) {
            String n = names.get(i);
            String lower = n.toLowerCase(Locale.US);
            if (cueBin != null && n.equalsIgnoreCase(cueBin)) {
                main = i;
                kind = "disc";
            }
            if (lower.endsWith(".iso")) {
                main = i;
                kind = "disc";
            }
        }
        for (int i = 0; i < uris.size() && main < 0; i++) {
            String lower = names.get(i).toLowerCase(Locale.US);
            if (lower.endsWith(".bin") || lower.endsWith(".img")) {
                main = i;
                kind = "disc";
            }
        }
        if (main < 0) {
            for (int i = 0; i < uris.size(); i++) {
                String lower = names.get(i).toLowerCase(Locale.US);
                if (!lower.endsWith(".cue")) {
                    main = i;
                    kind = "unknown";
                    break;
                }
            }
        }
        if (main < 0) {
            String msg = cueBin != null
                    ? "Pick the .bin file (" + cueBin + ") as well as the .cue."
                    : "Pick the game's .bin, .iso, ELF or .pr5900 file.";
            finishedNotification(msg);
            showError(msg);
            return null;
        }

        String name = names.get(main);
        long need = querySize(cr, uris.get(main));
        File root = Game.gamesRoot(this);
        //noinspection ResultOfMethodCallIgnored
        root.mkdirs();
        long free = new android.os.StatFs(root.getAbsolutePath()).getAvailableBytes();
        // The copy, the ISO, the extracted files and the build all need room.
        if (need > 0 && free < need * 3 + 1024L * 1024 * 1024) {
            String msg = String.format(Locale.US, "Not enough free space: about %.1f GB is needed and %.1f GB is free.",
                    (need * 3 + 1024L * 1024 * 1024) / 1073741824.0, free / 1073741824.0);
            finishedNotification(msg);
            showError(msg);
            return null;
        }
        String title = name.replaceAll("\\.[A-Za-z0-9]{1,4}$", "");
        Game g = Game.create(this, title);
        activeGame = g.id;
        File dst = new File(g.source(), safeName(name));
        if (!g.source().mkdirs()) {
            throw new IOException("Cannot create " + g.source());
        }
        long size = querySize(cr, uris.get(main));
        try (InputStream in = cr.openInputStream(uris.get(main));
             OutputStream out = new FileOutputStream(dst)) {
            if (in == null) {
                throw new IOException("Cannot open " + name);
            }
            byte[] buffer = new byte[1 << 20];
            long done = 0;
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
                done += n;
                show(g, String.format(Locale.US, "Copying %s - %d%%", name, size > 0 ? (int) (done * 100 / size) : 0),
                        size > 0 ? (float) done / size : -1);
            }
        }
        if (kind.equals("unknown")) {
            kind = Disc.isElf(dst) ? "elf" : (PcPackage.isPackage(dst) ? "package" : "disc");
        }
        if (kind.equals("package")) {
            String packaged = PcPackage.title(dst);
            if (packaged != null && !packaged.isEmpty()) {
                g.props.setProperty("title", packaged);
            }
        }
        g.props.setProperty("kind", kind);
        g.props.setProperty("sourceName", dst.getName());
        g.props.setProperty("step", Game.Step.IMPORTED.name());
        g.props.setProperty("opt", job.getStringExtra(EXTRA_OPT) != null ? job.getStringExtra(EXTRA_OPT) : Pipeline.OPT_FAST_CONVERT);
        g.save();
        return g;
    }

    private static String binFromCue(ContentResolver cr, Uri cue) {
        try (InputStream in = cr.openInputStream(cue)) {
            if (in == null) {
                return null;
            }
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.ISO_8859_1));
            String line;
            while ((line = r.readLine()) != null) {
                String t = line.trim();
                if (t.toUpperCase(Locale.US).startsWith("FILE")) {
                    int a = t.indexOf('"');
                    int b = t.lastIndexOf('"');
                    if (a >= 0 && b > a) {
                        String f = t.substring(a + 1, b);
                        int slash = Math.max(f.lastIndexOf('/'), f.lastIndexOf('\\'));
                        return f.substring(slash + 1);
                    }
                }
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    private static String displayName(ContentResolver cr, Uri uri) {
        try (Cursor c = cr.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (n != null) {
                    return n;
                }
            }
        } catch (RuntimeException ignored) {
        }
        String last = uri.getLastPathSegment();
        return last != null ? last : "game";
    }

    private static long querySize(ContentResolver cr, Uri uri) {
        try (Cursor c = cr.query(uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) {
                return c.getLong(0);
            }
        } catch (RuntimeException ignored) {
        }
        return -1;
    }

    private static String safeName(String n) {
        String s = n.replaceAll("[/\\\\:*?\"<>|]", "_");
        return s.isEmpty() ? "game" : s;
    }

    // --------------------------------------------------------- notifications

    private void show(Game g, String text, float progress) {
        activeGame = g.id;
        activeStatus = text;
        activeProgress = progress;
        notifyWatcher();
        long now = System.currentTimeMillis();
        if (now - lastNotify > 1000) {
            lastNotify = now;
            startAsForeground(g.title() + ": " + text, progress);
        }
    }

    private void showError(String msg) {
        lastMessage = msg;
        notifyWatcher();
    }

    private void startAsForeground(String text, float progress) {
        Notification n = buildNotification(text, progress, true);
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION, n);
        }
    }

    private void finishedNotification(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.notify(2, buildNotification(text, 0, false));
    }

    private Notification buildNotification(String text, float progress, boolean ongoing) {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("PocketR5900")
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setOnlyAlertOnce(true)
                .setOngoing(ongoing);
        if (ongoing) {
            b.setProgress(1000, progress < 0 ? 0 : (int) (progress * 1000), progress < 0);
            PendingIntent stop = PendingIntent.getService(this, 1,
                    new Intent(this, ConvertService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
            b.addAction(new Notification.Action.Builder(null, "Stop", stop).build());
        } else {
            b.setSmallIcon(android.R.drawable.stat_sys_download_done).setAutoCancel(true);
        }
        return b.build();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    static void start(Context context, Intent job) {
        context.startForegroundService(job.setClass(context, ConvertService.class));
    }
}
