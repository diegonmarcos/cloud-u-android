package com.diegonmarcos.cloudlib.rclone.engine

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The Unison half of the engine's answers (#1084), JSON in and JSON out like [RcloneCalls]. No
 * Android in here: the service hands in the exec, the pin, the dirs and the all-files check.
 *
 * A PUBLISHED CONTRACT (engine CONTRACT 2): the fields below may only grow.
 *   unison_start      {profile, profile_text, extra[], roots[], ssh?}  -> {id}
 *       profile_text may hold {PORT} (the loopback port of the SSH bridge) and {BACKUPS} (this
 *       engine's central backup dir for the pair); ssh = {host, port, user, key_path, key_pem (#1173, the key as text), passphrase,
 *       password, server_cmd}; extra = the flags UnisonPolicy allows.
 *   unison_poll       {id, cursor}                                      -> {lines[], cursor, done, rc?, rolled_back?}
 *   unison_cancel     {id}                                              -> {}
 *   unison_keep_copy  {profile, root, path, suffix}                     -> {copied}   a backup copy next to the file
 */
internal class UnisonCalls(
    private val exec: UnisonExec,
    private val home: File,
    private val knownHosts: File,
    private val filesAccess: () -> Boolean,
    private val openBridge: (SshTarget, File) -> UnisonExec.Bridged = { t, k -> UnisonSshBridge.open(t, k) },
) {
    fun backupDir(profile: String) = File(home, "backups/$profile")

    fun start(req: JSONObject): String {
        val profile = req.optString("profile")
        UnisonPolicy.nameRefusal(profile)?.let { return fail(it) }
        if (!exec.isAvailable) return fail("the unison binary is missing from this engine APK (reinstall Cloud-Lib-Rclone-Engine)")
        val text = req.optString("profile_text")
        if (text.isBlank()) return fail("unison_start: no profile_text")
        val extra = strings(req.optJSONArray("extra"))
        val roots = strings(req.optJSONArray("roots"))
        UnisonPolicy.refusal(extra, roots)?.let { return fail(it) }
        if (Regex("(?m)^\\s*(sshcmd|servercmd|rshcmd|sshargs|rshargs|merge|diff|logfile|copyprog|copyprogrest|copyquoterem|include|source|addprefsto)\\s*=").containsMatchIn(text))
            return fail("the profile names a command or log file the engine does not allow")
        if (!filesAccess() && roots.any { it.startsWith("/storage/") || it.startsWith("/sdcard") })
            return fail("the rclone engine has no All files access, so unison cannot read or write the shared storage — Store ▸ Access ▸ Android Perms ▸ Grant missing")
        if (exec.running(profile)) return fail("unison $profile is already running")
        val ssh = req.optJSONObject("ssh")
        var bridge: (() -> UnisonExec.Bridged)? = null
        if (ssh != null) {
            val target = SshTarget(
                host = ssh.optString("host"), port = ssh.optInt("port", 22), user = ssh.optString("user"),
                keyPath = ssh.optString("key_path"), keyPem = ssh.optString("key_pem"), passphrase = ssh.optString("passphrase"), password = ssh.optString("password"),
                serverCommand = ssh.optString("server_cmd", "unison -server").ifBlank { "unison -server" },
            )
            if (target.host.isBlank() || target.user.isBlank()) return fail("unison_start: ssh needs host and user")
            bridge = { openBridge(target, knownHosts) }
        }
        val dir = backupDir(profile).also { it.mkdirs() }
        val job = exec.start(UnisonExec.Run(profile, text.replace("{BACKUPS}", dir.absolutePath), extra, roots, bridge))
        return JSONObject().put("ok", true).put("id", job.id).toString()
    }

    fun poll(req: JSONObject): String {
        val job = exec.job(req.optString("id")) ?: return fail("unison_poll: unknown job (the engine restarted?)")
        val (lines, next) = job.read(req.optInt("cursor", 0))
        val out = JSONObject().put("ok", true).put("lines", JSONArray(lines)).put("cursor", next)
        val drained = job.done && lines.size < UnisonExec.POLL_LINES
        out.put("done", drained)
        if (drained) {
            out.put("rc", job.rc).put("rolled_back", job.rolledBack)
            exec.forget(job.id)
        }
        return out.toString()
    }

    fun cancel(req: JSONObject): String {
        exec.job(req.optString("id"))?.cancel()
        return JSONObject().put("ok", true).toString()
    }

    /**
     * KEEP BOTH: the version a resolution overwrote sits in this engine's central backup dir for the pair; copy it
     * next to the file the user kept, named with [suffix], so the next sync carries it to the other side as a
     * new file. Never overwrites an existing file.
     */
    fun keepCopy(req: JSONObject): String {
        val profile = req.optString("profile")
        UnisonPolicy.nameRefusal(profile)?.let { return fail(it) }
        val path = req.optString("path")
        UnisonPolicy.pathRefusal(path)?.let { return fail(it) }
        val root = File(req.optString("root"))
        if (!root.isDirectory) return fail("keep both: the root '${root.path}' is not a local folder")
        val src = File(backupDir(profile), path)
        if (!src.isFile) return fail("keep both: no backup of '$path' was kept, nothing to copy")
        val target = conflictName(File(root, path), req.optString("suffix").ifBlank { "conflict" })
        src.copyTo(target)
        return JSONObject().put("ok", true).put("copied", target.relativeTo(root).path).toString()
    }

    private fun strings(a: JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).map { a.getString(it) }

    private fun fail(why: String) = JSONObject().put("ok", false).put("error", why).toString()

    companion object {
        /** `dir/name.ext` → `dir/name.<suffix>.ext`, then `.<suffix>-2.ext`, … the first that does not exist. */
        fun conflictName(file: File, suffix: String): File {
            val clean = suffix.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val dot = file.name.lastIndexOf('.')
            val stem = if (dot > 0) file.name.substring(0, dot) else file.name
            val ext = if (dot > 0) file.name.substring(dot) else ""
            var n = 1
            while (true) {
                val tag = if (n == 1) clean else "$clean-$n"
                val f = File(file.parentFile, "$stem.$tag$ext")
                if (!f.exists()) return f
                n++
            }
        }
    }
}
