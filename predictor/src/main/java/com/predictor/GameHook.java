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
    private static final long TREE_BEGIN = 0x168L;
    private static final long NODE_PAYLOAD = 0x30L;

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
            for (int t = 0; t < 180; t++) {
                long[] r = findBase();
                if (r[0] < 0) { set(h, "v11 t=" + t + "\nno base"); sleep(1000); continue; }

                long base = r[0];
                long vt = base + VTABLE_OFF;
                List<Long> hits = scan(vt);

                String found = null;
                try (RandomAccessFile mem = new RandomAccessFile("/proc/self/mem", "r")) {
                    for (Long a : hits) {
                        long tl = readLong(mem, a + TABLELOGIC_OFF);
                        if (tl < 0x1000L || tl > 0x8000000000L) continue;
                        List<String> pucks = walk(mem, tl);
                        if (pucks.size() >= 3) {
                            StringBuilder sb = new StringBuilder();
                            sb.append("v11 MATCH\nhit=0x").append(Long.toHexString(a))
                              .append("\ntl=0x").append(Long.toHexString(tl))
                              .append(" pucks=").append(pucks.size()).append("\n");
                            int n = 0;
                            for (String s : pucks) { sb.append(s).append("\n"); if (++n >= 8) break; }
                            found = sb.toString();
                            break;
                        }
                    }
                } catch (Throwable e) {
                    set(h, "v11 t=" + t + "\nmem " + e);
                    sleep(1000);
                    continue;
                }

                if (found != null) { set(h, found); Log.d(TAG, found); break; }
                set(h, "v11 t=" + t + "\nhits=" + hits.size() + "\n(no valid table yet)");
                sleep(1000);
            }
        }).start();
    }

    private List<String> walk(RandomAccessFile mem, long tl) {
        List<String> out = new ArrayList<>();
        long end = readLong(mem, tl + TREE_BEGIN + 8);
        long node = readLong(mem, tl + TREE_BEGIN);
        int guard = 0;
        while (node > 0x1000L && node < 0x8000000000L && node != end && guard++ < 40) {
            long obj = readLong(mem, node + NODE_PAYLOAD);
            if (obj > 0x1000L && obj < 0x8000000000L) {
                double x = readDouble(mem, obj + 0x10);
                double y = readDouble(mem, obj + 0x18);
                double rad = readDouble(mem, obj + 0x20);
                int st = readInt(mem, obj + 0xa4);
                if (x > -20 && x < 220 && y > -20 && y < 220 && rad > 0.05 && rad < 12) {
                    out.add(String.format("x=%.2f y=%.2f r=%.2f st=%d", x, y, rad, st));
                }
            }
            node = next(mem, node);
        }
        return out;
    }

    private long next(RandomAccessFile mem, long node) {
        long right = readLong(mem, node + 0x8);
        if (right > 0x1000L && right < 0x8000000000L) {
            long n = right;
            long l = readLong(mem, n);
            int g = 0;
            while (l > 0x1000L && l < 0x8000000000L && g++ < 40) { n = l; l = readLong(mem, n); }
            return n;
        }
        long p = readLong(mem, node + 0x10);
        int g = 0;
        while (p > 0x1000L && p < 0x8000000000L && g++ < 40) {
            long pr = readLong(mem, p + 0x8);
            if (pr != node) break;
            node = p;
            p = readLong(mem, p + 0x10);
        }
        return p;
    }

    private void set(Handler h, String s) {
        h.post(() -> { if (box != null) box.setText(s); });
    }

    private void sleep(int ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }

    private long[] findBase() {
        long bestStart = -1, bestOff = -1;
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/self/maps"))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.contains(LIB)) continue;
                String[] p = line.split("\\s+");
                if (p.length < 3) continue;
                int dash = p[0].indexOf('-');
                if (dash < 0) continue;
                long start = Long.parseLong(p[0].substring(0, dash), 16);
                long off = Long.parseLong(p[2], 16);
                if (bestOff < 0 || off < bestOff) { bestOff = off; bestStart = start; }
            }
        } catch (Throwable t) { }
        return new long[]{bestStart, 0};
    }

    private List<Long> scan(long vt) {
        List<Long> hits = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/self/maps"))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.contains("rw-p")) continue;
                String[] p = line.split("\\s+");
                if (p.length < 3) continue;
                int dash = p[0].indexOf('-');
                if (dash < 0) continue;
                long start = Long.parseLong(p[0].substring(0, dash), 16);
                long end = Long.parseLong(p[0].substring(dash + 1), 16);
                long len = end - start;
                if (len <= 0 || len > (32L << 20)) continue;
                scanRange(start, end, vt, hits);
                if (hits.size() > 300) break;
            }
        } catch (Throwable t) { }
        return hits;
    }

    private void scanRange(long start, long end, long vt, List<Long> hits) {
        try (RandomAccessFile mem = new RandomAccessFile("/proc/self/mem", "r")) {
            long addr = start;
            byte[] buf = new byte[1 << 20];
            while (addr < end) {
                int want = (int) Math.min(buf.length, end - addr);
                int got;
                try { mem.seek(addr); got = mem.read(buf, 0, want); }
                catch (Throwable t) { addr += want; continue; }
                if (got <= 0) { addr += want; continue; }
                for (int i = 0; i + 8 <= got; i += 8) {
                    long v = 0;
                    for (int b = 7; b >= 0; b--) v = (v << 8) | (buf[i + b] & 0xFFL);
                    if (v == vt) hits.add(addr + i);
                }
                addr += got;
            }
        } catch (Throwable t) { }
    }

    private long readLong(RandomAccessFile mem, long addr) {
        try {
            byte[] b = new byte[8];
            mem.seek(addr);
            mem.readFully(b);
            long v = 0;
            for (int i = 7; i >= 0; i--) v = (v << 8) | (b[i] & 0xFFL);
            return v;
        } catch (Throwable t) { return -1; }
    }

    private int readInt(RandomAccessFile mem, long addr) {
        try {
            byte[] b = new byte[4];
            mem.seek(addr);
            mem.readFully(b);
            return (b[0] & 0xFF) | ((b[1] & 0xFF) << 8) | ((b[2] & 0xFF) << 16) | ((b[3] & 0xFF) << 24);
        } catch (Throwable t) { return 0; }
    }

    private double readDouble(RandomAccessFile mem, long addr) {
        long v = readLong(mem, addr);
        return v == -1 ? -999 : Double.longBitsToDouble(v);
    }

    private TextView addBox(Context ctx, String text) {
        try {
            WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            TextView tv = new TextView(ctx);
            tv.setText(text);
            tv.setTextSize(8f);
            tv.setTextColor(Color.WHITE);
            tv.setBackgroundColor(0xaa000000);
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
            lp.y = 100;
            wm.addView(tv, lp);
            return tv;
        } catch (Throwable t) {
            Log.e(TAG, "addBox " + t);
            return null;
        }
    }
}
