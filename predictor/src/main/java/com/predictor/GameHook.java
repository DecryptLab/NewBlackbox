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
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import top.niunaijun.blackbox.app.configuration.AppLifecycleCallback;

public class GameHook extends AppLifecycleCallback {

    private static final String TAG = "PredictorHook";
    private static final String GAME = "com.miniclip.carrom";
    private static volatile boolean started = false;

    private static final class P {
        int pid;
        String cmd = "";
        boolean readable = false;
        Set<String> libs = new TreeSet<>();
        Set<String> raw = new TreeSet<>();
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
            List<P> procs = new ArrayList<>();
            for (int i = 0; i < 180; i++) {              // up to 90 s
                procs = scanAll();
                boolean found = false;
                for (P p : procs) if (!p.raw.isEmpty()) { found = true; break; }
                if (found) break;
                try { Thread.sleep(500); } catch (InterruptedException ignored) { }
            }
            final String report = buildReport(procs);
            Log.d(TAG, report);
            new Handler(Looper.getMainLooper()).post(() -> showOverlay(app, report));
        }).start();
    }

    @Override
    public boolean onStoragePermissionNeeded(String packageName, int userId) {
        return false;
    }

    private String buildReport(List<P> procs) {
        StringBuilder sb = new StringBuilder();
        sb.append("Predictor v5\n");
        sb.append("my pid=").append(android.os.Process.myPid()).append("\n");
        sb.append("my proc=").append(readCmdline(android.os.Process.myPid())).append("\n");
        sb.append("procs=").append(procs.size()).append("\n");

        for (P p : procs) {
            sb.append("=== ").append(p.pid).append(" [").append(p.cmd).append("] libs=")
              .append(p.libs.size()).append(" ===\n");

            if (!p.raw.isEmpty()) {
                sb.append(">>> MATCH\n");
                int n = 0;
                for (String l : p.raw) {
                    sb.append(l).append("\n");
                    if (++n >= 5) break;
                }
            }
            int n = 0;
            for (String l : p.libs) {
                sb.append(l).append("\n");
                if (++n >= 35) { sb.append("...\n"); break; }
            }
        }
        return sb.toString();
    }

    private List<P> scanAll() {
        List<P> out = new ArrayList<>();
        File[] dirs = new File("/proc").listFiles();
        if (dirs == null) return out;

        for (File d : dirs) {
            String nm = d.getName();
            if (!nm.matches("\\d+")) continue;
            int pid;
            try { pid = Integer.parseInt(nm); } catch (Exception e) { continue; }

            P p = new P();
            p.pid = pid;
            p.cmd = readCmdline(pid);

            try (BufferedReader r = new BufferedReader(new FileReader("/proc/" + pid + "/maps"))) {
                String line;
                while ((line = r.readLine()) != null) {
                    int so = line.indexOf(".so");
                    if (so < 0) continue;
                    int slash = line.indexOf('/');
                    if (slash < 0 || slash > so) continue;
                    String full = line.substring(slash, so + 3);
                    if (full.startsWith("/system/") || full.startsWith("/apex/")
                            || full.startsWith("/vendor/") || full.startsWith("/system_ext/")
                            || full.startsWith("/product/") || full.startsWith("/odm/")) continue;

                    p.libs.add(full.substring(full.lastIndexOf('/') + 1));
                    String low = full.toLowerCase();
                    if (low.contains("carrom") || low.contains("libgame")
                            || low.contains("il2cpp") || low.contains("unity")
                            || low.contains("mono")) {
                        p.raw.add(line.trim());
                    }
                }
                p.readable = true;
            } catch (Throwable t) {
                p.readable = false;
            }

            if (p.readable && (!p.libs.isEmpty() || p.cmd.contains("carrom"))) out.add(p);
            else if (!p.readable && p.cmd.contains("carrom")) out.add(p);
        }
        return out;
    }

    private String readCmdline(int pid) {
        try {
            byte[] b = new byte[256];
            FileInputStream in = new FileInputStream("/proc/" + pid + "/cmdline");
            int n = in.read(b);
            in.close();
            if (n <= 0) return "";
            return new String(b, 0, n).replace('\0', ' ').trim();
        } catch (Throwable t) {
            return "";
        }
    }

    private void showOverlay(Context ctx, String text) {
        try {
            WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            TextView tv = new TextView(ctx);
            tv.setText(text);
            tv.setTextSize(8.5f);
            tv.setTextColor(Color.WHITE);
            tv.setBackgroundColor(0x99000000);
            tv.setPadding(16, 16, 16, 16);

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
            lp.y = 100;

            wm.addView(tv, lp);
        } catch (Throwable t) {
            Log.e(TAG, "overlay " + t);
        }
    }
}
