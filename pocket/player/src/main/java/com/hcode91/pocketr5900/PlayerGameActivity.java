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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Runs the exported game; its library is libgame.so inside this APK. */
public class PlayerGameActivity extends NativeActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        File root = PlayerSetupActivity.root(this);
        try {
            String boot = new String(Files.readAllBytes(new File(root, "boot.path").toPath()), StandardCharsets.UTF_8).trim();
            File iso = new File(root, "disc.iso");
            Os.setenv("POCKETR5900_BOOT_ELF", boot, true);
            Os.setenv("POCKETR5900_CD_IMAGE", iso.isFile() ? iso.getAbsolutePath() : "", true);
            Os.setenv("POCKETR5900_LOG_FILE", new File(root, "game.log").getAbsolutePath(), true);
        } catch (java.io.IOException | ErrnoException e) {
            Log.e("PocketR5900", "cannot set up the game", e);
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
