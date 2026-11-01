package com.predictor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Pure logic: calibration, coin finding (memory seed + pixel snap), shot planning,
 * launch analysis. No Android classes here, so it can be tested on a PC.
 *
 * All "base" constants below are in a 480x854 frame, scaled by s = SCX / 0.889.
 */
final class Core {

    static final double POCKET = 230.008;

    static int[] PIX;
    static int PW, PH;
    static double CX, CY, SCX, SCY;
    static boolean calOk = false;

    static double SXs = -9999, SYs = -9999;
    static final double[] PKx = new double[4], PKy = new double[4];
    static int NPK = 0;

    static double offX, offY;
    static int offN;

    private static double exS2, exP2;

    static final class Coin { double x, y; int cl; int mi; }
    static final class Snap { double x, y; int cl, n; }
    static final class Plan {
        double sc = 1e18; int k = -1, q = -1, blk; double dot, gx, gy, X; boolean anyColour;
    }

    /* ---------- basics ---------- */

    static double sc() { return SCX / 0.889; }
    static double px(double wx) { return CX + wx * SCX; }
    static double py(double wy) { return CY - wy * SCY; }
    static boolean okWorld(double x, double y) {
        return !Double.isNaN(x) && !Double.isNaN(y) && Math.abs(x) <= 231 && Math.abs(y) <= 231;
    }

    static int lum(int x, int y) {
        if (x < 0 || y < 0 || x >= PW || y >= PH) return 0;
        int c = PIX[y * PW + x];
        return (((c >> 16) & 0xFF) + ((c >> 8) & 0xFF) + (c & 0xFF)) / 3;
    }

    static void useDefaultCal(int w, int h) {
        double s = w / 480.0;
        CX = 238.0 * s; CY = 413.5 * s; SCX = 0.889 * s; SCY = 0.888 * s;
        calOk = true;
    }

    static void setStriker(double x, double y) {
        SXs = x; SYs = y;
        double s = sc();
        exS2 = (20 * s) * (20 * s);
        exP2 = (23 * s) * (23 * s);
    }

    static void setPockets(double[] x, double[] y, int n) {
        NPK = n;
        for (int i = 0; i < n; i++) { PKx[i] = x[i]; PKy[i] = y[i]; }
        double s = sc();
        exS2 = (20 * s) * (20 * s);
        exP2 = (23 * s) * (23 * s);
    }

    /* ---------- calibration from the 4 dark pocket holes ---------- */

    /** fraction of points on a circle that satisfy dark (lum < thr) */
    private static double ringDark(double cx, double cy, double r, int n, int thr) {
        int hit = 0;
        for (int i = 0; i < n; i++) {
            double a = 2 * Math.PI * i / n;
            if (lum((int) Math.round(cx + r * Math.cos(a)), (int) Math.round(cy + r * Math.sin(a))) < thr) hit++;
        }
        return (double) hit / n;
    }

    /** fraction of points on a circle that are brighter than thr */
    private static double ringBright(double cx, double cy, double r, int n, int thr) {
        int hit = 0;
        for (int i = 0; i < n; i++) {
            double a = 2 * Math.PI * i / n;
            if (lum((int) Math.round(cx + r * Math.cos(a)), (int) Math.round(cy + r * Math.sin(a))) > thr) hit++;
        }
        return (double) hit / n;
    }

