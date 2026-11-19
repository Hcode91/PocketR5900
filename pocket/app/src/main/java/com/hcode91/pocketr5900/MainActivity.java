package com.hcode91.pocketr5900;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/** The game library: add a game, convert it, play it, read its logs. */
public class MainActivity extends Activity implements ConvertService.Watcher {
    private static final int PICK = 1;
    private static final int BG = Color.rgb(0x16, 0x19, 0x22);
    private static final int CARD = Color.rgb(0x22, 0x27, 0x35);
    private static final int ACCENT = Color.rgb(0x3A, 0x7B, 0xFF);
    private static final int TEXT = Color.rgb(0xE8, 0xEB, 0xF2);
    private static final int MUTED = Color.rgb(0x9A, 0xA3, 0xB5);
    private static final int ERROR = Color.rgb(0xFF, 0x8A, 0x80);

    private LinearLayout list;
    private TextView activeLine;
    private ProgressBar activeBar;
    private String pendingOpt = Pipeline.OPT_FAST_CONVERT;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(16), dp(20), dp(16), dp(8));

        TextView title = text("PocketR5900", 26, TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);
        TextView sub = text("Converts PS2 games you own into native Android code, all on this phone.", 14, MUTED);
        sub.setPadding(0, dp(4), 0, dp(12));
        root.addView(sub);

        Button add = button("Add a game", true);
        add.setOnClickListener(v -> chooseOptimisationThenPick());
        root.addView(add, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        activeLine = text("", 13, MUTED);
        activeLine.setPadding(0, dp(10), 0, dp(4));
        activeLine.setMaxLines(3);
        activeLine.setEllipsize(TextUtils.TruncateAt.END);
        root.addView(activeLine);
        activeBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        activeBar.setMax(1000);
        root.addView(activeBar);

        ScrollView scroll = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, dp(8), 0, dp(24));
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        ConvertService.setWatcher(this);
        refresh();
    }

    @Override
    protected void onPause() {
        ConvertService.setWatcher(null);
        super.onPause();
    }

    @Override
    public void changed() {
        refresh();
    }

    // -------------------------------------------------------------- adding

    private void chooseOptimisationThenPick() {
        askOptimisation(opt -> {
            pendingOpt = opt;
            Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("*/*")
                    .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            startActivityForResult(pick, PICK);
        });
    }

    interface OptChoice {
        void chosen(String opt);
    }

    private void askOptimisation(OptChoice choice) {
        new AlertDialog.Builder(this)
                .setTitle("How should it be converted?")
                .setItems(new CharSequence[]{
                        "Faster conversion (recommended first time)",
                        "Faster game (takes much longer to convert)"},
                        (d, which) -> choice.chosen(which == 0 ? Pipeline.OPT_FAST_CONVERT : Pipeline.OPT_FAST_GAME))
                .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK || resultCode != RESULT_OK || data == null) {
            return;
        }
        Intent job = new Intent(ConvertService.ACTION_IMPORT)
                .putExtra(ConvertService.EXTRA_OPT, pendingOpt)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        ClipData clip = data.getClipData();
        if (clip != null) {
            job.setClipData(clip);
            for (int i = 0; i < clip.getItemCount(); i++) {
                keep(clip.getItemAt(i).getUri());
            }
        } else if (data.getData() != null) {
            job.setData(data.getData());
            keep(data.getData());
        } else {
            return;
        }
        activeLine.setText("Copying the game into the app...");
        ConvertService.start(this, job);
    }

    private void keep(Uri uri) {
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) {
        }
    }

    // ------------------------------------------------------------- the list

    private void refresh() {
        String active = ConvertService.activeGame;
        if (active != null || !ConvertService.activeStatus.isEmpty()) {
            activeLine.setText(ConvertService.activeStatus);
            activeLine.setVisibility(View.VISIBLE);
            float p = ConvertService.activeProgress;
            activeBar.setVisibility(active != null ? View.VISIBLE : View.GONE);
            activeBar.setIndeterminate(p < 0);
            if (p >= 0) {
                activeBar.setProgress((int) (p * 1000));
            }
        } else {
            activeLine.setVisibility(View.GONE);
            activeBar.setVisibility(View.GONE);
        }

        list.removeAllViews();
        List<Game> games = Game.all(this);
        if (games.isEmpty()) {
            TextView empty = text("No games yet.\n\nTap \"Add a game\" and pick your own dump: a .bin (pick its .cue too), an .iso, or a PS2 ELF.", 15, MUTED);
            empty.setPadding(0, dp(24), 0, 0);
            list.addView(empty);
            return;
        }
        for (Game g : games) {
            list.addView(card(g));
        }
    }

    private View card(Game g) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD);
        bg.setCornerRadius(dp(12));
        card.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        card.setLayoutParams(lp);

        TextView name = text(g.title(), 17, TEXT);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(name);

        boolean busy = ConvertService.isBusy(g.id);
        String state;
        int color = MUTED;
        if (busy) {
            state = "Converting...";
            color = ACCENT;
        } else if (g.ready()) {
            state = "Ready to play";
            color = ACCENT;
        } else if (!g.error().isEmpty()) {
            state = "Stopped with an error: " + g.error();
            color = ERROR;
        } else if (g.done() == null) {
            state = "Import did not finish";
        } else {
            state = "Not finished converting (last step: " + stepName(g.done()) + ")";
        }
        String serial = g.serial().isEmpty() ? "" : g.serial() + "  ·  ";
        String opt = Pipeline.OPT_FAST_GAME.equals(g.props.getProperty("opt")) ? "faster game" : "faster conversion";
        TextView info = text(serial + opt + "\n" + state, 13, color);
        info.setPadding(0, dp(2), 0, dp(8));
        card.addView(info);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        HorizontalScrollView rowScroll = new HorizontalScrollView(this);
        rowScroll.setHorizontalScrollBarEnabled(false);
        rowScroll.addView(row);
        card.addView(rowScroll);

        if (g.ready()) {
            Button play = button("Play", true);
            play.setOnClickListener(v -> startActivity(new Intent(this, GameActivity.class)
                    .putExtra(GameActivity.EXTRA_GAME, g.id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK)));
            row.addView(play);
            Button export = button("Export as APK", false);
            export.setOnClickListener(v -> exportGame(g));
            row.addView(export);
        }
        if (!busy && !g.ready() && g.done() != null) {
            Button go = button("Continue converting", !g.ready());
            go.setOnClickListener(v -> ConvertService.start(this, new Intent(ConvertService.ACTION_CONVERT)
                    .putExtra(ConvertService.EXTRA_GAME, g.id)));
            row.addView(go);
        }
        if (busy) {
            Button stop = button("Stop", false);
            stop.setOnClickListener(v -> ConvertService.start(this, new Intent(ConvertService.ACTION_STOP)));
            row.addView(stop);
        }
        if (!busy && g.done() != null && g.done().ordinal() >= Game.Step.DISC.ordinal()) {
            Button again = button("Convert again", false);
            again.setOnClickListener(v -> askOptimisation(chosen -> {
                // Keep the disc; redo everything from the analyzer on.
                g.props.setProperty("step", Game.Step.DISC.name());
                g.props.remove("error");
                try {
                    g.save();
                } catch (IOException ignored) {
                }
                //noinspection ResultOfMethodCallIgnored
                g.gameLib().delete();
                ConvertService.start(this, new Intent(ConvertService.ACTION_CONVERT)
                        .putExtra(ConvertService.EXTRA_GAME, g.id)
                        .putExtra(ConvertService.EXTRA_OPT, chosen));
            }));
            row.addView(again);
        }
        Button convertLog = button("Convert log", false);
        convertLog.setOnClickListener(v -> showLog("Convert log", g.convertLog()));
        row.addView(convertLog);
        if (g.gameLog().isFile()) {
            Button gameLog = button("Game log", false);
            gameLog.setOnClickListener(v -> showLog("Game log", g.gameLog()));
            row.addView(gameLog);
        }
        if (!busy) {
            Button delete = button("Delete", false);
            delete.setOnClickListener(v -> new AlertDialog.Builder(this)
                    .setTitle("Delete " + g.title() + "?")
                    .setMessage("This removes the converted game and its copy of the disc. Save files in its folder are deleted too.")
                    .setPositiveButton("Delete", (d, w) -> {
                        g.delete();
                        refresh();
                    })
                    .setNegativeButton("Cancel", null)
                    .show());
            row.addView(delete);
        }
        return card;
    }

    private void exportGame(Game g) {
        if (!getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(this)
                    .setTitle("Allow installing games")
                    .setMessage("To install the exported game, allow PocketR5900 to install apps, then tap Export again.")
                    .setPositiveButton("Open settings", (d, w) -> startActivity(new Intent(
                            android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + getPackageName()))))
                    .setNegativeButton("Cancel", null)
                    .show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Export " + g.title() + "?")
                .setMessage("This makes a separate app for the game that you can install and keep. "
                        + "It holds its own copy of the disc, so it takes about as much space again.")
                .setPositiveButton("Export", (d, w) -> ConvertService.start(this,
                        new Intent(ConvertService.ACTION_EXPORT).putExtra(ConvertService.EXTRA_GAME, g.id)))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private static String stepName(Game.Step s) {
        switch (s) {
            case IMPORTED:
                return "copied";
            case DISC:
                return "disc read";
            case ANALYZED:
                return "analysed";
            case RECOMPILED:
                return "recompiled";
            case COMPILED:
                return "compiled";
            default:
                return "ready";
        }
    }

    // ----------------------------------------------------------------- logs

    private void showLog(String title, File file) {
        String text = tail(file, 64 * 1024);
        TextView t = text(text.isEmpty() ? "(empty)" : text, 11, TEXT);
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextIsSelectable(true);
        t.setPadding(dp(12), dp(12), dp(12), dp(12));
        ScrollView s = new ScrollView(this);
        s.addView(t);
        s.post(() -> s.fullScroll(View.FOCUS_DOWN));
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(s)
                .setPositiveButton("Close", null)
                .setNeutralButton("Share", (d, w) -> startActivity(Intent.createChooser(
                        new Intent(Intent.ACTION_SEND).setType("text/plain")
                                .putExtra(Intent.EXTRA_SUBJECT, "PocketR5900 " + title)
                                .putExtra(Intent.EXTRA_TEXT, tail(file, 400 * 1024)), "Share log")))
                .show();
    }

    /** The end of a log file (logs can be very large). */
    private static String tail(File file, int max) {
        if (!file.isFile()) {
            return "";
        }
        try (RandomAccessFile f = new RandomAccessFile(file, "r")) {
            long len = f.length();
            long start = Math.max(0, len - max);
            byte[] b = new byte[(int) (len - start)];
            f.seek(start);
            f.readFully(b);
            String s = new String(b, StandardCharsets.UTF_8);
            return start > 0 ? "... (start of the log cut, showing the last " + (max / 1024) + " KB)\n" + s : s;
        } catch (IOException e) {
            return "Cannot read the log: " + e.getMessage();
        }
    }

    // ------------------------------------------------------------ widgets

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    private Button button(String label, boolean primary) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(primary ? Color.WHITE : TEXT);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(primary ? ACCENT : Color.rgb(0x30, 0x36, 0x48));
        bg.setCornerRadius(dp(10));
        b.setBackground(bg);
        b.setPadding(dp(14), 0, dp(14), 0);
        b.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40));
        lp.rightMargin = dp(8);
        b.setLayoutParams(lp);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        return b;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @SuppressWarnings("unused")
    private static String size(long bytes) {
        return String.format(Locale.US, "%.1f MB", bytes / 1048576.0);
    }
}
