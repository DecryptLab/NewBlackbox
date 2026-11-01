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
    static final long CLS_OFF_DEFAULT = 0x28E4160L;

    static final int OFF = 0, FULL = 1, A1 = 2, A2 = 3, A3 = 4;
    static final int COLOUR_AUTO = 0, COLOUR_BRIGHT = 1, COLOUR_DARK = 2;

    static final int F_STRIKER = 0x3c0, F_PUCK = 0x3d8, F_POCKET = 0x3f0;

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
    static TextView bubble;
    static TextView logView;
    static TextView statusView;
    static ScrollView scroller;
    static boolean logOpen = false;
    static boolean panelOpen = false;
    static File outDir;
    static SharedPreferences sp;
    static final StringBuilder LOGBUF = new StringBuilder();

    static RandomAccessFile mem;
    static long libBase = 0;
    static final long[] RW_S = new long[2048];
    static final long[] RW_E = new long[2048];
    static int rwN = 0;
    static int sw = 720, sh = 1600;
    static long clsOff = CLS_OFF_DEFAULT;

    static Object imObj;
    static Method imInject;

    static int CX, CY;
    static double SCX, SCY;
    static boolean calOk = false;
    static int[] PIX;
    static int PW, PH;
    static int[] QUEUE = new int[400000];

    static final double POCKET = 230.008;
    static final double BOARD_W = 460.0;
    static final String[] SU_PATHS = {"/system/bin/su", "/system/xbin/su", "/sbin/su", "/debug_ramdisk/su"};

    static int dp(float v) { return (int) (v * app.getResources().getDisplayMetrics().density); }

    @Override
    public void afterApplicationOnCreate(String pkg, String proc, Application a, int userId) {
        if (!GAME.equals(pkg) || a == null) return;
        app = a;
        final String processName = proc == null ? "?" : proc;

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
                clsOff = sp.getLong("clsoff", CLS_OFF_DEFAULT);
                ourColour = sp.getInt("colour", COLOUR_AUTO);
                dragScale = sp.getFloat("drag", 1.0f);
            } catch (Throwable t) { Log.e(TAG, "init " + t); }
            try { initInject(); } catch (Throwable t) { log(0, "INJECT FAILED " + t); }
            new Handler(Looper.getMainLooper()).post(GameHook::buildUi);
            log(0, "boot proc=" + processName + " inject=" + (imInject != null) + " dbg=" + dbg);
            startWatchdog();
        }).start();
    }

    static void buildUi() {
        try {
            wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
            android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(dm);
            sw = dm.widthPixels;
            sh = dm.heightPixels;

            bubble = new TextView(app);
            bubble.setText("AIM");
            bubble.setTextSize(11f);
            bubble.setTextColor(Color.WHITE);
            bubble.setGravity(Gravity.CENTER);
            bubble.setBackgroundColor(0xDD0A3D62);
            WindowManager.LayoutParams blp = new WindowManager.LayoutParams(
                    dp(46), dp(46), overlayType(),
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT);
            blp.gravity = Gravity.TOP | Gravity.START;
            blp.x = dp(2);
            blp.y = sh / 3;
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
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT);
            plp.gravity = Gravity.TOP;
            plp.y = dp(2);
            wm.addView(panel, plp);
            panel.setVisibility(View.GONE);
        } catch (Throwable t) { Log.e(TAG, "ui " + t); }
    }

    static LinearLayout row(String[] labels, int[] codes) {
        LinearLayout r = new LinearLayout(app);
        r.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < labels.length; i++) {
            Button b = new Button(app);
            b.setText(labels[i]);
            b.setAllCaps(false);
            b.setTextSize(11f);
            b.setIncludeFontPadding(false);
            b.setMinWidth(0);
            b.setMinimumWidth(0);
            b.setMinHeight(0);
            b.setMinimumHeight(0);
            b.setPadding(0, 0, 0, 0);
            b.setBackgroundColor(0xFF1E3A5F);
            b.setTextColor(Color.WHITE);
            final int code = codes[i];
            final String lab = labels[i];
            b.setOnClickListener(v -> onButton(lab, code));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(38), 1f);
            lp.setMargins(dp(1), dp(1), dp(1), dp(1));
            r.addView(b, lp);
        }
        return r;
    }

    static int overlayType() {
        return Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
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
            case -11: dragScale = Math.min(1.6, dragScale + 0.1); sp.edit().putFloat("drag", (float) dragScale).apply(); log(0, "power " + String.format(Locale.US, "%.1f", dragScale)); return;
            case -12: dragScale = Math.max(0.3, dragScale - 0.1); sp.edit().putFloat("drag", (float) dragScale).apply(); log(0, "power " + String.format(Locale.US, "%.1f", dragScale)); return;
            case -13: new Thread(GameHook::selfTest).start(); return;
            case -14: new Thread(GameHook::tapTest).start(); return;
            default:
                mode = code;
                log(0, "MODE " + label);
                status("mode " + label);
                if (code != OFF && !busy) new Thread(GameHook::loop).start();
        }
    }

    static void status(String s) {
        new Handler(Looper.getMainLooper()).post(() -> {
            if (statusView != null)
                statusView.setText(s + "  s" + shotsFired + " p" + pots + " f" + fails
                        + "  c" + ourColour + " pw" + String.format(Locale.US, "%.1f", dragScale));
        });
    }

    static class DragTouch implements View.OnTouchListener {
        final WindowManager.LayoutParams lp;
        int sx0, sy0, bx, by;
        DragTouch(WindowManager.LayoutParams lp2) { lp = lp2; }
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    sx0 = (int) e.getRawX(); sy0 = (int) e.getRawY(); bx = lp.x; by = lp.y;
                    return false;
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
                w.write(line + "\n");
                w.close();
            }
        } catch (Throwable t) {}
        new Handler(Looper.getMainLooper()).post(() -> {
            if (logView != null) {
                logView.setText(LOGBUF.toString());
                if (logOpen) scroller.fullScroll(ScrollView.FOCUS_DOWN);
            }
        });
    }

    /* ================= tests ================= */

    static void tapTest() {
        log(0, "== TAPTEST: touching board centre ==");
        if (!capture()) { log(0, "capture failed"); return; }
        int x = PW / 2, y = PH / 2;
        log(0, "tap " + x + "," + y);
        tap(x, y);
        sleep(900);
        log(0, "if the striker moved or a marker appeared, injection works");
        saveShot("taptest_" + System.currentTimeMillis());
    }

    static void selfTest() {
        log(0, "== SELFTEST ==");
        try {
            loadMaps();
            log(0, "libBase=0x" + Long.toHexString(libBase) + " rw=" + rwN);
            log(0, "inject=" + (imInject != null) + " act=" + (act != null));
            if (!capture()) { log(0, "capture FAILED"); return; }
            log(0, "capture ok " + PW + "x" + PH);
            boolean c = calibrate();
            log(0, "cal=" + c + (c ? String.format(Locale.US, " cx%d cy%d sx%.3f sy%.3f", CX, CY, SCX, SCY) : ""));
            if (!c) { saveShot("failcal_" + System.currentTimeMillis()); return; }
            long t0 = System.currentTimeMillis();
            long tn = findTable();
            log(0, "table=" + (tn == 0 ? "NONE" : "0x" + Long.toHexString(tn)) + " " + (System.currentTimeMillis() - t0) + "ms");
            if (tn == 0) return;
            log(0, "pucks=" + collect(tn + F_PUCK, 24).length + " pockets=" + collect(tn + F_POCKET, 4).length);
            int sy = strikerY(true);
            log(0, "striker=" + (sy < 0 ? "NOT FOUND" : strikerX(sy) + "," + sy));
        } catch (Throwable t) { log(0, "selftest err " + t); }
        log(0, "== END ==");
    }

    static void saveShot(String name) {
        try {
            File f = new File(outDir, name + ".png");
            if (captureToFile(f)) log(1, "saved " + f.getName() + " " + f.length());
        } catch (Throwable t) { log(0, "saveShot " + t); }
    }

    static void dumpState() {
        try {
            File f = new File(outDir, "dump_" + System.currentTimeMillis() + ".txt");
            FileWriter w = new FileWriter(f);
            w.write("dbg=" + dbg + " mode=" + mode + " colour=" + ourColour + " power=" + dragScale + "\n");
            w.write("shots=" + shotsFired + " pots=" + pots + " fails=" + fails + "\n");
            w.write("PW=" + PW + " PH=" + PH + "\n");
            w.write("libBase=0x" + Long.toHexString(libBase) + " rwN=" + rwN + "\n");
            w.write("cal=" + CX + "," + CY + "," + SCX + "," + SCY + " ok=" + calOk + "\n");
            w.close();
            log(0, "dump " + f.getName());
        } catch (Throwable t) { log(0, "dump " + t); }
    }

    static void startWatchdog() {
        new Thread(() -> {
            while (true) {
                sleep(3000);
                status(busy ? "RUNNING" : "idle");
            }
        }).start();
    }

    /* ================= inject ================= */

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
                Process p = new ProcessBuilder(su, "-c", "input " + args).redirectErrorStream(true).start();
                p.waitFor();
                return true;
            } catch (Throwable t) {}
        }
        return false;
    }

    static void tap(int x, int y) {
        long d = SystemClock.uptimeMillis();
        send(MotionEvent.ACTION_DOWN, x, y, d);
        sleep(45);
        send(MotionEvent.ACTION_UP, x, y, d);
    }

    static void swipe(int x1, int y1, int x2, int y2, int ms) {
        long d = SystemClock.uptimeMillis();
        send(MotionEvent.ACTION_DOWN, x1, y1, d);
        int steps = 12;
        for (int i = 1; i <= steps; i++) {
            send(MotionEvent.ACTION_MOVE, x1 + (x2 - x1) * i / steps, y1 + (y2 - y1) * i / steps, d);
            sleep(ms / steps);
        }
        send(MotionEvent.ACTION_UP, x2, y2, d);
        log(1, "swipe " + x1 + "," + y1 + "->" + x2 + "," + y2);
    }

    /* ================= capture ================= */

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
        if (a == null) { log(1, "no activity"); return false; }
        try {
            SurfaceView sv = findSurface(a.getWindow().getDecorView());
            if (sv == null) { log(1, "no surface"); return false; }
            final int w = sv.getWidth(), h = sv.getHeight();
            if (w < 200 || h < 200) { log(1, "surface " + w + "x" + h); return false; }
            final Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            final CountDownLatch latch = new CountDownLatch(1);
            PixelCopy.request(sv, b, res -> latch.countDown(), new Handler(Looper.getMainLooper()));
            if (!latch.await(1500, TimeUnit.MILLISECONDS)) { log(0, "pixelcopy timeout"); return false; }
            PW = w; PH = h;
            PIX = new int[w * h];
            b.getPixels(PIX, 0, PW, 0, 0, w, h);
            return true;
        } catch (Throwable t) { log(0, "pixelcopy " + t); return false; }
    }

    static boolean captureToFile(File f) {
        if (capturePixels()) {
            try {
                FileOutputStream o = new FileOutputStream(f);
                Bitmap b = Bitmap.createBitmap(PW, PH, Bitmap.Config.ARGB_8888);
                b.setPixels(PIX, 0, PW, 0, 0, PW, PH);
                b.compress(Bitmap.CompressFormat.PNG, 90, o);
                o.close();
                return true;
            } catch (Throwable t) { log(0, "png " + t); }
        }
        return screencap(f);
    }

    static boolean screencap(File f) {
        String cmd = "screencap -p " + f.getAbsolutePath();
        for (String su : SU_PATHS) {
            try {
                if (!new File(su).exists()) continue;
                Process p = new ProcessBuilder(su, "-c", cmd).redirectErrorStream(true).start();
                p.waitFor();
                if (f.exists() && f.length() > 1000) return true;
            } catch (Throwable t) {}
        }
        try {
            Process p = new ProcessBuilder("screencap", "-p", f.getAbsolutePath()).start();
            p.waitFor();
            if (f.exists() && f.length() > 1000) return true;
        } catch (Throwable t) {}
        return false;
    }

    static boolean capture() {
        if (capturePixels()) return true;
        File f = new File(outDir, "cur.png");
        if (!screencap(f)) { log(0, "capture: all paths failed"); return false; }
        Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath());
        if (b == null) return false;
        PW = b.getWidth(); PH = b.getHeight();
        PIX = new int[PW * PH];
        b.getPixels(PIX, 0, PW, 0, 0, PW, PH);
        return true;
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

    /* ================= memory ================= */

    static boolean openMem() {
        try { mem = new RandomAccessFile("/proc/self/mem", "rw"); return true; }
        catch (Throwable t) { log(0, "open mem " + t); return false; }
    }

    static long rq(long a) {
        try {
            byte[] b = new byte[8];
            mem.seek(a);
            mem.readFully(b);
            long v = 0;
            for (int i = 7; i >= 0; i--) v = (v << 8) | (b[i] & 0xFFL);
            return v;
        } catch (Throwable t) { return 0; }
    }

    static double rd(long a) { return Double.longBitsToDouble(rq(a)); }

    static void wr(long a, double d) {
        try {
            long v = Double.doubleToRawLongBits(d);
            byte[] b = new byte[8];
            for (int i = 0; i < 8; i++) b[i] = (byte) (v >>> (8 * i));
            mem.seek(a);
            mem.write(b);
        } catch (Throwable t) { log(1, "wr fail"); }
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
            int d = p[0].indexOf('-');
            if (d < 0) continue;
            long s, e;
            try {
                s = Long.parseLong(p[0].substring(0, d), 16);
                e = Long.parseLong(p[0].substring(d + 1), 16);
            } catch (Throwable t) { continue; }
            String path = p[5];
            if (path.contains(LIB)) { if (libBase == 0 || s < libBase) libBase = s; continue; }
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

    static int mapCount(long m) {
        long b = rq(m), e = rq(m + 8), sz = rq(m + 0x10);
        if (!inRw(b) || !inRw(e) || e < b) return -1;
        return (sz >= 0 && sz <= 64) ? (int) sz : -1;
    }

    static long findTable() {
        long t = findByClassref();
        if (t != 0) return t;
        t = findBySignature();
        if (t != 0) { log(0, "table via signature 0x" + Long.toHexString(t)); return t; }
        return 0;
    }

    static long findByClassref() {
        if (libBase == 0) return 0;
        long cr = libBase + clsOff;
        long best = 0;
        double bestSc = -1;
        for (int i = 0; i < rwN; i++) {
            for (long a = RW_S[i]; a + 8 <= RW_E[i]; a += 0x100000) {
                int n = (int) Math.min(0x100000, RW_E[i] - a);
                byte[] buf = new byte[n];
                try { mem.seek(a); mem.readFully(buf); } catch (Throwable t) { continue; }
                for (int o = 0; o + 8 <= n; o += 8) {
                    long v = 0;
                    for (int k = 7; k >= 0; k--) v = (v << 8) | (buf[o + k] & 0xFFL);
                    if (v != cr) continue;
                    long q = a + o;
                    if (!plausible(q)) continue;
                    double sc = scoreTable(q);
                    if (sc > bestSc) { bestSc = sc; best = q; }
                }
            }
        }
        if (best != 0) log(1, String.format(Locale.US, "classref 0x%x sc%.2f", best, bestSc));
        return bestSc >= 0.08 ? best : 0;
    }

    static boolean plausible(long q) {
        if (!inRw(rq(q + F_STRIKER)) || !inRw(rq(q + F_PUCK)) || !inRw(rq(q + F_POCKET))) return false;
        int a = mapCount(q + F_PUCK), b = mapCount(q + F_STRIKER), c = mapCount(q + F_POCKET);
        return a >= 1 && a <= 30 && b >= 1 && b <= 4 && c == 4;
    }

    static double scoreTable(long q) {
        long[] st = collect(q + F_STRIKER, 4);
        double sc = -1;
        for (long s : st) {
            double wx = rd(s + 0x258), wy = rd(s + 0x260);
            if (!okWorld(wx, wy)) continue;
            int x = px(wx), y = py(wy);
            if (x < 10 || x > PW - 11 || y < 10 || y > PH - 11) continue;
            int hit = 0, tot = 0;
            for (int dy = -6; dy <= 6; dy++) for (int dx = -6; dx <= 6; dx++) {
                tot++;
                if (lum(x + dx, y + dy) > 185) hit++;
            }
            sc = Math.max(sc, (double) hit / tot);
        }
        return sc;
    }

    static long findBySignature() {
        for (int i = 0; i < rwN; i++) {
            for (long a = RW_S[i]; a + 0x18 <= RW_E[i]; a += 8) {
                if (rq(a + 0x10) != 4) continue;
                if (!inRw(rq(a)) || !inRw(rq(a + 8))) continue;
                long[] vals = collect(a, 4);
                if (vals.length != 4) continue;
                int good = 0;
                for (long v : vals) {
                    double x = rd(v + 0x258), y = rd(v + 0x260);
                    if (Math.abs(Math.abs(x) - POCKET) < 0.6 && Math.abs(Math.abs(y) - POCKET) < 0.6) good++;
                }
                if (good < 4) continue;
                long tn = a - F_POCKET;
                if (!plausible(tn)) continue;
                if (scoreTable(tn) < 0.04) continue;
                return tn;
            }
        }
        return 0;
    }

    /* ================= geometry ================= */

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
                QUEUE[qt++] = i;
                seen[i] = true;
                while (qh < qt && cnt < 390000) {
                    int p = QUEUE[qh++];
                    int cx2 = p % PW, cy2 = p / PW;
                    cnt++;
                    if (cx2 < mnx) mnx = cx2;
                    if (cx2 > mxx) mxx = cx2;
                    if (cy2 < mny) mny = cy2;
                    if (cy2 > mxy) mxy = cy2;
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
            bw = bx1 - bx0 + 1;
            bh = by1 - by0 + 1;
            CX = bx0 + bw / 2;
            CY = by0 + bh / 2;
            log(1, "board " + bx0 + "," + by0 + "-" + bx1 + "," + by1);
        } else {
            bw = (int) (PW * 0.90);
            bh = (int) (PH * 0.55);
            CX = PW / 2;
            CY = PH / 2;
            log(0, "CAL: board not found, using frame");
        }
        if (bw < PW * 0.45 || bh < PH * 0.28) { log(0, "CAL: board too small " + bw + "x" + bh); return false; }

        SCX = (bw * 0.88) / BOARD_W;
        SCY = (bh * 0.88) / BOARD_W;
        log(1, String.format(Locale.US, "scale %.3f %.3f cx%d cy%d", SCX, SCY, CX, CY));
        if (SCX < 0.5 || SCX > 3.0 || SCY < 0.5 || SCY > 3.0) { log(0, "CAL: scale range"); return false; }
        double r = SCX / SCY;
        if (r < 0.7 || r > 1.45) { log(0, String.format(Locale.US, "CAL: ratio %.2f", r)); return false; }
        calOk = true;
        return true;
    }

    static int px(double wx) { return (int) (CX + wx * SCX); }
    static int py(double wy) { return (int) (CY - wy * SCY); }

    static boolean okWorld(double x, double y) {
        return !Double.isNaN(x) && !Double.isNaN(y) && Math.abs(x) <= 231 && Math.abs(y) <= 231;
    }

    static int strikerY(boolean bottom) {
        int from = bottom ? (int) (CY + 100 * SCY) : (int) (CY - 320 * SCY);
        int to = bottom ? (int) (CY + 320 * SCY) : (int) (CY - 100 * SCY);
        from = Math.max(4, Math.min(PH - 5, from));
        to = Math.max(4, Math.min(PH - 5, to));
        if (from > to) { int t = from; from = to; to = t; }
        int best = -1, bv = -1;
        for (int y = from; y <= to; y++) {
            for (int x = CX - 80; x <= CX + 80; x += 2) {
                if (x < 8 || x > PW - 9) continue;
                int s = 0;
                for (int u = -2; u <= 2; u++) for (int v = -2; v <= 2; v++) s += lum(x + u, y + v);
                if (s > bv) { bv = s; best = y; }
            }
        }
        log(1, "strikerY " + best + " bv" + bv);
        return bv > 3400 ? best : -1;
    }

    static int strikerX(int yy) {
        long ax = 0;
        int cn = 0;
        for (int dx = -80; dx <= 80; dx++) for (int dy = -14; dy <= 14; dy++) {
            int xx = CX + dx, y2 = yy + dy;
            if (lum(xx, y2) > 205) { ax += xx; cn++; }
        }
        return cn > 25 ? (int) (ax / cn) : CX;
    }

    /* ================= shot ================= */

    static void loop() {
        if (busy) return;
        busy = true;
        try {
            if (mem == null && !openMem()) return;
            int guard = 0;
            while (mode != OFF && guard++ < 400) {
                try { oneShot(); } catch (Throwable t) { log(0, "shot err " + t); fails++; }
                if (mode != FULL) break;
                sleep(1200);
            }
        } catch (Throwable t) { log(0, "loop err " + t); }
        finally { busy = false; log(0, "end s" + shotsFired + " p" + pots + " f" + fails); }
    }

    static void oneShot() throws Exception {
        loadMaps();
        if (libBase == 0) { log(0, "fail: lib not mapped"); fails++; return; }
        if (!capture()) { fails++; return; }
        if (!calibrate()) { fails++; saveShot("failcal_" + System.currentTimeMillis()); return; }

        long tn = findTable();
        if (tn == 0) { fails++; log(0, "fail: no table"); return; }

        int sy = strikerY(true);
        if (sy < 0) { log(0, "skip: no striker"); return; }
        int sx = strikerX(sy);
        log(1, "striker " + sx + "," + sy);

        long[] pucks = collect(tn + F_PUCK, 24);
        long[] pockets = collect(tn + F_POCKET, 4);
        if (pucks.length < 1 || pockets.length < 4) { fails++; log(0, "fail: nodes " + pucks.length + "/" + pockets.length); return; }

        int[] plv = new int[pucks.length];
        for (int i = 0; i < pucks.length; i++) {
            double wx = rd(pucks[i] + 0x258), wy = rd(pucks[i] + 0x260);
            if (!okWorld(wx, wy)) { plv[i] = -1; continue; }
            int x = px(wx), y = py(wy), s = 0, c = 0;
            for (int u = -2; u <= 2; u++) for (int v = -2; v <= 2; v++) {
                if (x + u < 0 || y + v < 0 || x + u >= PW || y + v >= PH) continue;
                s += lum(x + u, y + v);
                c++;
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
            double bd = 0, dd = 0;
            int bn = 0, dn = 0;
            for (int i = 0; i < plv.length; i++) {
                if (plv[i] < 0) continue;
                double y = py(rd(pucks[i] + 0x260));
                if (plv[i] >= med) { bd += (PH - y); bn++; } else { dd += (PH - y); dn++; }
            }
            col = (bn == 0) ? COLOUR_DARK : (dn == 0) ? COLOUR_BRIGHT : ((bd / bn) <= (dd / dn) ? COLOUR_BRIGHT : COLOUR_DARK);
        }
        log(1, "pucks " + pucks.length + " med " + med + " col " + col);

        int bp = -1, bq = -1, bblk = 0, bfoul = 0;
        double bestSc = 1e18, gxs = 0, gys = 0;
        for (int k = 0; k < pucks.length; k++) {
            if (plv[k] < 0) continue;
            boolean isBright = plv[k] >= med;
            if ((col == COLOUR_BRIGHT && !isBright) || (col == COLOUR_DARK && isBright)) continue;
            double pxs = px(rd(pucks[k] + 0x258)), pys = py(rd(pucks[k] + 0x260));
            if (pxs < 3 || pxs > PW - 4 || pys < 3 || pys > PH - 4) continue;
            for (int q = 0; q < pockets.length; q++) {
                double qxw = rd(pockets[q] + 0x258), qyw = rd(pockets[q] + 0x260);
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
                if (AL < 1) AL = 1;
                double ux = ax / AL, uy = ay / AL;
                int blk = 0;
                for (int o = 0; o < pucks.length; o++) {
                    if (o == k || plv[o] < 0) continue;
                    double oxs = px(rd(pucks[o] + 0x258)), oys = py(rd(pucks[o] + 0x260));
                    if (segd(oxs, oys, sx, sy, gx, gy) < 22) blk++;
                    if (segd(oxs, oys, pxs, pys, qxs, qys) < 22) blk += 2;
                }
                double npk = 1e18, npo = 1e18;
                for (long p2 : pucks) {
                    double wx = rd(p2 + 0x258), wy = rd(p2 + 0x260);
                    if (!okWorld(wx, wy)) continue;
                    npk = Math.min(npk, ray(sx, sy, ux, uy, px(wx), py(wy), 22));
                }
                for (long p2 : pockets) {
                    double wx = rd(p2 + 0x258), wy = rd(p2 + 0x260);
                    if (!okWorld(wx, wy)) continue;
                    npo = Math.min(npo, ray(sx, sy, ux, uy, px(wx), py(wy), 15));
                }
                int foul = (npo < npk) ? 1 : 0;
                double sc = L + 5000.0 * blk + 500.0 * foul + 0.25 * Math.hypot(gx - sx, gy - sy);
                if (sc < bestSc) { bestSc = sc; bp = k; bq = q; bblk = blk; bfoul = foul; gxs = gx; gys = gy; }
            }
        }
        if (bp < 0) { fails++; log(0, "fail: no target"); return; }

        double ax = gxs - sx, ay = gys - sy, AL = Math.hypot(ax, ay);
        double ux = ax / AL, uy = ay / AL;
        int drag = (int) (118 * (SCX + SCY) / 2 * dragScale);
        int tx = Math.max(1, Math.min(PW - 2, (int) (sx - ux * drag)));
        int ty = Math.max(1, Math.min(PH - 2, (int) (sy - uy * drag)));

        log(0, String.format(Locale.US, "SHOT p%d q%d aim%.2f,%.2f drag%d", bp, bq + 1, ux, uy, drag));
        saveShot("before_" + System.currentTimeMillis());

        if (mode == A3) {
            wr(pucks[bp] + 0xd0, ux);
            wr(pucks[bp] + 0xd8, uy);
            wr(pucks[bp] + 0xc, 1.0);
            sleep(120);
            tap(sx, sy);
        } else {
            swipe(sx, sy, tx, ty, 240);
        }
        shotsFired++;
        sleep(1100);

        capture();
        int sy2 = strikerY(true);
        boolean moved = false;
        if (sy2 >= 0) {
            int mx = strikerX(sy2);
            int d = (int) Math.hypot(mx - sx, sy2 - sy);
            log(0, "after d" + d);
            if (d >= 4) moved = true;
        } else { moved = true; pots++; }

        if (!moved && mode != A3) {
            log(0, "inject did not register, retry via su input");
            suInput("touchscreen swipe " + sx + " " + sy + " " + tx + " " + ty + " 240");
            sleep(1500);
        } else if (!moved) {
            log(0, "A3 did not register");
        }
        sleep(4500);
        saveShot("after_" + System.currentTimeMillis());
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
}