    static boolean calibrate() {
        calOk = false;
        if (PIX == null) return false;
        final int w = PW, h = PH;
        final double s = w / 480.0;
        int y0 = (int) (h * 0.12), y1 = (int) (h * 0.95);
        int step = Math.max(2, (int) Math.round(2 * s));
        List<double[]> hits = new ArrayList<>(); // x, y, score
        for (int y = y0; y < y1; y += step) {
            for (int x = 0; x < w; x += step) {
                if (lum(x, y) >= 60) continue;
                double d1 = ringDark(x, y, 8 * s, 12, 60);
                if (d1 < 0.9) continue;
                double d2 = ringDark(x, y, 14 * s, 16, 60);
                if (d2 < 0.9) continue;
                double o = ringBright(x, y, 26 * s, 16, 75);
                if (o < 0.4) continue;
                hits.add(new double[]{x, y, d1 + d2 + 0.5 * o});
            }
        }
        if (hits.size() < 4) return false;
        java.util.Collections.sort(hits, (p, q) -> Double.compare(q[2], p[2]));
        List<double[]> cands = new ArrayList<>();
        for (double[] hh : hits) {
            boolean near = false;
            for (double[] c : cands) if (Math.hypot(c[0] - hh[0], c[1] - hh[1]) < 24 * s) { near = true; break; }
            if (near) continue;
            // refine: centroid of the very dark ring/disc pixels around the coarse centre
            double ax = 0, ay = 0;
            int n = 0, R = (int) Math.round(22 * s);
            for (int dy = -R; dy <= R; dy++) for (int dx = -R; dx <= R; dx++) {
                if (dx * dx + dy * dy > R * R) continue;
                int xx = (int) hh[0] + dx, yy = (int) hh[1] + dy;
                if (lum(xx, yy) < 38) { ax += xx; ay += yy; n++; }
            }
            if (n < 300 * s * s) continue;
            cands.add(new double[]{ax / n, ay / n});
        }
        if (cands.size() < 4) return false;
        double minX = 1e9, maxX = -1e9, minY = 1e9, maxY = -1e9;
        for (double[] c : cands) {
            minX = Math.min(minX, c[0]); maxX = Math.max(maxX, c[0]);
            minY = Math.min(minY, c[1]); maxY = Math.max(maxY, c[1]);
        }
        double[][] corner = new double[4][];
        for (int q = 0; q < 4; q++) {
            double tx = (q & 1) == 1 ? maxX : minX, ty = (q >> 1) == 1 ? maxY : minY;
            double bd = 1e18; double[] bc = null;
            for (double[] c : cands) {
                double d = (c[0] - tx) * (c[0] - tx) + (c[1] - ty) * (c[1] - ty);
                if (d < bd) { bd = d; bc = c; }
            }
            corner[q] = bc;
        }
        for (int a = 0; a < 4; a++) for (int b = a + 1; b < 4; b++) if (corner[a] == corner[b]) return false;
        double xL = (corner[0][0] + corner[2][0]) / 2, xR = (corner[1][0] + corner[3][0]) / 2;
        double yT = (corner[0][1] + corner[1][1]) / 2, yB = (corner[2][1] + corner[3][1]) / 2;
        double tol = 10 * s;
        if (Math.abs(corner[0][0] - corner[2][0]) > tol || Math.abs(corner[1][0] - corner[3][0]) > tol
                || Math.abs(corner[0][1] - corner[1][1]) > tol || Math.abs(corner[2][1] - corner[3][1]) > tol)
            return false;
        double nx = (xR - xL) / (2 * POCKET), ny = (yB - yT) / (2 * POCKET);
        if (nx < 0.4 || nx > 2.5 || ny < 0.4 || ny > 2.5 || Math.abs(nx - ny) / Math.max(nx, ny) > 0.12) return false;
        CX = (xL + xR) / 2; CY = (yT + yB) / 2; SCX = nx; SCY = ny;
        calOk = true;
        return true;
    }

    /* ---------- pixel classes ---------- */

    /** 1 = black coin, 2 = white coin, 0 = anything else (wood, holes, striker). */
    static int cls(int x, int y) {
        if (x < 0 || y < 0 || x >= PW || y >= PH) return 0;
        double dx = x - SXs, dy = y - SYs;
        if (dx * dx + dy * dy < exS2) return 0;
        for (int q = 0; q < NPK; q++) {
            double ex = x - PKx[q], ey = y - PKy[q];
            if (ex * ex + ey * ey < exP2) return 0;
        }
        int c = PIX[y * PW + x];
        int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
        int l = (r + g + b) / 3;
        if (l < 70) return 1;
        if (l > 180 && r - b < 75) return 2;
        return 0;
    }

    /** Move a rough position onto the nearest real coin blob. null = no coin there. */
    static Snap snap(double sx, double sy, double r0) {
        double s = sc();
        Snap best = null;
        for (int c = 1; c <= 2; c++) {
            double cx = sx, cy = sy;
            int n = 0;
            for (int it = 0; it < 3; it++) {
                int R = (int) Math.round(it == 0 ? r0 : 13 * s);
                int icx = (int) cx, icy = (int) cy;
                double ax = 0, ay = 0;
                n = 0;
                for (int dy = -R; dy <= R; dy++) for (int dx = -R; dx <= R; dx++) {
                    if (dx * dx + dy * dy > R * R) continue;
                    if (cls(icx + dx, icy + dy) == c) { ax += icx + dx; ay += icy + dy; n++; }
                }
                if (n < 40 * s * s) { n = 0; break; }
                cx = ax / n; cy = ay / n;
            }
            if (n > 0 && (best == null || n > best.n)) {
                best = new Snap(); best.x = cx; best.y = cy; best.cl = c; best.n = n;
            }
        }
        if (best == null || best.n < 140 * s * s) return null;
        return best;
    }

    static double median(List<Double> v) {
        if (v.isEmpty()) return 0;
        List<Double> c = new ArrayList<>(v);
        java.util.Collections.sort(c);
        return c.get(c.size() / 2);
    }

