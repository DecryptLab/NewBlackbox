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
    private static List<long[]> LIBRW = new ArrayList<>();

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
                String s = work(t);
                set(h, s);
                sleep(1500);
            }
        }).start();
    }

    private String work(int t) {
        StringBuilder sb = new StringBuilder();
        long base = findBase();
        if (base < 0) return "v16 t=" + t + " no base\n";
        loadRegions();

        sb.append("v16 t=").append(t).append(" base=0x").append(hx(base)).append("\n");
        sb.append("oldOff=0x").append(hx(base + VTABLE_OFF)).append("\n");

        List<long[]> found = new ArrayList<>();
        int total = 0;

        try (RandomAccessFile mem = new RandomAccessFile("/proc/self/mem", "r")) {
            for (long[] r : LIBRW) {
                long a = r[0];
                byte[] buf = new byte[1 << 18];
                while (a < r[1]) {
                    int want = (int) Math.min(buf.length, r[1] - a);
                    int got;
                    try { mem.seek(a); got = mem.read(buf, 0, want); }
                    catch (Throwable x) { a += want; continue; }
                    if (got <= 0) { a += want; continue; }
                    for (int i = 0; i + 8 <= got; i += 8) {
                        long v = 0;
                        for (int b = 7; b >= 0; b--) v = (v << 8) | (buf[i + b] & 0xFFL);
                        if (!inLib(v)) continue;
                        String nm = ident(mem, v);
                        if (nm == null) continue;
                        total++;
                        if (nm.matches("(?i).*(puck|coin|disc|striker|table).*") && found.size() < 30) {
                            found.add(new long[]{v, a + i});
                        }
                    }
                    a += got;
                }
            }

            sb.append("names=").append(total).append(" m=").append(found.size()).append("\n");

            long tn = 0;
            for (long[] f : found) {
                String nm = ident(mem, f[0]);
                long rec = f[1] - 0x10;
                sb.append("M ").append(nm).append(" rec=0x").append(hx(rec)).append("\n");
                if ("TableNode".equals(nm)) tn = rec;
            }
            sb.append("TableNode ").append(tn == 0 ? "absent"
                    : (tn == base + VTABLE_OFF ? "MATCH" : "DIFF 0x" + hx(tn))).append("\n");

            long pk = 0;
            for (long[] f : found) {
                String nm = ident(mem, f[0]);
                if (nm != null && nm.matches("(?i).*(puck|coin|disc).*")) { pk = f[1] - 0x10; break; }
            }
            if (pk == 0) { sb.append("no puck class\n"); return sb.toString(); }

            sb.append("pk=0x").append(hx(pk)).append("\n");
            List<Long> hits = scan(pk);
            sb.append("pkHits=").append(hits.size()).append("\n");
            if (!hits.isEmpty()) {
                long o = hits.get(0);
                for (long off = 0; off < 0x100; off += 0x20) {
                    byte[] b = new byte[0x20];
                    try { mem.seek(o + off); mem.readFully(b); } catch (Throwable e) { break; }
                    sb.append("+").append(hx(off)).append(" ").append(hx(b)).append("\n");
                    sb.append("  d");
                    for (int j = 0; j < 4; j++) sb.append(" ").append(dm(Double.longBitsToDouble(ld(b, j * 8))));
                    sb.append("\n");
                }
            }
        } catch (Throwable e) {
            sb.append("err ").append(e).append("\n");
        }
        return sb.toString();
    }

    private String ident(RandomAccessFile mem, long a) {
        byte[] b = new byte[48];
        try { mem.seek(a); mem.readFully(b); } catch (Throwable t) { return null; }
        int n = 0;
        while (n < b.length && b[n] != 0) n++;
        if (n < 3 || n > 40) return null;
        char c0 = (char) (b[0] & 0xFF);
        if (!(Character.isLetter(c0) || c0 == '_')) return null;
        for (int i = 0; i < n; i++) {
            char c = (char) (b[i] & 0xFF);
            if (!(Character.isLetterOrDigit(c) || c == '_')) return null;
        }
        return new String(b, 0, n);
    }

    private void loadRegions() {
        RW = new ArrayList<>();
        LIBR = new ArrayList<>();
        LIBRW = new ArrayList<>();
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
                if (path.contains(LIB)) {
                    LIBR.add(new long[]{s, e});
                    if (p[1].startsWith("rw")) LIBRW.add(new long[]{s, e});
                }
                if (p[1].startsWith("rw")) RW.add(new long[]{s, e});
            }
        } catch (Throwable t) { }
    }

    private boolean inLib(long v) {
        if (v < 0x1000L) return false;
        for (long[] r : LIBR) if (v >= r[0] && v < r[1]) return true;
        return false;
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
            if (hits.size() > 200) break;
        }
        return hits;
    }

    private static String dm(double d) {
        if (Double.isNaN(d)) return "nan";
        if (Double.isInfinite(d)) return "inf";
        if (d > -0.005 && d < 0.005) return "0";
        if (d > -1e7 && d < 1e7) return String.format("%.2f", d);
        return "big";
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
            tv.setBackgroundColor(0xcc000000);
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
