package com.predictor;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;
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

import top.niunaijun.blackbox.app.configuration.AppLifecycleCallback;

public class GameHook extends AppLifecycleCallback {

    static final String TAG = "PRED";
    static final String GAME = "com.miniclip.carrom";
    static final String LIB = "libgame-CARROM";
    static final long CLS_OFF_DEFAULT = 0x28E4160L;

    static final int OFF = 0, FULL = 1, A1 = 2, A2 = 3, A3 = 4;

    static final int F_OFF = 0x3c0, F_PUCK = 0x3d8, F_POCKET = 0x3f0;
    static final int F_STRIKER = 0x3c0;

    static volatile int mode = OFF;
    static volatile boolean busy = false;
    static volatile boolean stopAll = false;
    static volatile int dbg = 1;
    static volatile boolean armed = true;

    static int shotsFired = 0, pots = 0, fails = 0, noTable = 0;

    static Application app;
    static WindowManager wm;
    static LinearLayout panel;
    static TextView logView;
    static TextView statusView;
    static ScrollView scroller;
    static File outDir;
    static SharedPreferences sp;
    static final StringBuilder LOGBUF = new StringBuilder();
    static final int LOGBUF_MAX = 60000;

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

    static final double POCKET = 230.008;

    @Override
    public void afterApplicationOnCreate(String pkg, String proc, Application a, int userId) {
        if (!GAME.equals(pkg) || a == null) return;
        app = a;
        final String processName = proc == null ? "?" : proc;
        new Thread(() -> {
            try {
                outDir = new File(Environment.getExternalStorageDirectory(), "CarromPredictor");
                outDir.mkdirs();
                sp = app.getSharedPreferences("pred", Context.MODE_PRIVATE);
                dbg = sp.getInt("dbg", 1);
                clsOff = sp.getLong("clsoff", CLS_OFF_DEFAULT);
            } catch (Throwable t) { Log.e(TAG, "init " + t); }
            try { initInject(); } catch (Throwable t) { log(1, "inject init failed: " + t); }
            new Handler(Looper.getMainLooper()).post(GameHook::buildUi);
            log(1, "v2 boot. pkg=" + pkg + " proc=" + processName + " inject=" + (imInject != null) + " dbg=" + dbg);
            if (!GAME.equals(processName) && !(GAME + ":main").equals(processName)) {
                log(1, "NOT main process -> table lives elsewhere, idling");
            }
            startWatchdog();
        }).start();
    }

    /* ================= ui ================= */

    static void buildUi() {
        try {
            wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
            android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(dm);
            sw = dm.widthPixels; sh = dm.heightPixels;

            LinearLayout root = new LinearLayout(app);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setBackgroundColor(0xD9000000);

            LinearLayout r1 = new LinearLayout(app);
            r1.addView(btn("FULL", FULL));
            r1.addView(btn("A1cut", A1));
            r1.addView(btn("A2full", A2));
            r1.addView(btn("A3mem", A3));
            r1.addView(btn("STOP", OFF));
            root.addView(r1);

            LinearLayout r2 = new LinearLayout(app);
            r2.addView(btn("DBG+", -1));
            r2.addView(btn("DBG-", -2));
            r2.addView(btn("CAL", -3));
            r2.addView(btn("SHOT", -4));
            r2.addView(btn("DUMP", -5));
            r2.addView(btn("CLR", -6));
            root.addView(r2);

            statusView = new TextView(app);
            statusView.setTextSize(9f);
            statusView.setTextColor(Color.CYAN);
            statusView.setText("idle");
            root.addView(statusView);

            scroller = new ScrollView(app);
            logView = new TextView(app);
            logView.setTextSize(8f);
            logView.setTextColor(Color.WHITE);
            scroller.addView(logView);
            root.addView(scroller, new LinearLayout.LayoutParams(-1, 300));

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    -1, -2,
                    Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                            : WindowManager.LayoutParams.TYPE_PHONE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP;
            wm.addView(root, lp);
            panel = root;
        } catch (Throwable t) { Log.e(TAG, "ui " + t); }
    }

