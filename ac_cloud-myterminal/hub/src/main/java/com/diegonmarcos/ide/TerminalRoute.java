package com.diegonmarcos.ide;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * How MyTerminal reaches a fleet terminal, decided without a device: which
 * backend to use when none was chosen, and what to tell the owner when the
 * zero-setup path did not work. Plain Java with no Android import, so
 * test/test-terminal-session.sh compiles and runs it with javac alone.
 *
 * THE ORDER OF ATTEMPTS. A declared backend is reached through its app's
 * signature-guarded session service (ICloudSession): nothing to install,
 * start or authorise. Loopback SSH is only the fallback for a terminal build
 * that predates that service, and the manual sshd steps are shown only when
 * both failed — with the reason, never as the first thing a new phone sees.
 */
public final class TerminalRoute {

    /** Why a terminal is not reachable; OK when it is. Ordered by what to fix first. */
    public enum Reason {
        OK,
        /** The env app is not installed at all. */
        NOT_INSTALLED,
        /** Installed, but this build has no session service (or answers version 0). */
        TOO_OLD,
        /** The system refused the bind or a call: not signed with the fleet key. */
        PERMISSION_MISSING,
        /** Bound, but the env could not bootstrap or start a shell. */
        SESSION_FAILED,
        /** SSH fallback: nothing listens on the port. */
        SSHD_NOT_RUNNING,
        /** SSH fallback: sshd answered and refused our key. */
        KEY_NOT_AUTHORIZED,
        /** SSH fallback: any other failure (timeout, host key, protocol). */
        SSH_FAILED,
    }

    private TerminalRoute() {}

    /**
     * The backend to use. A stored choice that is still declared wins, so a
     * deliberate pick is never second-guessed while that terminal works. With
     * no stored choice (a fresh install): the build default if its app has the
     * session service, else the first declared backend that has it, else the
     * first one installed at all, else the build default.
     */
    public static String pickBackend(List<String> declared, Set<String> withSession,
                                     Set<String> installed, String stored, String buildDefault) {
        if (stored != null && declared.contains(stored)) return stored;
        if (buildDefault != null && withSession.contains(buildDefault)) return buildDefault;
        for (String k : declared) if (withSession.contains(k)) return k;
        if (buildDefault != null && installed.contains(buildDefault)) return buildDefault;
        for (String k : declared) if (installed.contains(k)) return k;
        if (buildDefault != null && declared.contains(buildDefault)) return buildDefault;
        return declared.isEmpty() ? buildDefault : declared.get(0);
    }

    /** What a session-service probe found, before any SSH fallback. */
    public static Reason sessionReason(boolean installed, boolean bound, boolean securityRefused,
                                       int version, String sessionError) {
        if (!installed) return Reason.NOT_INSTALLED;
        if (securityRefused) return Reason.PERMISSION_MISSING;
        if (!bound || version < 1) return Reason.TOO_OLD;
        if (sessionError != null && !sessionError.isEmpty()) return Reason.SESSION_FAILED;
        return Reason.OK;
    }

    /** A JSch / socket failure message, classified. null or empty means it worked. */
    public static Reason sshReason(String error) {
        if (error == null || error.isEmpty()) return Reason.OK;
        String e = error.toLowerCase(Locale.ROOT);
        if (e.contains("econnrefused") || e.contains("connection refused")) return Reason.SSHD_NOT_RUNNING;
        if (e.contains("auth fail") || e.contains("auth cancel") || e.contains("permission denied")
            || e.contains("publickey")) return Reason.KEY_NOT_AUTHORIZED;
        return Reason.SSH_FAILED;
    }

    /**
     * True when the SSH fallback is worth trying after [session]: only for a
     * terminal that is installed but predates the session service. A missing
     * app has no sshd either, and a refused signature is not something SSH
     * should paper over.
     */
    public static boolean trySshAfter(Reason session) {
        return session == Reason.TOO_OLD;
    }

    /**
     * One line for the owner: what is wrong, where, and the one thing to do.
     * [ssh] is the fallback's verdict (OK when it was not tried), [detail] the
     * raw error text worth quoting.
     */
    public static String explain(Reason session, Reason ssh, String label, String host, int port, String detail) {
        String where = label + " (" + host + ":" + port + ")";
        String d = detail == null || detail.isEmpty() ? "" : " — " + detail;
        switch (session) {
            case OK:
                return where + ": connected";
            case NOT_INSTALLED:
                return label + " is not installed — install it from the Store; MyTerminal connects to it on its own";
            case PERMISSION_MISSING:
                return label + " refused MyTerminal: the two are not signed with the same fleet key" + d;
            case SESSION_FAILED:
                return label + " is installed but could not start a shell" + d;
            case TOO_OLD:
            default:
                String old = label + " is too old for zero-setup sessions — update it from the Store";
                switch (ssh) {
                    case OK: return old + "; connected over the SSH fallback " + host + ":" + port;
                    case SSHD_NOT_RUNNING: return old + ". SSH fallback " + where + ": sshd not running" + d;
                    case KEY_NOT_AUTHORIZED: return old + ". SSH fallback " + where + ": key not authorized" + d;
                    default: return old + ". SSH fallback " + where + " failed" + d;
                }
        }
    }
}
