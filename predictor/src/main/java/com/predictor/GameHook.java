package com.predictor;

import android.app.Application;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;

import top.niunaijun.blackbox.app.configuration.AppLifecycleCallback;

public class GameHook extends AppLifecycleCallback {

    private static final String GAME = "com.miniclip.carrom";
    private static final String LIB = "libgame";
    private static final long VTABLE_OFF = 0x0296f2a0L;

    private static volatile TextView box;
    private static volatile boolean started = false;
    private static List<long[]> RW = new ArrayList<>();

    @Override
    public void afterApplicationOnCreate(String packageName, String processName,
                                         Application application, int userId) {
        if (!GAME.equals(packageName) || application == null) return;
        if (started) return;
        started = true;

        final Application app = application;
        new Thread(() -> {
            final Handler h = new Handler(Looper.getMainLooper());
            h.post(() -> box = addBox(app, "starting..."));
            for (int t = 0; t < 600; t++) {
                long base = findBase();
                if (base < 0) { set(h, "v15 t=" + t + " no base"); sleep(1000); continue; }
                loadRW();
                List<Long> hits = scan(base + VTABLE_OFF);

                StringBuilder sb = new StringBuilder();
                sb.append("v15 t=").append(t).append(" hits=").append(hits.size()).append("\n");

                long bh = 0, bo = 0, bt = 0, bn = 0, bs = 0;

                try (RandomAccessFile mem = new RandomAccessFile("/proc/self/mem", "r")) {
                    for (Long a : hits) {
                        long[] best = bestArr(mem, a);
                        sb.append("h=0x").append(hx(a)).append(" off=0x").append(hx(best[0]))
                          .append(" n=").append(best[2]).append(" sc=").append(best[3]).append("\n");
                        if (best[3] > bs || (best[3] == bs && best[2] > bn)) {
                            bs = best[3]; bn = best[2]; bo = best[0]; bt = best[1]; bh = a;
                        }
                    }

                    if (bh != 0) {
                        sb.append("BEST h=0x").append(hx(bh)).append(" +0x").append(hx(bo))
                          .append(" arr=0x").append(hx(bt)).append(" n=").append(bn)
                          .append(" sc=").append(bs).append("\n");
                        byte[] hd = new byte[0x40];
                        try { mem.seek(bh); mem.readFully(hd); } catch (Throwable e) { hd = null; }
                        if (hd != null) {
                            sb.append(" H");
                            for (int j = 0; j < 8; j++) sb.append(" ").append(dm(Double.longBitsToDouble(ld(hd, j * 8))));
                            sb.append("\n");
                        }
                        long a = readLong(mem, bt);
                        for (int i = 0; i < Math.min(bn, 8); i++) {
                            long pv = readLong(mem, a + i * 8L);
                            sb.append(" e").append(i).append("=0x").append(hx(pv)).append(" ").append(tag(pv)).append("\n");
                            if (!isHeap(pv)) continue;
                            byte[] b = new byte[0x40];
                            try { mem.seek(pv); mem.readFully(b); } catch (Throwable e) { continue; }
                            sb.append("  ").append(hx(b)).append("\n");
                            sb.append("  d");
                            for (int j = 0; j < 8; j++) sb.append(" ").append(dm(Double.longBitsToDouble(ld(b, j * 8))));
                            sb.append("\n");
                        }
                    }
                } catch (Throwable e) {
                    sb.append("mem ").append(e).append("\n");
                }
                set(h, sb.toString());
                sleep(1000);
            }
        }).start();
    }

    private long[] bestArr(RandomAccessFile mem, long h) {
        long bo = 0, bt = 0, bn = 0, bs = 0;
        for (long off = 0; off < 0x5B0; off += 8) {
            long v = readLong(mem, h + off);
            if (!isHeap(v)) continue;
            for (int k = 0; k < 3; k++) {
                long a = readLong(mem, v + k * 8L);
                long b = readLong(mem, v + k * 8L + 8);
                if (a < 0x1000L || b <= a) continue;
                long d = b - a;
                if (d % 8 != 0) continue;
                long n = d / 8;
                if (n < 2 || n > 40) continue;
                if (!isHeap(a) || !isHeap(b)) continue;
                long sc = score(mem, a, n);
                if (sc > bs || (sc == bs && n > bn)) { bs = sc; bn = n; bt = v; bo = off; }
            }
        }
        return new long[]{bo, bt, bn, bs};
    }

