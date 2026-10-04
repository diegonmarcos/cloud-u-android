package com.termux.app;

import com.termux.shared.file.BoundedRecursiveDelete;

import java.io.IOException;
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
