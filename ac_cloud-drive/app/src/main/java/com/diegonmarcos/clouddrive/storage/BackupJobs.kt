package com.diegonmarcos.clouddrive.storage

import com.diegonmarcos.cloudlib.sharedstore.StoreExec.Companion.q
import org.json.JSONArray
import org.json.JSONObject

/**
 * Fsync > Backup: ONE-WAY mirror jobs the user creates, run by rsync inside Cloud Terminal's Termux guest (the same
 * CLOUD_EXEC channel the git scripts use) to an SSH remote or a local folder of the guest. Pure: the job model, its
 * JSON (the phone keeps only these non-secret fields; the SSH key is Cloud Account's and arrives on stdin at run time),
 * the rsync script and the parser of rsync's own summary. No job is ever declared in the APK: an empty list is the
 * honest first state.
 */
object BackupJobs {

    sealed class Target {
        data class Local(val path: String) : Target()
        data class Ssh(val user: String, val host: String, val port: Int, val path: String) : Target()
    }

    /**
     * [mirror] = rsync --delete (the destination ends up identical): the one flag that loses data, so it is a written
     * choice on the job and its badge, never a default. [useKey] = authenticate with the SSH key Cloud Account holds.
     */
    data class Job(
        val id: String, val name: String, val source: String, val target: Target, val mirror: Boolean, val useKey: Boolean,
        val lastRunEpochSeconds: Long = 0, val lastExit: Int? = null, val lastSummary: String = "",
    )

    private val ID = Regex("[a-z0-9][a-z0-9-]{0,30}")
    private val HOST = Regex("[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?")
    private val USER = Regex("[a-z_][a-z0-9_.-]{0,31}")

    private fun okPath(p: String) = p.startsWith("/") && p.length > 1 && p.none { it == '\n' || it == '\u0000' } && p.split('/').none { it == ".." }

    /** Null when the job may run, else the sentence that refuses it. */
    fun refusal(j: Job): String? {
        if (!ID.matches(j.id)) return "job id must be [a-z0-9-], 1..31 characters"
        if (j.name.isBlank() || '\n' in j.name) return "a job needs a name"
        if (!okPath(j.source)) return "source must be an absolute folder in the Termux guest (no ..)"
        return when (val t = j.target) {
            is Target.Local -> when {
                !okPath(t.path) -> "destination must be an absolute folder in the Termux guest (no ..)"
                t.path.trimEnd('/') == j.source.trimEnd('/') -> "source and destination are the same folder"
                else -> null
            }
            is Target.Ssh -> when {
                !HOST.matches(t.host) -> "host is not a host name"
                !USER.matches(t.user) -> "user is not a login name"
                t.port !in 1..65535 -> "port must be 1..65535"
                !okPath(t.path) -> "remote path must be absolute (no ..)"
                else -> null
            }
        }
    }

    private fun keyPath(id: String) = "${FuseWire.STATE_DIR}/bk-$id.key"

    /**
     * The rsync run. Archive mode keeps times and permissions, `--partial` resumes, stats2 prints the totals [summary]
     * reads. A trailing slash on the source copies its CONTENTS, which is what "mirror this folder" means. The staged key
     * (read from stdin by the first command) is removed by a trap however the run ends.
     */
    fun script(j: Job): String {
        refusal(j)?.let { throw IllegalArgumentException(it) }
        val src = q(j.source.trimEnd('/') + "/")
        val del = if (j.mirror) " --delete" else ""
        return when (val t = j.target) {
            is Target.Local -> "set -e; mkdir -p ${q(t.path)}; rsync -a --partial --info=stats2$del $src ${q(t.path.trimEnd('/') + "/")}"
            is Target.Ssh -> {
                val ssh = "ssh -p ${t.port} -o StrictHostKeyChecking=accept-new -o BatchMode=yes" + if (j.useKey) " -o IdentitiesOnly=yes -i ${keyPath(j.id)}" else ""
                val stage = if (j.useKey) "mkdir -p ${FuseWire.STATE_DIR}; chmod 700 ${FuseWire.STATE_DIR}; (umask 177; cat > ${keyPath(j.id)}); trap 'rm -f ${keyPath(j.id)}' EXIT; " else ""
                "set -e; $stage rsync -a --partial --info=stats2$del -e \"$ssh\" $src ${q("${t.user}@${t.host}:${t.path.trimEnd('/')}/")}"
            }
        }
    }

    /** What rsync's `--info=stats2` footer says happened, in one line; the exit code carries success or not. */
    fun summary(exit: Int, out: String): String {
        fun grab(label: String) = Regex("$label: ([0-9,.]+)").find(out)?.groupValues?.get(1)
        val files = grab("Number of regular files transferred") ?: grab("Number of files transferred")
        val bytes = grab("Total transferred file size")
        return when {
            exit == 0 && files != null -> "ok · $files file(s) copied" + (bytes?.let { " · $it bytes" } ?: "")
            exit == 0 -> "ok"
            else -> "exit $exit" + (out.lines().lastOrNull { it.isNotBlank() }?.let { " · " + it.trim().take(160) } ?: "")
        }
    }

    // ── persistence: only non-secret fields ─────────────────────────────

    fun toJson(jobs: List<Job>): String = JSONObject().put("jobs", JSONArray(jobs.map { j ->
        JSONObject().put("id", j.id).put("name", j.name).put("source", j.source).put("mirror", j.mirror).put("use_key", j.useKey)
            .put("last_run", j.lastRunEpochSeconds).put("last_summary", j.lastSummary).also { o ->
                j.lastExit?.let { o.put("last_exit", it) }
                when (val t = j.target) {
                    is Target.Local -> o.put("target", JSONObject().put("path", t.path))
                    is Target.Ssh -> o.put("target", JSONObject().put("user", t.user).put("host", t.host).put("port", t.port).put("path", t.path))
                }
            }
    })).toString(2)

    /** A job that does not parse or does not pass [refusal] is dropped, so a hand-edited file can never run something odd. */
    fun fromJson(text: String): List<Job> {
        val arr = runCatching { JSONObject(text).optJSONArray("jobs") }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val t = o.optJSONObject("target") ?: return@mapNotNull null
            val target = if (t.has("host")) Target.Ssh(t.optString("user"), t.optString("host"), t.optInt("port", 22), t.optString("path")) else Target.Local(t.optString("path"))
            Job(o.optString("id"), o.optString("name"), o.optString("source"), target, o.optBoolean("mirror", false), o.optBoolean("use_key", false),
                o.optLong("last_run", 0), if (o.has("last_exit")) o.optInt("last_exit") else null, o.optString("last_summary")).takeIf { refusal(it) == null }
        }
    }
}