    private long score(RandomAccessFile mem, long a, long n) {
        long s = 0;
        for (int i = 0; i < Math.min(n, 20); i++) {
            long o = readLong(mem, a + i * 8L);
            if (!isHeap(o)) continue;
            double dx = Double.longBitsToDouble(readLong(mem, o + 0x10));
            double dy = Double.longBitsToDouble(readLong(mem, o + 0x18));
            if (ok(dx) && ok(dy)) { s += 2; continue; }
            long v = readLong(mem, o + 0x10);
            float fx = Float.intBitsToFloat((int) v);
            float fy = Float.intBitsToFloat((int) (v >>> 32));
            if (ok(fx) && ok(fy)) s++;
        }
        return s;
    }

    private static boolean ok(double d) {
        return d > -6000 && d < 6000 && (d > 0.5 || d < -0.5);
    }

    private static String dm(double d) {
        if (Double.isNaN(d)) return "nan";
        if (Double.isInfinite(d)) return "inf";
        if (d > -0.005 && d < 0.005) return "0";
        if (d > -1e7 && d < 1e7) return String.format("%.2f", d);
        return "big";
    }

    private void loadRW() {
        RW = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/self/maps"))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.split("\\s+");
                if (p.length < 2 || !p[1].startsWith("rw")) continue;
                int d = p[0].indexOf('-');
                if (d < 0) continue;
                try {
                    RW.add(new long[]{Long.parseLong(p[0].substring(0, d), 16),
                            Long.parseLong(p[0].substring(d + 1), 16)});
                } catch (Throwable x) { }
            }
        } catch (Throwable t) { }
    }

    private long findBase() {
        long bs = -1, bo = -1;
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/self/maps"))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.contains(LIB)) continue;
                String[] p = line.split("\\s+");
                if (p.length < 3) continue;
                int d = p[0].indexOf('-');
                if (d < 0) continue;
                long s = Long.parseLong(p[0].substring(0, d), 16);
                long o = Long.parseLong(p[2], 16);
                if (bo < 0 || o < bo) { bo = o; bs = s; }
            }
        } catch (Throwable t) { }
        return bs;
    }

    private List<Long> scan(long vt) {
        List<Long> hits = new ArrayList<>();
        for (long[] r : RW) {
            long len = r[1] - r[0];
            if (len <= 0 || len > (32L << 20)) continue;
            try (RandomAccessFile mem = new RandomAccessFile("/proc/self/mem", "r")) {
                long a = r[0];
                byte[] buf = new byte[1 << 20];
                while (a < r[1]) {
                    int want = (int) Math.min(buf.length, r[1] - a);
                    int got;
                    try { mem.seek(a); got = mem.read(buf, 0, want); }
                    catch (Throwable t) { a += want; continue; }
                    if (got <= 0) { a += want; continue; }
                    for (int i = 0; i + 8 <= got; i += 8) {
                        long v = 0;
                        for (int b = 7; b >= 0; b--) v = (v << 8) | (buf[i + b] & 0xFFL);
                        if (v == vt) hits.add(a + i);
                    }
                    a += got;
                }
            } catch (Throwable t) { }
            if (hits.size() > 300) break;
        }
        return hits;
    }

    private long readLong(RandomAccessFile mem, long a) {
        try {
            byte[] b = new byte[8];
            mem.seek(a);
            mem.readFully(b);
            long v = 0;
            for (int i = 7; i >= 0; i--) v = (v << 8) | (b[i] & 0xFFL);
            return v;
        } catch (Throwable t) { return -1; }
    }

    private boolean isHeap(long v) {
        if (v < 0x1000L) return false;
        for (long[] r : RW) if (v >= r[0] && v < r[1]) return true;
        return false;
    }

    private String tag(long v) { return isHeap(v) ? "HEAP" : "-"; }

    private static long ld(byte[] b, int o) {
        long v = 0;
        for (int k = 7; k >= 0; k--) v = (v << 8) | (b[o + k] & 0xFFL);
        return v;
    }

    private static String hx(long v) { return Long.toHexString(v); }

    private static String hx(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) {
            int v = x & 0xFF;
            s.append(Character.forDigit(v >> 4, 16)).append(Character.forDigit(v & 15, 16));
        }
        return s.toString();
    }

    private void set(Handler h, String s) { h.post(() -> { if (box != null) box.setText(s); }); }
    private void sleep(int ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) { } }

    private TextView addBox(Context ctx, String text) {
        try {
            WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            TextView tv = new TextView(ctx);
            tv.setText(text);
            tv.setTextSize(6f);
            tv.setTextColor(Color.WHITE);
            tv.setBackgroundColor(0xaa000000);
            tv.setPadding(8, 8, 8, 8);
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
        } catch (Throwable t) { return null; }
    }
}
