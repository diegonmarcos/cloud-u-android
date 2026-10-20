package com.diegonmarcos.cloudlib.rclone.engine

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * The engine's answers, as JSON in and JSON out (the wire is IRcloneEngine.call(method, request)).
 * No Android in here: the service hands in the exec, the pin and the all-files check, and the JVM
 * suite hands in a fake binary.
 *
 * Every answer is {"ok":true,...} or {"ok":false,"error":...}; nothing throws across the binder.
 *
 * A PUBLISHED CONTRACT: the request and answer fields below may only grow.
 *   info        {}                              -> {version, available, files_access}
 *   run         {config, args[]}                -> {rc, stdout, stderr, config?, more?, id?}
 *   out_chunk   {id, offset}                    -> {stdout, more}      the rest of an over-long stdout
 *   job_start   {config, args[]}                -> {id}
 *   job_poll    {id, cursor}                    -> {lines[], cursor, done, rc?, config?}
 *   job_cancel  {id}                            -> {}
 */
internal class RcloneCalls(
    private val exec: RcloneExec,
    private val pinnedVersion: String,
    private val filesAccess: () -> Boolean,
) {
    /** Over-long stdout (a big `lsjson`) waits here for out_chunk: one binder transaction carries about 1 MB. */
    private val pending = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun info(): String = JSONObject()
        .put("ok", true).put("version", pinnedVersion).put("available", exec.isAvailable).put("files_access", filesAccess())
        .toString()

    fun run(req: JSONObject): String {
        val args = args(req) ?: return fail("run: no args")
        gate(args)?.let { return fail(it) }
        val r = exec.run(req.optString("config"), args)
        val out = JSONObject().put("ok", true).put("rc", r.rc).put("stderr", r.stderr.take(MAX_ERR))
        r.config?.let { out.put("config", it) }
        if (r.stdout.length <= CHUNK) out.put("stdout", r.stdout)
        else {
            val id = UUID.randomUUID().toString()
            pending[id] = r.stdout
            out.put("stdout", r.stdout.substring(0, CHUNK)).put("more", true).put("id", id)
        }
        return out.toString()
    }

    fun outChunk(req: JSONObject): String {
        val id = req.optString("id")
        val whole = pending[id] ?: return fail("out_chunk: unknown or finished id")
        val from = req.optInt("offset", 0).coerceIn(0, whole.length)
        val to = minOf(whole.length, from + CHUNK)
        val more = to < whole.length
        if (!more) pending.remove(id)
        return JSONObject().put("ok", true).put("stdout", whole.substring(from, to)).put("more", more).toString()
    }

    fun jobStart(req: JSONObject): String {
        val args = args(req) ?: return fail("job_start: no args")
        gate(args)?.let { return fail(it) }
        val job = exec.start(req.optString("config"), args)
        return JSONObject().put("ok", true).put("id", job.id).toString()
    }

    fun jobPoll(req: JSONObject): String {
        val job = exec.job(req.optString("id")) ?: return fail("job_poll: unknown job (the engine restarted?)")
        val (lines, next) = job.read(req.optInt("cursor", 0))
        val out = JSONObject().put("ok", true).put("lines", JSONArray(lines)).put("cursor", next)
        // done only once every line has been handed over, so the client never loses the tail
        val drained = job.done && lines.size < RcloneExec.POLL_LINES
        out.put("done", drained)
        if (drained) {
            out.put("rc", job.rc)
            job.configOut?.let { out.put("config", it) }
            exec.forget(job.id)
        }
        return out.toString()
    }

    fun jobCancel(req: JSONObject): String {
        exec.job(req.optString("id"))?.cancel()
        return JSONObject().put("ok", true).toString()
    }

    private fun args(req: JSONObject): List<String>? {
        val a = req.optJSONArray("args") ?: return null
        return (0 until a.length()).map { a.getString(it) }
    }

    /** The reasons a run cannot start: the policy, a missing binary, a shared-storage path with no all-files grant. */
    private fun gate(args: List<String>): String? {
        ArgPolicy.refusal(args)?.let { return it }
        if (!exec.isAvailable) return "the rclone binary is missing from this engine APK (reinstall Cloud-Lib-Rclone-Engine)"
        if (ArgPolicy.touchesSharedStorage(args) && !filesAccess())
            return "the rclone engine has no All files access, so it cannot read or write the shared storage — Store ▸ Access ▸ Android Perms ▸ Grant missing"
        return null
    }

    private fun fail(why: String) = JSONObject().put("ok", false).put("error", why).toString()

    companion object {
        const val CHUNK = 400_000
        const val MAX_ERR = 100_000
    }
}
