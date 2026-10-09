package com.termux.app;

import com.termux.shared.file.BoundedRecursiveDelete;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * #863 the file-system steps of a bootstrap re-extract, made safe to repeat.
 *
 * A37 crash: an interrupted extract left files in usr-staging; the next attempt's Os.symlink
 * hit EEXIST, and because $PREFIX had already been wiped up front, usr/ stayed empty and the
 * terminal was dead. Here: (1) staging is wiped completely before every extract and after a
 * failed one; (2) a symlink whose path already exists is accepted when it is the same link and
 * replaced otherwise; (3) $PREFIX is only replaced at the end, by two renames, so a failure at
 * any earlier point leaves the old $PREFIX (or none) and a retry starts clean; (4) the error
 * text shown for a failure is capped. Android-free on purpose: test/test-bootstrap-reextract.sh
 * runs it on a plain JVM.
 */
final class BootstrapStaging {

    /** Hard cap on the markdown a bootstrap failure may carry into the dialog/notification. */
    static final int MAX_ERROR_CHARS = 16 * 1024;
    static final String TRUNCATED = "\n\n... (truncated)";

    private BootstrapStaging() {}

    /** Removes staging and everything in it (read-only Nix dirs included), and any leftover old $PREFIX. */
    static void wipe(Path staging) throws IOException {
        BoundedRecursiveDelete.delete(staging);
    }

    // startup-step: wipe_stale_root
    /**
     * The nix terminal's port of ac_cloud-termux/rootfs/enter.sh::wipe_rootfs: empties {@code dir}
     * before a re-extract, read-only trees included (every directory is made writable first,
     * BoundedRecursiveDelete.deleteContents), carries on past an entry that will not go, and never
     * throws. Returns how many entries are left so the caller can say so in ONE line; the
     * directory itself is kept, since the extract recreates what it needs. A leftover is not
     * fatal here: whatever it blocks (a file the extract must overwrite, a swap that must move
     * usr-old) fails on its own, with its own message, where it matters. A start that died on
     * stale read-only leftovers is the termux terminal's 2026-10-07 failure; it is not repeated.
     * (The old $PREFIX's tmp needs no exemption as enter.sh's rootfs /tmp has: swap() discards it.)
     */
    static int wipeQuiet(Path dir) {
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) return 0;
        try { BoundedRecursiveDelete.deleteContents(dir); } catch (IOException | RuntimeException ignored) { /* counted below */ }
        try (DirectoryStream<Path> left = Files.newDirectoryStream(dir)) {
            int n = 0;
            for (Path ignored : left) n++;
            return n;
        } catch (IOException e) {
            return 1;
        }
    }

    /** Creates {@code link -> target}; an existing same link is kept, anything else at the path is replaced. */
    static void placeSymlink(String target, Path link) throws IOException {
        if (Files.isSymbolicLink(link)) {
            if (Files.readSymbolicLink(link).equals(Paths.get(target))) return;
            Files.delete(link);
        } else if (Files.exists(link, LinkOption.NOFOLLOW_LINKS)) {
            BoundedRecursiveDelete.delete(link);
        }
        Path parent = link.getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.createSymbolicLink(link, Paths.get(target));
    }

    /**
     * Replaces {@code prefix} with the fully extracted {@code staging}: prefix -> old, staging -> prefix,
     * then old is deleted. If the second rename fails the old prefix is put back.
     */
    static void swap(Path staging, Path prefix, Path old) throws IOException {
        BoundedRecursiveDelete.delete(old);
        boolean hadPrefix = Files.exists(prefix, LinkOption.NOFOLLOW_LINKS);
        if (hadPrefix) Files.move(prefix, old, StandardCopyOption.ATOMIC_MOVE);
        try {
            Files.move(staging, prefix, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            if (hadPrefix) {
                try { Files.move(old, prefix, StandardCopyOption.ATOMIC_MOVE); } catch (IOException r) { e.addSuppressed(r); }
            }
            throw e;
        }
        try { BoundedRecursiveDelete.delete(old); } catch (IOException ignored) { /* next install wipes it */ }
    }

    /** {@code s} cut to {@link #MAX_ERROR_CHARS}. */
    static String bound(String s) {
        if (s == null) return "null";
        return s.length() <= MAX_ERROR_CHARS ? s : s.substring(0, MAX_ERROR_CHARS) + TRUNCATED;
    }
}
