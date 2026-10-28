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
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;

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
            for (int t = 1; t <= 120; t++) {
                final String s = report();
                h.post(() -> { if (box != null) box.setText(s); });
                if (t % 10 == 0) dump(app);
                try { Thread.sleep(1500); } catch (InterruptedException ignored) { }
            }
        }).start();
    }

    @Override
    public boolean onStoragePermissionNeeded(String packageName, int userId) { return false; }

    private String report() {
        int lines = 0, dataLines = 0, apkLines = 0, carrom = 0, libgame = 0, blackbox = 0;
        List<String> dataSamples = new ArrayList<>();
        List<String> keySamples = new ArrayList<>();

        try (BufferedReader r = new BufferedReader(new FileReader("/proc/self/maps"))) {
            String line;
            while ((line = r.readLine()) != null) {
                lines++;
                String low = line.toLowerCase();
                if (low.contains("/data/")) { dataLines++; if (dataSamples.size() < 12) dataSamples.add(shorten(line)); }
                if (low.contains(".apk")) apkLines++;
                if (low.contains("carrom")) carrom++;
                if (low.contains("libgame")) libgame++;
                if (low.contains("blackbox")) blackbox++;
                if ((low.contains("carrom") || low.contains("libgame")
                        || low.contains("libblackbox") || low.contains(".apk"))
                        && keySamples.size() < 8) keySamples.add(shorten(line));
            }
        } catch (Throwable t) { }

        StringBuilder sb = new StringBuilder();
        sb.append("Predictor v9\n");
        sb.append("pid=").append(android.os.Process.myPid())
          .append(" [").append(readCmdline(android.os.Process.myPid())).append("]\n");
        sb.append("lines=").append(lines).append("\n");
        sb.append("data=").append(dataLines).append(" apk=").append(apkLines).append("\n");
        sb.append("carrom=").append(carrom).append(" libgame=").append(libgame)
          .append(" bb=").append(blackbox).append("\n");
        sb.append("-- key --\n");
        for (String s : keySamples) sb.append(s).append("\n");
        sb.append("-- data --\n");
        for (String s : dataSamples) sb.append(s).append("\n");
        return sb.toString();
    }

    private String shorten(String line) {
        if (line.length() <= 118) return line;
        int slash = line.indexOf('/');
        if (slash < 0) return line.substring(0, 118);
        String head = line.substring(0, 20);
        String path = line.substring(slash);
        if (path.length() > 95) path = "..." + path.substring(path.length() - 92);
        return head + " " + path;
    }

    private void dump(Application app) {
        try {
            File dir = app.getExternalFilesDir(null);
            if (dir == null) dir = app.getFilesDir();
            if (!dir.exists()) dir.mkdirs();

            StringBuilder self = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new FileReader("/proc/self/maps"))) {
                String line;
                while ((line = r.readLine()) != null) self.append(line).append("\n");
            }
            write(new File(dir, "self_maps.txt"), self.toString());

            StringBuilder all = new StringBuilder();
            File[] dirs = new File("/proc").listFiles();
            if (dirs != null) {
                for (File f : dirs) {
                    String nm = f.getName();
                    if (!nm.matches("\\d+")) continue;
                    int pid;
                    try { pid = Integer.parseInt(nm); } catch (Exception e) { continue; }
                    StringBuilder one = new StringBuilder();
                    try (BufferedReader r = new BufferedReader(new FileReader("/proc/" + pid + "/maps"))) {
                        String line;
                        while ((line = r.readLine()) != null) one.append(line).append("\n");
                    } catch (Throwable t) { continue; }
                    if (one.length() == 0) continue;
                    all.append("=== pid ").append(pid).append(" [")
                       .append(readCmdline(pid)).append("] ===\n").append(one);
                }
            }
            write(new File(dir, "all_maps.txt"), all.toString());
        } catch (Throwable t) { Log.e(TAG, "dump " + t); }
    }

    private void write(File f, String s) {
        try (FileWriter w = new FileWriter(f, false)) { w.write(s); } catch (Throwable t) { }
    }

    private String readCmdline(int pid) {
        try {
            byte[] b = new byte[256];
            FileInputStream in = new FileInputStream("/proc/" + pid + "/cmdline");
            int n = in.read(b);
            in.close();
            if (n <= 0) return "";
            return new String(b, 0, n).replace('\0', ' ').trim();
        } catch (Throwable t) { return ""; }
    }

    private TextView addBox(Context ctx, String text) {
        try {
            WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            TextView tv = new TextView(ctx);
            tv.setText(text);
            tv.setTextSize(7.5f);
            tv.setTextColor(Color.WHITE);
            tv.setBackgroundColor(0xcc000000);
            tv.setPadding(12, 12, 12, 12);
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
            lp.y = 60;
            wm.addView(tv, lp);
            return tv;
        } catch (Throwable t) { Log.e(TAG, "addBox " + t); return null; }
    }
}
