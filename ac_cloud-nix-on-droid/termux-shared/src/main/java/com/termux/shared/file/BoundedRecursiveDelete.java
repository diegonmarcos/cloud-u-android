package com.termux.shared.file;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.io.File;

/**
 * #832 recursive delete that cannot fill the heap and that can delete a Nix store.
 *
 * Guava's MoreFiles.deleteRecursively (what FileUtils.deleteFile used on API 26+) attaches one
 * suppressed exception PER FILE it fails to delete. A Nix rootfs keeps its store directories
 * read-only (r-x), so on a re-extract every one of its ~10^5 entries failed with
 * AccessDeniedException, the resulting exception carried ~10^5 suppressed stack traces, and
 * formatting it for the bootstrap error dialog exhausted a 256 MB heap (A37, Android 16).
 *
 * Here: (1) every directory is made owner-writable before its entries are unlinked, so the
 * read-only store deletes; (2) the tree is walked with Files.walkFileTree, which streams each
 * directory, never follows symlinks and never lists a whole tree; (3) at most
 * {@link #MAX_FAILURES} failures are kept, the rest are only counted. Android-free on purpose:
 * test/test-bootstrap-oom.sh runs it on a plain JVM under a small -Xmx.
 */
public final class BoundedRecursiveDelete {

    public static final int MAX_FAILURES = 16;

    private BoundedRecursiveDelete() {}

    /** Deletes {@code root} and everything under it. Throws one IOException summarising failures. */
    public static void delete(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        final IOException[] first = {null};
        final long[] failures = {0};
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            void fail(IOException e) {
                failures[0]++;
                if (first[0] == null) first[0] = new IOException("deleting " + root + " failed");
                if (failures[0] <= MAX_FAILURES) first[0].addSuppressed(e);
            }

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                makeWritable(dir);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                deleteOne(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException e) {
                // An unreadable directory: make it accessible and try once more, else just unlink.
                if (makeWritable(file)) {
                    try {
                        delete(file);
                        return FileVisitResult.CONTINUE;
                    } catch (IOException again) {
                        e = again;
                    }
                }
                fail(e);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException e) {
                if (e != null) fail(e);
                deleteOne(dir);
                return FileVisitResult.CONTINUE;
            }

            private void deleteOne(Path p) {
                try {
                    Files.deleteIfExists(p);
                } catch (AccessDeniedException ade) {
                    Path parent = p.getParent();
                    if (parent != null && makeWritable(parent)) {
                        try { Files.deleteIfExists(p); return; } catch (IOException e2) { fail(e2); return; }
                    }
                    fail(ade);
                } catch (NoSuchFileException ignored) {
                } catch (IOException e) {
                    fail(e);
                }
            }
        });
        if (first[0] != null) {
            if (failures[0] > MAX_FAILURES)
                first[0].addSuppressed(new IOException((failures[0] - MAX_FAILURES) + " more failures not kept"));
            throw first[0];
        }
    }

    /** Deletes everything under {@code dir}, keeping {@code dir} itself. */
    public static void deleteContents(Path dir) throws IOException {
        makeWritable(dir);
        IOException first = null;
        try (java.nio.file.DirectoryStream<Path> entries = Files.newDirectoryStream(dir)) {
            for (Path p : entries) {
                try { delete(p); } catch (IOException e) { if (first == null) first = e; }
            }
        }
        if (first != null) throw first;
    }

    /** chmod u+rwx on a real directory (never through a symlink). Returns true if it is a directory. */
    static boolean makeWritable(Path dir) {
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) return false;
        File f = dir.toFile();
        f.setReadable(true, true);
        f.setWritable(true, true);
        f.setExecutable(true, true);
        return true;
    }
}
