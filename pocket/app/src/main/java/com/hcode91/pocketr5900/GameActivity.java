package com.hcode91.pocketr5900;

import android.app.NativeActivity;
import android.os.Bundle;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;

import java.io.File;

/**
 * Runs a converted game. The native library is the small pocketloader, which
 * loads the game library named in POCKETR5900_GAME_LIB (see loader.c).
 * Runs in its own process (":game"), so every launch starts clean.
 */
public class GameActivity extends NativeActivity {
    static final String EXTRA_GAME = "game";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        String id = getIntent().getStringExtra(EXTRA_GAME);
        Game g = id != null ? Game.load(this, id) : null;
        if (g != null && g.ready()) {
            try {
                //noinspection ResultOfMethodCallIgnored
                g.logs().mkdirs();
                Os.setenv("POCKETR5900_GAME_LIB", g.gameLib().getAbsolutePath(), true);
                Os.setenv("POCKETR5900_BOOT_ELF", g.bootElf().getAbsolutePath(), true);
                Os.setenv("POCKETR5900_LOG_FILE", g.gameLog().getAbsolutePath(), true);
                File iso = g.iso();
                Os.setenv("POCKETR5900_CD_IMAGE", iso.isFile() ? iso.getAbsolutePath() : "", true);
            } catch (ErrnoException e) {
                Log.e("PocketR5900", "cannot set up the game", e);
            }
        } else {
            try {
                Os.setenv("POCKETR5900_GAME_LIB", "", true);
            } catch (ErrnoException ignored) {
            }
        }

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        super.onCreate(savedInstanceState);
        hideSystemBars();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemBars();
        }
    }

    private void hideSystemBars() {
        View decor = getWindow().getDecorView();
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = decor.getWindowInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }
}
