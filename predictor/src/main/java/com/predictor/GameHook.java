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

    private static final int PAGE = 0x1000;
    private static final int CHUNK = 0x10000;
    private static final int MAXP = 150000;
    private static final int MAXCHG = 12000;

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
            for (int i = 0; i < 30; i++) {
                try { Thread.sleep(6000); } catch (InterruptedException ignored) { }
                if (i == 0) continue;
                String rep = work(i * 6);
                h.post(() -> { if (box != null) box.setText(rep); });
                write(app, "v20_report.txt", rep);
            }
        }).start();
    }

    @Override
    public boolean onStoragePermissionNeeded(String packageName, int userId) { return false; }

    private String work(int t) {
        StringBuilder sb = new StringBuilder();
        sb.append("Predictor v20 t=").append(t).append("s pid=")
          .append(android.os.Process.myPid()).append("\n");
        if (!Mem.open()) { sb.append("MEM FAIL\n"); return sb.toString(); }

        List<Mem.Region> heap = new ArrayList<>();
        for (Mem.Region r : Mem.regions()) {
            if (!r.rw()) continue;
            String p = r.path;
            if (p.startsWith("[stack") || p.startsWith("[vvar") || p.startsWith("[vsys")) continue;
            if (p.contains("jit-cache")) continue;
            if (p.contains(".art") || p.contains(".oat") || p.contains(".vdex") || p.contains(".dex")) continue;
            heap.add(r);
        }

        int np = 0;
        for (Mem.Region r : heap) np += (int) ((r.end - r.start) / PAGE);
        if (np > MAXP) np = MAXP;
        if (np < 32) np = 32;
        long[] pa = new long[np];
        long[] ph = new long[np];
        int idx = 0;

        outer1:
        for (Mem.Region r : heap) {
            long p = r.start;
            while (p + PAGE <= r.end) {
                int len = (int) Math.min((long) CHUNK, r.end - p);
                len = (len / PAGE) * PAGE;
                if (len <= 0) break;
                byte[] buf = Mem.read(p, len);
                if (buf == null || buf.length < PAGE) { p += len; continue; }
                for (int o = 0; o + PAGE <= buf.length; o += PAGE) {
                    if (idx >= np) break outer1;
                    pa[idx] = p + o;
                    ph[idx] = hash(buf, o, PAGE);
                    idx++;
                }
                p += len;
            }
        }

        try { Thread.sleep(2000); } catch (InterruptedException ignored) { }

        List<Integer> chg = new ArrayList<>();
        List<byte[]> snap = new ArrayList<>();
        for (int i = 0; i < idx; i++) {
            if (chg.size() >= MAXCHG) break;
            byte[] buf = Mem.read(pa[i], PAGE);
            if (buf == null) continue;
            if (hash(buf, 0, PAGE) == ph[i]) continue;
            chg.add(i);
            snap.add(buf);
        }
        sb.append("pages=").append(idx).append(" changed=").append(chg.size()).append("\n");

        try { Thread.sleep(2000); } catch (InterruptedException ignored) { }

        List<long[]> movers = new ArrayList<>();
        for (int c = 0; c < chg.size(); c++) {
            byte[] a = snap.get(c);
            byte[] b = Mem.read(pa[chg.get(c)], PAGE);
            if (b == null) continue;
            for (int o = 0; o + 4 <= PAGE; o += 4) {
                float fa = Float.intBitsToFloat((int) ld32(a, o));
                float fb = Float.intBitsToFloat((int) ld32(b, o));
                if (Float.isNaN(fa) || Float.isNaN(fb)) continue;
                if (Float.isInfinite(fa) || Float.isInfinite(fb)) continue;
                if (Math.abs(fa) > 2000f || Math.abs(fb) > 2000f) continue;
                float d = Math.abs(fb - fa);
                if (d < 0.5f || d > 2000f) continue;
                movers.add(new long[]{pa[chg.get(c)] + o,
                        Float.floatToIntBits(fa), Float.floatToIntBits(fb)});
            }
        }
        Collections.sort(movers, (x, y) -> Float.compare(
                Math.abs(Float.intBitsToFloat((int) y[2]) - Float.intBitsToFloat((int) y[1])),
                Math.abs(Float.intBitsToFloat((int) x[2]) - Float.intBitsToFloat((int) x[1]))));

        sb.append("movers=").append(movers.size()).append("\n");
        int pr = 0;
        for (long[] m : movers) {
            if (pr++ >= 40) break;
            float fa = Float.intBitsToFloat((int) m[1]);
            float fb = Float.intBitsToFloat((int) m[2]);
            sb.append("M=0x").append(hx(m[0]))
              .append(" ").append(fm(fa)).append("->").append(fm(fb))
              .append(" d=").append(String.format("%.1f", Math.abs(fb - fa)));
            byte[] ctx = Mem.read(m[0], 40);
            if (ctx != null) {
                sb.append(" ctx");
                for (int j = 0; j < 8; j++) sb.append(" ").append(fm(f32(ctx, j * 4)));
            }
            sb.append("\n");
        }
        return sb.toString();
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
