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
            for (int i = 0; i < 24; i++) {
                try { Thread.sleep(5000); } catch (InterruptedException ignored) { }
                if (i == 0) continue;
                String rep = work(i * 5);
                h.post(() -> { if (box != null) box.setText(rep); });
                if (i % 4 == 0) write(app, "v15_report.txt", rep);
            }
        }).start();
    }

    @Override
    public boolean onStoragePermissionNeeded(String packageName, int userId) { return false; }

    private String work(int t) {
        StringBuilder sb = new StringBuilder();
        sb.append("Predictor v15 t=").append(t).append("s pid=").append(android.os.Process.myPid()).append("\n");
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
        if (bias < 0) { sb.append("NO MATCH\n"); return sb.toString(); }
        long cp = base + OFF_CLASSREC - bias;
        sb.append("BASE=0x").append(hx(base)).append(" malloc=").append(MALLOC.size()).append("\n");

        List<Long> nodes = new ArrayList<>();
        for (Mem.Region r : MALLOC) { scanIsa(r, cp, nodes); if (nodes.size() >= 20) break; }
        sb.append("nodes=").append(nodes.size()).append("\n");

        long bestTl = 0, bestArr = 0, bestOff = 0, bestN = 0;

        int ni = 0;
        for (long node : nodes) {
            long tl = Mem.readLong(node + OFF_TL);
            sb.append("N").append(ni).append(" node=0x").append(hx(node)).append(" tl=0x").append(hx(tl)).append("\n");
            if (!isHeap(tl)) { if (++ni >= 2) break; continue; }

            long vt = Mem.readLong(tl);
            sb.append(" vt=0x").append(hx(vt)).append(" ").append(tagOf(vt)).append("\n");

            int cnt = 0;
            for (long off = 0; off < 0x400; off += 8) {
                long tt = Mem.readLong(tl + off);
                if (!isHeap(tt)) continue;
                long av = Mem.readLong(tt);
                String fl = (isLib(av) ? " V" : "") + arrFlag(tt);
                sb.append("  +").append(hx(off)).append(" -> 0x").append(hx(tt))
                  .append(" ").append(tagOf(tt)).append(fl).append("\n");
                long n = arrCount(tt);
                if (n > bestN) { bestN = n; bestTl = tl; bestArr = tt; bestOff = off; }
                if (++cnt >= 14) break;
            }
            if (++ni >= 2) break;
        }

        if (bestN > 0) {
            sb.append("best tl=0x").append(hx(bestTl)).append(" +").append(hx(bestOff))
              .append(" arr=0x").append(hx(bestArr)).append(" n=").append(bestN).append("\n");
            long a = Mem.readLong(bestArr);
            for (int i = 0; i < Math.min(bestN, 3); i++) {
                long pv = Mem.readLong(a + i * 8L);
                sb.append("  e").append(i).append("=0x").append(hx(pv)).append(" ").append(tagOf(pv)).append("\n");
                if (isHeap(pv)) {
                    byte[] pb = Mem.read(pv, 64);
                    if (pb != null) {
                        sb.append("   ").append(hx(pb)).append("\n");
                        sb.append("   f");
                        for (int k = 0; k < 8; k++) sb.append(" ").append(Float.intBitsToFloat((int) ld(pb, k * 4)));
                        sb.append("\n");
                    }
                }
            }
        }
        return sb.toString();
    }

    private static long arrCount(long t) {
        for (int k = 0; k < 3; k++) {
            long a = Mem.readLong(t + k * 8L);
            long b = Mem.readLong(t + k * 8L + 8);
            if (a == 0 || b <= a) continue;
            long d = b - a;
            if (d % 8 != 0 || d / 8 < 2 || d / 8 > 40) continue;
            if (!isHeap(a) || !isHeap(b)) continue;
            return d / 8;
        }
        return 0;
    }

    private static String arrFlag(long t) {
        long n = arrCount(t);
        return n > 0 ? (" n=" + n) : "";
    }

    private void scanIsa(Mem.Region r, long cp, List<Long> out) {
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
                if (x == cp || x == cp - OFF_META) { if (out.size() < 20) out.add(p + o); }
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

    private static boolean isLib(long v) {
        int i = regionOf(v);
        return i >= 0 && HEAP.get(i).path.endsWith(".apk");
    }

    private static String tagOf(long v) {
        int i = regionOf(v);
        if (i < 0) return "-";
        String p = HEAP.get(i).path;
        if (p.isEmpty()) return "anon";
        if (p.contains("malloc") || p.contains("scudo")) return "MALLOC";
        if (p.endsWith(".apk")) return "LIB";
        return p.length() > 18 ? p.substring(p.length() - 18) : p;
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
            tv.setTextSize(5.5f);
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
            lp.y = 60;
            wm.addView(tv, lp);
            return tv;
        } catch (Throwable t) { return null; }
    }
}
