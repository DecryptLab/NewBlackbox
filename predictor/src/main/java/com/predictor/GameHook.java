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
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;

import top.niunaijun.blackbox.app.configuration.AppLifecycleCallback;

public class GameHook extends AppLifecycleCallback {

    private static final String TAG = "PredictorHook";
    private static final String GAME = "com.miniclip.carrom";
    private static final String LIB = "libgame";
    private static final long VTABLE_OFF = 0x0296f2a0L;
    private static final long TABLELOGIC_OFF = 0x3F0L;

    private static volatile TextView box;
    private static volatile boolean started = false;

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
                long[] r = findBase();
                if (r[0] < 0) { set(h, "v12 t=" + t + " no base"); sleep(1000); continue; }
                List<Long> hits = scan(r[0] + VTABLE_OFF);

                StringBuilder sb = new StringBuilder();
                sb.append("v12 t=").append(t).append(" hits=").append(hits.size()).append("\n");
                int shown = 0;
                try (RandomAccessFile mem = new RandomAccessFile("/proc/self/mem", "r")) {
                    for (Long a : hits) {
                        long tl = readLong(mem, a + TABLELOGIC_OFF);
                        if (tl < 0x1000L || tl > 0x8000000000L) continue;
                        sb.append("tl=0x").append(Long.toHexString(tl)).append("\n");

                        for (long off = 0x130; off <= 0x1d0; off += 8) {
                            sb.append(Long.toHexString(off)).append(":")
                              .append(Long.toHexString(readLong(mem, tl + off))).append(" ");
                        }
                        sb.append("\n");

                        sb.append("vec=").append(countVec(mem, tl))
                          .append(" tree=").append(countTree(mem, tl))
                          .append(" c2c0=").append(readLong(mem, tl + 0x2c0)).append("\n");
                        if (++shown >= 2) break;
                    }
                } catch (Throwable e) {
                    sb.append("mem ").append(e).append("\n");
                }
                set(h, sb.toString());
                sleep(1000);
            }
        }).start();
    }

    private int countVec(RandomAccessFile mem, long tl) {
        long b = readLong(mem, tl + 0x168);
        long e = readLong(mem, tl + 0x170);
        if (b < 0x1000L || b > 0x8000000000L || e <= b || (e - b) > 0x4000L) return -1;
        int ok = 0;
        for (long p = b; p + 8 <= e && ok < 64; p += 8) {
            long o = readLong(mem, p);
            if (o < 0x1000L || o > 0x8000000000L) continue;
            double x = readDouble(mem, o + 0x10);
            double y = readDouble(mem, o + 0x18);
            if (x > -20 && x < 220 && y > -20 && y < 220) ok++;
        }
        return ok;
    }

    private int countTree(RandomAccessFile mem, long tl) {
        long end = readLong(mem, tl + 0x170);
        long n = readLong(mem, tl + 0x168);
        int ok = 0, g = 0;
        while (n > 0x1000L && n < 0x8000000000L && n != end && g++ < 40) {
            long o = readLong(mem, n + 0x30);
            if (o > 0x1000L && o < 0x8000000000L) {
                double x = readDouble(mem, o + 0x10);
                double y = readDouble(mem, o + 0x18);
                if (x > -20 && x < 220 && y > -20 && y < 220) ok++;
            }
            n = next(mem, n);
        }
        return ok;
    }

    private long next(RandomAccessFile mem, long node) {
        long right = readLong(mem, node + 0x8);
        if (right > 0x1000L && right < 0x8000000000L) {
            long n = right, l = readLong(mem, n);
            int g = 0;
            while (l > 0x1000L && l < 0x8000000000L && g++ < 40) { n = l; l = readLong(mem, n); }
            return n;
        }
        long p = readLong(mem, node + 0x10);
        int g = 0;
        while (p > 0x1000L && p < 0x8000000000L && g++ < 40) {
            if (readLong(mem, p + 0x8) != node) break;
            node = p;
            p = readLong(mem, p + 0x10);
        }
        return p;
    }

    private void set(Handler h, String s) { h.post(() -> { if (box != null) box.setText(s); }); }
    private void sleep(int ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) { } }

    private long[] findBase() {
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
        return new long[]{bs};
    }

    private List<Long> scan(long vt) {
        List<Long> hits = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/self/maps"))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.contains("rw-p")) continue;
                String[] p = line.split("\\s+");
                if (p.length < 3) continue;
                int d = p[0].indexOf('-');
                if (d < 0) continue;
                long s = Long.parseLong(p[0].substring(0, d), 16);
                long e = Long.parseLong(p[0].substring(d + 1), 16);
                long len = e - s;
                if (len <= 0 || len > (32L << 20)) continue;
                scanRange(s, e, vt, hits);
                if (hits.size() > 300) break;
            }
        } catch (Throwable t) { }
        return hits;
    }

    private void scanRange(long s, long e, long vt, List<Long> hits) {
        try (RandomAccessFile mem = new RandomAccessFile("/proc/self/mem", "r")) {
            long a = s;
            byte[] buf = new byte[1 << 20];
            while (a < e) {
                int want = (int) Math.min(buf.length, e - a);
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

    private double readDouble(RandomAccessFile mem, long a) {
        long v = readLong(mem, a);
        return v == -1 ? -999 : Double.longBitsToDouble(v);
    }

    private TextView addBox(Context ctx, String text) {
        try {
            WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            TextView tv = new TextView(ctx);
            tv.setText(text);
            tv.setTextSize(7f);
            tv.setTextColor(Color.WHITE);
            tv.setBackgroundColor(0xaa000000);
            tv.setPadding(10, 10, 10, 10);
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
