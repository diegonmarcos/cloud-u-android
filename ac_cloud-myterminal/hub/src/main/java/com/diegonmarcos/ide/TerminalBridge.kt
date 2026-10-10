package com.diegonmarcos.ide

import android.app.Activity
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * JavascriptInterface named "AndroidTerm" — bridges the my-konsole frontend's
 * `window.Transport` shim to the selected fleet terminal: natively through
 * [TerminalSessions] (the env's signature-guarded session service, zero setup),
 * or through [SshBackend] only for a terminal build that predates it.
 *
 * Threading model:
 *   - All @JavascriptInterface methods are called from a background WebView thread.
 *   - SSH work runs on [executor] (separate pool) so the WebView thread never blocks.
 *   - Callbacks to JS use [webView.post { webView.evaluateJavascript(...) }] —
 *     evaluateJavascript must be called on the UI thread; webView.post() delivers there.
 *
 * JS contract (what the Transport shim expects):
 *   - window.__aptyData(id, dataString)   — streamed pty output
 *   - window.__aptyExit(id)               — pty channel closed
 *   - window.__afsResult(rid, ok, json)   — fs call resolved/rejected
 *
 * @param backendKey lambda so the active backend is read fresh at call time
 *   (the user may change it in Configs while a session is open).
 */
class TerminalBridge(
    private val activity:   Activity,
    private val webView:    WebView,
    private val ssh:        SshBackend,
    private val backendKey: () -> String,
) {

    private val executor = Executors.newCachedThreadPool()

    /** Open the native Configs screen (terminal backend switcher, connection
     *  editor + test, SSH key, setup steps) — the frontend "Configs" button
     *  calls this so everything lives in one scrollable place. */
    @JavascriptInterface
    fun openConfigs() {
        activity.runOnUiThread {
            activity.startActivity(android.content.Intent(activity, ConfigsActivity::class.java))
        }
    }

    /** Open the Dev Control screen (logcat viewer, copy/export, local dev API). */
    @JavascriptInterface
    fun openDevControl() {
        activity.runOnUiThread {
            activity.startActivity(android.content.Intent(activity, DevControlActivity::class.java))
        }
    }

    /** Open the native browser overlay for [url] — bypasses X-Frame-Options. */
    @JavascriptInterface
    fun browserOpen(url: String) {
        activity.runOnUiThread { (activity as? MainActivity)?.openBrowser(url) }
    }

    // ── Terminal selector (frontend Configs overlay) ──────────────────────────

    /** The choices for the Configs "Terminal" field: EVERY declared backend in
     *  declared order plus the selected key. The options are never listed in
     *  JS or here — a third env in terminal-targets.json appears on its own. */
    @JavascriptInterface
    fun terminals(): String {
        val arr = JSONArray()
        TerminalTargets.all().forEach { t ->
            arr.put(JSONObject().put("key", t.key).put("label", t.label)
                .put("host", t.host).put("port", t.port))
        }
        return JSONObject().put("selected", TerminalTargets.forBackend(backendKey()).key)
            .put("backends", arr).toString()
    }

    /** Select [key] as the terminal every pty and fs call routes through, then
     *  PROBE it and report back via window.__termProbe(key, err|null). A key
     *  the JSON does not declare is refused rather than silently falling back,
     *  and an unreachable terminal (not installed, sshd not running, key not
     *  authorised) comes back as the named error instead of a dead tab. */
    @JavascriptInterface
    fun selectTerminal(key: String) {
        if (TerminalTargets.all().none { it.key == key }) {
            emitRaw("window.__termProbe(${q(key)},${q("'$key' is not a declared terminal")})")
            return
        }
        IdePrefs.setTerminalBackend(activity, key)
        probeTerminal()
    }

    /** Probe the SELECTED terminal; result via window.__termProbe. Native session first
     *  (TerminalSessions.probe binds the env's service; a never-opened terminal bootstraps
     *  itself), the SSH fallback only for a build without it — null when either works, else
     *  the precise reason naming t.label and its host:port. */
    @JavascriptInterface
    fun probeTerminal() {
        executor.submit {
            val t = TerminalTargets.effectiveTarget(activity, backendKey())
            val p = TerminalSessions.probe(activity, ssh, t)
            val err = if (p.usable) null else p.message
            emitRaw("window.__termProbe(${q(t.key)},${if (err == null) "null" else q(err)})")
        }
    }

    // ── PTY ───────────────────────────────────────────────────────────────────

    @JavascriptInterface
    fun ptyStart(id: String, cols: Int, rows: Int, cwd: String) {
        executor.submit {
            val target = TerminalTargets.effectiveTarget(activity, backendKey())
            val onData = { data: String -> emitRaw("window.__aptyData(${q(id)},${q(data)})") }
            val onExit = { emitRaw("window.__aptyExit(${q(id)})") }
            try {
                // Zero-setup path: the env's own login shell over ICloudSession.
                val refused = TerminalSessions.open(activity, ssh, target, id, cols, rows, onData, onExit)
                if (refused == null) {
                    if (cwd.isNotEmpty()) TerminalSessions.write(id, "cd '${cwd.replace("'", "'\\''")}'\n")
                    return@submit
                }
                // Not natively reachable and no SSH fallback worth trying: say exactly why.
                if (!TerminalRoute.trySshAfter(refused.session)) throw TerminalSessions.Unreachable(refused.message)
                ssh.openShell(
                    target  = target,
                    id      = id,
                    cols    = cols,
                    rows    = rows,
                    cwd     = cwd,
                    onData  = onData,
                    onExit  = onExit,
                )
            } catch (e: Exception) {
                // Surface the error inline in the terminal — red ANSI error line, naming the
                // terminal and, for the SSH fallback, the classified reason.
                val why = if (e is TerminalSessions.Unreachable) e.message ?: "" else TerminalRoute.explain(
                    TerminalRoute.Reason.TOO_OLD, TerminalRoute.sshReason(e.message ?: e.toString()),
                    target.label, target.host, target.port, e.message)
                val msg = "\r\n\u001b[31m⚠ ${target.label} " +
                    "(${target.host}:${target.port}): $why\u001b[0m\r\n" +
                    "\u001b[2m  Configs ▸ Terminal shows the setup for this case.\u001b[0m\r\n"
                emitRaw("window.__aptyData(${q(id)},${q(msg)})")
            }
        }
    }

    @JavascriptInterface
    fun ptyWrite(id: String, data: String) {
        executor.submit { if (TerminalSessions.owns(id)) TerminalSessions.write(id, data) else ssh.write(id, data) }
    }

    @JavascriptInterface
    fun ptyResize(id: String, cols: Int, rows: Int) {
        executor.submit { if (TerminalSessions.owns(id)) TerminalSessions.resize(id, cols, rows) else ssh.resize(id, cols, rows) }
    }

    @JavascriptInterface
    fun ptyKill(id: String) {
        executor.submit { if (TerminalSessions.owns(id)) TerminalSessions.kill(activity, id) else ssh.kill(id) }
    }

    // ── Clipboard + links (the pane menu: Copy All / Copy Last / Paste / URLs) ──

    /** Put [text] on the system clipboard; the WebView's navigator.clipboard may be refused
     *  on a file:// page, this never is. */
    @JavascriptInterface
    fun clipboardSet(text: String) {
        activity.runOnUiThread {
            val cm = activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("terminal", text))
        }
    }

    /** Whether the clipboard holds anything, asked WITHOUT reading it (no "pasted from your
     *  clipboard" notice): the pane menu greys Paste out on an empty clipboard. */
    @JavascriptInterface
    fun clipboardHas(): Boolean {
        val cm = activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        return cm.hasPrimaryClip()
    }

    /** The clipboard's text, "" when it holds none. Read only when the user pastes. */
    @JavascriptInterface
    fun clipboardGet(): String {
        val cm = activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = cm.primaryClip ?: return ""
        if (clip.itemCount == 0) return ""
        return clip.getItemAt(0).coerceToText(activity)?.toString() ?: ""
    }

    /** Open [url] in Cloud Browser when it is installed, else in the phone's default browser. */
    @JavascriptInterface
    fun openUrl(url: String) {
        activity.runOnUiThread { LinkOpener.open(activity, url) }
    }

    // ── File system ───────────────────────────────────────────────────────────

    @JavascriptInterface
    fun listDir(rid: Int, path: String) {
        executor.submit {
            val target = TerminalTargets.effectiveTarget(activity, backendKey())
            try {
                val entries = if (TerminalSessions.native(activity, target))
                    FsScripts.parseList(TerminalSessions.exec(activity, target, FsScripts.list(path)))
                        .map { it.key to it.value }
                else ssh.listDir(target, path)
                val arr = JSONArray()
                entries.forEach { (name, isDir) ->
                    arr.put(JSONObject().apply {
                        put("name",   name)
                        put("is_dir", isDir)
                    })
                }
                // Shim does JSON.parse on the json arg → pass the serialised array.
                emitRaw("window.__afsResult($rid,true,${JSONObject.quote(arr.toString())})")
            } catch (e: Exception) {
                emitRaw("window.__afsResult($rid,false,${q(e.message ?: "listDir failed")})")
            }
        }
    }

    @JavascriptInterface
    fun readFile(rid: Int, path: String) {
        executor.submit {
            val target = TerminalTargets.effectiveTarget(activity, backendKey())
            try {
                val text = if (TerminalSessions.native(activity, target))
                    TerminalSessions.exec(activity, target, FsScripts.read(path))
                else ssh.readFile(target, path)
                // Shim does JSON.parse(j) → must pass JSONObject.quote(text) so
                // JSON.parse yields the raw string (not a nested object).
                emitRaw("window.__afsResult($rid,true,${JSONObject.quote(text)})")
            } catch (e: Exception) {
                emitRaw("window.__afsResult($rid,false,${q(e.message ?: "readFile failed")})")
            }
        }
    }

    @JavascriptInterface
    fun writeFile(rid: Int, path: String, content: String) {
        executor.submit {
            val target = TerminalTargets.effectiveTarget(activity, backendKey())
            try {
                if (TerminalSessions.native(activity, target))
                    TerminalSessions.exec(activity, target, FsScripts.write(path, content))
                else ssh.writeFile(target, path, content)
                emitRaw("window.__afsResult($rid,true,null)")
            } catch (e: Exception) {
                emitRaw("window.__afsResult($rid,false,${q(e.message ?: "writeFile failed")})")
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** JSON-quote a string so it is safe as a JS argument. */
    private fun q(s: String): String = JSONObject.quote(s)

    /** Evaluate arbitrary JS on the UI thread via the WebView. */
    private fun emitRaw(js: String) {
        webView.post { webView.evaluateJavascript(js, null) }
    }
}
