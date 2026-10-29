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
    private static final String APK = "split_config.arm64_v8a.apk";

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
            h.post(() -> box = addBox(app, "waiting for engine..."));
            set(h, "waiting for engine...");

            long base = -1;
            int apkLines = 0;
            for (int i = 0; i < 240; i++) {
                long[] r = findBase();
                apkLines = (int) r[1];
                if (r[0] >= 0) { base = r[0]; break; }
                set(h, "waiting for engine...\napkLines=" + apkLines + " t=" + i);
                sleep(500);
            }
            if (base < 0) {
                set(h, "NO BASE\napkLines=" + apkLines + "\nmaps has no " + APK);
                return;
            }
            long vt = base + VTABLE_OFF;
            set(h, "base=0x" + Long.toHexString(base) + "\nvtable=0x" + Long.toHexString(vt)
                    + "\nscanning...");

            List<Long> hits = scan(vt);
            StringBuilder sb = new StringBuilder();
            sb.append("v9 base=0x").append(Long.toHexString(base)).append("\n");
            sb.append("vtable=0x").append(Long.toHexString(vt)).append("\n");
            sb.append("hits=").append(hits.size()).append("\n");
            try (RandomAccessFile mem = new RandomAccessFile("/proc/self/mem", "r")) {
                int n = 0;
                for (Long a : hits) {
                    long tl = readLong(mem, a + TABLELOGIC_OFF);
                    sb.append("0x").append(Long.toHexString(a))
                      .append(" -> +0x3F0=0x").append(Long.toHexString(tl)).append("\n");
                    if (++n >= 8) break;
                }
            } catch (Throwable t) {
                sb.append("mem ").append(t).append("\n");
            }
            Log.d(TAG, sb.toString());
            set(h, sb.toString());
        }).start();
    }

    private void set(Handler h, String s) {
        h.post(() -> { if (box != null) box.setText(s); });
    }

    private void sleep(int ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }

    private long[] findBase() {
        long best = -1;
        long lines = 0;
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/self/maps"))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.contains(APK)) continue;
                lines++;
                String[] p = line.split("\\s+");
                if (p.length < 3) continue;
                int dash = p[0].indexOf('-');
                if (dash < 0) continue;
                long start = Long.parseLong(p[0].substring(0, dash), 16);
                long off = Long.parseLong(p[2], 16);
                long b = start - off;
                if (best < 0 || b < best) best = b;
            }
        } catch (Throwable t) { }
        return new long[]{best, lines};
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
                try {
                    mem.seek(addr);
                    got = mem.read(buf, 0, want);
                } catch (Throwable t) {
                    addr += want;
                    continue;
                }
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
        } catch (Throwable t) {
            return -1;
        }
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
