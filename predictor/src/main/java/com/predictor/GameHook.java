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
    private static final long OFF_TL       = 0x3f0L;
    private static final long OFF_META     = 0x90L;

    private static final int[][] PAIRS8 = {{45, 46}, {44, 45}, {46, 47}};

    private static volatile TextView box;
    private static volatile boolean started = false;
    private static List<Mem.Region> HEAP = new ArrayList<>();
    private static List<Mem.Region> MALLOC = new ArrayList<>();
    private static long[] RS = new long[0];
    private static long[] RE = new long[0];

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
            for (int i = 0; i < 8; i++) {
                try { Thread.sleep(15000); } catch (InterruptedException ignored) { }
                if (i == 0) continue;
                String rep = work();
                h.post(() -> { if (box != null) box.setText(rep); });
                write(app, "v13_report.txt", rep);
            }
        }).start();
    }

    @Override
    public boolean onStoragePermissionNeeded(String packageName, int userId) { return false; }

    private String work() {
        StringBuilder sb = new StringBuilder();
        sb.append("Predictor v13 pid=").append(android.os.Process.myPid())
          .append(" [").append(readCmdline(android.os.Process.myPid())).append("]\n");
        if (!Mem.open()) { sb.append("MEM FAIL\n"); return sb.toString(); }

        List<Mem.Region> regs = Mem.regions();
        HEAP = new ArrayList<>();
        MALLOC = new ArrayList<>();
        List<Mem.Region> apk = new ArrayList<>();
        for (Mem.Region r : regs) {
            if (r.read() && r.path.startsWith(APK_MARK) && r.path.endsWith(".apk")) apk.add(r);
            if (!r.rw()) continue;
            String p = r.path;
            if (p.startsWith("[stack") || p.startsWith("[vvar") || p.startsWith("[vsys")) continue;
            if (p.contains(".art") || p.contains(".oat") || p.contains(".vdex") || p.contains(".dex")) continue;
            if (p.contains("jit-cache")) continue;
            HEAP.add(r);
            if (p.contains("malloc") || p.contains("scudo")) MALLOC.add(r);
        }
        RS = new long[HEAP.size()];
        RE = new long[HEAP.size()];
        for (int i = 0; i < HEAP.size(); i++) { RS[i] = HEAP.get(i).start; RE[i] = HEAP.get(i).end; }

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
        sb.append("BASE=0x").append(hx(base)).append(" heap=").append(HEAP.size())
          .append(" malloc=").append(MALLOC.size()).append("\n");

        // ---- instances
        List<long[]> nodes = new ArrayList<>();
        for (Mem.Region r : HEAP) {
            scanIsa(r, cp, nodes);
            if (nodes.size() >= 200) break;
        }
        sb.append("isa=").append(nodes.size()).append("\n");
        int shown = 0;
        for (long[] nd : nodes) {
            long node = nd[0];
            long tl = Mem.readLong(node + OFF_TL);
            String tag = tagOf(node);
            if (!isHeap(tl)) { sb.append("i=0x").append(hx(node)).append(" tl=0x").append(hx(tl))
                                 .append(" ").append(tag).append("\n"); continue; }
            long a = Mem.readLong(tl + 0x168);
            long b = Mem.readLong(tl + 0x170);
            long n = -1;
            if (a != 0 && b > a && (b - a) % 32 == 0 && (b - a) / 32 <= 40) n = (b - a) / 32;
            sb.append("i=0x").append(hx(node)).append(" tl=0x").append(hx(tl))
              .append(" n=").append(n).append(" ").append(tag).append("\n");
            if (n > 0 && shown < 2) {
                byte[] z = Mem.read(tl + 0x100, 128);
                if (z != null) sb.append("  B").append(hx(z)).append("\n");
                byte[] rec = Mem.read(a, (int) Math.min(256, n * 32));
                if (rec != null) sb.append("  C").append(hx(rec)).append("\n");
                long e = Mem.readLong(a);
                if (isHeap(e)) {
                    byte[] eb = Mem.read(e, 64);
                    if (eb != null) sb.append("  E").append(hx(eb)).append("\n");
                }
                shown++;
            }
        }

        // ---- sigs, malloc only
        List<long[]> sigs = new ArrayList<>();
        for (Mem.Region r : MALLOC) { scanSig(r, sigs, 120); if (sigs.size() >= 120) break; }
        sigs.sort((x, y) -> Long.compare(y[2], x[2]));
        sb.append("sig=").append(sigs.size()).append("\n");
        int s2 = 0;
        for (long[] s : sigs) {
            long tl = s[0];
            sb.append("s=0x").append(hx(tl)).append(" off=0x").append(hx(s[1]))
              .append(" n=").append(s[2]).append(" ").append(tagOf(tl)).append("\n");
            if (s2 < 2) {
                byte[] z = Mem.read(tl + 0x100, 128);
                if (z != null) sb.append("  B").append(hx(z)).append("\n");
                long begin = Mem.readLong(tl + s[1]);
                byte[] rec = Mem.read(begin, (int) Math.min(256, s[2] * 32));
                if (rec != null) sb.append("  C").append(hx(rec)).append("\n");
                long e = Mem.readLong(begin);
                if (isHeap(e)) {
                    byte[] eb = Mem.read(e, 64);
                    if (eb != null) sb.append("  E").append(hx(eb)).append("\n");
                }
                s2++;
            }
            if (++s2 >= 8 && s2 > 2) { }
            if (s2 >= 4) break;
        }
        return sb.toString();
    }

    private void scanIsa(Mem.Region r, long cp, List<long[]> out) {
        long p = r.start;
        while (p + 8 <= r.end) {
            int len = (int) Math.min((long) (1 << 20), r.end - p);
            byte[] buf = Mem.read(p, len);
            if (buf == null || buf.length < 8) { p += len; continue; }
            int n8 = buf.length / 8;
            for (int k = 0; k < n8; k++) {
                int o = k * 8;
                long x = 0;
                for (int j = 7; j >= 0; j--) x = (x << 8) | (buf[o + j] & 0xffL);
                if (x == cp || x == cp - OFF_META) {
                    if (out.size() < 200) out.add(new long[]{p + o, 0});
                }
            }
            p += len;
        }
    }

    private void scanSig(Mem.Region r, List<long[]> out, int cap) {
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
            }
            for (int k = 0; k + 48 <= n8; k++) {
                for (int pi = 0; pi < PAIRS8.length; pi++) {
                    long a = v[k + PAIRS8[pi][0]];
                    long b = v[k + PAIRS8[pi][1]];
                    long c = v[k + PAIRS8[pi][1] + 1];
                    if (a == 0 || b <= a || c < b) continue;
                    long d = b - a;
                    if ((d & 31) != 0 || d > 1280) continue;
                    if ((c - a) > 2048) continue;
                    int ra = regionOf(a);
                    if (ra < 0 || regionOf(c) != ra) continue;
                    out.add(new long[]{p + k * 8L, PAIRS8[pi][0] * 8L, d / 32});
                    if (out.size() >= cap) return;
                }
            }
            p += len;
        }
    }

    private static int regionOf(long v) {
        int lo = 0, hi = RS.length - 1, ans = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (RS[mid] <= v) { ans = mid; lo = mid + 1; } else hi = mid - 1;
        }
        if (ans < 0) return -1;
        return (v < RE[ans]) ? ans : -1;
    }

    private static boolean isHeap(long v) { return regionOf(v) >= 0; }

    private static String tagOf(long v) {
        int i = regionOf(v);
        if (i < 0) return "-";
        String p = HEAP.get(i).path;
        if (p.isEmpty()) return "anon";
        if (p.contains("malloc") || p.contains("scudo")) return "MALLOC";
        if (p.endsWith(".apk")) return "LIB";
        return p.length() > 20 ? p.substring(p.length() - 20) : p;
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
            tv.setTextSize(6f);
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
