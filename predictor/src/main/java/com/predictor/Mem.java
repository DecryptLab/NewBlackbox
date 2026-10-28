package com.predictor;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;

public class Mem {

    public static class Region {
        public long start, end;
        public String perms = "", path = "";
        public boolean rw()   { return perms.length() >= 2 && perms.charAt(0) == 'r' && perms.charAt(1) == 'w'; }
        public boolean read() { return perms.length() >= 1 && perms.charAt(0) == 'r'; }
        public long size()    { return end - start; }
    }

    private static RandomAccessFile raf;
    private static FileChannel ch;

    public static synchronized boolean open() {
        if (ch != null) return true;
        try {
            raf = new RandomAccessFile("/proc/self/mem", "r");
            ch = raf.getChannel();
            return true;
        } catch (Throwable t) { return false; }
    }

    public static synchronized byte[] read(long addr, int len) {
        if (ch == null || len <= 0) return null;
        try {
            ByteBuffer bb = ByteBuffer.allocate(len);
            ch.position(addr);
            int total = 0;
            while (total < len) {
                int n = ch.read(bb);
                if (n <= 0) break;
                total += n;
            }
            if (total <= 0) return null;
            byte[] out = new byte[total];
            bb.flip();
            bb.get(out);
            return out;
        } catch (Throwable t) { return null; }
    }

    public static long u32(byte[] b, int off) {
        return (b[off] & 0xffL) | ((b[off + 1] & 0xffL) << 8)
             | ((b[off + 2] & 0xffL) << 16) | ((b[off + 3] & 0xffL) << 24);
    }

    public static long readLong(long addr) {
        byte[] b = read(addr, 8);
        if (b == null || b.length < 8) return 0;
        long v = 0;
        for (int i = 7; i >= 0; i--) v = (v << 8) | (b[i] & 0xffL);
        return v;
    }

    public static String readCstr(long addr, int max) {
        byte[] b = read(addr, max);
        if (b == null) return "";
        int n = 0;
        while (n < b.length && b[n] != 0) n++;
        return new String(b, 0, n);
    }

    public static List<Region> regions() {
        List<Region> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/self/maps"))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.split("\\s+", 6);
                if (p.length < 5) continue;
                int d = p[0].indexOf('-');
                if (d < 0) continue;
                Region g = new Region();
                g.start = hex(p[0].substring(0, d));
                g.end = hex(p[0].substring(d + 1));
                g.perms = p[1];
                g.path = p.length >= 6 ? p[5] : "";
                out.add(g);
            }
        } catch (Throwable t) { }
        return out;
    }

    private static long hex(String s) {
        long v = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int d = (c <= '9') ? (c - '0') : ((c | 32) - 'a' + 10);
            if (d < 0 || d > 15) break;
            v = (v << 4) | d;
        }
        return v;
    }

    public static List<Long> scan(long start, long end, long value, int maxHits) {
        List<Long> hits = new ArrayList<>();
        if (maxHits <= 0) return hits;
        final int CHUNK = 1 << 20;
        long p = start;
        while (p + 8 <= end && hits.size() < maxHits) {
            int len = (int) Math.min((long) CHUNK, end - p);
            byte[] buf = read(p, len);
            if (buf == null || buf.length < 8) { p += len; continue; }
            int limit = buf.length - 8;
            for (int i = 0; i <= limit; i += 8) {
                long v = 0;
                for (int k = 7; k >= 0; k--) v = (v << 8) | (buf[i + k] & 0xffL);
                if (v == value) {
                    hits.add(p + i);
                    if (hits.size() >= maxHits) break;
                }
            }
            p += len;
        }
        return hits;
    }
}
