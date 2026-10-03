package com.termux.shared.logger;

import java.io.PrintWriter;
import java.io.Writer;
import java.util.List;

/**
 * #832 stack traces formatted for logs and error dialogs, with a hard ceiling on memory.
 *
 * Throwable.printStackTrace writes every cause and every suppressed exception; an exception
 * carrying 10^5 suppressed ones (Guava's recursive delete over a read-only Nix store) became a
 * string larger than a 256 MB heap. Each trace is now cut at {@link #MAX_CHARS} and at most
 * {@link #MAX_TRACES} traces are formatted. Android-free so test/test-bootstrap-oom.sh runs it.
 */
public final class BoundedStackTrace {

    public static final int MAX_CHARS = 64 * 1024;
    public static final int MAX_TRACES = 16;
    public static final String TRUNCATED = "\n... [truncated: stack trace exceeded " + MAX_CHARS + " chars]";

    private BoundedStackTrace() {}

    /** Thrown inside printStackTrace once the cap is hit, to stop walking the rest of the chain. */
    private static final class Full extends RuntimeException {
        Full() { super(null, null, false, false); }
    }

    private static final class CappedWriter extends Writer {
        final StringBuilder sb = new StringBuilder();
        boolean truncated;
        @Override public void write(char[] c, int off, int len) {
            int room = MAX_CHARS - sb.length();
            if (len > room) { sb.append(c, off, Math.max(room, 0)); truncated = true; throw new Full(); }
            sb.append(c, off, len);
        }
        @Override public void flush() {}
        @Override public void close() {}
    }

    public static String of(Throwable throwable) {
        if (throwable == null) return null;
        CappedWriter w = new CappedWriter();
        PrintWriter pw = new PrintWriter(w);
        try {
            throwable.printStackTrace(pw);
            pw.flush();
        } catch (Full ignored) {
        } catch (OutOfMemoryError | RuntimeException e) {
            w.truncated = true;
        }
        return w.truncated ? w.sb + TRUNCATED : w.sb.toString();
    }

    public static String[] ofAll(List<Throwable> throwables) {
        if (throwables == null) return null;
        int n = Math.min(throwables.size(), MAX_TRACES);
        boolean more = throwables.size() > MAX_TRACES;
        String[] out = new String[more ? n + 1 : n];
        for (int i = 0; i < n; i++) out[i] = of(throwables.get(i));
        if (more) out[n] = "... " + (throwables.size() - MAX_TRACES) + " more stack traces not shown";
        return out;
    }
}
