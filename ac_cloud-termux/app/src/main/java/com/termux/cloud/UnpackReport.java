package com.termux.cloud;

/**
 * #1121 The vocabulary of the self-unpack, pure so it is tested without a device.
 *
 * THE SAME FILE, byte for byte, in ac_cloud-termux and ac_cloud-nix-on-droid
 * (test/test-unpack-after-update.sh fails when they drift).
 *
 * An update of either terminal must leave its bundled files unpacked without anybody opening
 * the app: UnpackReceiver (MY_PACKAGE_REPLACED) starts UnpackService, a short foreground
 * service with no UI, and Cloud Store starts the same service explicitly after it installs the
 * package. The outcome is written to files/{@link #FILE} and, when the starter passed a
 * ResultReceiver, handed back to it.
 */
public final class UnpackReport {

    /** The explicit start action; the Store names it, the fleet IPC registry lists it. */
    public static final String ACTION = "com.diegonmarcos.cloud.action.UNPACK";
    /** The ResultReceiver a starter passes to hear the outcome. */
    public static final String EXTRA_RECEIVER = "result_receiver";
    /** Written under the app's files/ directory. */
    public static final String FILE = "unpack-result.json";
    public static final int RESULT_OK = 0;
    public static final int RESULT_FAILED = 1;

    private UnpackReport() {}

    /** Only the system's own "this package was just replaced" and the fleet's explicit start run an unpack. */
    public static boolean runsOn(String action) {
        return "android.intent.action.MY_PACKAGE_REPLACED".equals(action) || ACTION.equals(action);
    }

    /** {"ok":true,"package":"...","at":123} or {"ok":false,...,"error":"..."}; error null = success. */
    public static String json(String pkg, String error, long at) {
        StringBuilder b = new StringBuilder("{\"ok\":").append(error == null)
            .append(",\"package\":\"").append(esc(pkg)).append("\",\"at\":").append(at);
        if (error != null) b.append(",\"error\":\"").append(esc(error)).append('"');
        return b.append('}').toString();
    }

    /** True when {@link #json} was written for a success. */
    public static boolean isOk(String json) {
        return json != null && json.startsWith("{\"ok\":true");
    }

    private static String esc(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : (s == null ? "" : s).toCharArray()) {
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c == '\n') b.append("\\n");
            else if (c == '\r') b.append("\\r");
            else if (c == '\t') b.append("\\t");
            else if (c < 0x20) b.append(' ');
            else b.append(c);
        }
        return b.toString();
    }
}
