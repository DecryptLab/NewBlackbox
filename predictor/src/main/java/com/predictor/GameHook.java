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
import java.util.Set;
import java.util.TreeSet;

import top.niunaijun.blackbox.app.configuration.AppLifecycleCallback;

public class GameHook extends AppLifecycleCallback {

    private static final String TAG = "PredictorHook";
    private static final String GAME = "com.miniclip.carrom";
    private static volatile boolean started = false;

    private static final class Scan {
        final Set<String> app = new TreeSet<>();
        int total = 0;
    }

    @Override
    public void afterApplicationOnCreate(String packageName, String processName,
                                         Application application, int userId) {
        Log.d(TAG, "hook pkg=" + packageName + " proc=" + processName + " uid=" + userId);
        if (!GAME.equals(packageName) || application == null) return;
        if (started) return;
        started = true;

        final Application app = application;
        new Thread(() -> {
            Scan scan = new Scan();
            String engine = null;
            int last = -1, stable = 0;
            for (int i = 0; i < 90; i++) {
                scan = scan();
                engine = findEngine(scan.app);
                if (engine != null) break;
                if (scan.app.size() == last) stable++; else stable = 0;
                last = scan.app.size();
                if (i >= 8 && stable >= 6) break;   // >=4s in, and stable for 3s
                try { Thread.sleep(500); } catch (InterruptedException ignored) { }
            }
            final String report = buildReport(scan, engine);
            Log.d(TAG, report);
            new Handler(Looper.getMainLooper()).post(() -> showOverlay(app, report));
        }).start();
    }

    @Override
    public boolean onStoragePermissionNeeded(String packageName, int userId) {
        return false;
    }

    private String findEngine(Set<String> libs) {
        for (String l : libs) {
            if (l.matches("(?i).*(carrom|il2cpp|unity|mono|cocos).*")) return l;
        }
        return null;
    }

    private String buildReport(Scan scan, String engine) {
        StringBuilder sb = new StringBuilder();
        sb.append("Predictor hook OK\n");
        sb.append("pid=").append(android.os.Process.myPid()).append("\n");
        sb.append("total libs=").append(scan.total).append("\n");
        sb.append("app libs=").append(scan.app.size()).append("\n");
        sb.append("engine=").append(engine == null ? "NOT FOUND" : engine).append("\n");
        sb.append("--- app libs ---\n");
        int n = 0;
        for (String l : scan.app) {
            sb.append(l).append("\n");
            if (++n >= 40) { sb.append("...\n"); break; }
        }
        if (n == 0) sb.append("(none)\n");
        return sb.toString();
    }

    private Scan scan() {
        Scan s = new Scan();
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/self/maps"))) {
            String line;
            while ((line = r.readLine()) != null) {
                int so = line.indexOf(".so");
                if (so < 0) continue;
                int firstSlash = line.indexOf('/');
                if (firstSlash < 0 || firstSlash > so) continue;
                String full = line.substring(firstSlash, so + 3);
                s.total++;
                if (full.startsWith("/system/") || full.startsWith("/apex/")
                        || full.startsWith("/vendor/") || full.startsWith("/system_ext/")
                        || full.startsWith("/product/") || full.startsWith("/odm/")) continue;
                s.app.add(full.substring(full.lastIndexOf('/') + 1));
            }
        } catch (Throwable t) {
            Log.e(TAG, "scan " + t);
        }
        return s;
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
