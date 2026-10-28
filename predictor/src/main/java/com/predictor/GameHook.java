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
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import top.niunaijun.blackbox.app.configuration.AppLifecycleCallback;

public class GameHook extends AppLifecycleCallback {

    private static final String GAME = "com.miniclip.carrom";
    private static final String APK_MARK = "/data/app/com.miniclip.carrom-";

    private static final long OFF_CLASSREC = 0x02a6f2a0L;
    private static final long OFF_NAMEPTR  = 0x02a6f2b0L;
    private static final long OFF_TL       = 0x3f0L;
    private static final long OFF_META     = 0x90L;

    private static final int PAGE = 0x1000;
    private static final int CHUNK = 0x10000;
    private static final int MAXP = 200000;

    private static volatile TextView box;
    private static volatile boolean started = false;
    private static List<Mem.Region> HEAP = new ArrayList<>();
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
            for (int i = 0; i < 30; i++) {
                try { Thread.sleep(8000); } catch (InterruptedException ignored) { }
                if (i == 0) continue;
                String rep = work(i * 8);
                h.post(() -> { if (box != null) box.setText(rep); });
                write(app, "v19_report.txt", rep);
            }
        }).start();
    }

    @Override
    public boolean onStoragePermissionNeeded(String packageName, int userId) { return false; }

    private String work(int t) {
        StringBuilder sb = new StringBuilder();
        sb.append("Predictor v19 t=").append(t).append("s pid=")
          .append(android.os.Process.myPid()).append("\n");
        if (!Mem.open()) { sb.append("MEM FAIL\n"); return sb.toString(); }

        List<Mem.Region> regs = Mem.regions();
        HEAP = new ArrayList<>();
        List<Mem.Region> apk = new ArrayList<>();
        for (Mem.Region r : regs) {
            if (r.read() && r.path.startsWith(APK_MARK) && r.path.endsWith(".apk")) apk.add(r);
            if (!r.rw()) continue;
            String p = r.path;
            if (p.startsWith("[stack") || p.startsWith("[vvar") || p.startsWith("[vsys")) continue;
            if (p.contains("jit-cache")) continue;
            if (p.contains(".art") || p.contains(".oat") || p.contains(".vdex") || p.contains(".dex")) continue;
            HEAP.add(r);
        }
        RS = new long[HEAP.size()];
        RE = new long[HEAP.size()];
        for (int i = 0; i < HEAP.size(); i++) { RS[i] = HEAP.get(i).start; RE[i] = HEAP.get(i).end; }

        long base = 0, bias = -1;
        for (Mem.Region c : apk) {
            for (long b : new long[]{0L, 0x100000L}) {
                long p = Mem.readLong(c.start + OFF_NAMEPTR - b);
                if (p == 0) continue;
                if ("TableNode".equals(Mem.readCstr(p, 16))) { base = c.start; bias = b; break; }
            }
            if (bias >= 0) break;
        }
        if (bias < 0) { sb.append("NO MATCH\n"); return sb.toString(); }
        long cp = base + OFF_CLASSREC - bias;

        List<Long> hits = new ArrayList<>();
        for (Mem.Region r : HEAP) {
            long p = r.start;
            while (p + 8 <= r.end && hits.size() < 40) {
                int len = (int) Math.min((long) (1 << 20), r.end - p);
                byte[] buf = Mem.read(p, len);
                if (buf == null || buf.length < 8) { p += len; continue; }
                int n8 = buf.length / 8;
                for (int k = 0; k < n8 && hits.size() < 40; k++) {
                    long x = ld(buf, k * 8);
                    if (x == cp || x == cp - OFF_META) hits.add(p + k * 8L);
                }
                p += len;
            }
        }
        sb.append("BASE=0x").append(hx(base)).append(" isa=").append(hits.size()).append("\n");

        int hn = hits.size();
        long[] nodeH1 = new long[hn];
        long[] tlH1 = new long[hn];
        long[] tls = new long[hn];
        for (int i = 0; i < hn; i++) {
            byte[] nb = Mem.read(hits.get(i), 0x200);
            nodeH1[i] = nb == null ? -1 : hash(nb, 0, nb.length);
            long tl = Mem.readLong(hits.get(i) + OFF_TL);
            tls[i] = tl;
            byte[] tb = isHeap(tl) ? Mem.read(tl, 0x300) : null;
            tlH1[i] = tb == null ? -1 : hash(tb, 0, tb.length);
        }

        int np = 0;
        for (Mem.Region r : HEAP) np += (int) ((r.end - r.start) / PAGE);
        if (np > MAXP) np = MAXP;
        if (np < 16) np = 16;
        long[] pa = new long[np];
        long[] ph1 = new long[np];
        int idx = 0;
        outer:
        for (Mem.Region r : HEAP) {
            long p = r.start;
            while (p + PAGE <= r.end) {
                int len = (int) Math.min((long) CHUNK, r.end - p);
                len = (len / PAGE) * PAGE;
                if (len <= 0) break;
                byte[] buf = Mem.read(p, len);
                if (buf == null || buf.length < PAGE) { p += len; continue; }
                for (int o = 0; o + PAGE <= buf.length; o += PAGE) {
                    if (idx >= np) break outer;
                    pa[idx] = p + o;
                    ph1[idx] = hash(buf, o, PAGE);
                    idx++;
                }
                p += len;
            }
        }
        sb.append("pages=").append(idx).append("\n");

        try { Thread.sleep(3000); } catch (InterruptedException ignored) { }

        int liveNodes = 0, liveTls = 0;
        StringBuilder isaSb = new StringBuilder();
        for (int i = 0; i < hn; i++) {
            byte[] nb = Mem.read(hits.get(i), 0x200);
            boolean nc = nb != null && hash(nb, 0, nb.length) != nodeH1[i];
            long tl = tls[i];
            boolean tc = false;
            if (isHeap(tl)) {
                byte[] tb = Mem.read(tl, 0x300);
                tc = tb != null && hash(tb, 0, tb.length) != tlH1[i];
            }
            if (nc) liveNodes++;
            if (tc) liveTls++;
            isaSb.append("N=0x").append(hx(hits.get(i))).append(" tl=0x").append(hx(tl))
                 .append(" nodeChg=").append(nc ? 1 : 0)
                 .append(" tlChg=").append(tc ? 1 : 0).append("\n");
        }
        sb.append("liveNodes=").append(liveNodes).append(" liveTls=").append(liveTls).append("\n");
        sb.append(isaSb);

        List<long[]> found = new ArrayList<>();
        int changed = 0;
        for (int i = 0; i < idx; i++) {
            byte[] buf = Mem.read(pa[i], PAGE);
            if (buf == null) continue;
            if (hash(buf, 0, PAGE) == ph1[i]) continue;
            changed++;
            int[] r = bestRun(buf, 0, PAGE);
            if (r[1] >= 8) found.add(new long[]{pa[i] + r[0], r[1]});
        }
        sb.append("changedPages=").append(changed)
          .append(" withRun=").append(found.size()).append("\n");

        Collections.sort(found, (a, b) -> Long.compare(b[1], a[1]));
        int pr = 0;
        for (long[] f : found) {
            if (pr++ >= 20) break;
            byte[] buf = Mem.read(f[0], 0x100);
            if (buf == null) continue;
            sb.append("P=0x").append(hx(f[0])).append(" run=").append(f[1]).append(" ");
            int n = (int) Math.min(f[1], 16);
            for (int j = 0; j < n; j++) sb.append(fm(f32(buf, j * 4))).append(" ");
            sb.append("\n");
        }
        return sb.toString();
    }

    private static int[] bestRun(byte[] b, int off, int len) {
        int bestL = 0, bestO = -1, curL = 0, curO = -1;
        for (int o = off; o + 4 <= off + len; o += 4) {
            float f = Float.intBitsToFloat((int) ld32(b, o));
            if (f >= 0.5f && f <= 2000f) {
                if (curL == 0) curO = o;
                curL++;
                if (curL > bestL) { bestL = curL; bestO = curO; }
            } else {
                curL = 0;
            }
        }
        return new int[]{bestO, bestL};
    }

    private static long hash(byte[] b, int off, int len) {
        long h = 0xcbf29ce484222325L;
        int end = off + len;
        for (int i = off; i + 8 <= end; i += 8) {
            h ^= ld(b, i);
            h *= 0x100000001b3L;
        }
        return h;
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

    private static long ld32(byte[] b, int o) {
        return (b[o] & 0xffL) | ((b[o + 1] & 0xffL) << 8)
                | ((b[o + 2] & 0xffL) << 16) | ((b[o + 3] & 0xffL) << 24);
    }

    private static float f32(byte[] b, int o) { return Float.intBitsToFloat((int) ld32(b, o)); }

    private static String fm(float f) {
        if (Float.isNaN(f)) return "nan";
        if (Float.isInfinite(f)) return f > 0 ? "inf" : "-inf";
        float a = Math.abs(f);
        if (a == 0f) return "0";
        if (a >= 1e-3f && a < 1e6f) return String.format("%.2f", f);
        return "~" + Integer.toHexString(Float.floatToIntBits(f));
    }

    private static String hx(long v) { return Long.toHexString(v); }

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