    /**
     * mx/my = rough coin positions from memory (pixels). The memory positions are shifted
     * by a constant, so learn that shift from the full isolated coins, then re-snap tightly.
     */
    static List<Coin> locate(double[] mx, double[] my, int[] mi, int nm) {
        double s = sc();
        List<Double> ox = new ArrayList<>(), oy = new ArrayList<>();
        for (int i = 0; i < nm; i++) {
            Snap sn = snap(mx[i], my[i], 26 * s);
            if (sn != null && sn.n >= 300 * s * s) { ox.add(sn.x - mx[i]); oy.add(sn.y - my[i]); }
        }
        boolean learned = ox.size() >= 3;
        double mdx = learned ? median(ox) : 0, mdy = learned ? median(oy) : 0;
        offX = mdx; offY = mdy; offN = ox.size();
        List<Coin> out = new ArrayList<>();
        for (int i = 0; i < nm; i++) {
            Snap sn = snap(mx[i] + mdx, my[i] + mdy, (learned ? 15 : 26) * s);
            if (sn == null) continue;
            boolean dup = false;
            for (Coin c : out) if (Math.hypot(c.x - sn.x, c.y - sn.y) < 12 * s) dup = true;
            if (dup) continue;
            Coin c = new Coin();
            c.x = sn.x; c.y = sn.y; c.cl = sn.cl; c.mi = mi[i];
            out.add(c);
        }
        return out;
    }

    /* ---------- geometry ---------- */

    static double segd(double px, double py, double ax, double ay, double bx, double by) {
        double vx = bx - ax, vy = by - ay, l2 = vx * vx + vy * vy;
        double t = l2 > 0 ? ((px - ax) * vx + (py - ay) * vy) / l2 : 0;
        if (t < 0) t = 0;
        if (t > 1) t = 1;
        return Math.hypot(px - (ax + vx * t), py - (ay + vy * t));
    }

    /** how far we can drag from (x,y) in direction (vx,vy) before leaving the screen */
    static double dragRoom(double x, double y, double vx, double vy) {
        double tm = 1e9;
        if (vx < -1e-6) tm = Math.min(tm, (x - 3) / -vx);
        if (vx > 1e-6) tm = Math.min(tm, (PW - 4 - x) / vx);
        if (vy < -1e-6) tm = Math.min(tm, (y - 3) / -vy);
        if (vy > 1e-6) tm = Math.min(tm, (PH - 4 - y) / vy);
        return tm;
    }

    static void pickAt(double X, double Y, List<Coin> cs, int mine, boolean only, double gd, Plan bp) {
        double s = sc();
        for (Coin c : cs) if (Math.hypot(c.x - X, c.y - Y) < 32 * s) return;
        double lx0 = px(-226), lx1 = px(226), ly0 = py(226), ly1 = py(-226);
        for (int k = 0; k < cs.size(); k++) {
            Coin ck = cs.get(k);
            if (only && ck.cl != mine) continue;
            for (int q = 0; q < NPK; q++) {
                double dx = PKx[q] - ck.x, dy = PKy[q] - ck.y, L = Math.hypot(dx, dy);
                if (L < 1) L = 1;
                double gx = ck.x - dx / L * gd, gy = ck.y - dy / L * gd;
                if (gx < lx0 || gx > lx1 || gy < ly0 || gy > ly1) continue;
                double ax = gx - X, ay = gy - Y, AL = Math.hypot(ax, ay);
                if (AL < 40 * s) continue;
                double dot = (ax / AL) * (dx / L) + (ay / AL) * (dy / L);
                if (dot < 0.30) continue;
                if (dragRoom(X, Y, -ax / AL, -ay / AL) < 125 * s) continue;
                int blk = 0;
                for (int o = 0; o < cs.size(); o++) {
                    if (o == k) continue;
                    Coin co = cs.get(o);
                    if (segd(co.x, co.y, X, Y, gx, gy) < 29 * s) blk++;
                    if (segd(co.x, co.y, ck.x, ck.y, PKx[q], PKy[q]) < 26 * s) blk++;
                }
                double sc = L / s + 0.25 * AL / s + 5000.0 * blk + 350.0 * (1.0 - dot);
                if (sc < bp.sc) {
                    bp.sc = sc; bp.k = k; bp.q = q; bp.blk = blk; bp.dot = dot; bp.gx = gx; bp.gy = gy; bp.X = X;
                }
            }
        }
    }

    /** Best shot from the current striker spot, optionally scanning the whole baseline. */
    static Plan bestPlan(double X, double Y, List<Coin> cs, int mine, double gd,
                         boolean allowMove, double xmin, double xmax) {
        double s = sc();
        for (int pass = 0; pass < 2; pass++) {
            Plan best = new Plan();
            best.X = X;
            pickAt(X, Y, cs, mine, pass == 0, gd, best);
            if (allowMove) {
                for (double xc = xmin; xc <= xmax; xc += 6 * s) {
                    Plan t = new Plan();
                    t.X = xc;
                    pickAt(xc, Y, cs, mine, pass == 0, gd, t);
                    if (t.k >= 0 && t.sc < best.sc - 40) best = t;
                }
            }
            if (best.k >= 0) { best.anyColour = pass == 1; return best; }
        }
        return null;
    }

