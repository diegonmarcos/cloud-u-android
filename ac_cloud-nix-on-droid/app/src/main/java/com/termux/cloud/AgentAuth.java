package com.termux.cloud;

import android.content.Context;
import android.system.Os;

import com.termux.BuildConfig;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * #790 — the agent CLIs (claude, goose, hermes) come up logged in from the fleet Account, not
 * from a /login typed into this phone.
 *
 * A rootfs update on 2026-10-03 left both terminals answering "Not logged in · Please run /login":
 * the credentials existed only as files claude had written into $HOME, so the one copy was the
 * phone's, and nothing could put it back. Now the copy that counts is the Account's: Configs ▸
 * Account imports {@link #STORE} (libs:core fleet-config.json, class secret) over the #783
 * FleetConfig contract from the declared profile, and an import restarts this app — so this runs
 * from TermuxApplication.onCreate on every start and turns the store into
 * $HOME/{@link BuildConfig#CLOUD_AGENT_AUTH_ENV}, which login-init.sh sources into every session.
 * A fresh install, a re-unpack and a new phone are therefore one Account apply away from logged in.
 *
 * The store's keys ARE the environment names (CLAUDE_CODE_OAUTH_TOKEN, OPENROUTER_API_KEY); this
 * class knows none of them. The file is created 0600 in a 0700 directory before a byte is written,
 * renamed into place, rewritten only when it would change, and deleted when the store is empty.
 * No value is ever logged. Byte-identical in ac_cloud-termux and ac_cloud-nix-on-droid.
 */
public final class AgentAuth {

    private static final String LOG_TAG = "AgentAuth";

    /** The libs:core fleet-config.json store Account imports into (fleet-config-guard reads this literal). */
    static final String STORE = "agent-auth";

    private static final Pattern NAME = Pattern.compile("[A-Z_][A-Z0-9_]*");

    private AgentAuth() {}

    /** Writes (or removes) the credentials file. Never throws: a terminal still opens logged out. */
    public static void provision(Context context) {
        File file = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, BuildConfig.CLOUD_AGENT_AUTH_ENV);
        try {
            String body = render(context.getSharedPreferences(STORE, Context.MODE_PRIVATE).getAll());
            if (body == null) {
                if (file.delete()) Logger.logInfo(LOG_TAG, "No agent credentials declared; removed " + file);
                return;
            }
            if (file.isFile() && body.equals(read(file))) return;
            File dir = file.getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
            //noinspection OctalInteger
            if (dir != null) Os.chmod(dir.getAbsolutePath(), 0700);
            File tmp = new File(dir, file.getName() + ".new");
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            // Empty and 0600 first, so the values never sit in a file anyone else could open.
            new FileOutputStream(tmp).close();
            //noinspection OctalInteger
            Os.chmod(tmp.getAbsolutePath(), 0600);
            try (OutputStream out = new FileOutputStream(tmp)) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }
            Os.rename(tmp.getAbsolutePath(), file.getAbsolutePath());
            Logger.logInfo(LOG_TAG, "Wrote the agent credentials to " + file);
        } catch (Exception e) {
            // The class name only: an exception message could quote a path or a value.
            Logger.logError(LOG_TAG, "Could not write " + file + ": " + e.getClass().getSimpleName());
        }
    }

    /**
     * The file's text: one {@code export NAME='value'} per non-blank string whose key is a valid
     * environment name, sorted, single-quoted (a quote inside is closed, escaped and reopened, so no
     * value can run as shell). Null when nothing qualifies.
     */
    static String render(Map<String, ?> values) {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, ?> e : new TreeMap<>(values).entrySet()) {
            if (!(e.getValue() instanceof String) || !NAME.matcher(e.getKey()).matches()) continue;
            String v = ((String) e.getValue()).trim();
            if (v.isEmpty()) continue;
            out.append("export ").append(e.getKey()).append("='").append(v.replace("'", "'\\''")).append("'\n");
        }
        return out.length() == 0 ? null
            : "# #790 written by this terminal from its agent-auth store (Configs > Account). Do not edit.\n" + out;
    }

    private static String read(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            byte[] bytes = new byte[(int) file.length()];
            int n = 0;
            while (n < bytes.length) {
                int r = in.read(bytes, n, bytes.length - n);
                if (r < 0) break;
                n += r;
            }
            return new String(bytes, 0, n, StandardCharsets.UTF_8);
        }
    }
}
