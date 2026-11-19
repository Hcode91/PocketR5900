package com.hcode91.pocketr5900;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * First screen of an exported game. On the first launch it unpacks the game's disc
 * from the APK into the app's storage, then starts the game.
 *
 * <p>Assets written by PocketR5900's exporter:
 * game/boot.txt (path of the boot ELF inside the disc), and either game/disc.iso
 * or, for a loose ELF, game/elf/&lt;name&gt;.
 */
public class PlayerSetupActivity extends Activity {
    private TextView status;
    private ProgressBar bar;

    static File root(Activity a) {
        File ext = a.getExternalFilesDir(null);
        return ext != null ? ext : a.getFilesDir();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setGravity(Gravity.CENTER);
        l.setBackgroundColor(Color.rgb(0x16, 0x19, 0x22));
        int pad = (int) (32 * getResources().getDisplayMetrics().density);
        l.setPadding(pad, pad, pad, pad);
        status = new TextView(this);
        status.setTextColor(Color.rgb(0xE8, 0xEB, 0xF2));
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        status.setGravity(Gravity.CENTER);
        status.setText("Getting the game ready (first launch only)...");
        l.addView(status);
        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        l.addView(bar);
        setContentView(l);

        if (new File(root(this), "ready").isFile()) {
            startGame();
            return;
        }
        new Thread(this::setUp, "setup").start();
    }

    private void setUp() {
        try {
            File root = root(this);
            String boot = new String(readAll(getAssets().open("game/boot.txt")), StandardCharsets.UTF_8).trim();
            File disc = new File(root, "disc");
            String[] elf = getAssets().list("game/elf");
            if (elf != null && elf.length > 0) {
                if (!disc.isDirectory() && !disc.mkdirs()) {
                    throw new IOException("Cannot create " + disc);
                }
                copyAsset("game/elf/" + elf[0], new File(disc, elf[0]), "Unpacking");
            } else {
                File iso = new File(root, "disc.iso");
                copyAsset("game/disc.iso", iso, "Unpacking the disc");
                Disc.extract(iso, disc, (d, t) -> show("Extracting the disc", d, t));
            }
            File bootFile = Disc.findIgnoreCase(disc, boot);
            if (bootFile == null) {
                throw new IOException("The game's boot program " + boot + " is missing.");
            }
            try (OutputStream o = new FileOutputStream(new File(root, "boot.path"))) {
                o.write(bootFile.getAbsolutePath().getBytes(StandardCharsets.UTF_8));
            }
            try (OutputStream o = new FileOutputStream(new File(root, "ready"))) {
                o.write('1');
            }
            runOnUiThread(this::startGame);
        } catch (IOException e) {
            runOnUiThread(() -> status.setText("Could not set up the game: " + e.getMessage()));
        }
    }

    private void copyAsset(String name, File dst, String what) throws IOException {
        long total;
        try (android.content.res.AssetFileDescriptor fd = getAssets().openFd(name)) {
            total = fd.getLength();
        } catch (IOException e) {
            total = -1; // compressed asset
        }
        byte[] buffer = new byte[1 << 20];
        try (InputStream in = new BufferedInputStream(getAssets().open(name), 1 << 20);
             OutputStream out = new FileOutputStream(dst)) {
            long done = 0;
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
                done += n;
                show(what, done, total);
            }
        }
    }

    private long last;

    private void show(String what, long done, long total) {
        long now = System.currentTimeMillis();
        if (now - last < 250) {
            return;
        }
        last = now;
        runOnUiThread(() -> {
            if (total > 0) {
                bar.setIndeterminate(false);
                bar.setProgress((int) (done * 1000 / total));
                status.setText(String.format(Locale.US, "%s - %d%%", what, (int) (done * 100 / total)));
            } else {
                bar.setIndeterminate(true);
                status.setText(what + "...");
            }
        });
    }

    private void startGame() {
        startActivity(new Intent(this, PlayerGameActivity.class));
        finish();
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try (InputStream i = in) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[4096];
            int n;
            while ((n = i.read(b)) > 0) {
                out.write(b, 0, n);
            }
            return out.toByteArray();
        }
    }
}