    static Button btn(String t, int code) {
        Button b = new Button(app);
        b.setText(t);
        b.setTextSize(9f);
        b.setOnClickListener(v -> onButton(t, code));
        return b;
    }

    static void onButton(String label, int code) {
        switch (code) {
            case -1: dbg = Math.min(2, dbg + 1); sp.edit().putInt("dbg", dbg).apply(); log(1, "dbg=" + dbg); return;
            case -2: dbg = Math.max(0, dbg - 1); sp.edit().putInt("dbg", dbg).apply(); log(1, "dbg=" + dbg); return;
            case -3: new Thread(() -> {
                        if (!capture()) { log(1, "capture failed"); return; }
                        if (calibrate()) { log(1, String.format(Locale.US, "CAL OK cx%d cy%d sx%.3f sy%.3f", CX, CY, SCX, SCY)); saveCal(); }
                        else log(1, "CAL FAILED");
                        status("cal " + (calOk ? "ok" : "fail"));
                     }).start(); return;
            case -4: new Thread(() -> { if (!busy) { busy = true; try { oneShot(); } catch (Throwable t) { log(1, "shot err " + t); } busy = false; } }).start(); return;
            case -5: new Thread(GameHook::dumpState).start(); return;
            case -6: LOGBUF.setLength(0); logView.setText(""); return;
            default:
                mode = code;
                stopAll = false;
                armed = true;
                log(1, "MODE " + label);
                status("mode " + label);
                if (code != OFF && !busy) new Thread(GameHook::loop).start();
        }
    }

    static void status(String s) {
        new Handler(Looper.getMainLooper()).post(() -> { if (statusView != null) statusView.setText(s); });
    }

