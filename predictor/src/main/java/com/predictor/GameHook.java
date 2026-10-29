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
    private static List<long[]> LIBR = new ArrayList<>();
    private static List<String> LIBP = new ArrayList<>();

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
                if (base < 0) { set(h, "v14 t=" + t + " no base"); sleep(1000); continue; }
                loadRegions();
                List<Long> hits = scan(base + VTABLE_OFF);

                StringBuilder sb = new StringBuilder();
                sb.append("v14 t=").append(t).append(" base=0x").append(hx(base))
                  .append(" vt=0x").append(hx(base + VTABLE_OFF))
                  .append(" hits=").append(hits.size()).append("\n");

                try (RandomAccessFile mem = new RandomAccessFile("/proc/self/mem", "r")) {
                    int hi = 0;
                    for (Long a : hits) {
                        sb.append("h=0x").append(hx(a)).append(" tag=").append(tag(a)).append("\n");
                        int shown = 0, dumped = 0;
                        for (long off = 0; off < 0x5B0; off += 8) {
                            long v = readLong(mem, a + off);
                            if (!isHeap(v)) continue;
                            String fl = isLib(readLong(mem, v)) ? " V" : "";
                            fl += arrFlag(mem, v);
                            sb.append("  +").append(hx(off)).append(" -> 0x").append(hx(v))
                              .append(" ").append(tag(v)).append(fl).append("\n");
                            if (dumped < 2) {
                                byte[] b = new byte[32];
                                try { mem.seek(v); mem.readFully(b); } catch (Throwable e) { b = null; }
                                if (b != null) {
                                    sb.append("    ").append(hx(b)).append("\n");
                                    sb.append("    d");
                                    for (int j = 0; j < 4; j++) sb.append(" ").append(dm(Double.longBitsToDouble(ld(b, j * 8))));
                                    sb.append("\n");
                                }
                                dumped++;
                            }
                            if (++shown >= (hi == 0 ? 18 : 12)) break;
                        }
                        if (++hi >= 2) break;
                    }
                } catch (Throwable e) {
                    sb.append("mem ").append(e).append("\n");
                }
                set(h, sb.toString());
                sleep(1000);
            }
        }).start();
    }

    private static String dm(double d) {
        if (Double.isNaN(d)) return "nan";
        if (Double.isInfinite(d)) return "inf";
        if (d > -0.005 && d < 0.005) return "0";
        if (d > -1e9 && d < 1e9) return String.format("%.2f", d);
        return "big";
    }

    private String arrFlag(RandomAccessFile mem, long t) {
        for (int k = 0; k < 3; k++) {
            long a = readLong(mem, t + k * 8L);
            long b = readLong(mem, t + k * 8L + 8);
            if (a < 0x1000L || b <= a) continue;
            long d = b - a;
            if (d % 8 != 0 || d / 8 < 2 || d / 8 > 40) continue;
            if (!isHeap(a) || !isHeap(b)) continue;
            return " n=" + (d / 8);
        }
        return "";
    }

    private void loadRegions() {
        RW = new ArrayList<>();
        LIBR = new ArrayList<>();
        LIBP = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/self/maps"))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.split("\\s+");
                if (p.length < 2) continue;
                int d = p[0].indexOf('-');
                if (d < 0) continue;
                long s, e;
                try {
                    s = Long.parseLong(p[0].substring(0, d), 16);
                    e = Long.parseLong(p[0].substring(d + 1), 16);
                } catch (Throwable x) { continue; }
                String path = p.length >= 6 ? p[5] : "";
                if (path.contains(LIB)) { LIBR.add(new long[]{s, e}); LIBP.add(path); }
                if (p[1].startsWith("rw")) RW.add(new long[]{s, e});
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
            scanRange(r[0], r[1], vt, hits);
            if (hits.size() > 300) break;
        }
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

    private boolean isHeap(long v) {
        if (v < 0x1000L) return false;
        for (long[] r : RW) if (v >= r[0] && v < r[1]) return true;
        return false;
    }

    private boolean isLib(long v) {
        for (long[] r : LIBR) if (v >= r[0] && v < r[1]) return true;
        return false;
    }

    private String tag(long v) {
        if (isLib(v)) return "LIB";
        for (long[] r : LIBR) { }
        if (v < 0x1000L) return "-";
        for (long[] r : RW) if (v >= r[0] && v < r[1]) return "HEAP";
        return "-";
    }

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
