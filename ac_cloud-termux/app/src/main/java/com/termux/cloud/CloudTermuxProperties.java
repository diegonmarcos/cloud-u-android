package com.termux.cloud;

import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * #620 — make {@code allow-external-apps=true} true of the DEVICE, not of a
 * fresh install.
 *
 * RunCommandService refuses every RUN_COMMAND intent unless
 * ~/.termux/termux.properties sets that property (RunCommandService.java, the
 * PROP_ALLOW_EXTERNAL_APPS check), and the boot runner arrives as exactly such
 * an intent — so every reboot logged "requires allow-external-apps=true in
 * ~/.termux/termux.properties" and ran nothing.
 *
 * Nothing in this repository ever WROTE that property: the app only read it.
 * The file is created once, at first setup, and an app update never rewrites
 * it, so on a phone that was already set up the property was absent forever —
 * the same shape as #605 and the reason #198/#436 were inert: a fix hung off
 * first-run setup never reaches an installed phone.
 *
 * So this runs from TermuxApplication.onCreate, on EVERY launch and every boot,
 * BEFORE TermuxAppSharedProperties reads the file and therefore before any
 * service in this process can consult the cached value. It is idempotent: the
 * line is appended only when absent, rewritten only when present with another
 * value, and every other property in the file is preserved. The directory and
 * the file are created when missing, so it also covers a device on which setup
 * has not run yet.
 */
public final class CloudTermuxProperties {

    private static final String LOG_TAG = "CloudTermuxProperties";

    /** The property RunCommandService gates on, and the only value that opens that gate. */
    private static final String KEY = TermuxConstants.PROP_ALLOW_EXTERNAL_APPS;
    private static final String VALUE = "true";
    private static final String LINE = KEY + "=" + VALUE;

    private CloudTermuxProperties() {}

    /** Ensures {@code allow-external-apps=true} is set. Never throws: a terminal still opens without it. */
    public static void ensureAllowExternalApps() {
        File file = TermuxConstants.TERMUX_PROPERTIES_PRIMARY_FILE;
        try {
            File dir = file.getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
                Logger.logError(LOG_TAG, "Cannot create " + dir + ": " + KEY + " stays unset");
                return;
            }

            String existing = file.isFile() ? read(new FileInputStream(file)) : "";
            String updated = withAllowExternalApps(existing);
            if (updated == null) return; // already true — do not rewrite the file

            try (OutputStream out = new FileOutputStream(file)) {
                out.write(updated.getBytes(StandardCharsets.UTF_8));
            }
            Logger.logInfo(LOG_TAG, "Set " + LINE + " in " + file);
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Could not set " + LINE + " in " + file, e);
        }
    }

    /**
     * The merge, kept pure so it is readable as one rule: returns the new file
     * contents, or null when the property is already {@code true} and nothing
     * needs writing. Comments, blank lines and every other property survive.
     */
    static String withAllowExternalApps(String contents) {
        String[] lines = contents.split("\n", -1);
        boolean replaced = false;
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if (trimmed.startsWith("#") || trimmed.startsWith("!")) continue;
            int eq = trimmed.indexOf('=');
            if (eq < 0) continue;
            if (!KEY.equals(trimmed.substring(0, eq).trim())) continue;
            if (VALUE.equals(trimmed.substring(eq + 1).trim()) && !replaced) return null;
            lines[i] = LINE;
            replaced = true;
        }
        if (replaced) return join(lines);

        StringBuilder appended = new StringBuilder(contents);
        if (appended.length() > 0 && appended.charAt(appended.length() - 1) != '\n') appended.append('\n');
        return appended.append(LINE).append('\n').toString();
    }

    private static String join(String[] lines) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) out.append('\n');
            out.append(lines[i]);
        }
        return out.toString();
    }

    private static String read(InputStream stream) throws IOException {
        try (InputStream in = stream) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
