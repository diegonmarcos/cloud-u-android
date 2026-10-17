package com.termux.cloud;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.zip.GZIPInputStream;

/**
 * cloud-shared-store, the pure half (no Android): where a provider path lands inside the store,
 * and the one-time restore of a terminal's $HOME from the backup tarball after the sharedUserId
 * reinstall. Plain JVM, so test/test-shared-store.sh compiles and runs THIS file. The SAME file
 * (package line aside) is in cld.termux and cld.termux.nix; that tester fails when they drift.
 */
public final class SharedStoreFiles {

    private SharedStoreFiles() {}

    /**
     * [rel] (a provider path, '/'-separated, may be empty for the root) resolved under [root], or
     * null when it would leave the store: "..", or a symlink inside the store pointing outside it.
     */
    public static File resolve(File root, String rel) throws IOException {
        String r = rel == null ? "" : rel;
        while (r.startsWith("/")) r = r.substring(1);
        for (String seg : r.split("/")) if (seg.equals("..")) return null;
        File base = root.getCanonicalFile();
        File f = r.isEmpty() ? base : new File(base, r);
        File parent = f.getParentFile() == null ? f : f.getParentFile();
        // A file that does not exist yet (a write) is judged by its parent.
        File judged = (f.exists() ? f : parent).getCanonicalFile();
        String b = base.getPath();
        if (!(judged.getPath().equals(b) || judged.getPath().startsWith(b + File.separator))) return null;
        return f;
    }

    /**
     * Restore a ustar/GNU tar.gz into [home]. Files already present are kept unless the archive's
     * copy is newer. Entries that would leave [home] are refused. Returns the number of files written.
     */
    public static int restoreTarGz(InputStream in, File home) throws IOException {
        File base = home.getCanonicalFile();
        int written = 0;
        String longName = null, longLink = null;
        try (InputStream z = new BufferedInputStream(new GZIPInputStream(in))) {
            byte[] h = new byte[512];
            while (readFully(z, h)) {
                if (isZero(h)) break;
                String name = longName != null ? longName : header(h);
                String link = longLink != null ? longLink : str(h, 157, 100);
                longName = null; longLink = null;
                long size = octal(h, 124, 12);
                long mtime = octal(h, 136, 12);
                int mode = (int) octal(h, 100, 8);
                char type = (char) h[156];
                if (type == 'L' || type == 'K') {
                    byte[] data = readData(z, size);
                    String v = new String(data, StandardCharsets.UTF_8);
                    int nul = v.indexOf('\0'); if (nul >= 0) v = v.substring(0, nul);
                    if (type == 'L') longName = v; else longLink = v;
                    continue;
                }
                while (name.startsWith("./")) name = name.substring(2);
                File dest = name.isEmpty() ? base : new File(base, name);
                String dp = dest.getCanonicalFile().getParentFile() == null ? "" : dest.getCanonicalFile().getParent();
                boolean inside = dest.getCanonicalPath().equals(base.getPath()) || dest.getCanonicalPath().startsWith(base.getPath() + File.separator)
                    || (type == '2' && (dp.equals(base.getPath()) || dp.startsWith(base.getPath() + File.separator)));
                if (!inside || name.contains("../")) { skip(z, pad(size)); continue; }
                if (type == '5') {
                    dest.mkdirs();
                } else if (type == '2') {
                    if (!dest.exists() && !Files.isSymbolicLink(dest.toPath())) {
                        dest.getParentFile().mkdirs();
                        Files.createSymbolicLink(dest.toPath(), Paths.get(link));
                        written++;
                    }
                } else if (type == '0' || type == '\0' || type == '7') {
                    if (dest.exists() && dest.lastModified() / 1000 >= mtime) { skip(z, pad(size)); continue; }
                    dest.getParentFile().mkdirs();
                    try (OutputStream o = new FileOutputStream(dest)) { copy(z, o, size); }
                    skip(z, pad(size) - size);
                    dest.setLastModified(mtime * 1000);
                    dest.setExecutable((mode & 0100) != 0, true);
                    written++;
                } else {
                    skip(z, pad(size));
                }
            }
        }
        return written;
    }

    private static String header(byte[] h) {
        String name = str(h, 0, 100);
        String magic = str(h, 257, 6);
        if (magic.startsWith("ustar")) {
            String prefix = str(h, 345, 155);
            if (!prefix.isEmpty()) name = prefix + "/" + name;
        }
        return name;
    }

    private static String str(byte[] b, int off, int len) {
        int end = off;
        while (end < off + len && b[end] != 0) end++;
        return new String(b, off, end - off, StandardCharsets.UTF_8);
    }

    private static long octal(byte[] b, int off, int len) {
        long v = 0;
        for (int i = off; i < off + len; i++) {
            int c = b[i];
            if (c == 0 || c == ' ') { if (v != 0) break; else continue; }
            if (c < '0' || c > '7') break;
            v = v * 8 + (c - '0');
        }
        return v;
    }

    private static boolean isZero(byte[] b) { for (byte x : b) if (x != 0) return false; return true; }

    private static long pad(long size) { return (size + 511) / 512 * 512; }

    private static boolean readFully(InputStream in, byte[] b) throws IOException {
        int n = 0;
        while (n < b.length) { int r = in.read(b, n, b.length - n); if (r < 0) return false; n += r; }
        return true;
    }

    private static byte[] readData(InputStream in, long size) throws IOException {
        byte[] d = new byte[(int) size];
        if (!readFully(in, d)) throw new IOException("truncated tar");
        skip(in, pad(size) - size);
        return d;
    }

    private static void copy(InputStream in, OutputStream out, long size) throws IOException {
        byte[] buf = new byte[65536];
        long left = size;
        while (left > 0) {
            int r = in.read(buf, 0, (int) Math.min(buf.length, left));
            if (r < 0) throw new IOException("truncated tar");
            out.write(buf, 0, r); left -= r;
        }
    }

    private static void skip(InputStream in, long n) throws IOException {
        long left = n;
        while (left > 0) { long s = in.skip(left); if (s <= 0) { if (in.read() < 0) throw new IOException("truncated tar"); s = 1; } left -= s; }
    }
}
