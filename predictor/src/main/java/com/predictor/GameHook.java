package com.predictor;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;
import android.view.PixelCopy;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.RandomAccessFile;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import top.niunaijun.blackbox.app.configuration.AppLifecycleCallback;

public class GameHook extends AppLifecycleCallback {

    static final String TAG = "PRED";
    static final String GAME = "com.miniclip.carrom";
    static final String LIB = "libgame-CARROM";

    static final int OFF = 0, FULL = 1, A1 = 2, A2 = 3, A3 = 4;
    static final int COLOUR_AUTO = 0, COLOUR_BRIGHT = 1, COLOUR_DARK = 2;

    static volatile int mode = OFF;
    static volatile boolean busy = false;
    static volatile int dbg = 1;
    static volatile int ourColour = COLOUR_AUTO;
    static volatile double dragScale = 1.0;

    static int shotsFired = 0, pots = 0, fails = 0;

    static Application app;
    static volatile Activity act;
    static WindowManager wm;
    static LinearLayout panel;
    static TextView bubble, logView, statusView;
    static ScrollView scroller;
    static boolean logOpen = false, panelOpen = false;
    static File outDir;
    static SharedPreferences sp;
    static final StringBuilder LOGBUF = new StringBuilder();

    static RandomAccessFile mem;
    static long libBase = 0;
    static final long[] RW_S = new long[2048];
    static final long[] RW_E = new long[2048];
    static int rwN = 0;
    static int sw = 720, sh = 1600;

    static Object imObj;
    static Method imInject;

    static int CX, CY;
    static double SCX, SCY;
    static boolean calOk = false;
    static int[] PIX;
    static int PW, PH;
    static int[] QUEUE = new int[400000];

    static int CO = 0x258;
    static int OP = 0x3f0, OS = 0x3c0, OU = 0x3d8;
    static long cacheTn = 0, cacheAt = 0;

    static final double POCKET = 230.008;
    static final double BOARD_W = 460.0;
    static final String[] SU_PATHS = {"/system/bin/su", "/system/xbin/su", "/sbin/su", "/debug_ramdisk/su"};

    static int dp(float v) { return (int)(v * app.getResources().getDisplayMetrics().density); }