    static void log(int lvl, String s) {
        if (lvl > dbg) return;
        Log.i(TAG, s);
        String line = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date()) + " " + s;
        LOGBUF.append(line).append('\n');
        if (LOGBUF.length() > LOGBUF_MAX) LOGBUF.delete(0, 12000);
        try { FileWriter w = new FileWriter(new File(outDir, "log.txt"), true); w.write(line + "\n"); w.close(); } catch (Throwable t) {}
        new Handler(Looper.getMainLooper()).post(() -> {
            if (logView != null) { logView.setText(LOGBUF.toString()); scroller.fullScroll(ScrollView.FOCUS_DOWN); }
        });
    }

    static void saveShot(String name) {
        try {
            File f = new File(outDir, name + ".png");
            String cmd = "screencap -p " + f.getAbsolutePath();
            Process p = hasSu() ? Runtime.getRuntime().exec(new String[]{"su", "-c", cmd})
                                : Runtime.getRuntime().exec(cmd);
            p.waitFor();
            log(2, "shot saved " + f.getName() + " size=" + f.length());
        } catch (Throwable t) { log(1, "saveShot " + t); }
    }

    static boolean hasSu = true;
    static boolean hasSu() { return hasSu; }

    static void saveCal() {
        try { FileWriter w = new FileWriter(new File(outDir, "cal.txt")); w.write(CX + " " + CY + " " + SCX + " " + SCY); w.close(); } catch (Throwable t) {}
    }

    static void dumpState() {
        try {
            File f = new File(outDir, "dump_" + System.currentTimeMillis() + ".txt");
            FileWriter w = new FileWriter(f);
            w.write("dbg=" + dbg + " mode=" + mode + " shots=" + shotsFired + " pots=" + pots + " fails=" + fails + " noTable=" + noTable + "\n");
            w.write("sw=" + sw + " sh=" + sh + " PW=" + PW + " PH=" + PH + "\n");
            w.write("libBase=0x" + Long.toHexString(libBase) + " clsOff=0x" + Long.toHexString(clsOff) + " rwN=" + rwN + "\n");
            w.write("cal=" + CX + "," + CY + "," + SCX + "," + SCY + " ok=" + calOk + "\n");
            if (libBase != 0) {
                loadMaps();
                long t = findTable();
                w.write("findTable=" + (t == 0 ? "none" : "0x" + Long.toHexString(t)) + "\n");
                if (t != 0) {
                    long[] pk = collect(t + F_PUCK, 24);
                    long[] po = collect(t + F_POCKET, 4);
                    long[] st = collect(t + F_STRIKER, 4);
                    w.write("pucks=" + pk.length + " pockets=" + po.length + " strikers=" + st.length + "\n");
                    for (long n : po) w.write("P " + rd(n + 0x258) + "," + rd(n + 0x260) + "\n");
                    for (long n : st) w.write("S " + rd(n + 0x258) + "," + rd(n + 0x260) + "\n");
                }
            }
            w.close();
            log(1, "dump " + f.getName());
        } catch (Throwable t) { log(1, "dump " + t); }
    }

    static void startWatchdog() {
        new Thread(() -> {
            while (true) {
                sleep(5000);
                if (busy) {
                    status("busy shots=" + shotsFired + " pots=" + pots + " fails=" + fails);
                } else {
                    status("idle mode=" + mode + " shots=" + shotsFired);
                }
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
        if (imInject == null) { log(1, "inject null"); return; }
        try {
            MotionEvent e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0);
            e.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            imInject.invoke(imObj, e, 0);
            e.recycle();
        } catch (Throwable t) { log(1, "inject err " + t); }
    }

    static void swipe(int x1, int y1, int x2, int y2, int ms) {
        long d = SystemClock.uptimeMillis();
        send(MotionEvent.ACTION_DOWN, x1, y1, d);
        int steps = 10;
        for (int i = 1; i <= steps; i++) {
            send(MotionEvent.ACTION_MOVE, x1 + (x2 - x1) * i / steps, y1 + (y2 - y1) * i / steps, d);
            sleep(ms / steps);
        }
        send(MotionEvent.ACTION_UP, x2, y2, d);
        log(2, "swipe " + x1 + "," + y1 + " -> " + x2 + "," + y2 + " " + ms + "ms");
    }

    static void tap(int x, int y) {
        long d = SystemClock.uptimeMillis();
        send(MotionEvent.ACTION_DOWN, x, y, d);
        sleep(40);
        send(MotionEvent.ACTION_UP, x, y, d);
        log(2, "tap " + x + "," + y);
    }

    /* ================= memory ================= */

    static boolean openMem() {
        try { mem = new RandomAccessFile("/proc/self/mem", "rw"); return true; }
        catch (Throwable t) { log(1, "open mem " + t); return false; }
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
        } catch (Throwable t) { log(2, "wr fail " + Long.toHexString(a)); }
    }

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
        long b = rq(m), e = rq(m + 8);
        if (!inRw(b) || !inRw(e) || e < b) return -1;
        long sz = rq(m + 0x10);
        if (sz >= 0 && sz <= 64) return (int) sz;
        return -1;
    }

    /* ================= discovery ================= */

    static long findTable() {
        long t = findByClassref();
        if (t != 0) { log(2, "table via classref 0x" + Long.toHexString(t)); return t; }
        t = findBySignature();
        if (t != 0) { log(1, "table via signature 0x" + Long.toHexString(t)); return t; }
        noTable++;
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
        if (best != 0) log(2, String.format(Locale.US, "classref best 0x%x sc %.2f", best, bestSc));
        return bestSc >= 0.10 ? best : 0;
    }

    static boolean plausible(long q) {
        if (!inRw(rq(q + F_STRIKER)) || !inRw(rq(q + F_PUCK)) || !inRw(rq(q + F_POCKET))) return false;
        int a = mapCount(q + F_PUCK), b = mapCount(q + F_STRIKER), c = mapCount(q + F_POCKET);
        if (a < 0 || b < 0 || c < 0) return false;
        if (c != 4) return false;
        if (b < 1 || b > 4) return false;
        if (a < 1 || a > 30) return false;
        return true;
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
                if (lum(x + dx, y + dy) > 190) hit++;
            }
            sc = Math.max(sc, (double) hit / tot);
        }
        return sc;
    }

    static long findBySignature() {
        for (int i = 0; i < rwN; i++) {
            for (long a = RW_S[i]; a + 0x18 <= RW_E[i]; a += 8) {
                long b = rq(a), e = rq(a + 8), sz = rq(a + 0x10);
                if (sz != 4) continue;
                if (!inRw(b) || !inRw(e) || e < b) continue;
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
                if (scoreTable(tn) < 0.05) continue;
                return tn;
            }
        }
        return 0;
    }

    /* ================= image ================= */

    static boolean capture() {
        try {
            File f = new File(outDir, "cur.png");
            String cmd = "screencap -p " + f.getAbsolutePath();
            Process p = hasSu() ? Runtime.getRuntime().exec(new String[]{"su", "-c", cmd})
                                : Runtime.getRuntime().exec(cmd);
            int rc = p.waitFor();
            if (rc != 0 && hasSu) { hasSu = false; log(1, "su screencap rc=" + rc + " -> trying without"); return capture(); }
            Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath());
            if (b == null) { log(1, "decode null rc=" + rc + " size=" + f.length()); return false; }
            PW = b.getWidth();
            PH = b.getHeight();
            PIX = new int[PW * PH];
            b.getPixels(PIX, 0, PW, 0, 0, PW, PH);
            log(2, "capture " + PW + "x" + PH);
            return true;
        } catch (Throwable t) { log(1, "capture " + t); return false; }
    }

    static int lum(int x, int y) {
        if (x < 0 || y < 0 || x >= PW || y >= PH) return 0;
        int c = PIX[y * PW + x];
        return (((c >> 16) & 0xFF) + ((c >> 8) & 0xFF) + (c & 0xFF)) / 3;
    }

    static boolean calibrate() {
        calOk = false;
        if (PIX == null) return false;
        int y0 = PH * 12 / 100, y1 = PH * 95 / 100;
        boolean[] vis = new boolean[PW * PH];
        int[] hx = new int[16], hy = new int[16];
        int hn = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = 0; x < PW; x++) {
                int idx = y * PW + x;
                if (vis[idx] || lum(x, y) >= 55) continue;
                int[] qx = new int[4000], qy = new int[4000];
                int qh = 0, qt = 0, cnt = 0;
                long ax = 0, ay = 0;
                qx[qt] = x; qy[qt] = y; qt++; vis[idx] = true;
                while (qh < qt && cnt < 4000) {
                    int cxp = qx[qh], cyp = qy[qh]; qh++; ax += cxp; ay += cyp; cnt++;
                    for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
                        int bx = cxp + dx, by = cyp + dy;
                        if (bx < 1 || by < y0 || bx >= PW - 1 || by >= y1) continue;
                        int j = by * PW + bx;
                        if (vis[j] || lum(bx, by) >= 55) continue;
                        vis[j] = true;
                        if (qt < 4000) { qx[qt] = bx; qy[qt] = by; qt++; }
                    }
                }
                if (cnt < 50 || cnt > 900) continue;
                int mx = (int) (ax / cnt), my = (int) (ay / cnt);
                int okc = 0;
                if (mx > 16 && lum(mx - 16, my) > 130) okc++;
                if (mx < PW - 16 && lum(mx + 16, my) > 130) okc++;
                if (my > 16 && lum(mx, my - 16) > 130) okc++;
                if (my < PH - 17 && lum(mx, my + 16) > 130) okc++;
                if (okc < 3) continue;
                if (hn < 16) { hx[hn] = mx; hy[hn] = my; hn++; }
            }
        }
        log(2, "dark blobs=" + hn);
        if (hn < 4) return false;
        int[] cx4 = {-1, -1}, cy4 = {-1, -1};
        for (int q = 0; q < 4; q++) {
            int right = q & 1, low = q >> 1;
            long bx = right == 1 ? PW : 0, by = low == 1 ? y1 : 0;
            long bd = Long.MAX_VALUE; int bi = -1;
            for (int i = 0; i < hn; i++) {
                long dx = hx[i] - bx, dy = hy[i] - by;
                long d = dx * dx + dy * dy;
                if (d < bd) { bd = d; bi = i; }
            }
            if (bi >= 0) { cx4[right] = hx[bi]; cy4[low] = hy[bi]; }
        }
        if (cx4[0] <= 0 || cx4[1] <= 0 || cy4[0] <= 0 || cy4[1] <= 0) {
            log(2, "holes incomplete");
            return false;
        }
        double nx = (cx4[1] - cx4[0]) / (2 * POCKET);
        double ny = (cy4[1] - cy4[0]) / (2 * POCKET);
        log(2, String.format(Locale.US, "holes L%d R%d T%d B%d sx%.3f sy%.3f", cx4[0], cx4[1], cy4[0], cy4[1], nx, ny));
        if (nx < 0.4 || nx > 2.5 || ny < 0.4 || ny > 2.5) return false;
        if (Math.abs(nx - ny) > 0.7) return false;
        if (Math.abs(cx4[0] + cx4[1]) / 2.0 < PW * 0.3) return false;
        CX = (cx4[0] + cx4[1]) / 2;
        CY = (cy4[0] + cy4[1]) / 2;
        SCX = nx;
        SCY = ny;
        calOk = true;
        return true;
    }

    static int px(double wx) { return (int) (CX + wx * SCX); }
    static int py(double wy) { return (int) (CY - wy * SCY); }

    static int strikerY(boolean bottom) {
        int base = bottom ? (int) (CY + 254.6 * SCY) : (int) (CY - 254.6 * SCY);
        int best = -1, bv = -1;
        for (int dy = -30; dy <= 30; dy++) {
            int yy = base + dy;
            if (yy < 3 || yy > PH - 4) continue;
            for (int dx = -50; dx <= 50; dx += 2) {
                int xx = CX + dx;
                if (xx < 6 || xx > PW - 7) continue;
                int s = 0;
                for (int u = -2; u <= 2; u++) for (int v = -2; v <= 2; v++) s += lum(xx + u, yy + v);
                if (s > bv) { bv = s; best = yy; }
            }
        }
        return bv > 150 * 25 ? best : -1;
    }

    static int strikerX(int yy) {
        long ax = 0;
        int cn = 0;
        for (int dx = -70; dx <= 70; dx++) for (int dy = -12; dy <= 12; dy++) {
            int xx = CX + dx, y2 = yy + dy;
            if (lum(xx, y2) > 200) { ax += xx; cn++; }
        }
        return cn > 20 ? (int) (ax / cn) : CX;
    }

    static boolean okWorld(double x, double y) {
        return !Double.isNaN(x) && !Double.isNaN(y) && Math.abs(x) <= 231 && Math.abs(y) <= 231;
    }

    /* ================= loop ================= */

    static void loop() {
        if (busy) { log(2, "loop already running"); return; }
        busy = true;
        try {
            if (mem == null && !openMem()) return;
            int guard = 0;
            while (mode != OFF && !stopAll && guard++ < 600) {
                try { oneShot(); } catch (Throwable t) { log(1, "shot err " + t); fails++; }
                if (mode != FULL) break;
                sleep(1300);
            }
        } catch (Throwable t) {
            log(1, "loop err " + t);
        } finally {
            busy = false;
            log(1, "loop end shots=" + shotsFired + " pots=" + pots + " fails=" + fails);
            status("stopped");
        }
    }

    static void oneShot() throws Exception {
        loadMaps();
        if (libBase == 0) { log(1, "lib not mapped yet"); fails++; return; }
        if (!capture()) { fails++; return; }
        if (!calibrate()) {
            fails++;
            saveShot("failcal_" + System.currentTimeMillis());
            return;
        }
        log(2, String.format(Locale.US, "cal cx%d cy%d sx%.3f sy%.3f %dx%d", CX, CY, SCX, SCY, PW, PH));

        long tn = 0;
        for (int tryn = 0; tryn < 4 && tn == 0; tryn++) {
            tn = findTable();
            if (tn == 0) { log(2, "no table try " + (tryn + 1)); sleep(350); }
        }
        if (tn == 0) { fails++; log(1, "no live table after 4 tries"); return; }

        int sy = strikerY(true);
        if (sy < 0) { log(1, "no striker on bottom rail (opponent turn?)"); return; }
        int sx = strikerX(sy);
        log(2, "striker " + sx + "," + sy);

        long[] pucks = collect(tn + F_PUCK, 24);
        long[] pockets = collect(tn + F_POCKET, 4);
        if (pucks.length < 1 || pockets.length < 4) { fails++; log(1, "nodes p=" + pucks.length + " q=" + pockets.length); return; }

        int[] plv = new int[pucks.length];
        for (int i = 0; i < pucks.length; i++) {
            double wx = rd(pucks[i] + 0x258), wy = rd(pucks[i] + 0x260);
            if (!okWorld(wx, wy)) { plv[i] = -1; continue; }
            int x = px(wx), y = py(wy);
            int s = 0, c = 0;
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
        log(2, "pucks=" + pucks.length + " median=" + med + " values=" + Arrays.toString(plv));

        int bp = -1, bq = -1, bblk = 0, bfoul = 0;
        double bestSc = 1e18, gxs = 0, gys = 0;
        for (int k = 0; k < pucks.length; k++) {
            if (plv[k] < 0) continue;
            double pxw = rd(pucks[k] + 0x258), pyw = rd(pucks[k] + 0x260);
            double pxs = px(pxw), pys = py(pyw);
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
        if (bp < 0) { fails++; log(1, "no target"); return; }

        double ax = gxs - sx, ay = gys - sy;
        double AL = Math.hypot(ax, ay);
        double ux = ax / AL, uy = ay / AL;
        int drag = (int) (140 * (SCX + SCY) / 2 / 0.888);
        int tx = (int) (sx - ux * drag), ty = (int) (sy - uy * drag);
        tx = Math.max(1, Math.min(PW - 2, tx));
        ty = Math.max(1, Math.min(PH - 2, ty));

        log(1, String.format(Locale.US, "SHOT puck%d pocket%d blk%d foul%d score%.0f aim %.3f,%.3f drag%d",
                bp, bq + 1, bblk, bfoul, bestSc, ux, uy, drag));
        saveShot("before_" + System.currentTimeMillis());

        int beforeX = sx, beforeY = sy;
        if (mode == A3) {
            wr(pucks[bp] + 0xd0, ux);
            wr(pucks[bp] + 0xd8, uy);
            wr(pucks[bp] + 0xc, 1.0);
            sleep(120);
            tap(sx, sy);
        } else {
            swipe(sx, sy, tx, ty, 220);
        }
        shotsFired++;

        sleep(1200);
        int movedX = -1, movedY = -1;
        capture();
        int sy2 = strikerY(true);
        if (sy2 >= 0) {
            movedX = strikerX(sy2);
            movedY = sy2;
            int d = (int) Math.hypot(movedX - beforeX, movedY - beforeY);
            log(2, "striker after " + movedX + "," + movedY + " delta=" + d);
            if (d < 4) { log(1, "SHOT DID NOT REGISTER (delta " + d + ")"); fails++; }
        } else {
            log(1, "striker gone after shot (fired)");
            pots++;
        }
        sleep(5200);
        saveShot("after_" + System.currentTimeMillis());
        log(2, "shot done, totals shots=" + shotsFired + " pots=" + pots + " fails=" + fails);
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
        double px = ax + ux * t, py = ay + uy * t;
        double d = Math.hypot(cx - px, cy - py);
        if (d > r) return 1e18;
        double b = Math.sqrt(r * r - d * d);
        double tt = t - b;
        return tt < 0 ? t + b : tt;
    }

    static void sleep(int ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) {} }
}
