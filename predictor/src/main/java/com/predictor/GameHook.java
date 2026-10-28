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

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;

import top.niunaijun.blackbox.app.configuration.AppLifecycleCallback;

public class GameHook extends AppLifecycleCallback {

    private static final String GAME = "com.miniclip.carrom";
    private static final String APK_MARK = "/data/app/com.miniclip.carrom-";

    private static final long OFF_CLASSREC = 0x02a6f2a0L;
    private static final long OFF_NAMEPTR  = 0x02a6f2b0L;
    private static final long OFF_TL_IN_NODE = 0x3f0L;

    // vector{begin,end} candidate offsets, as 8-byte indexes
    private static final int[][] PAIRS8 = {{45, 46}, {44, 45}, {46, 47}};

    private static volatile TextView box;
    private static volatile boolean started = false;
    private static List<Mem.Region> HEAP = new ArrayList<>();

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
            for (int i = 0; i < 12; i++) {
                try { Thread.sleep(10000); } catch (InterruptedException ignored) { }
                if (i < 2) continue;
                String rep = work();
                h.post(() -> { if (box != null) box.setText(rep); });
                write(app, "v11_report.txt", rep);
            }
        }).start();
    }

    @Override
    public boolean onStoragePermissionNeeded(String packageName, int userId) { return false; }

    private String work() {
        StringBuilder sb = new StringBuilder();
        sb.append("Predictor v11 pid=").append(android.os.Process.myPid())
          .append(" [").append(readCmdline(android.os.Process.myPid())).append("]\n");
        if (!Mem.open()) { sb.append("MEM FAIL\n"); return sb.toString(); }

        List<Mem.Region> regs = Mem.regions();
        HEAP = new ArrayList<>();
        List<Mem.Region> apk = new ArrayList<>();
        for (Mem.Region r : regs) {
            if (r.rw() && !r.path.contains("/")
                    && !r.path.startsWith("[stack") && !r.path.startsWith("[vvar")
                    && !r.path.startsWith("[vsys")) HEAP.add(r);
            if (r.read() && r.path.startsWith(APK_MARK) && r.path.endsWith(".apk")) apk.add(r);
        }
        sb.append("apk=").append(apk.size()).append(" heap=").append(HEAP.size()).append("\n");

        List<Mem.Region> cand = new ArrayList<>();
        for (Mem.Region r : apk) {
            byte[] m = Mem.read(r.start, 4);
            if (m != null && m.length == 4 && (m[0] & 0xff) == 0x7f
                    && m[1] == 'E' && m[2] == 'L' && m[3] == 'F') cand.add(r);
        }

        long base = 0, bias = -1;
        for (Mem.Region c : cand) {
            for (long b : new long[]{0L, 0x100000L}) {
                long p = Mem.readLong(c.start + OFF_NAMEPTR - b);
                if (p == 0) continue;
                if ("TableNode".equals(Mem.readCstr(p, 16))) { base = c.start; bias = b; break; }
            }
            if (bias >= 0) break;
        }
        if (bias < 0) { sb.append("NO MATCH elf=").append(cand.size()).append("\n"); return sb.toString(); }

        long cp = base + OFF_CLASSREC - bias;
        sb.append("BASE=0x").append(hx(base)).append(" BIAS=0x").append(hx(bias)).append("\n");
        sb.append("rec=0x").append(hx(cp)).append("\n");

        byte[] rb = Mem.read(cp, 64);
        if (rb != null) {
            sb.append(hx(rb, 0, 32)).append("\n");
            sb.append(hx(rb, 32, 32)).append("\n");
        }
        sb.append("super=0x").append(hx(Mem.readLong(cp + 8))).append("\n");

        List<Long> hits = new ArrayList<>();
        List<long[]> sigs = new ArrayList<>();
        long scanned = 0;

        outer:
        for (Mem.Region r : HEAP) {
            long p = r.start;
            while (p + 0x200 <= r.end) {
                int len = (int) Math.min((long) (1 << 20), r.end - p);
                byte[] buf = Mem.read(p, len);
                if (buf == null || buf.length < 0x200) { p += len; continue; }

                int n8 = buf.length / 8;
                long[] v = new long[n8];
                for (int k = 0; k < n8; k++) {
                    int o = k * 8;
                    long x = 0;
                    for (int j = 7; j >= 0; j--) x = (x << 8) | (buf[o + j] & 0xffL);
                    v[k] = x;
                    if (x == cp && hits.size() < 64) hits.add(p + o);
                }
                for (int k = 0; k + 48 <= n8; k++) {
                    for (int pi = 0; pi < PAIRS8.length; pi++) {
                        long a = v[k + PAIRS8[pi][0]];
                        long b = v[k + PAIRS8[pi][1]];
                        if (a == 0 || b <= a) continue;
                        long d = b - a;
                        if ((d & 31) != 0 || d > 1280) continue;
                        if (!isHeap(a) || !isHeap(b)) continue;
                        sigs.add(new long[]{p + k * 8L, PAIRS8[pi][0] * 8L, d / 32});
                        if (sigs.size() >= 200) break outer;
                    }
                }
                p += len;
            }
            scanned += r.size();
            if (scanned > (1500L << 20)) break;
        }

        sb.append("isaHits=").append(hits.size()).append("\n");
        int shown = 0;
        for (long node : hits) {
            long tl = Mem.readLong(node + OFF_TL_IN_NODE);
            sb.append("n=0x").append(hx(node)).append(" t3f0=0x").append(hx(tl)).append("\n");
            if (tl != 0 && isHeap(tl) && shown < 2) {
                byte[] z = Mem.read(tl + 0x140, 64);
                if (z != null) sb.append("  ").append(hx(z)).append("\n");
            }
            shown++;
            if (shown >= 5) break;
        }

        sb.append("sig=").append(sigs.size()).append("\n");
        int s2 = 0;
        for (long[] s : sigs) {
            long tl = s[0];
            byte[] rec1 = Mem.read(tl + s[1], 32);
            sb.append("s=0x").append(hx(tl)).append(" off=0x").append(hx(s[1]))
              .append(" n=").append(s[2]).append("\n");
            if (rec1 != null && s2 < 2) {
                sb.append("  r=").append(hx(rec1)).append("\n");
                long e = ld(rec1, 0);
                if (isHeap(e)) {
                    byte[] eb = Mem.read(e, 48);
                    if (eb != null) sb.append("  e=0x").append(hx(e)).append(" ").append(hx(eb)).append("\n");
                }
            }
            s2++;
            if (s2 >= 4) break;
        }
        return sb.toString();
    }

    private static boolean isHeap(long v) {
        for (Mem.Region r : HEAP) if (v >= r.start && v < r.end) return true;
        return false;
    }

    private static long ld(byte[] b, int o) {
        long v = 0;
        for (int k = 7; k >= 0; k--) v = (v << 8) | (b[o + k] & 0xffL);
        return v;
    }

    private static String hx(long v) { return Long.toHexString(v); }

    private static String hx(byte[] b) { return b == null ? "null" : hx(b, 0, b.length); }

    private static String hx(byte[] b, int off, int len) {
        if (b == null) return "null";
        StringBuilder s = new StringBuilder();
        int e = Math.min(b.length, off + len);
        for (int i = off; i < e; i++) {
            int x = b[i] & 0xff;
            s.append(Character.forDigit(x >> 4, 16)).append(Character.forDigit(x & 15, 16));
        }
        return s.toString();
    }

    private void write(Application app, String name, String text) {
        try {
            File d = app.getExternalFilesDir(null);
            if (d == null) d = app.getFilesDir();
            if (!d.exists()) d.mkdirs();
            FileWriter w = new FileWriter(new File(d, name), false);
            w.write(text);
            w.close();
        } catch (Throwable ignored) { }
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
            tv.setTextSize(6.5f);
            tv.setTextColor(Color.WHITE);
            tv.setBackgroundColor(0xcc000000);
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
            lp.y = 60;
            wm.addView(tv, lp);
            return tv;
        } catch (Throwable t) { return null; }
    }
}
