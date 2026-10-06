package com.diegonmarcos.superapp.system

import android.content.Context
import android.util.Base64
import android.util.Log
import com.diegonmarcos.superapp.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps the shell-applied device settings declared in data/device-tuning.json
 * true on this phone — first of all the Android 12+ phantom-process killer
 * that SIGKILLs the Cloud Terminal's proot sessions ("[Process completed
 * (signal 9)]"; memory is not the cause).
 *
 * Fully data-driven: every entry is {id, title, why, apply[], verify{cmd,
 * expect}} in BuildConfig.DEVICE_TUNING_B64 (baked by app/build.gradle). No
 * command string lives here. For each entry the engine runs `verify.cmd`
 * through the caller's shell; when the trimmed output already equals `expect`
 * the entry is APPLIED and nothing is written. Otherwise `apply[]` runs in
 * order and `verify` runs again: APPLIED when it now matches, FAILED (with the
 * truncated output) when not, PENDING when the shell answered nothing at all
 * (no channel yet). States are remembered per entry with a timestamp so the
 * Permissions page and GET /api/devcontrol/tuning can show them without a
 * shell round trip.
 *
 * Idempotent (verify-first), one run at a time ([running]), never loops — one
 * pass over the entries and out. Triggered by PrivilegedPlaneWorker right
 * after its connect succeeds, which is (a) the pairing/connect success hook
 * (AdbPairingService.onConnected in App.kt) and (b) the once-per-app-start
 * unique work App.kt enqueues when a shell is already paired.
 */
object DeviceTuning {

    enum class State { APPLIED, PENDING, FAILED }

    class Entry(val id: String, val title: String, val why: String,
                val apply: List<String>, val verifyCmd: String, val expect: String)

    class Record(val id: String, val state: State?, val detail: String, val at: Long)

    private const val TAG = "DeviceTuning"
    private const val PREFS = "device_tuning"
    private const val DETAIL_MAX = 240
    private val running = AtomicBoolean(false)

    /** The declared entries, decoded from the baked data file (never null). */
    fun entries(): List<Entry> {
        val raw = runCatching { String(Base64.decode(BuildConfig.DEVICE_TUNING_B64, Base64.DEFAULT)) }
            .getOrDefault("{}")
        val arr = runCatching { JSONObject(raw).optJSONArray("entries") }.getOrNull() ?: JSONArray()
        val out = mutableListOf<Entry>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id"); val verify = o.optJSONObject("verify") ?: continue
            if (id.isBlank() || verify.optString("cmd").isBlank()) continue
            val apply = o.optJSONArray("apply") ?: JSONArray()
            out.add(Entry(id, o.optString("title", id), o.optString("why"),
                (0 until apply.length()).map { apply.optString(it) }.filter { it.isNotBlank() },
                verify.optString("cmd"), verify.optString("expect")))
        }
        return out
    }

    /**
     * One pass over every entry through [exec] (null = the shell could not
     * serve the command). Returns false when another pass is still running.
     */
    fun run(ctx: Context, exec: (String) -> String?): Boolean {
        if (!running.compareAndSet(false, true)) return false
        try {
            for (e in entries()) {
                val first = exec("${e.verifyCmd} 2>&1")
                if (first == null) { record(ctx, e.id, State.PENDING, "no shell channel"); continue }
                if (first.trim() == e.expect) { record(ctx, e.id, State.APPLIED, first.trim()); continue }
                val log = StringBuilder()
                for (cmd in e.apply) log.append(exec("$cmd 2>&1")?.trim().orEmpty()).append(' ')
                val again = exec("${e.verifyCmd} 2>&1")?.trim()
                if (again == e.expect) record(ctx, e.id, State.APPLIED, again)
                else record(ctx, e.id, State.FAILED, "got '${again ?: "no output"}' want '${e.expect}' — ${log.toString().trim()}")
            }
        } finally {
            running.set(false)
        }
        return true
    }

    /** Remembered state per declared entry, in declaration order. */
    fun records(ctx: Context): List<Record> {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return entries().map { e ->
            val st = p.getString("${e.id}.state", null)?.let { s -> State.values().firstOrNull { it.name == s } }
            Record(e.id, st, p.getString("${e.id}.detail", "").orEmpty(), p.getLong("${e.id}.at", 0L))
        }
    }

    /** `GET /api/devcontrol/tuning` — {entries:[{id,title,why,state,detail,at}]}. */
    fun toJson(ctx: Context): String {
        val byId = records(ctx).associateBy { it.id }
        val arr = JSONArray()
        for (e in entries()) {
            val r = byId[e.id]
            arr.put(JSONObject()
                .put("id", e.id).put("title", e.title).put("why", e.why)
                .put("state", r?.state?.name ?: "PENDING")
                .put("detail", r?.detail ?: "never run")
                .put("at", r?.at ?: 0L))
        }
        return JSONObject().put("running", running.get()).put("entries", arr).toString()
    }

    private fun record(ctx: Context, id: String, state: State, detail: String) {
        val d = detail.take(DETAIL_MAX)
        Log.i(TAG, "$id -> $state: $d")
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("$id.state", state.name).putString("$id.detail", d)
            .putLong("$id.at", System.currentTimeMillis()).apply()
    }
}
