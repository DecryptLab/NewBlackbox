package com.predictor;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.widget.TextView;
import android.widget.Toast;

import top.niunaijun.blackbox.BlackBoxCore;

public class MainActivity extends Activity {

    private static final String TAG = "Predictor";
    private static final String GAME = "com.miniclip.carrom";
    private static final int USER = 0;
    private static final int REQ_OVERLAY = 1001;

    private TextView status;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        status = new TextView(this);
        status.setTextSize(18f);
        status.setPadding(60, 220, 60, 60);
        setContentView(status);
        status.setText("Carrom Predictor\n\nchecking overlay permission...");
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!Settings.canDrawOverlays(this)) {
            status.setText("Overlay permission needed.\nAsking now...");
            startActivityForResult(
                new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                           Uri.parse("package:" + getPackageName())),
                REQ_OVERLAY);
        } else {
            prepare();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_OVERLAY && Settings.canDrawOverlays(this)) {
            prepare();
        }
    }

    private void prepare() {
        status.setText("Overlay OK.\nPreparing Carrom Pool in sandbox...");
        new Thread(() -> {
            try {
                if (!BlackBoxCore.get().isInstalled(GAME, USER)) {
                    say("Installing Carrom Pool into sandbox...");
                    BlackBoxCore.get().installPackageAsUser(GAME, USER);
                }
                if (!BlackBoxCore.get().isInstalled(GAME, USER)) {
                    say("Install failed. Is Carrom Pool installed on this phone?");
                    return;
                }
                say("Launching Carrom Pool...");
                boolean ok = BlackBoxCore.get().launchApk(GAME, USER);
                say(ok ? "Launched. Open Carrom Pool." : "launchApk returned false.");
            } catch (Throwable t) {
                Log.e(TAG, "prepare", t);
                say("Error: " + t);
            }
        }).start();
    }

    private void say(String s) {
        ui.post(() -> {
            status.setText(s);
            Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
        });
    }
}
