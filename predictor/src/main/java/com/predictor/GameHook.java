package com.predictor;

import android.app.Application;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import top.niunaijun.blackbox.app.configuration.AppLifecycleCallback;

public class GameHook extends AppLifecycleCallback {

    private static final String TAG = "PredictorHook";
    private static final String GAME = "com.miniclip.carrom";

    private static volatile boolean started = false;

    @Override
    public void beforeCreateApplication(String packageName, String processName, Context context, int userId) {
        Log.d(TAG, "beforeCreateApplication " + packageName + " / " + processName);
    }

    @Override
    public void beforeApplicationOnCreate(String packageName, String processName, Application application, int userId) {
        Log.d(TAG, "beforeApplicationOnCreate " + packageName + " / " + processName);
    }

    @Override
    public void afterApplicationOnCreate(String packageName, String processName, Application application, int userId) {
        Log.d(TAG, "afterApplicationOnCreate " + packageName + " / " + processName);
        if (!GAME.equals(packageName) || application == null) return;
        if (started) return;
        started = true;

        final Application app = application;
        new Thread(() -> {
            String report = buildReport();
            Log.d(TAG, report);
            new Handler(Looper.getMainLooper()).post(() -> showOverlay(app, report));
        }).start();
    }

    @Override
    public boolean onStoragePermissionNeeded(String packageName, int userId) {
        return false;
    }

    private String buildReport() {
        Set<String> libs = new TreeSet<>();
        for (int i = 0; i < 40; i++) {
            libs = scanLibs();
            if (!libs.isEmpty()) break;
            try { Thread.sleep(500); } catch (InterruptedException ignored) { }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Predictor hook OK\n");
        sb.append("pid=").append(android.os.Process.myPid()).append("\n");
        sb.append("loaded libs=").append(libs.size()).append("\n");
        sb.append("--- matches ---\n");

        Pattern p = Pattern.compile("(?i).*(carrom|game|unity|il2cpp|mono|cocos|miniclip|physx|bullet|box2d|main|native).*");
        int shown = 0;
        for (String lib : libs) {
            Matcher m = p.matcher(lib);
            if (m.matches()) {
                sb.append(lib).append("\n");
                if (++shown > 25) { sb.append("...\n"); break; }
            }
        }
        if (shown == 0) sb.append("(none matched)\n");
        return sb.toString();
    }

    private Set<String> scanLibs() {
        Set<String> out = new LinkedHashSet<>();
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/self/maps"))) {
            String line;
            while ((line = r.readLine()) != null) {
                int i = line.indexOf(".so");
                if (i < 0) continue;
                int slash = line.lastIndexOf('/', i);
                if (slash < 0) continue;
                out.add(line.substring(slash + 1, i + 3));
            }
        } catch (Throwable t) {
            Log.e(TAG, "scanLibs " + t);
        }
        return out;
    }

    private void showOverlay(Context ctx, String text) {
        try {
            WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            TextView tv = new TextView(ctx);
            tv.setText(text);
            tv.setTextSize(10f);
            tv.setTextColor(Color.WHITE);
            tv.setBackgroundColor(0x99000000);
            tv.setPadding(20, 20, 20, 20);

            int type = Build.VERSION.SDK_INT >= 26
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.y = 120;

            wm.addView(tv, lp);
            Log.d(TAG, "overlay added");
        } catch (Throwable t) {
            Log.e(TAG, "overlay " + t);
        }
    }
}