    @Override
    public void afterApplicationOnCreate(String pkg, String proc, Application a, int userId) {
        if (!GAME.equals(pkg) || a == null) return;
        app = a;
        a.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            public void onActivityResumed(Activity x) { act = x; }
            public void onActivityCreated(Activity x, Bundle b) {}
            public void onActivityStarted(Activity x) {}
            public void onActivityPaused(Activity x) {}
            public void onActivityStopped(Activity x) {}
            public void onActivitySaveInstanceState(Activity x, Bundle b) {}
            public void onActivityDestroyed(Activity x) { if (act == x) act = null; }
        });
        new Thread(() -> {
            try {
                outDir = new File(Environment.getExternalStorageDirectory(), "CarromPredictor");
                outDir.mkdirs();
                sp = app.getSharedPreferences("pred", Context.MODE_PRIVATE);
                dbg = sp.getInt("dbg", 1);
                ourColour = sp.getInt("colour", COLOUR_AUTO);
                dragScale = sp.getFloat("drag", 1.0f);
            } catch (Throwable t) { Log.e(TAG, "init " + t); }
            try { initInject(); } catch (Throwable t) { log(0, "INJECT FAILED " + t); }
            new Handler(Looper.getMainLooper()).post(GameHook::buildUi);
            log(0, "boot inject=" + (imInject != null));
        }).start();
    }

    // ================== UI ==================

    static void buildUi() {
        try {
            wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
            android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(dm);
            sw = dm.widthPixels; sh = dm.heightPixels;

            bubble = new TextView(app);
            bubble.setText("AIM");
            bubble.setTextSize(11f);
            bubble.setTextColor(Color.WHITE);
            bubble.setGravity(Gravity.CENTER);
            bubble.setBackgroundColor(0xDD0A3D62);
            WindowManager.LayoutParams blp = new WindowManager.LayoutParams(
                    dp(46), dp(46), overlayType(),
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT);
            blp.gravity = Gravity.TOP | Gravity.START;
            blp.x = dp(2); blp.y = sh / 3;
            bubble.setOnTouchListener(new DragTouch(blp));
            bubble.setOnClickListener(v -> togglePanel());
            wm.addView(bubble, blp);

            panel = new LinearLayout(app);
            panel.setOrientation(LinearLayout.VERTICAL);
            panel.setBackgroundColor(0xF0000000);
            panel.setPadding(dp(4), dp(4), dp(4), dp(4));

            panel.addView(row(new String[]{"FULL", "A1", "A2", "A3", "STOP", "HIDE"},
                    new int[]{FULL, A1, A2, A3, OFF, -7}));
            panel.addView(row(new String[]{"WHITE", "BLACK", "AUTO", "P-", "P+"},
                    new int[]{-9, -10, -8, -12, -11}));
            panel.addView(row(new String[]{"TEST", "TAP", "CAL", "SHOT", "DUMP", "LOG"},
                    new int[]{-13, -14, -3, -4, -5, -6}));

            statusView = new TextView(app);
            statusView.setTextSize(10f);
            statusView.setTextColor(Color.parseColor("#00E5FF"));
            statusView.setText("ready");
            panel.addView(statusView);

            scroller = new ScrollView(app);
            logView = new TextView(app);
            logView.setTextSize(9f);
            logView.setTextColor(Color.WHITE);
            scroller.addView(logView);
            panel.addView(scroller, new LinearLayout.LayoutParams(-1, dp(190)));
            scroller.setVisibility(View.GONE);

            WindowManager.LayoutParams plp = new WindowManager.LayoutParams(
                    -1, -2, overlayType(),
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT);
            plp.gravity = Gravity.TOP; plp.y = dp(2);
            wm.addView(panel, plp);
            panel.setVisibility(View.GONE);
        } catch (Throwable t) { Log.e(TAG, "ui " + t); }
    }

    static LinearLayout row(String[] labels, int[] codes) {
        LinearLayout r = new LinearLayout(app);
        r.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < labels.length; i++) {
            Button b = new Button(app);
            b.setText(labels[i]); b.setAllCaps(false); b.setTextSize(11f);
            b.setIncludeFontPadding(false);
            b.setMinWidth(0); b.setMinimumWidth(0); b.setMinHeight(0); b.setMinimumHeight(0);
            b.setPadding(0, 0, 0, 0);
            b.setBackgroundColor(0xFF1E3A5F); b.setTextColor(Color.WHITE);
            final int code = codes[i]; final String lab = labels[i];
            b.setOnClickListener(v -> onButton(lab, code));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(38), 1f);
            lp.setMargins(dp(1), dp(1), dp(1), dp(1));
            r.addView(b, lp);
        }
        return r;
    }

    static int overlayType() {
        return Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
    }

    static void togglePanel() {
        panelOpen = !panelOpen;
        panel.setVisibility(panelOpen ? View.VISIBLE : View.GONE);
        bubble.setBackgroundColor(panelOpen ? 0xDD804000 : 0xDD0A3D62);
    }

    static void onButton(String label, int code) {
        switch (code) {
            case -1: dbg = Math.min(2, dbg + 1); sp.edit().putInt("dbg", dbg).apply(); log(0, "dbg=" + dbg); return;
            case -2: dbg = Math.max(0, dbg - 1); sp.edit().putInt("dbg", dbg).apply(); log(0, "dbg=" + dbg); return;
            case -3: new Thread(() -> {
                if (!capture()) { log(0, "CAL: capture failed"); return; }
                if (calibrate()) log(0, String.format(Locale.US, "CAL OK cx%d cy%d sx%.3f sy%.3f", CX, CY, SCX, SCY));
                else log(0, "CAL FAILED");
            }).start(); return;
            case -4: new Thread(() -> {
                if (!busy) { busy = true; try { oneShot(); } catch (Throwable t) { log(0, "shot err " + t); } busy = false; }
            }).start(); return;
            case -5: new Thread(GameHook::dumpState).start(); return;
            case -6: logOpen = !logOpen; scroller.setVisibility(logOpen ? View.VISIBLE : View.GONE); return;
            case -7: togglePanel(); return;
            case -8: ourColour = COLOUR_AUTO; sp.edit().putInt("colour", ourColour).apply(); log(0, "colour AUTO"); return;
            case -9: ourColour = COLOUR_BRIGHT; sp.edit().putInt("colour", ourColour).apply(); log(0, "colour WHITE"); return;
            case -10: ourColour = COLOUR_DARK; sp.edit().putInt("colour", ourColour).apply(); log(0, "colour BLACK"); return;
            case -11: dragScale = Math.min(1.6, dragScale + 0.1); sp.edit().putFloat("drag", (float) dragScale).apply();
                log(0, "power " + String.format(Locale.US, "%.1f", dragScale)); return;
            case -12: dragScale = Math.max(0.3, dragScale - 0.1); sp.edit().putFloat("drag", (float) dragScale).apply();
                log(0, "power " + String.format(Locale.US, "%.1f", dragScale)); return;
            case -13: new Thread(GameHook::selfTest).start(); return;
            case -14: new Thread(GameHook::tapTest).start(); return;
            default:
                mode = code; log(0, "MODE " + label); status("mode " + label);
                if (code != OFF && !busy) new Thread(GameHook::loop).start();
        }
    }

    static void status(String s) {
        new Handler(Looper.getMainLooper()).post(() -> {
            if (statusView != null) statusView.setText(s + "  s" + shotsFired + " p" + pots + " f" + fails);
        });
    }

    static class DragTouch implements View.OnTouchListener {
        final WindowManager.LayoutParams lp;
        int sx0, sy0, bx, by;
        DragTouch(WindowManager.LayoutParams l) { lp = l; }
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    sx0 = (int) e.getRawX(); sy0 = (int) e.getRawY(); bx = lp.x; by = lp.y; return false;
                case MotionEvent.ACTION_MOVE:
                    lp.x = bx + (int) e.getRawX() - sx0;
                    lp.y = by + (int) e.getRawY() - sy0;
                    try { wm.updateViewLayout(v, lp); } catch (Throwable t) {}
                    return true;
            }
            return false;
        }
    }

    static void log(int lvl, String s) {
        if (lvl > dbg) return;
        Log.i(TAG, s);
        String line = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date()) + " " + s;
        LOGBUF.append(line).append('\n');
        if (LOGBUF.length() > 80000) LOGBUF.delete(0, 16000);
        try {
            if (outDir != null) {
                FileWriter w = new FileWriter(new File(outDir, "log.txt"), true);
                w.write(line + "\n"); w.close();
            }
        } catch (Throwable t) {}
        new Handler(Looper.getMainLooper()).post(() -> {
            if (logView != null) {
                logView.setText(LOGBUF.toString());
                if (logOpen) scroller.fullScroll(ScrollView.FOCUS_DOWN);
            }
        });
    }

    // ================== INJECTION ==================

    static void initInject() throws Exception {
        Class<?> c = Class.forName("android.hardware.input.InputManager");
        imObj = c.getMethod("getInstance").invoke(null);
        imInject = c.getMethod("injectInputEvent", InputEvent.class, int.class);
    }

    static void send(int action, float x, float y, long down) {
        if (imInject == null) return;
        try {
            MotionEvent e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0);
            e.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            imInject.invoke(imObj, e, 0);
            e.recycle();
        } catch (Throwable t) { log(0, "inject err " + t); }
    }

    static boolean suInput(String args) {
        for (String su : SU_PATHS) {
            try {
                if (!new File(su).exists()) continue;
                new ProcessBuilder(su, "-c", "input " + args).redirectErrorStream(true).start().waitFor();
                return true;
            } catch (Throwable t) {}
        }
        return false;
    }

    static void tap(int x, int y) {
        long d = SystemClock.uptimeMillis();
        send(MotionEvent.ACTION_DOWN, x, y, d); sleep(45);
        send(MotionEvent.ACTION_UP, x, y, d);
    }

    static void swipe(int x1, int y1, int x2, int y2, int ms) {
        long d = SystemClock.uptimeMillis();
        send(MotionEvent.ACTION_DOWN, x1, y1, d);
        int steps = 14;
        for (int i = 1; i <= steps; i++) {
            send(MotionEvent.ACTION_MOVE, x1 + (x2 - x1) * i / steps, y1 + (y2 - y1) * i / steps, d);
            sleep(ms / steps);
        }
        send(MotionEvent.ACTION_UP, x2, y2, d);
        log(1, "swipe " + x1 + "," + y1 + "->" + x2 + "," + y2);
    }

    // ================== CAPTURE ==================

    static SurfaceView findSurface(View v) {
        if (v instanceof SurfaceView) return (SurfaceView) v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                SurfaceView s = findSurface(g.getChildAt(i));
                if (s != null) return s;
            }
        }
        return null;
    }

    static boolean capturePixels() {
        Activity a = act;
        if (a == null) return false;
        try {
            SurfaceView sv = findSurface(a.getWindow().getDecorView());
            if (sv == null) return false;
            final int w = sv.getWidth(), h = sv.getHeight();
            if (w < 200 || h < 200) return false;
            final Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            final CountDownLatch latch = new CountDownLatch(1);
            PixelCopy.request(sv, b, res -> latch.countDown(), new Handler(Looper.getMainLooper()));
            if (!latch.await(1500, TimeUnit.MILLISECONDS)) return false;
            PW = w; PH = h;
            PIX = new int[w * h];
            b.getPixels(PIX, 0, PW, 0, 0, w, h);
            return true;
        } catch (Throwable t) { return false; }
    }

    static boolean screencap(File f) {
        String cmd = "screencap -p " + f.getAbsolutePath();
        for (String su : SU_PATHS) {
            try {
                if (!new File(su).exists()) continue;
                new ProcessBuilder(su, "-c", cmd).redirectErrorStream(true).start().waitFor();
                if (f.exists() && f.length() > 1000) return true;
            } catch (Throwable t) {}
        }
        try {
            new ProcessBuilder("screencap", "-p", f.getAbsolutePath()).start().waitFor();
            if (f.exists() && f.length() > 1000) return true;
        } catch (Throwable t) {}
        return false;
    }

    static boolean capture() {
        if (capturePixels()) return true;
        File f = new File(outDir, "cur.png");
        if (!screencap(f)) return false;
        Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath());
        if (b == null) return false;
        PW = b.getWidth(); PH = b.getHeight();
        PIX = new int[PW * PH];
        b.getPixels(PIX, 0, PW, 0, 0, PW, PH);
        return true;
    }

    static boolean saveShot(String name) {
        try {
            File f = new File(outDir, name + ".png");
            if (capturePixels()) {
                Bitmap b = Bitmap.createBitmap(PW, PH, Bitmap.Config.ARGB_8888);
                b.setPixels(PIX, 0, PW, 0, 0, PW, PH);
                FileOutputStream o = new FileOutputStream(f);
                b.compress(Bitmap.CompressFormat.PNG, 90, o); o.close();
                return true;
            }
            return screencap(f);
        } catch (Throwable t) { return false; }
    }

    static int lum(int x, int y) {
        if (x < 0 || y < 0 || x >= PW || y >= PH) return 0;
        int c = PIX[y * PW + x];
        return (((c >> 16) & 0xFF) + ((c >> 8) & 0xFF) + (c & 0xFF)) / 3;
    }

    static boolean boardPix(int x, int y) {
        if (x < 0 || y < 0 || x >= PW || y >= PH) return false;
        int c = PIX[y * PW + x];
        int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
        return (r + g + b) / 3 > 110 && r - b > 40 && r - g > 8;
    }

    // ================== MEMORY ==================

    static boolean openMem() {
        try { mem = new RandomAccessFile("/proc/self/mem", "rw"); return true; }
        catch (Throwable t) { return false; }
    }

    static long rq(long a) {
        try {
            byte[] b = new byte[8]; mem.seek(a); mem.readFully(b);
            long v = 0; for (int i = 7; i >= 0; i--) v = (v << 8) | (b[i] & 0xFFL);
            return v;
        } catch (Throwable t) { return 0; }
    }

    static double rd(long a) { return Double.longBitsToDouble(rq(a)); }

    static void wr(long a, double d) {
        try {
            long v = Double.doubleToRawLongBits(d);
            byte[] b = new byte[8];
            for (int i = 0; i < 8; i++) b[i] = (byte)(v >>> (8 * i));
            mem.seek(a); mem.write(b);
        } catch (Throwable t) {}
    }

    static boolean inRw(long a) {
        for (int i = 0; i < rwN; i++) if (a >= RW_S[i] && a < RW_E[i]) return true;
        return false;
    }

    static void loadMaps() throws Exception {
        rwN = 0; libBase = 0;
        BufferedReader r = new BufferedReader(new FileReader("/proc/self/maps"));
        String ln;
        while ((ln = r.readLine()) != null) {
            String[] p = ln.split("\\s+");
            if (p.length < 6) continue;
            int d = p[0].indexOf('-'); if (d < 0) continue;
            long s, e;
            try {
                s = Long.parseLong(p[0].substring(0, d), 16);
                e = Long.parseLong(p[0].substring(d + 1), 16);
            } catch (Throwable t) { continue; }
            if (p[5].contains(LIB)) { if (libBase == 0 || s < libBase) libBase = s; continue; }
            if (!p[1].startsWith("rw")) continue;
            if (rwN < RW_S.length) { RW_S[rwN] = s; RW_E[rwN] = e; rwN++; }
        }
        r.close();
    }

    static long[] collect(long m, int max) {
        List<Long> out = new ArrayList<>();
        long root = rq(m + 8);
        if (!inRw(root)) return new long[0];
        long[] stack = new long[600];
        int sp = 0;
        stack[sp++] = root;
        int guard = 0;
        while (sp > 0 && out.size() < max && guard++ < 5000) {
            long x = stack[--sp];
            if (!inRw(x) || out.contains(x)) continue;
            long v = rq(x + 0x28);
            if (inRw(v)) out.add(v);
            long rr = rq(x + 8), ll = rq(x);
            if (inRw(rr) && sp < 590) stack[sp++] = rr;
            if (inRw(ll) && sp < 590) stack[sp++] = ll;
        }
        long[] a = new long[out.size()];
        for (int i = 0; i < a.length; i++) a[i] = out.get(i);
        return a;
    }

    static int mapSize(long m) {
        long b = rq(m), e = rq(m + 8), sz = rq(m + 0x10);
        if (!inRw(b) || !inRw(e) || e < b) return -1;
        return (sz >= 0 && sz <= 64) ? (int) sz : -1;
    }

    static int coordOff(long v) {
        for (int o = 0; o < 0x300; o += 8) {
            double x = rd(v + o), y = rd(v + o + 8);
            if (Math.abs(Math.abs(x) - POCKET) < 1.0 && Math.abs(Math.abs(y) - POCKET) < 1.0) return o;
        }
        return -1;
    }

    static int nearMap(long base, int lo, int hi, int co) {
        for (int d = -0x700; d <= 0x700; d += 8) {
            long m = base + d;
            int sz = mapSize(m);
            if (sz < lo || sz > hi) continue;
            long[] v = collect(m, hi);
            if (v.length < lo) continue;
            int okc = 0;
            for (long n : v) {
                double x = rd(n + co), y = rd(n + co + 8);
                if (!Double.isNaN(x) && !Double.isNaN(y) && Math.abs(x) <= 231 && Math.abs(y) <= 231) okc++;
            }
            if (okc >= v.length) return d;
        }
        return Integer.MIN_VALUE;
    }

    static long findTable() {
        if (cacheTn != 0 && System.currentTimeMillis() - cacheAt < 6000 && mapSize(cacheTn + OP) == 4) return cacheTn;

        for (int i = 0; i < rwN; i++) {
            for (long a = RW_S[i]; a + 0x20 <= RW_E[i]; a += 0x100000) {
                int n = (int) Math.min(0x100000, RW_E[i] - a);
                byte[] buf = new byte[n];
                try { mem.seek(a); mem.readFully(buf); } catch (Throwable t) { continue; }
                for (int o = 0; o + 0x20 <= n; o += 8) {
                    long sz = 0;
                    for (int k = 7; k >= 0; k--) sz = (sz << 8) | (buf[o + 0x10 + k] & 0xFFL);
                    if (sz < 4 || sz > 8) continue;
                    long q = a + o;
                    long[] vals = collect(q, 8);
                    if (vals.length != sz) continue;
                    int co = -1, good = 0;
                    for (long v : vals) {
                        int c = coordOff(v);
                        if (c >= 0) { co = c; good++; }
                    }
                    if (good < sz || co < 0) continue;

                    int dS = nearMap(q, 2, 3, co);
                    if (dS == Integer.MIN_VALUE) continue;
                    int dU = nearMap(q, 8, 30, co);
                    if (dU == Integer.MIN_VALUE) continue;

                    long cand = q;
                    int saveP = OP, saveS = OS, saveU = OU, saveC = CO;
                    OP = 0; OS = dS; OU = dU; CO = co;
                    double sc = scoreTable(cand);
                    if (sc < 0.08) { OP = saveP; OS = saveS; OU = saveU; CO = saveC; continue; }
                    log(0, String.format(Locale.US, "table 0x%x sc%.2f P%x S%x U%x C%x", cand, sc, OP, OS, OU, CO));
                    cacheTn = cand; cacheAt = System.currentTimeMillis();
                    return cand;
                }
            }
        }
        return 0;
    }

    static double scoreTable(long q) {
        long[] st = collect(q + OS, 4);
        double sc = -1;
        for (long s : st) {
            double wx = rd(s + CO), wy = rd(s + CO + 8);
            if (!okWorld(wx, wy)) continue;
            int x = px(wx), y = py(wy);
            if (x < 10 || x > PW - 11 || y < 10 || y > PH - 11) continue;
            int hit = 0, tot = 0;
            for (int dy = -6; dy <= 6; dy++) for (int dx = -6; dx <= 6; dx++) {
                tot++; if (lum(x + dx, y + dy) > 185) hit++;
            }
            sc = Math.max(sc, (double) hit / tot);
        }
        return sc;
    }

    // ================== CALIBRATION ==================

    static boolean calibrate() {
        calOk = false;
        if (PIX == null) return false;

        int bx0 = PW, by0 = PH, bx1 = -1, by1 = -1;
        long bestArea = 0;
        boolean[] seen = new boolean[PW * PH];
        for (int y = 0; y < PH; y += 2) {
            for (int x = 0; x < PW; x += 2) {
                int i = y * PW + x;
                if (seen[i] || !boardPix(x, y)) continue;
                int qh = 0, qt = 0, cnt = 0;
                int mnx = x, mxx = x, mny = y, mxy = y;
                QUEUE[qt++] = i; seen[i] = true;
                while (qh < qt && cnt < 390000) {
                    int p = QUEUE[qh++];
                    int cx2 = p % PW, cy2 = p / PW; cnt++;
                    if (cx2 < mnx) mnx = cx2; if (cx2 > mxx) mxx = cx2;
                    if (cy2 < mny) mny = cy2; if (cy2 > mxy) mxy = cy2;
                    for (int dy = -2; dy <= 2; dy += 2) for (int dx = -2; dx <= 2; dx += 2) {
                        int nx = cx2 + dx, ny = cy2 + dy;
                        if (nx < 0 || ny < 0 || nx >= PW || ny >= PH) continue;
                        int j = ny * PW + nx;
                        if (seen[j] || !boardPix(nx, ny)) continue;
                        seen[j] = true;
                        if (qt < 390000) QUEUE[qt++] = j;
                    }
                }
                if (cnt > bestArea) { bestArea = cnt; bx0 = mnx; by0 = mny; bx1 = mxx; by1 = mxy; }
            }
        }

        int bw, bh;
        if (bx1 > 0 && bestArea > PW * PH * 0.05) {
            bw = bx1 - bx0 + 1; bh = by1 - by0 + 1;
            CX = bx0 + bw / 2; CY = by0 + bh / 2;
            log(1, "board " + bx0 + "," + by0 + "-" + bx1 + "," + by1);
        } else {
            bw = (int)(PW * 0.92); bh = (int)(PH * 0.42);
            CX = PW / 2; CY = PH / 2;
            log(0, "CAL: board not found, frame fallback");
        }
        if (bw < PW * 0.45 || bh < PH * 0.28) { log(0, "CAL: too small " + bw + "x" + bh); return false; }

        SCX = (bw * 0.70) / BOARD_W;
        SCY = (bh * 0.70) / BOARD_W;
        log(1, String.format(Locale.US, "scale %.3f %.3f cx%d cy%d", SCX, SCY, CX, CY));
        if (SCX < 0.4 || SCX > 3.0 || SCY < 0.4 || SCY > 3.0) { log(0, "CAL: scale range"); return false; }
        double r = SCX / SCY;
        if (r < 0.7 || r > 1.45) { log(0, String.format(Locale.US, "CAL: ratio %.2f", r)); return false; }
        calOk = true;
        return true;
    }

    static int px(double wx) { return (int)(CX + wx * SCX); }
    static int py(double wy) { return (int)(CY - wy * SCY); }

    static boolean okWorld(double x, double y) {
        return !Double.isNaN(x) && !Double.isNaN(y) && Math.abs(x) <= 231 && Math.abs(y) <= 231;
    }

    // ================== STRIKER (MEMORY-BASED) ==================

    static class Striker {
        long node;
        double wx, wy;
        int px, py;
        double bright;   // halo brightness
    }

    /** Read both strikers from memory, project each to screen, score by brightness */
    static Striker[] readStrikers(long tn) {
        long[] nodes = collect(tn + OS, 4);
        Striker[] out = new Striker[nodes.length];
        for (int i = 0; i < nodes.length; i++) {
            Striker s = new Striker();
            s.node = nodes[i];
            s.wx = rd(nodes[i] + CO);
            s.wy = rd(nodes[i] + CO + 8);
            s.bright = -1;
            if (!okWorld(s.wx, s.wy)) { out[i] = s; continue; }
            s.px = px(s.wx);
            s.py = py(s.wy);
            if (s.px < 12 || s.px > PW - 13 || s.py < 12 || s.py > PH - 13) { out[i] = s; continue; }
            int hit = 0, tot = 0;
            for (int dy = -14; dy <= 14; dy++) for (int dx = -20; dx <= 20; dx++) {
                tot++;
                if (lum(s.px + dx, s.py + dy) > 185) hit++;
            }
            s.bright = (double) hit / tot;
            out[i] = s;
        }
        return out;
    }

    /** Pick the striker with highest brightness (the one with active halo) */
    static Striker pickActiveStriker(Striker[] all) {
        Striker best = null;
        double bs = 0.35;
        for (Striker s : all) {
            if (s == null || s.bright < bs) continue;
            bs = s.bright;
            best = s;
        }
        return best;
    }

    /** Read board state, wait for it to stabilize. Return true when stable. */
    static boolean waitBoardStable(long tn, long timeoutMs) {
        long[] pucks = collect(tn + OU, 24);
        if (pucks.length == 0) return false;
        double[] prev = new double[pucks.length * 2];
        for (int i = 0; i < pucks.length; i++) {
            prev[i * 2] = rd(pucks[i] + CO);
            prev[i * 2 + 1] = rd(pucks[i] + CO + 8);
        }
        long t0 = System.currentTimeMillis();
        while (System.currentTimeMillis() - t0 < timeoutMs) {
            sleep(300);
            try { loadMaps(); } catch (Throwable t) {}
            long[] now = collect(tn + OU, 24);
            if (now.length != pucks.length) {
                // board changed, reset
                pucks = now;
                prev = new double[pucks.length * 2];
                for (int i = 0; i < pucks.length; i++) {
                    prev[i * 2] = rd(pucks[i] + CO);
                    prev[i * 2 + 1] = rd(pucks[i] + CO + 8);
                }
                continue;
            }
            boolean moved = false;
            for (int i = 0; i < pucks.length; i++) {
                double x = rd(pucks[i] + CO), y = rd(pucks[i] + CO + 8);
                if (Math.abs(x - prev[i * 2]) > 0.3 || Math.abs(y - prev[i * 2 + 1]) > 0.3) {
                    moved = true; break;
                }
            }
            if (!moved) {
                // re-verify striker is present too
                Striker[] ss = readStrikers(tn);
                if (pickActiveStriker(ss) != null) return true;
            }
            for (int i = 0; i < pucks.length; i++) {
                prev[i * 2] = rd(pucks[i] + CO);
                prev[i * 2 + 1] = rd(pucks[i] + CO + 8);
            }
        }
        return false;
    }

    /** Main wait: stable board + active striker visible (brightness > 0.35) */
    static Striker waitForMyTurn(long tn, long timeoutMs) {
        long t0 = System.currentTimeMillis();
        while (System.currentTimeMillis() - t0 < timeoutMs && mode != OFF) {
            try { loadMaps(); } catch (Throwable t) {}
            if (!capture()) { sleep(500); continue; }
            if (!calibrate()) { sleep(500); continue; }
            Striker[] ss = readStrikers(tn);
            Striker active = pickActiveStriker(ss);
            if (active == null) { status("waiting striker"); sleep(500); continue; }
            // stable?
            if (!waitBoardStable(tn, 2500)) { status("board not stable"); continue; }
            // re-read striker after stability (position might shift when opponent moved)
            try { loadMaps(); } catch (Throwable t) {}
            capture();
            ss = readStrikers(tn);
            active = pickActiveStriker(ss);
            if (active == null) { sleep(500); continue; }
            return active;
        }
        return null;
    }

    // ================== SHOT ==================

    static void loop() {
        if (busy) return;
        busy = true;
        try {
            if (mem == null && !openMem()) { log(0, "mem fail"); return; }
            int guard = 0;
            while (mode != OFF && guard++ < 200) {
                try { oneShot(); } catch (Throwable t) { log(0, "shot err " + t); fails++; }
                if (mode != FULL) break;
                sleep(500);
            }
        } catch (Throwable t) { log(0, "loop err " + t); }
        finally { busy = false; log(0, "end s" + shotsFired + " p" + pots + " f" + fails); }
    }

    static void oneShot() throws Exception {
        loadMaps();
        if (libBase == 0) { log(0, "lib not mapped"); fails++; return; }

        long tn = findTable();
        if (tn == 0) { log(0, "no table"); fails++; return; }

        // Wait for our turn (stable + active striker visible)
        log(0, "waiting for my turn...");
        Striker active = waitForMyTurn(tn, 60000);
        if (active == null) { log(0, "no turn within 60s"); fails++; return; }

        int sx = active.px;
        int sy = active.py;
        boolean isBottom = (sy > CY);
        log(0, String.format(Locale.US, "turn: striker %d,%d side=%s bright=%.2f",
                sx, sy, isBottom ? "BOTTOM" : "TOP", active.bright));

        // Read current board
        try { loadMaps(); } catch (Throwable t) {}
        long[] pucks = collect(tn + OU, 24);
        long[] pockets = collect(tn + OP, 8);
        if (pucks.length < 1 || pockets.length < 4) { fails++; log(0, "nodes " + pucks.length + "/" + pockets.length); return; }

        // Determine own color
        int[] plv = new int[pucks.length];
        for (int i = 0; i < pucks.length; i++) {
            double wx = rd(pucks[i] + CO), wy = rd(pucks[i] + CO + 8);
            if (!okWorld(wx, wy)) { plv[i] = -1; continue; }
            int x = px(wx), y = py(wy), s = 0, c = 0;
            for (int u = -2; u <= 2; u++) for (int v = -2; v <= 2; v++) {
                if (x + u < 0 || y + v < 0 || x + u >= PW || y + v >= PH) continue;
                s += lum(x + u, y + v); c++;
            }
            plv[i] = c == 0 ? -1 : s / c;
        }
        int[] sorted = plv.clone();
        Arrays.sort(sorted);
        int n2 = 0;
        for (int v : sorted) if (v >= 0) n2++;
        int med = 128;
        if (n2 > 0) {
            int acc = 0;
            for (int v : sorted) { if (v < 0) continue; acc++; if (acc >= n2 / 2) { med = v; break; } }
        }
        int col = ourColour;
        if (col == COLOUR_AUTO) {
            // Which color is more common on our side? That's ours.
            double bSum = 0, dSum = 0;
            int bN = 0, dN = 0;
            for (int i = 0; i < plv.length; i++) {
                if (plv[i] < 0) continue;
                double screenY = py(rd(pucks[i] + CO + 8));
                double distFromOurSide = isBottom ? (PH - screenY) : screenY;
                if (plv[i] >= med) { bSum += distFromOurSide; bN++; }
                else { dSum += distFromOurSide; dN++; }
            }
            double bAvg = bN > 0 ? bSum / bN : 1e9;
            double dAvg = dN > 0 ? dSum / dN : 1e9;
            // If a color is much closer to our side, that's ours
            if (Math.abs(bAvg - dAvg) < 40) col = COLOUR_DARK;  // tie -> black default
            else col = (bAvg < dAvg) ? COLOUR_BRIGHT : COLOUR_DARK;
        }
        log(1, "pucks " + pucks.length + " med " + med + " col " + (col == COLOUR_BRIGHT ? "WHITE" : "BLACK"));

        // Find best shot — try own color first, then any puck as fallback
        int bp = -1, bq = -1, bblk = 0, bfoul = 0;
        double bestSc = 1e18, gxs = 0, gys = 0;
        for (int pass = 0; pass < 2; pass++) {
            for (int k = 0; k < pucks.length; k++) {
                if (plv[k] < 0) continue;
                boolean isBright = plv[k] >= med;
                boolean isOwnCol = (col == COLOUR_BRIGHT) ? isBright : !isBright;
                if (pass == 0 && !isOwnCol) continue;
                // pass 1: only if we haven't found anything yet
                double pxs = px(rd(pucks[k] + CO)), pys = py(rd(pucks[k] + CO + 8));
                if (pxs < 3 || pxs > PW - 4 || pys < 3 || pys > PH - 4) continue;
                for (int q = 0; q < pockets.length; q++) {
                    double qxw = rd(pockets[q] + CO), qyw = rd(pockets[q] + CO + 8);
                    if (!okWorld(qxw, qyw)) continue;
                    double qxs = px(qxw), qys = py(qyw);
                    double dx = qxs - pxs, dy = qys - pys;
                    double L = Math.hypot(dx, dy);
                    if (L < 1) L = 1;
                    double off = (mode == A2) ? 0.0 : 20.0;
                    double gx = pxs - dx / L * off, gy = pys - dy / L * off;
                    if (gx < 13 || gx > PW - 14 || gy < 13 || gy > PH - 14) continue;
                    double ax = gx - sx, ay = gy - sy;
                    double AL = Math.hypot(ax, ay);
                    if (AL < 32) continue;
                    double ux = ax / AL, uy = ay / AL;
                    int blk = 0;
                    for (int o = 0; o < pucks.length; o++) {
                        if (o == k || plv[o] < 0) continue;
                        double oxs = px(rd(pucks[o] + CO)), oys = py(rd(pucks[o] + CO + 8));
                        if (segd(oxs, oys, sx, sy, gx, gy) < 30) blk++;
                        if (segd(oxs, oys, pxs, pys, qxs, qys) < 30) blk += 2;
                    }
                    double npk = 1e18, npo = 1e18;
                    for (long p2 : pucks) {
                        double wx = rd(p2 + CO), wy = rd(p2 + CO + 8);
                        if (!okWorld(wx, wy)) continue;
                        npk = Math.min(npk, ray(sx, sy, ux, uy, px(wx), py(wy), 24));
                    }
                    for (long p2 : pockets) {
                        double wx = rd(p2 + CO), wy = rd(p2 + CO + 8);
                        if (!okWorld(wx, wy)) continue;
                        npo = Math.min(npo, ray(sx, sy, ux, uy, px(wx), py(wy), 15));
                    }
                    int foul = (npo < npk) ? 1 : 0;
                    double sc = L + 800.0 * blk + 500.0 * foul + 0.25 * Math.hypot(gx - sx, gy - sy);
                    if (pass == 1) sc += 50000.0;  // penalty for off-color
                    if (sc < bestSc) {
                        bestSc = sc; bp = k; bq = q; bblk = blk; bfoul = foul; gxs = gx; gys = gy;
                    }
                }
            }
            if (bp >= 0) break;
        }
        if (bp < 0) { fails++; log(0, "no target"); return; }

        // Build swipe
        double ax = gxs - sx, ay = gys - sy, AL = Math.hypot(ax, ay);
        double ux = ax / AL, uy = ay / AL;
        // Drag: ~60% of screen width scaled by user power
        int drag = (int)(PW * 0.60 * dragScale);
        int tx = Math.max(1, Math.min(PW - 2, (int)(sx - ux * drag)));
        int ty = Math.max(1, Math.min(PH - 2, (int)(sy - uy * drag)));

        log(0, String.format(Locale.US, "SHOT p%d q%d blk%d foul%d aim=%.2f,%.2f drag=%d swipe %d,%d->%d,%d",
                bp, bq + 1, bblk, bfoul, ux, uy, drag, sx, sy, tx, ty));
        saveShot("before_" + System.currentTimeMillis());

        if (mode == A3) {
            wr(pucks[bp] + CO, ux);
            wr(pucks[bp] + CO + 8, uy);
            tap(sx, sy);
        } else {
            swipe(sx, sy, tx, ty, 250);
        }
        shotsFired++;
        sleep(1500);

        // Verify shot fired (striker moved or disappeared)
        try { loadMaps(); } catch (Throwable t) {}
        Striker[] after = readStrikers(tn);
        Striker act2 = pickActiveStriker(after);
        boolean moved = (act2 == null) || (Math.hypot(act2.px - sx, act2.py - sy) > 15);

        if (!moved && mode != A3) {
            log(0, "swipe failed, retry via su");
            suInput("touchscreen swipe " + sx + " " + sy + " " + tx + " " + ty + " 250");
            sleep(1500);
            after = readStrikers(tn);
            act2 = pickActiveStriker(after);
            moved = (act2 == null) || (Math.hypot(act2.px - sx, act2.py - sy) > 15);
        }

        if (moved) pots++;  // rough success check — will refine via next stable check
        sleep(3000);
        saveShot("after_" + System.currentTimeMillis());
        log(0, "shot complete");
    }

    static double segd(double px, double py, double ax, double ay, double bx, double by) {
        double vx = bx - ax, vy = by - ay, l2 = vx * vx + vy * vy;
        double t = l2 > 0 ? ((px - ax) * vx + (py - ay) * vy) / l2 : 0;
        if (t < 0) t = 0;
        if (t > 1) t = 1;
        return Math.hypot(px - (ax + vx * t), py - (ay + vy * t));
    }

    static double ray(double ax, double ay, double ux, double uy, double cx, double cy, double r) {
        double dx = cx - ax, dy = cy - ay;
        double t = dx * ux + dy * uy;
        if (t < -r) return 1e18;
        double d = Math.hypot(cx - (ax + ux * t), cy - (ay + uy * t));
        if (d > r) return 1e18;
        double b = Math.sqrt(r * r - d * d);
        return (t - b < 0) ? t + b : t - b;
    }

    static void sleep(int ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) {} }

    // ================== TESTS ==================

    static void tapTest() {
        log(0, "== TAPTEST ==");
        try { loadMaps(); } catch (Throwable t) {}
        if (!capture() || !calibrate()) { log(0, "prep fail"); return; }
        long tn = findTable();
        if (tn == 0) { log(0, "no table"); return; }
        Striker[] ss = readStrikers(tn);
        for (Striker s : ss) {
            if (s == null) continue;
            log(0, String.format(Locale.US, "striker px%d py%d bright%.2f", s.px, s.py, s.bright));
        }
        Striker active = pickActiveStriker(ss);
        if (active == null) { log(0, "no active striker"); return; }
        log(0, "swipe up from " + active.px + "," + active.py);
        swipe(active.px, active.py, active.px, active.py - 200, 250);
        sleep(2500);
        saveShot("taptest_" + System.currentTimeMillis());
    }

    static void selfTest() {
        log(0, "== SELFTEST ==");
        try {
            loadMaps();
            log(0, "libBase=0x" + Long.toHexString(libBase) + " rw=" + rwN);
            if (!capture()) { log(0, "capture FAILED"); return; }
            log(0, "capture ok " + PW + "x" + PH);
            if (!calibrate()) { log(0, "cal failed"); return; }
            log(0, String.format(Locale.US, "cal cx%d cy%d sx%.3f sy%.3f", CX, CY, SCX, SCY));
            long tn = findTable();
            if (tn == 0) { log(0, "no table"); return; }
            log(0, "table=0x" + Long.toHexString(tn));
            Striker[] ss = readStrikers(tn);
            for (Striker s : ss) {
                if (s == null) continue;
                log(0, String.format(Locale.US, "striker mem(%.1f,%.1f) px%d py%d bright%.2f",
                        s.wx, s.wy, s.px, s.py, s.bright));
            }
            Striker active = pickActiveStriker(ss);
            log(0, "active=" + (active == null ? "NONE" : active.px + "," + active.py));
        } catch (Throwable t) { log(0, "err " + t); }
    }

    static void dumpState() {
        try {
            File f = new File(outDir, "dump_" + System.currentTimeMillis() + ".txt");
            FileWriter w = new FileWriter(f);
            w.write("cal=" + CX + "," + CY + "," + SCX + "," + SCY + " ok=" + calOk + "\n");
            w.write(String.format(Locale.US, "OP%x OS%x OU%x CO%x\n", OP, OS, OU, CO));
            w.write("shots=" + shotsFired + " pots=" + pots + " fails=" + fails + "\n");
            w.close();
            log(0, "dump " + f.getName());
        } catch (Throwable t) { log(0, "dump " + t); }
    }
}
