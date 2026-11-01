package com.predictor;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;
import android.view.PixelCopy;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
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
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import top.niunaijun.blackbox.app.configuration.AppLifecycleCallback;

/**
 * Carrom aim helper. Runs inside the game process (BlackBox).
 *
 * Flow per shot: wait until the board is still and our striker is on screen ->
 * read coins (memory seed + pixel snap) -> pick a clean shot (sliding the striker if a
 * better line exists) -> swipe -> measure how the striker/coin really moved and learn from it.
 */
public class GameHook extends AppLifecycleCallback {

    static final String TAG = "PRED";
    static final String GAME = "com.miniclip.carrom";
    static final String LIB = "libgame-CARROM";
    static final long CLS_OFF_DEFAULT = 0x28E4160L;
    static final int F_STRIKER = 0x3c0, F_PUCK = 0x3d8, F_POCKET = 0x3f0;

    static final String[] SU_PATHS = {"/system/bin/su", "/system/xbin/su", "/sbin/su", "/debug_ramdisk/su"};

    /* ---------- settings (saved) ---------- */
    static volatile int colourPref = 0;       // 0 auto, 1 white, 2 black
    static volatile double dragScale = 1.0;
    static volatile double gdWorld = 31.5;    // striker+coin centre distance at contact (world units)
    static volatile int injMode = 0;          // 0 dispatch to game view, 1 system inject
    static volatile boolean moveOn = true;    // slide the striker along the baseline
    static volatile boolean learnGd = true;   // learn contact distance from measurements
    static volatile int dbg = 1;

    /* ---------- run state ---------- */
    static volatile boolean running = false, busy = false, cancel = false;
    static volatile String state = "idle";
    static volatile int shots = 0, pots = 0, misses = 0, fails = 0, injFail = 0;
    static volatile String lastAim = "-", lastLaunch = "-", lastCal = "-";
    static double gain = 1.6;                 // slider knob px per striker px
    static final List<Double> gdHist = new ArrayList<>();

    /* ---------- android bits ---------- */
    static Application app;
    static volatile Activity act;
    static WindowManager wm;
    static SharedPreferences sp;
    static File outDir;
    static int sw = 720, sh = 1600;
    static float density = 2f;
    static int svX = 0, svY = 0, svSX = 0, svSY = 0;   // surface offset in window / on screen

    static Object imObj;
    static Method imInject;

    /* ---------- memory ---------- */
    static RandomAccessFile mem;
    static long libBase = 0;
    static final long[] RW_S = new long[2048];
    static final long[] RW_E = new long[2048];
    static int rwN = 0;
    static long clsOff = CLS_OFF_DEFAULT;
    static long tnCache = 0;
    static int calPW = -1;

    /* ---------- ui ---------- */
    static final int BG = 0xF2111720, CARD = 0xFF1B2330, TXT = 0xFFE8EEF5, SUB = 0xFF8A97A8,
            ACC = 0xFF2DD4BF, OK = 0xFF34D399, WARN = 0xFFFBBF24, BAD = 0xFFF87171, BTN = 0xFF263041;
    static LinearLayout panel;
    static TextView bubble, statusView, logView;
    static ScrollView scroller;
    static boolean panelOpen = false, logOpen = false;
    static final StringBuilder LOGBUF = new StringBuilder();
    static final List<Runnable> refreshers = new ArrayList<>();

    interface IGet { int get(); }
    interface ISet { void set(int v); }
    interface SGet { String get(); }

