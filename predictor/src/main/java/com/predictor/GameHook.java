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
import java.util.Set;
import java.util.TreeSet;

import top.niunaijun.blackbox.app.configuration.AppLifecycleCallback;

public class GameHook extends AppLifecycleCallback {

    private static final String TAG = "PredictorHook";
    private static final String GAME = "com.miniclip.carrom";

    private static volatile TextView box;
    private static volatile boolean started = false;

    @Override
    public void afterApplicationOnCreate(String packageName, String processName,
                                         Application application, int userId) {
        Log.d(TAG, "hook pkg=" + packageName + " proc=" + processName);
        if (!GAME.equals(packageName) || application == null) return;
        if (started) return;
        started = true;

        final Application app = application;
        new Thread(() -> {
            final Handler h = new Handler(Looper.getMainLooper());
            h.post(() -> box = addBox(app, "starting..."));
            for (int i = 0; i < 300; i++) {                 // up to 5 min, live
                final String s = snapshot(i + 1);
                h.post(() -> { if (box != null) box.setText(s); });
                if (s.contains("CARROM=1")) break;
                try { Thread.sleep(1000); } catch (InterruptedException ignored) { }
            }
        }).start();
    }

    @Override
    public boolean onStoragePermissionNeeded(String packageName, int userId) {
        return false;
    }

    private static boolean isSystem(String path) {
        return path.startsWith("/system/") || path.startsWith("/apex/")
                || path.startsWith("/vendor/") || path.startsWith("/system_ext/")
                || path.startsWith("/product/") || path.startsWith("/odm/");
    }

    private String snapshot(int t) {
        StringBuilder sb = new StringBuilder();
        sb.append("Predictor v6  t=").append(t).append("s\n");
        sb.append("pid=").append(android.os.Process.myPid())
          .append(" [").append(readCmdline(android.os.Process.myPid())).append("]\n");

        int total = 0, soLines = 0, carrom = 0;
        Set<String> names = new TreeSet<>();
        Set<String> carromLines = new TreeSet<>();

        try (BufferedReader r = new BufferedReader(new FileReader("/proc/self/maps"))) {
            String line;
            while ((line = r.readLine()) != null) {
                total++;
                int so = line.indexOf(".so");
                if (so < 0) continue;
                soLines++;
                int slash = line.indexOf('/');
                if (slash < 0) continue;
                String path = line.substring(slash, so + 3);
                String low = path.toLowerCase();
                if (low.contains("carrom") || low.contains("libgame")) {
                    carrom++;
                    carromLines.add(line.trim());
                }
                if (!isSystem(path)) {
                    names.add(path.substring(path.lastIndexOf('/') + 1));
                }
            }
        } catch (Throwable e) {
            sb.append("self err ").append(e).append("\n");
        }

        sb.append("self lines=").append(total).append(" so=").append(soLines).append("\n");
        sb.append("nonSys=").append(names.size())
          .append(" CARROM=").append(carrom > 0 ? 1 : 0).append("\n");

        int n = 0;
        for (String l : carromLines) { sb.append(l).append("\n"); if (++n >= 3) break; }

        sb.append("-- names --\n");
        n = 0;
        for (String l : names) { sb.append(l).append("\n"); if (++n >= 12) break; }

        sb.append("-- procs --\n");
        File[] dirs = new File("/proc").listFiles();
        if (dirs != null) {
            for (File d : dirs) {
                String nm = d.getName();
                if (!nm.matches("\\d+")) continue;
                int pid;
                try { pid = Integer.parseInt(nm); } catch (Exception e) { continue; }
                int c = 0;
                try (BufferedReader r = new BufferedReader(new FileReader("/proc/" + pid + "/maps"))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        int so = line.indexOf(".so");
                        if (so < 0) continue;
                        int slash = line.indexOf('/');
                        if (slash < 0) continue;
                        String path = line.substring(slash, so + 3);
                        if (!isSystem(path)) c++;
                    }
                } catch (Throwable e) { continue; }
                sb.append(pid).append(" [").append(readCmdline(pid)).append("] ns=").append(c).append("\n");
            }
        }
        return sb.toString();
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

    private TextView addBox(Context ctx, String text) {
        try {
            WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            TextView tv = new TextView(ctx);
            tv.setText(text);
            tv.setTextSize(8f);
            tv.setTextColor(Color.WHITE);
            tv.setBackgroundColor(0xaa000000);
            tv.setPadding(14, 14, 14, 14);

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
            return tv;
        } catch (Throwable t) {
            Log.e(TAG, "addBox " + t);
            return null;
        }
    }
}
