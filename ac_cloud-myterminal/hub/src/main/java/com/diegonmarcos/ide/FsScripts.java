package com.diegonmarcos.ide;

import java.nio.charset.StandardCharsets;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * The file browser's three operations as POSIX sh scripts, for the native
 * session path (ICloudSession.exec runs them through the env's login, script on
 * stdin, so the login shell — fish in both terminals — never parses them).
 * Pure Java: test/test-terminal-session.sh runs them under a real sh.
 *
 * Every path is single-quoted, so nothing in a file name is ever code; a
 * leading ~ is expanded by the script itself, which a quoted path cannot do.
 * File content travels base64 in a quoted heredoc, so no byte of it is
 * interpreted either.
 */
public final class FsScripts {

    private FsScripts() {}

    /** 'text' with every ' closed, escaped and reopened. */
    public static String quote(String s) {
        return "'" + (s == null ? "" : s.replace("'", "'\\''")) + "'";
    }

    private static String enter(String path) {
        return "p=" + quote(path) + "\n"
            + "case \"$p\" in \"~\") p=\"$HOME\" ;; \"~/\"*) p=\"$HOME/${p#\"~/\"}\" ;; esac\n";
    }

    /** One line per entry: name TAB 1|0 (1 = directory); no . or .. */
    public static String list(String path) {
        return enter(path)
            + "cd -- \"$p\" || exit 1\n"
            + "for e in .* *; do\n"
            + "  [ -e \"$e\" ] || [ -L \"$e\" ] || continue\n"
            + "  case \"$e\" in .|..) continue ;; esac\n"
            + "  if [ -d \"$e\" ]; then printf '%s\\t1\\n' \"$e\"; else printf '%s\\t0\\n' \"$e\"; fi\n"
            + "done\n";
    }

    public static String read(String path) {
        return enter(path) + "cat -- \"$p\"\n";
    }

    public static String write(String path, String content) {
        String b64 = Base64.getMimeEncoder(76, "\n".getBytes(StandardCharsets.US_ASCII))
            .encodeToString((content == null ? "" : content).getBytes(StandardCharsets.UTF_8));
        return enter(path) + "base64 -d > \"$p\" <<'CLOUD_FS_EOF'\n" + b64 + "\nCLOUD_FS_EOF\n";
    }

    /** [list]'s output as (name, isDir), in the order printed. */
    public static List<Map.Entry<String, Boolean>> parseList(String out) {
        List<Map.Entry<String, Boolean>> entries = new ArrayList<>();
        if (out == null) return entries;
        for (String line : out.split("\n")) {
            int tab = line.lastIndexOf('\t');
            if (tab <= 0) continue;
            String name = line.substring(0, tab);
            if (name.equals(".") || name.equals("..")) continue;
            entries.add(new AbstractMap.SimpleEntry<>(name, "1".equals(line.substring(tab + 1).trim())));
        }
        return entries;
    }
}