    /* ======================================================================
     *  boot
     * ====================================================================== */

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
                if (!outDir.exists() && !outDir.mkdirs()) {
                    File alt = app.getExternalFilesDir(null);
                    if (alt != null) outDir = alt;
                }
                File old = new File(outDir, "log.txt");
                if (old.exists() && old.length() > 2_000_000) old.delete();
            } catch (Throwable t) { Log.e(TAG, "dir " + t); }
            try {
                sp = app.getSharedPreferences("pred", Context.MODE_PRIVATE);
                dbg = sp.getInt("dbg", 1);
                clsOff = sp.getLong("clsoff", CLS_OFF_DEFAULT);
                colourPref = sp.getInt("colour2", 0);
                dragScale = sp.getFloat("drag", 1.0f);
                gdWorld = sp.getFloat("gd", 31.5f);
                injMode = sp.getInt("inj", 0);
                moveOn = sp.getBoolean("move", true);
                learnGd = sp.getBoolean("learn", true);
                density = app.getResources().getDisplayMetrics().density;
            } catch (Throwable t) { Log.e(TAG, "prefs " + t); }
            try { initInject(); } catch (Throwable t) { log("system inject unavailable: " + t); }
            new Handler(Looper.getMainLooper()).post(GameHook::buildUi);
            log("v6 boot proc=" + processName + " inject=" + (imInject != null));
            startTicker();
        }).start();
    }

    static void save() {
        try {
            sp.edit().putInt("dbg", dbg).putInt("colour2", colourPref).putFloat("drag", (float) dragScale)
                    .putFloat("gd", (float) gdWorld).putInt("inj", injMode).putBoolean("move", moveOn)
                    .putBoolean("learn", learnGd).apply();
        } catch (Throwable ignored) {}
    }

    /* ======================================================================
     *  UI
     * ====================================================================== */

    static int dp(float v) { return (int) (v * density + 0.5f); }

    static GradientDrawable box(int color, float rDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(rDp));
        return g;
    }

    static TextView tv(String t, float sp, int color, boolean bold) {
        TextView v = new TextView(app);
        v.setText(t);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT_BOLD);
        return v;
    }

    static TextView pill(String t, int bg, int fg, Runnable r) {
        TextView v = tv(t, 12, fg, true);
        v.setGravity(Gravity.CENTER);
        v.setBackground(box(bg, 10));
        v.setPadding(dp(8), dp(10), dp(8), dp(10));
        v.setOnClickListener(x -> r.run());
        return v;
    }

    static LinearLayout.LayoutParams weight(float w, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w);
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        return p;
    }

    static LinearLayout hrow() {
        LinearLayout r = new LinearLayout(app);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(8);
        r.setLayoutParams(p);
        return r;
    }

    static LinearLayout seg(String[] labels, IGet g, ISet st) {
        LinearLayout row = new LinearLayout(app);
        row.setBackground(box(CARD, 10));
        row.setPadding(dp(3), dp(3), dp(3), dp(3));
        final TextView[] items = new TextView[labels.length];
        final Runnable upd = () -> {
            for (int i = 0; i < items.length; i++) {
                boolean on = g.get() == i;
                items[i].setBackground(on ? box(ACC, 8) : null);
                items[i].setTextColor(on ? 0xFF05201C : SUB);
            }
        };
        for (int i = 0; i < labels.length; i++) {
            TextView t = tv(labels[i], 11, SUB, true);
            t.setGravity(Gravity.CENTER);
            t.setPadding(dp(6), dp(7), dp(6), dp(7));
            final int idx = i;
            t.setOnClickListener(v -> { st.set(idx); save(); refreshUi(); });
            items[i] = t;
            row.addView(t, weight(1f, 0, 0, 0, 0));
        }
        refreshers.add(upd);
        upd.run();
        return row;
    }

    static LinearLayout labelled(String label, View control) {
        LinearLayout r = hrow();
        TextView l = tv(label, 11, SUB, true);
        l.setWidth(dp(64));
        r.addView(l);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1f);
        r.addView(control, p);
        return r;
    }

    static LinearLayout stepper(SGet val, Runnable minus, Runnable plus) {
        LinearLayout r = new LinearLayout(app);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setBackground(box(CARD, 10));
        r.setPadding(dp(3), dp(3), dp(3), dp(3));
        TextView m = tv("\u2013", 16, TXT, true);
        TextView p = tv("+", 16, TXT, true);
        final TextView v = tv(val.get(), 12, ACC, true);
        for (TextView t : new TextView[]{m, p}) {
            t.setGravity(Gravity.CENTER);
            t.setBackground(box(BTN, 8));
            t.setPadding(dp(14), dp(4), dp(14), dp(4));
        }
        m.setOnClickListener(x -> { minus.run(); save(); refreshUi(); });
        p.setOnClickListener(x -> { plus.run(); save(); refreshUi(); });
        v.setGravity(Gravity.CENTER);
        r.addView(m);
        r.addView(v, weight(1f, 0, 0, 0, 0));
        r.addView(p);
        refreshers.add(() -> v.setText(val.get()));
        return r;
    }

    static void buildUi() {
        try {
            wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
            android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(dm);
            sw = dm.widthPixels;
            sh = dm.heightPixels;
            density = dm.density;

            /* floating round button */
            bubble = tv("AIM", 11, 0xFF05201C, true);
            bubble.setGravity(Gravity.CENTER);
            bubble.setBackground(circle(ACC));
            final WindowManager.LayoutParams blp = new WindowManager.LayoutParams(
                    dp(46), dp(46), overlayType(),
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            blp.gravity = Gravity.TOP | Gravity.START;
            blp.x = dp(4);
            blp.y = sh / 3;
            bubble.setOnTouchListener(new DragTouch(blp, bubble, GameHook::togglePanel));
            wm.addView(bubble, blp);

            /* panel */
            panel = new LinearLayout(app);
            panel.setOrientation(LinearLayout.VERTICAL);
            panel.setBackground(box(BG, 16));
            panel.setPadding(dp(12), dp(10), dp(12), dp(12));

            LinearLayout head = new LinearLayout(app);
            head.setGravity(Gravity.CENTER_VERTICAL);
            TextView title = tv("CARROM AIM", 14, TXT, true);
            head.addView(title, weight(1f, 0, 0, 0, 0));
            TextView close = tv("  \u2013  ", 18, SUB, true);
            close.setOnClickListener(v -> togglePanel());
            head.addView(close);
            panel.addView(head);

            statusView = tv("idle", 10.5f, ACC, false);
            statusView.setTypeface(Typeface.MONOSPACE);
            statusView.setBackground(box(CARD, 10));
            statusView.setPadding(dp(10), dp(8), dp(10), dp(8));
            LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, -2);
            sl.topMargin = dp(8);
            panel.addView(statusView, sl);

            LinearLayout main = hrow();
            main.addView(pill("\u25b6  AUTO", ACC, 0xFF05201C, GameHook::startAuto), weight(1.2f, 0, 0, 4, 0));
            main.addView(pill("\u25ce  1 SHOT", BTN, TXT, GameHook::startSingle), weight(1f, 0, 0, 4, 0));
            main.addView(pill("\u25a0  STOP", 0xFF4A2327, BAD, GameHook::stopAll), weight(0.9f, 0, 0, 0, 0));
            panel.addView(main);

            panel.addView(labelled("SIDE", seg(new String[]{"AUTO", "WHITE", "BLACK"},
                    () -> colourPref, v -> colourPref = v)));
            panel.addView(labelled("POWER", stepper(
                    () -> String.format(Locale.US, "%.1f\u00d7", dragScale),
                    () -> dragScale = Math.max(0.5, Math.round((dragScale - 0.1) * 10) / 10.0),
                    () -> dragScale = Math.min(1.6, Math.round((dragScale + 0.1) * 10) / 10.0))));
            panel.addView(labelled("CONTACT", stepper(
                    () -> String.format(Locale.US, "%.1f", gdWorld),
                    () -> gdWorld = Math.max(24, gdWorld - 0.5),
                    () -> gdWorld = Math.min(40, gdWorld + 0.5))));
            panel.addView(labelled("TAP VIA", seg(new String[]{"VIEW", "SYSTEM"},
                    () -> injMode, v -> { injMode = v; injFail = 0; })));
            panel.addView(labelled("SLIDE", seg(new String[]{"ON", "OFF"},
                    () -> moveOn ? 0 : 1, v -> moveOn = v == 0)));
            panel.addView(labelled("LEARN", seg(new String[]{"ON", "OFF"},
                    () -> learnGd ? 0 : 1, v -> learnGd = v == 0)));

            LinearLayout tools = hrow();
            tools.addView(pill("CAL", BTN, SUB, GameHook::doCal), weight(1f, 0, 0, 4, 0));
            tools.addView(pill("DUMP", BTN, SUB, () -> new Thread(GameHook::dumpState).start()), weight(1f, 0, 0, 4, 0));
            tools.addView(pill("VERBOSE", BTN, SUB, () -> { dbg = dbg == 1 ? 2 : 1; save(); log("verbose " + (dbg == 2)); }),
                    weight(1.3f, 0, 0, 4, 0));
            tools.addView(pill("LOG", BTN, SUB, () -> {
                logOpen = !logOpen;
                scroller.setVisibility(logOpen ? View.VISIBLE : View.GONE);
            }), weight(1f, 0, 0, 0, 0));
            panel.addView(tools);

            scroller = new ScrollView(app);
            logView = tv("", 9, TXT, false);
            logView.setTypeface(Typeface.MONOSPACE);
            scroller.addView(logView);
            scroller.setBackground(box(CARD, 10));
            scroller.setPadding(dp(6), dp(6), dp(6), dp(6));
            LinearLayout.LayoutParams scl = new LinearLayout.LayoutParams(-1, dp(150));
            scl.topMargin = dp(8);
            panel.addView(scroller, scl);
            scroller.setVisibility(View.GONE);

            final WindowManager.LayoutParams plp = new WindowManager.LayoutParams(
                    dp(300), -2, overlayType(),
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            plp.gravity = Gravity.TOP | Gravity.START;
            plp.x = dp(60);
            plp.y = dp(36);
            title.setOnTouchListener(new DragTouch(plp, panel, null));
            wm.addView(panel, plp);
            panel.setVisibility(View.GONE);
            refreshUi();
        } catch (Throwable t) { Log.e(TAG, "ui " + t); }
    }

    static GradientDrawable circle(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        return g;
    }

    static int overlayType() {
        return Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
    }

    static void togglePanel() {
        if (panel == null) return;
        panelOpen = !panelOpen;
        panel.setVisibility(panelOpen ? View.VISIBLE : View.GONE);
        refreshUi();
    }

    static void refreshUi() {
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                for (Runnable r : refreshers) r.run();
                if (statusView != null) {
                    statusView.setText(
                            "state   " + state + "\n"
                                    + "shots " + shots + "   potted " + pots + "   missed " + misses + "   fail " + fails + "\n"
                                    + "aim     " + lastAim + "\n"
                                    + "launch  " + lastLaunch + "\n"
                                    + "board   " + lastCal);
                }
                if (bubble != null) {
                    int c = running ? OK : busy ? WARN : ACC;
                    bubble.setBackground(circle(c));
                    bubble.setText(running ? "RUN" : busy ? "\u2026" : "AIM");
                }
            } catch (Throwable ignored) {}
        });
    }

    static void state(String s) { state = s; refreshUi(); }

    static void startTicker() {
        new Thread(() -> {
            while (true) {
                sleep(900);
                refreshUi();
            }
        }).start();
    }

    static class DragTouch implements View.OnTouchListener {
        final WindowManager.LayoutParams lp;
        final View target;
        final Runnable onClick;
        int sx0, sy0, bx, by;
        boolean moved;
        DragTouch(WindowManager.LayoutParams l, View t, Runnable click) { lp = l; target = t; onClick = click; }
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    sx0 = (int) e.getRawX(); sy0 = (int) e.getRawY(); bx = lp.x; by = lp.y; moved = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    int dx = (int) e.getRawX() - sx0, dy = (int) e.getRawY() - sy0;
                    if (Math.abs(dx) > dp(6) || Math.abs(dy) > dp(6)) moved = true;
                    if (moved) {
                        lp.x = bx + dx;
                        lp.y = by + dy;
                        try { wm.updateViewLayout(target, lp); } catch (Throwable ignored) {}
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!moved && onClick != null) onClick.run();
                    return true;
            }
            return false;
        }
    }

    /* ======================================================================
     *  logging
     * ====================================================================== */

    static void log(String s) { logv(1, s); }

    static void logv(int lvl, String s) {
        if (lvl > dbg) return;
        Log.i(TAG, s);
        String line = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date()) + " " + s;
        synchronized (LOGBUF) {
            LOGBUF.append(line).append('\n');
            if (LOGBUF.length() > 40000) LOGBUF.delete(0, 10000);
        }
        try {
            if (outDir != null) {
                FileWriter w = new FileWriter(new File(outDir, "log.txt"), true);
                w.write(line + "\n");
                w.close();
            }
        } catch (Throwable ignored) {}
        new Handler(Looper.getMainLooper()).post(() -> {
            if (logView != null && logOpen) {
                synchronized (LOGBUF) { logView.setText(LOGBUF.toString()); }
                scroller.post(() -> scroller.fullScroll(ScrollView.FOCUS_DOWN));
            }
        });
    }

    static void saveShot(String name) {
        try {
            if (outDir == null || Core.PIX == null) return;
            File f = new File(outDir, name + ".png");
            Bitmap b = Bitmap.createBitmap(Core.PW, Core.PH, Bitmap.Config.ARGB_8888);
            b.setPixels(Core.PIX, 0, Core.PW, 0, 0, Core.PW, Core.PH);
            FileOutputStream o = new FileOutputStream(f);
            b.compress(Bitmap.CompressFormat.PNG, 85, o);
            o.close();
        } catch (Throwable t) { logv(2, "saveShot " + t); }
    }

    static void dumpState() {
        try {
            File f = new File(outDir, "dump_" + System.currentTimeMillis() + ".txt");
            FileWriter w = new FileWriter(f);
            w.write("running=" + running + " colour=" + colourPref + " drag=" + dragScale + " gd=" + gdWorld
                    + " inj=" + injMode + " move=" + moveOn + "\n");
            w.write("shots=" + shots + " pots=" + pots + " misses=" + misses + " fails=" + fails + "\n");
            w.write("PW=" + Core.PW + " PH=" + Core.PH + " cal=" + Core.CX + "," + Core.CY + "," + Core.SCX + "," + Core.SCY + "\n");
            w.write("surface offset win " + svX + "," + svY + " screen " + svSX + "," + svSY + "\n");
            if (mem != null || openMem()) {
                capture();
                setupCal();
                loadMaps();
                w.write("libBase=0x" + Long.toHexString(libBase) + " rwN=" + rwN + "\n");
                long t = libBase == 0 ? 0 : tableCached();
                w.write("table=" + (t == 0 ? "none" : "0x" + Long.toHexString(t)) + "\n");
                if (t != 0) {
                    for (long n : collect(t + F_POCKET, 4)) w.write("P " + rd(n + 0x258) + "," + rd(n + 0x260) + "\n");
                    for (long n : collect(t + F_STRIKER, 4)) w.write("S " + rd(n + 0x258) + "," + rd(n + 0x260) + "\n");
                    for (long n : collect(t + F_PUCK, 24)) w.write("K " + rd(n + 0x258) + "," + rd(n + 0x260) + "\n");
                }
            }
            synchronized (LOGBUF) { w.write("\n" + LOGBUF); }
            w.close();
            log("dump saved " + f.getName());
        } catch (Throwable t) { log("dump " + t); }
    }

    /* ======================================================================
     *  input injection
     * ====================================================================== */

    static void initInject() throws Exception {
        Class<?> c = Class.forName("android.hardware.input.InputManager");
        imObj = c.getMethod("getInstance").invoke(null);
        imInject = c.getMethod("injectInputEvent", InputEvent.class, int.class);
    }

    static void onMainSync(Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) { r.run(); return; }
        final CountDownLatch l = new CountDownLatch(1);
        new Handler(Looper.getMainLooper()).post(() -> {
            try { r.run(); } finally { l.countDown(); }
        });
        try { l.await(800, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) {}
    }

    /** x,y are surface pixels (the same space as the captured frame) */
    static void send(int action, float x, float y, long down) {
        try {
            if (injMode == 0) {
                final Activity a = act;
                if (a == null) { logv(1, "no activity to tap"); return; }
                final MotionEvent e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x + svX, y + svY, 0);
                e.setSource(InputDevice.SOURCE_TOUCHSCREEN);
                onMainSync(() -> {
                    try { a.getWindow().getDecorView().dispatchTouchEvent(e); }
                    catch (Throwable t) { logv(1, "dispatch err " + t); }
                    e.recycle();
                });
            } else {
                if (imInject == null) { logv(1, "system inject not available"); return; }
                MotionEvent e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x + svSX, y + svSY, 0);
                e.setSource(InputDevice.SOURCE_TOUCHSCREEN);
                imInject.invoke(imObj, e, 0);
                e.recycle();
            }
        } catch (Throwable t) { logv(1, "inject err " + t); }
    }

    /** returns the time (seconds, nanoTime based) when the finger was lifted */
    static double swipe(float x1, float y1, float x2, float y2, int ms) {
        long d = SystemClock.uptimeMillis();
        send(MotionEvent.ACTION_DOWN, x1, y1, d);
        sleep(40);
        int steps = 14;
        for (int i = 1; i <= steps; i++) {
            send(MotionEvent.ACTION_MOVE, x1 + (x2 - x1) * i / steps, y1 + (y2 - y1) * i / steps, d);
            sleep(Math.max(1, ms / steps));
        }
        sleep(60);
        send(MotionEvent.ACTION_UP, x2, y2, d);
        double t = System.nanoTime() / 1e9;
        logv(2, String.format(Locale.US, "swipe %.0f,%.0f -> %.0f,%.0f", x1, y1, x2, y2));
        return t;
    }

    /* ======================================================================
     *  capture
     * ====================================================================== */

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
        if (a == null) { logv(1, "no game activity yet"); return false; }
        try {
            SurfaceView sv = findSurface(a.getWindow().getDecorView());
            if (sv == null) { logv(2, "no surface view"); return false; }
            final int w = sv.getWidth(), h = sv.getHeight();
            if (w < 200 || h < 200) { logv(2, "surface " + w + "x" + h); return false; }
            int[] loc = new int[2];
            sv.getLocationInWindow(loc);
            svX = loc[0]; svY = loc[1];
            sv.getLocationOnScreen(loc);
            svSX = loc[0]; svSY = loc[1];
            final Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            final CountDownLatch latch = new CountDownLatch(1);
            final int[] res = {-1};
            PixelCopy.request(sv, b, r -> { res[0] = r; latch.countDown(); }, new Handler(Looper.getMainLooper()));
            if (!latch.await(1500, TimeUnit.MILLISECONDS)) { logv(1, "pixelcopy timeout"); return false; }
            if (res[0] != PixelCopy.SUCCESS) { logv(2, "pixelcopy code " + res[0]); return false; }
            int[] px = new int[w * h];
            b.getPixels(px, 0, w, 0, 0, w, h);
            Core.PIX = px; Core.PW = w; Core.PH = h;
            return true;
        } catch (Throwable t) { logv(1, "pixelcopy " + t); return false; }
    }

    static boolean screencapFile(File f) {
        String cmd = "screencap -p " + f.getAbsolutePath();
        for (String su : SU_PATHS) {
            try {
                if (!new File(su).exists()) continue;
                Process p = new ProcessBuilder(su, "-c", cmd).redirectErrorStream(true).start();
                p.waitFor();
                if (f.exists() && f.length() > 1000) return true;
            } catch (Throwable ignored) {}
        }
        try {
            Process p = new ProcessBuilder("screencap", "-p", f.getAbsolutePath()).start();
            p.waitFor();
            return f.exists() && f.length() > 1000;
        } catch (Throwable t) { return false; }
    }

    static boolean capture() {
        if (capturePixels()) return true;
        if (outDir == null) return false;
        File f = new File(outDir, "cur.png");
        if (!screencapFile(f)) { log("capture failed (PixelCopy and screencap)"); return false; }
        Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath());
        if (b == null) return false;
        Core.PW = b.getWidth(); Core.PH = b.getHeight();
        Core.PIX = new int[Core.PW * Core.PH];
        b.getPixels(Core.PIX, 0, Core.PW, 0, 0, Core.PW, Core.PH);
        return true;
    }

    /* ======================================================================
     *  memory
     * ====================================================================== */

    static boolean openMem() {
        try { mem = new RandomAccessFile("/proc/self/mem", "r"); return true; }
        catch (Throwable t) { log("open mem " + t); return false; }
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

    static boolean inRw(long a) {
        for (int i = 0; i < rwN; i++) if (a >= RW_S[i] && a < RW_E[i]) return true;
        return false;
    }

    static void loadMaps() throws Exception {
        rwN = 0;
        libBase = 0;
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

    static int mapCount(long m) {
        long b = rq(m), e = rq(m + 8), sz = rq(m + 0x10);
        if (!inRw(b) || !inRw(e) || e < b) return -1;
        return (sz >= 0 && sz <= 64) ? (int) sz : -1;
    }

    static boolean plausible(long q) {
        if (!inRw(rq(q + F_STRIKER)) || !inRw(rq(q + F_PUCK)) || !inRw(rq(q + F_POCKET))) return false;
        int a = mapCount(q + F_PUCK), b = mapCount(q + F_STRIKER), c = mapCount(q + F_POCKET);
        return a >= 1 && a <= 30 && b >= 1 && b <= 4 && c == 4;
    }

    static boolean pocketsSane(long t) {
        long[] po = collect(t + F_POCKET, 4);
        if (po.length < 4) return false;
        for (long p : po) {
            double x = rd(p + 0x258), y = rd(p + 0x260);
            if (Math.abs(Math.abs(x) - Core.POCKET) > 0.6 || Math.abs(Math.abs(y) - Core.POCKET) > 0.6) return false;
        }
        return true;
    }

    /** score a table candidate by how white the pixels around its strikers are */
    static double scoreTable(long q) {
        double sc = -1;
        for (long s : collect(q + F_STRIKER, 4)) {
            double wx = rd(s + 0x258), wy = rd(s + 0x260);
            if (!Core.okWorld(wx, wy)) continue;
            double x = Core.px(wx), y = Core.py(wy);
            if (x < 10 || x > Core.PW - 11 || y < 10 || y > Core.PH - 11) continue;
            sc = Math.max(sc, Core.brightScore(x, y));
        }
        return sc;
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
        if (best != 0) logv(2, String.format(Locale.US, "classref 0x%x score %.2f", best, bestSc));
        return bestSc >= 0.10 ? best : 0;
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
                    if (Math.abs(Math.abs(x) - Core.POCKET) < 0.6 && Math.abs(Math.abs(y) - Core.POCKET) < 0.6) good++;
                }
                if (good < 4) continue;
                long tn = a - F_POCKET;
                if (!plausible(tn)) continue;
                if (scoreTable(tn) < 0.05) continue;
                return tn;
            }
        }
        return 0;
    }

    static long findTable() {
        long t = findByClassref();
        if (t != 0) return t;
        t = findBySignature();
        if (t != 0) log("table via signature");
        return t;
    }

    static long tableCached() {
        if (tnCache != 0 && plausible(tnCache) && pocketsSane(tnCache)) return tnCache;
        tnCache = findTable();
        if (tnCache != 0) logv(1, "table 0x" + Long.toHexString(tnCache));
        return tnCache;
    }

    /* ======================================================================
     *  calibration
     * ====================================================================== */

    static void doCal() {
        new Thread(() -> {
            if (!capture()) { log("CAL: capture failed"); return; }
            if (Core.calibrate()) {
                calPW = Core.PW;
                saveCal();
                lastCal = String.format(Locale.US, "holes ok  %.0f,%.0f  x%.3f", Core.CX, Core.CY, Core.SCX);
                log("CAL OK " + lastCal);
            } else { log("CAL FAILED (open the board first)"); }
            refreshUi();
        }).start();
    }

    static void saveCal() {
        try {
            sp.edit().putString("cal", Core.PW + "," + Core.CX + "," + Core.CY + "," + Core.SCX + "," + Core.SCY).apply();
        } catch (Throwable ignored) {}
    }

    static boolean loadCal() {
        try {
            String[] p = sp.getString("cal", "").split(",");
            if (p.length != 5 || Integer.parseInt(p[0]) != Core.PW) return false;
            Core.CX = Double.parseDouble(p[1]); Core.CY = Double.parseDouble(p[2]);
            Core.SCX = Double.parseDouble(p[3]); Core.SCY = Double.parseDouble(p[4]);
            Core.calOk = true;
            return true;
        } catch (Throwable t) { return false; }
    }

    static void setupCal() {
        if (Core.calOk && calPW == Core.PW) return;
        if (Core.calibrate()) {
            calPW = Core.PW;
            saveCal();
            lastCal = String.format(Locale.US, "holes ok  %.0f,%.0f  x%.3f", Core.CX, Core.CY, Core.SCX);
        } else if (loadCal()) {
            calPW = Core.PW;
            lastCal = "saved calibration";
        } else {
            Core.useDefaultCal(Core.PW, Core.PH);
            calPW = Core.PW;
            lastCal = "default guess (press CAL on board)";
        }
        log("calibration: " + lastCal);
    }

    /* ======================================================================
     *  control
     * ====================================================================== */

    static void startAuto() {
        if (busy) { log("already running"); return; }
        cancel = false;
        running = true;
        if (panelOpen) togglePanel();
        new Thread(GameHook::loop, "pred-loop").start();
        refreshUi();
    }

    static void startSingle() {
        if (busy) { log("already running"); return; }
        cancel = false;
        running = false;
        busy = true;
        if (panelOpen) togglePanel();
        new Thread(() -> {
            try {
                if (mem == null && !openMem()) return;
                oneShot(true);
            } catch (Throwable t) { log("shot error " + t); fails++; }
            finally { busy = false; state("idle"); }
        }, "pred-shot").start();
        refreshUi();
    }

    static void stopAll() {
        cancel = true;
        running = false;
        log("STOP");
        refreshUi();
    }

    static void loop() {
        busy = true;
        try {
            if (mem == null && !openMem()) return;
            while (running && !cancel) {
                try { oneShot(false); }
                catch (Throwable t) { log("shot error " + t); fails++; }
                if (injFail >= 4) { log("touch never registered in either mode - stopping"); break; }
                sleep(700);
            }
        } finally {
            busy = false;
            running = false;
            state("idle");
            log("ended: shots " + shots + " potted " + pots + " missed " + misses);
        }
    }

    /* ======================================================================
     *  shot
     * ====================================================================== */

    static double sumPos(long[] pucks, long[] strikers) {
        double r = 0;
        for (long p : pucks) {
            double a = rd(p + 0x258), b = rd(p + 0x260);
            if (Core.okWorld(a, b)) r += a + 1.7 * b;
        }
        for (long p : strikers) {
            double a = rd(p + 0x258), b = rd(p + 0x260);
            if (Core.okWorld(a, b)) r += a * 3.1 + b * 2.3;
        }
        return r;
    }

    /** wait until nothing moves and one of OUR (bottom) strikers is drawn on screen */
    static long waitMyTurn(long[] pucks, long[] strikers, int maxMs) {
        long t0 = System.currentTimeMillis();
        int n = 0;
        while (!cancel && System.currentTimeMillis() - t0 < maxMs) {
            state("waiting for my turn");
            double a = sumPos(pucks, strikers);
            sleep(400);
            double b = sumPos(pucks, strikers);
            if (Math.abs(a - b) < 0.3 && capture()) {
                long best = 0;
                double bs = 0.5;
                for (long st : strikers) {
                    double wx = rd(st + 0x258), wy = rd(st + 0x260);
                    if (!Core.okWorld(wx, wy)) continue;
                    double x = Core.px(wx), y = Core.py(wy);
                    if (y < Core.CY) continue;                       // top rail belongs to the opponent
                    if (x < 12 || x > Core.PW - 13 || y < 12 || y > Core.PH - 13) continue;
                    double sc = Core.brightScore(x, y);
                    if (sc >= bs) { bs = sc; best = st; }
                }
                if (best != 0) return best;
            }
            if (++n % 10 == 0) logv(1, "still waiting for my turn...");
            sleep(500);
        }
        return 0;
    }

    static int mineClass(double strikerY) {
        if (colourPref == 1) return 2;
        if (colourPref == 2) return 1;
        int h = Core.hudColour();
        if (h != 0) return h;
        return strikerY > Core.CY ? 1 : 2;
    }

    static boolean moveStriker(long str, double target) {
        double s = Core.sc();
        target = Math.max(Core.CX - 122 * s, Math.min(Core.CX + 122 * s, target));
        double cur = Core.px(rd(str + 0x258)), e = target - cur;
        if (!capture()) return false;
        double[] kn = Core.findKnob();
        if (kn == null) { log("slider knob not found"); return false; }
        double x2 = kn[0] + e * gain;
        x2 = Math.max(Core.CX - 193 * s, Math.min(Core.CX + 193 * s, x2));
        swipe((float) kn[0], (float) kn[1], (float) x2, (float) kn[1], 450);
        sleep(900);
        double nw = Core.px(rd(str + 0x258)), moved = nw - cur;
        log(String.format(Locale.US, "slide %.0f -> %.0f (wanted %.0f) gain %.2f", cur, nw, target, gain));
        if (Math.abs(x2 - kn[0]) > 8 * s && Math.abs(moved) > 2 * s) {
            double g = Math.abs((x2 - kn[0]) / moved);
            if (g >= 0.5 && g <= 3.0) gain = g;
        }
        return Math.abs(moved) > 2 * s;
    }

    static boolean oneShot(boolean single) throws Exception {
        state("reading board");
        loadMaps();
        if (libBase == 0) { log("game library not mapped (wrong process?)"); fails++; sleep(2000); return false; }
        if (!capture()) { fails++; sleep(1500); return false; }
        setupCal();

        long tn = tableCached();
        if (tn == 0) { log("no table found - are you in a match?"); fails++; sleep(2000); return false; }
        long[] pockets = collect(tn + F_POCKET, 4);
        long[] pucks = collect(tn + F_PUCK, 24);
        long[] strikers = collect(tn + F_STRIKER, 4);
        if (pockets.length < 4 || pucks.length < 1 || strikers.length < 1) { fails++; tnCache = 0; return false; }

        double[] kx = new double[4], ky = new double[4];
        int np = 0;
        for (long p : pockets) {
            double wx = rd(p + 0x258), wy = rd(p + 0x260);
            if (Core.okWorld(wx, wy) && np < 4) { kx[np] = Core.px(wx); ky[np] = Core.py(wy); np++; }
        }
        if (np < 4) { fails++; return false; }
        Core.setPockets(kx, ky, np);

        long str = waitMyTurn(pucks, strikers, single ? 30000 : 240000);
        if (str == 0) { if (!cancel) log("timeout waiting for my turn"); return false; }

        double s = Core.sc();
        double[] mx = new double[24], my = new double[24];
        int[] mi = new int[24];
        Core.Plan plan = null;
        List<Core.Coin> coins = null;
        double X = 0, Y = 0;
        int mine = 1;
        int tries = 0;
        while (!cancel) {
            state("planning");
            double swx = rd(str + 0x258), swy = rd(str + 0x260);
            X = Core.px(swx);
            Y = Core.py(swy);
            Core.setStriker(X, Y);
            int nm = 0;
            for (int k = 0; k < pucks.length && nm < 24; k++) {
                double wx = rd(pucks[k] + 0x258), wy = rd(pucks[k] + 0x260);
                if (!Core.okWorld(wx, wy)) continue;
                mx[nm] = Core.px(wx); my[nm] = Core.py(wy); mi[nm] = k; nm++;
            }
            coins = Core.locate(mx, my, mi, nm);
            mine = mineClass(Y);
            plan = Core.bestPlan(X, Y, coins, mine, gdWorld * Core.SCX, moveOn && tries < 3,
                    Core.CX - 122 * s, Core.CX + 122 * s);
            if (plan == null) { log("no shot found (" + coins.size() + " coins seen)"); fails++; sleep(1500); return false; }
            if (moveOn && tries < 3 && Math.abs(plan.X - X) > 6 * s) {
                tries++;
                log(String.format(Locale.US, "better line from x=%.0f (cut %.2f, blocked %d) -> sliding", plan.X, plan.dot, plan.blk));
                state("sliding striker");
                if (!moveStriker(str, plan.X)) tries = 3;
                sleep(500);
                if (!capture()) return false;
                continue;
            }
            break;
        }
        if (cancel || plan == null || coins == null) return false;

        /* ---- aim ---- */
        Core.Coin tc = coins.get(plan.k);
        double gdPx = gdWorld * Core.SCX;
        double dx = Core.PKx[plan.q] - tc.x, dy = Core.PKy[plan.q] - tc.y, L = Math.max(1, Math.hypot(dx, dy));
        double gx = tc.x - dx / L * gdPx, gy = tc.y - dy / L * gdPx;
        double ax = gx - X, ay = gy - Y, AL = Math.max(1, Math.hypot(ax, ay));
        double ux = ax / AL, uy = ay / AL;
        double room = Core.dragRoom(X, Y, -ux, -uy);
        double dragPx = Math.min(170 * s * dragScale, room * 0.98);
        float x1 = (float) Math.round(X), y1 = (float) Math.round(Y);
        float x2 = (float) Math.round(x1 - ux * dragPx), y2 = (float) Math.round(y1 - uy * dragPx);
        lastAim = String.format(Locale.US, "%s coin %.0f,%.0f \u2192 hole %d  cut %.2f  blk %d  %.1f\u00b0",
                tc.cl == 1 ? "black" : "white", tc.x, tc.y, plan.q + 1, plan.dot, plan.blk, Math.toDegrees(Math.atan2(uy, ux)));
        log(String.format(Locale.US, "SHOT %s | striker %.0f,%.0f | offset %+.1f,%+.1f (%d) | %d coins | gd %.1f drag %.0f",
                lastAim, X, Y, Core.offX, Core.offY, Core.offN, coins.size(), gdWorld, dragPx));
        if (dbg >= 2) saveShot("before_" + System.currentTimeMillis());

        /* ---- sampler: striker + target coin straight from memory ---- */
        final long strN = str, coinN = pucks[tc.mi];
        final List<double[]> smp = Collections.synchronizedList(new ArrayList<double[]>());
        final boolean[] stop = {false};
        Thread poll = new Thread(() -> {
            while (!stop[0]) {
                double t = System.nanoTime() / 1e9;
                double a = rd(strN + 0x258), b = rd(strN + 0x260), c = rd(coinN + 0x258), d = rd(coinN + 0x260);
                if (smp.size() < 8000) smp.add(new double[]{t, Core.px(a), Core.py(b), Core.px(c), Core.py(d)});
                sleep(3);
            }
        });
        poll.start();
        state("shooting");
        double tRel = swipe(x1, y1, x2, y2, 260);
        shots++;
        sleep(1300);
        stop[0] = true;
        try { poll.join(500); } catch (InterruptedException ignored) {}

        /* ---- what really happened ---- */
        double[] an;
        synchronized (smp) { an = Core.analyze(new ArrayList<>(smp), tRel, tc.x, tc.y); }
        double want = Math.atan2(uy, ux);
        if (Double.isNaN(an[0])) {
            lastLaunch = "striker did not move";
            log("NO LAUNCH - touch not registered in mode " + (injMode == 0 ? "VIEW" : "SYSTEM"));
            injFail++;
            if (injFail < 4) {
                injMode = 1 - injMode;
                save();
                log("switching tap method to " + (injMode == 0 ? "VIEW" : "SYSTEM"));
            }
            fails++;
            shots--;
            return false;
        }
        injFail = 0;
        double err = Math.toDegrees(Core.wrap(an[0] - want));
        StringBuilder lr = new StringBuilder(String.format(Locale.US, "err %+.1f\u00b0  speed %.0f px/s", err, an[1]));
        if (!Double.isNaN(an[3])) {
            double dWorld = an[3] / Core.SCX;
            lr.append(String.format(Locale.US, "  contact %.1f", dWorld));
            if (learnGd && dWorld > 22 && dWorld < 42) {
                gdHist.add(dWorld);
                if (gdHist.size() > 9) gdHist.remove(0);
                if (gdHist.size() >= 3) {
                    gdWorld = Math.max(26, Math.min(38, Core.median(gdHist)));
                    save();
                }
            }
        }
        if (!Double.isNaN(an[2])) {
            double wantCoin = Math.atan2(dy, dx);
            lr.append(String.format(Locale.US, "  coin %+.1f\u00b0 off", Math.toDegrees(Core.wrap(an[2] - wantCoin))));
        }
        lastLaunch = lr.toString();
        log("LAUNCH " + lastLaunch);

        /* ---- result ---- */
        state("settling");
        long t0 = System.currentTimeMillis();
        while (!cancel && System.currentTimeMillis() - t0 < 14000) {
            double a = sumPos(pucks, strikers);
            sleep(500);
            if (Math.abs(a - sumPos(pucks, strikers)) < 0.2) break;
        }
        double cwx = rd(coinN + 0x258), cwy = rd(coinN + 0x260);
        boolean gone = !Core.okWorld(cwx, cwy) || (Math.abs(cwx) > 222 && Math.abs(cwy) > 222);
        if (gone) { pots++; log("POTTED"); }
        else { misses++; log("missed"); }
        if (dbg >= 2) { capture(); saveShot("after_" + System.currentTimeMillis()); }
        return true;
    }

    static void sleep(int ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) {} }
}