    /* ---------- slider knob + HUD ---------- */

    /** white round knob on the striker slider under the board */
    static double[] findKnob() {
        double s = sc();
        int ky0 = (int) (CY + 289 * s), ky1 = (int) (CY + 349 * s);
        int kx0 = (int) (CX - 185 * s), kx1 = (int) (CX + 185 * s);
        double ax = 0, ay = 0;
        int n = 0;
        for (int y = Math.max(0, ky0); y <= Math.min(PH - 1, ky1); y++) {
            for (int x = Math.max(0, kx0); x <= Math.min(PW - 1, kx1); x++) {
                int c = PIX[y * PW + x];
                int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
                if ((r + g + b) / 3 > 185 && r - b < 80) { ax += x; ay += y; n++; }
            }
        }
        if (n < 150 * s * s) return null;
        return new double[]{ax / n, ay / n};
    }

    /** colour of our coin icon in the top-left HUD: 1 black, 2 white, 0 unknown */
    static int hudColour() {
        double s = sc();
        int cx = (int) (CX - 185 * s), cy = (int) (CY - 295.5 * s), r = (int) Math.max(2, 4 * s);
        long sum = 0;
        int n = 0;
        for (int dy = -r; dy <= r; dy++) for (int dx = -r; dx <= r; dx++) { sum += lum(cx + dx, cy + dy); n++; }
        double l = (double) sum / n;
        if (l < 85) return 1;
        if (l > 175) return 2;
        return 0;
    }

    /** how white a window around the striker memory position is (0..1) */
    static double brightScore(double x, double y) {
        int r = (int) Math.max(3, Math.round(6 * sc()));
        int hit = 0, tot = 0;
        for (int dy = -r; dy <= r; dy++) for (int dx = -r; dx <= r; dx++) {
            tot++;
            if (lum((int) x + dx, (int) y + dy) > 190) hit++;
        }
        return (double) hit / tot;
    }

    /* ---------- launch analysis ---------- */

    /**
     * smp rows: {t, strikerX, strikerY, coinX, coinY} in pixels (memory frame).
     * returns {launchAngle, speedPxPerSec, coinAngle, contactPx}, NaN where unknown.
     * coinTrueX/Y = the real (image) centre of the target coin, used for the contact distance.
     */
    static double[] analyze(List<double[]> smp, double tRel, double coinTrueX, double coinTrueY) {
        double nan = Double.NaN;
        double[] out = {nan, nan, nan, nan};
        double s = sc();
        int i0 = -1;
        for (int i = 0; i < smp.size(); i++) if (smp.get(i)[0] >= tRel) { i0 = i; break; }
        if (i0 < 0) return out;
        double[] a = smp.get(i0);
        for (int i = i0 + 1; i < smp.size(); i++) {
            double[] b = smp.get(i);
            double d = Math.hypot(b[1] - a[1], b[2] - a[2]);
            if (d >= 30 * s) {
                out[0] = Math.atan2(b[2] - a[2], b[1] - a[1]);
                double dt = b[0] - a[0];
                out[1] = dt > 0 ? d / dt : nan;
                break;
            }
        }
        int i1 = -1;
        for (int i = i0 + 1; i < smp.size(); i++) {
            if (Math.hypot(smp.get(i)[3] - a[3], smp.get(i)[4] - a[4]) >= 6 * s) { i1 = i; break; }
        }
        if (i1 > 0) {
            double[] p = smp.get(i1 - 1);
            for (int i = i1; i < smp.size(); i++) {
                double[] b = smp.get(i);
                if (Math.hypot(b[3] - p[3], b[4] - p[4]) >= 25 * s) {
                    out[2] = Math.atan2(b[4] - p[4], b[3] - p[3]);
                    break;
                }
            }
        }
        if (!Double.isNaN(out[0]) && !Double.isNaN(out[2])) {
            double ux = Math.cos(out[0]), uy = Math.sin(out[0]);
            double b = (coinTrueX - a[1]) * uy - (coinTrueY - a[2]) * ux;
            double d = out[2] - out[0];
            while (d > Math.PI) d -= 2 * Math.PI;
            while (d < -Math.PI) d += 2 * Math.PI;
            double sd = Math.sin(d);
            if (Math.abs(sd) > 0.12 && b * sd < 0) out[3] = Math.abs(b) / Math.abs(sd);
        }
        return out;
    }

    static double wrap(double d) {
        while (d > Math.PI) d -= 2 * Math.PI;
        while (d < -Math.PI) d += 2 * Math.PI;
        return d;
    }

    static String dbgArr(double[] a) { return Arrays.toString(a); }
}
