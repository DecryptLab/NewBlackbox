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

    // Ghidra vaddrs (Antigravity, image base 0x00100000)
    private static final long OFF_INIT     = 0x01bf7bc8L;   // -[TableNode init]
    private static final long OFF_CLASSREC = 0x02a6f2a0L;   // objc_class record
    private static final long OFF_NAMEPTR  = 0x02a6f2b0L;   // -> "TableNode"
    private static final long OFF_NAMESTR  = 0x0135a61dL;   // "TableNode" bytes
    private static final long OFF_TL_IN_NODE = 0x3f0L;      // TableNode -> TableLogic
    private static final long OFF_PUCKS_A  = 0x168L;
    private static final long OFF_PUCKS_B  = 0x170L;

    private static final long STP_X22_X21  = 0xA9BD57F6L;   // stp x22,x21,[sp,#-0x30]!

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
            for (int i = 0; i < 12; i++) {
                try { Thread.sleep(10000); } catch (InterruptedException ignored) { }
                if (i < 2) continue;
                String rep = work();
                h.post(() -> { if (box != null) box.setText(rep); });
                write(app, "v10_report.txt", rep);
            }
        }).start();
    }

    @Override
    public boolean onStoragePermissionNeeded(String packageName, int userId) { return false; }

    private String work() {
        StringBuilder sb = new StringBuilder();
        sb.append("Predictor v10\npid=").append(android.os.Process.myPid())
          .append(" [").append(readCmdline(android.os.Process.myPid())).append("]\n");

        if (!Mem.open()) { sb.append("MEM OPEN FAIL\n"); return sb.toString(); }

        List<Mem.Region> regs = Mem.regions();
        List<Mem.Region> apk = new ArrayList<>();
        for (Mem.Region r : regs) {
            if (r.read() && r.path.startsWith(APK_MARK) && r.path.endsWith(".apk")) apk.add(r);
        }
        sb.append("apkRegions=").append(apk.size()).append("\n");

        List<Mem.Region> cand = new ArrayList<>();
        for (Mem.Region r : apk) {
            byte[] m = Mem.read(r.start, 4);
            if (m != null && m.length == 4 && (m[0] & 0xff) == 0x7f
                    && m[1] == 'E' && m[2] == 'L' && m[3] == 'F') cand.add(r);
        }
        sb.append("elfBases=").append(cand.size()).append("\n");
        if (cand.isEmpty()) { sb.append("NO ELF BASE FOUND\n"); return sb.toString(); }

        long base = 0, bias = -1;
        for (Mem.Region c : cand) {
            for (long b : new long[]{0L, 0x100000L}) {
                long a = c.start + OFF_NAMEPTR - b;
                long p = Mem.readLong(a);
                if (p == 0) continue;
                String s = Mem.readCstr(p, 16);
                if ("TableNode".equals(s)) { base = c.start; bias = b; break; }
            }
            if (bias >= 0) break;
        }
        sb.append("candScanned=").append(cand.size()).append("\n");
        if (bias < 0) { sb.append("NO TableNode MATCH\n"); return sb.toString(); }

        sb.append("BASE=0x").append(Long.toHexString(base)).append("\n");
        sb.append("BIAS=0x").append(Long.toHexString(bias)).append("\n");

        byte[] ini = Mem.read(base + OFF_INIT - bias, 4);
        sb.append("init=").append(ini == null ? "----" : Long.toHexString(Mem.u32(ini, 0)))
          .append(Mem.u32(ini, 0) == STP_X22_X21 ? " OK" : " ?").append("\n");

        long cp = base + OFF_CLASSREC - bias;
        sb.append("classPtr=0x").append(Long.toHexString(cp)).append("\n");

        List<Mem.Region> heap = new ArrayList<>();
        for (Mem.Region r : regs) {
            if (!r.rw()) continue;
            if (r.path.contains("/")) continue;
            if (r.path.startsWith("[stack") || r.path.startsWith("[vvar") || r.path.startsWith("[vsys")) continue;
            heap.add(r);
        }

        List<Long> hits = new ArrayList<>();
        long scanned = 0;
        for (Mem.Region r : heap) {
            String p = r.path;
            if (!(p.contains("malloc") || p.contains("scudo") || p.contains("dalvik-alloc") || p.contains("dalvik-main"))) continue;
            hits.addAll(Mem.scan(r.start, r.end, cp, 300));
            scanned += r.size();
            if (hits.size() >= 300) break;
        }
        if (hits.isEmpty()) {
            for (Mem.Region r : heap) {
                hits.addAll(Mem.scan(r.start, r.end, cp, 300));
                scanned += r.size();
                if (hits.size() >= 300 || scanned > (1200L << 20)) break;
            }
        }
        sb.append("scanned=").append(scanned / (1024 * 1024)).append("MB hits=").append(hits.size()).append("\n");

        int shown = 0;
        for (long node : hits) {
            long tl = Mem.readLong(node + OFF_TL_IN_NODE);
            if (tl == 0) continue;
            long a = Mem.readLong(tl + OFF_PUCKS_A);
            long b2 = Mem.readLong(tl + OFF_PUCKS_B);
            long n = -1;
            if (a != 0 && b2 > a && (b2 - a) % 32 == 0 && (b2 - a) / 32 <= 60) n = (b2 - a) / 32;
            else if (b2 > 0 && b2 <= 60) n = b2;
            if (n <= 0) continue;
            sb.append("#").append(shown).append(" node=0x").append(Long.toHexString(node))
              .append(" tl=0x").append(Long.toHexString(tl)).append(" n=").append(n).append("\n");
            byte[] rec = Mem.read(a, (int) Math.min(96, n * 32));
            if (rec != null) sb.append("  rec=").append(hex(rec)).append("\n");
            shown++;
            if (shown >= 5) break;
        }
        if (shown == 0) sb.append("no valid TableNode instance\n");
        return sb.toString();
    }

    private static String hex(byte[] b) {
        StringBuilder s = new StringBuilder(b.length * 2);
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
            tv.setTextSize(7f);
            tv.setTextColor(Color.WHITE);
            tv.setBackgroundColor(0xcc000000);
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
            lp.y = 60;
            wm.addView(tv, lp);
            return tv;
        } catch (Throwable t) { return null; }
    }
}
