package com.termux.cloud;

/**
 * Who may drive a terminal session through CloudSessionService, and how a
 * session is named — pure, so it is tested without a device.
 *
 * THE SAME FILE, byte for byte, in ac_cloud-termux and ac_cloud-nix-on-droid
 * (ac_cloud-myterminal/test/test-terminal-session.sh fails when they drift):
 * the two terminals must refuse exactly the same callers.
 *
 * TWO LOCKS, NOT ONE. The manifest guards the service with a signature-level
 * permission, so the system refuses the bind for any APK not signed with the
 * fleet key before this class is reached. Every binder call is checked again
 * here anyway, permission AND signature, because the manifest is the one place
 * a careless edit (an `exported` without `permission`, a `normal` protection
 * level) opens a shell to every app on the phone, and that edit compiles,
 * installs and passes every other test. A shell is the most powerful thing
 * this app has; it is refused twice.
 */
public final class SessionGate {

    /** Declared (protectionLevel="signature") by both terminals AND MyTerminal, so whichever
     *  is installed first defines it and the grant never depends on install order. */
    public static final String PERMISSION = "com.diegonmarcos.cloud.permission.TERMINAL_SESSION";
    /** The bind action; the client sets the package from its declaration. */
    public static final String ACTION = "com.diegonmarcos.cloud.terminal.SESSION";
    /** ICloudSession.version(); bump when the AIDL grows (append only). */
    public static final int VERSION = 1;

    private static final int MAX_ID = 64;

    private SessionGate() {}

    /**
     * null when the caller may proceed, else the reason it may not. The env's
     * own uid always may (its own activity and services).
     */
    public static String refusal(int callingUid, int myUid, boolean permissionGranted, boolean signatureMatches) {
        if (callingUid == myUid) return null;
        if (!permissionGranted) return "uid " + callingUid + " does not hold " + PERMISSION;
        if (!signatureMatches) return "uid " + callingUid + " is not signed with this terminal's key";
        return null;
    }

    /**
     * The table key of a caller's session: ids are the CALLER's handles, so two
     * apps may both call theirs "p1" without reaching each other's shell. null
     * for an id that is empty, too long or not [A-Za-z0-9._-].
     */
    public static String key(int callingUid, String id) {
        if (id == null || id.isEmpty() || id.length() > MAX_ID) return null;
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                || c == '.' || c == '_' || c == '-';
            if (!ok) return null;
        }
        return callingUid + ":" + id;
    }

    /** A PTY size the kernel accepts; anything else becomes the 80x24 default. */
    public static int cols(int cols) { return cols > 0 && cols <= 1000 ? cols : 80; }
    public static int rows(int rows) { return rows > 0 && rows <= 1000 ? rows : 24; }
}
