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
            for (int i = 0; i < 40; i++) {
                try { Thread.sleep(5000); } catch (InterruptedException ignored) { }
                if (i == 0) continue;
                String rep = work(i * 5);
                h.post(() -> { if (box != null) box.setText(rep); });
                if (i % 2 == 0) write(app, "v17_report.txt", rep);
            }
        }).start();
    }

    @Override
    public boolean onStoragePermissionNeeded(String packageName, int userId) { return false; }

    private String work(int t) {
        StringBuilder sb = new StringBuilder();
        sb.append("Predictor v17 t=").append(t).append("s pid=")
          .append(android.os.Process.myPid()).append("\n");
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

        List<Long> exact = new ArrayList<>();
        List<Long> near = new ArrayList<>();
        for (Mem.Region r : MALLOC) { scanIsa(r, cp, exact, near); if (exact.size() >= 40) break; }
        sb.append("isaExact=").append(exact.size()).append(" isaNear=").append(near.size()).append("\n");

        List<Long> all = new ArrayList<>();
        all.addAll(exact);
        all.addAll(near);

        int live = 0, dumped = 0;
        for (long node : all) {
            long tl = Mem.readLong(node + OFF_TL);
            if (!isHeap(tl)) continue;

            long b0 = Mem.readLong(tl + 0x168);
            long e0 = Mem.readLong(tl + 0x170);
            long cnt = -1;
            if (b0 != 0 && e0 >= b0 && (e0 - b0) % 8 == 0 && (e0 - b0) / 8 <= 40) cnt = (e0 - b0) / 8;

            sb.append("N=0x").append(hx(node)).append(" tl=0x").append(hx(tl))
              .append(" vec=").append(cnt).append("\n");

            if (dumped < 2) {
                byte[] lo = Mem.read(tl, 0x50);
                if (lo != null) {
                    sb.append(" lo=").append(hx(lo)).append("\n");
                    sb.append(" ld");
                    for (int k = 0; k < 10; k++) sb.append(" ").append(dm(ld(lo, k * 8)));
                    sb.append("\n");
                }
                dumped++;
            }

            if (cnt > 0) {
                live++;
                byte[] hi = Mem.read(tl + 0x100, 0xA0);
                if (hi != null) {
                    sb.append("  H").append(hx(hi)).append("\n");
                    sb.append("  D");
                    for (int k = 0; k < 20; k++) sb.append(" ").append(dm(ld(hi, k * 8)));
                    sb.append("\n");
                }
                for (int i = 0; i < Math.min(cnt, 3); i++) {
                    long pv = Mem.readLong(b0 + i * 8L);
                    sb.append("  p").append(i).append("=0x").append(hx(pv)).append("\n");
                    if (isHeap(pv)) {
                        byte[] pb = Mem.read(pv, 0x90);
                        if (pb != null) {
                            sb.append("   D");
                            for (int k = 0; k < 18; k++) sb.append(" ").append(dm(ld(pb, k * 8)));
                            sb.append("\n");
                        }
                    }
                }
            }
            if (live >= 2) break;
        }
        if (live == 0) sb.append("no non-empty vec\n");

        List<String> vs = new ArrayList<>();
        for (Mem.Region r : MALLOC) { scanVec(r, vs, 10); if (vs.size() >= 10) break; }
        sb.append("vecs=").append(vs.size()).append("\n");
        int q = 0;
        for (String s : vs) { sb.append(s).append("\n"); if (++q >= 5) break; }

        return sb.toString();
    }

    private void scanIsa(Mem.Region r, long cp, List<Long> exact, List<Long> near) {
        long p = r.start;
        while (p + 8 <= r.end) {
            int len = (int) Math.min((long) (1 << 20), r.end - p);
            byte[] buf = Mem.read(p, len);
            if (buf == null || buf.length < 8) { p += len; continue; }
            int n8 = buf.length / 8;
            for (int k = 0; k < n8; k++) {
                long x = ld(buf, k * 8);
                if (x == cp) { if (exact.size() < 40) exact.add(p + k * 8L); }
                else if (x == cp - OFF_META) { if (near.size() < 40) near.add(p + k * 8L); }
            }
            p += len;
        }
    }

    private void scanVec(Mem.Region r, List<String> out, int cap) {
        long p = r.start;
        while (p + 0x200 <= r.end) {
            int len = (int) Math.min((long) (1 << 20), r.end - p);
            byte[] buf = Mem.read(p, len);
            if (buf == null || buf.length < 0x200) { p += len; continue; }
            int n8 = buf.length / 8;
            for (int k = 0; k + 2 < n8; k++) {
                long a = ld(buf, k * 8);
                long b = ld(buf, k * 8 + 8);
                if (a == 0 || b <= a) continue;
                long d = b - a;
                if ((d & 7) != 0) continue;
                long n = d / 8;
                if (n < 5 || n > 40) continue;
                if (!isHeap(a) || !isHeap(b)) continue;

                int good = 0;
                long first = 0;
                for (int i = 0; i < Math.min(n, 4); i++) {
                    long pv = Mem.readLong(a + i * 8L);
                    if (!isHeap(pv)) break;
                    double x = Double.longBitsToDouble(Mem.readLong(pv + 0x10));
                    double y = Double.longBitsToDouble(Mem.readLong(pv + 0x18));
                    if (plaus(x) && plaus(y)) { good++; if (first == 0) first = pv; }
                }
                if (good >= 2 && first != 0) {
                    StringBuilder s = new StringBuilder();
                    s.append("V=0x").append(hx(p + k * 8L)).append(" n=").append(n)
                     .append(" rec=0x").append(hx(first)).append("\n");
                    byte[] rb = Mem.read(first, 0x90);
                    if (rb != null) {
                        s.append("  D");
                        for (int j = 0; j < 18; j++) s.append(" ").append(dm(ld(rb, j * 8)));
                    }
                    out.add(s.toString());
                    if (out.size() >= cap) return;
                }
            }
            p += len;
        }
    }

    private static boolean plaus(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) return false;
        double a = Math.abs(d);
        return a > 1e-6 && a < 5000.0;
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

    private static long ld(byte[] b, int o) {
        long v = 0;
        for (int k = 7; k >= 0; k--) v = (v << 8) | (b[o + k] & 0xffL);
        return v;
    }

    private static String dm(double d) {
        if (Double.isNaN(d)) return "nan";
        if (Double.isInfinite(d)) return d > 0 ? "inf" : "-inf";
        double a = Math.abs(d);
        if (a == 0) return "0";
        if (a >= 1e-4 && a < 1e7) return String.format("%.3f", d);
        return "~" + Long.toHexString(Double.doubleToLongBits(d));
    }

    private static String hx(long v) { return Long.toHexString(v); }

    private static String hx(byte[] b) {
        if (b == null) return "null";
        StringBuilder s = new StringBuilder();
        for (byte x : b) {
            int v = x & 0xff;
            s.append(Character.forDigit(v >> 4, 16)).append(Character.forDigit(v & 15, 16));
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
