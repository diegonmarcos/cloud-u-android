package com.diegonmarcos.cloudtoolssmallllms.data

import android.app.ActivityManager
import android.content.Context
import android.os.StatFs
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** A model present on a runtime's disk: [ref] is the runtime's own name for it, [bits] its quantization (null = unknown). */
data class InstalledModel(val name: String, val ref: String, val bits: Int?, val sizeGb: Double)

/** A model resident in a runtime's memory, [ramGb] of it. */
data class LoadedModel(val name: String, val ref: String, val ramGb: Double)

/** What a runtime reports: installed and loaded models, free disk and RAM when it knows them, and why it could not ([error]). */
data class RuntimeState(
    val installed: List<InstalledModel> = emptyList(),
    val loaded: List<LoadedModel> = emptyList(),
    val freeDiskGb: Double? = null,
    val freeRamGb: Double? = null,
    val error: String? = null,
)

/**
 * The engine contract of the Store: where models live and run. Every state the Store shows comes from [state]; nothing
 * is kept by the app. Each action returns null on success or the reason it failed. All calls block (run them off the
 * main thread). [canManage] false = the runtime reports state but cannot install, load or unload (no engine).
 */
interface ModelRuntime {
    val id: String
    val canManage: Boolean
    fun state(): RuntimeState
    fun install(model: String, bits: Int): String?
    fun uninstall(installed: InstalledModel): String?
    fun load(installed: InstalledModel): String?
    fun unload(loaded: LoadedModel): String?
}

/**
 * This phone: free disk and RAM from Android. There is no on-device inference engine in the app yet (no llama.cpp, MLC or
 * LiteRT-LM runtime is linked), so nothing is installed or loaded here and every action says so: the missing backend is
 * the only thing to add behind this interface.
 */
class PhoneRuntime(private val ctx: Context, override val id: String) : ModelRuntime {
    override val canManage: Boolean = false
    override fun state(): RuntimeState {
        val disk = runCatching { StatFs(ctx.filesDir.path).availableBytes / GB }.getOrNull()
        val ram = runCatching {
            val mi = ActivityManager.MemoryInfo()
            (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
            mi.availMem / GB
        }.getOrNull()
        return RuntimeState(freeDiskGb = disk, freeRamGb = ram)
    }
    override fun install(model: String, bits: Int): String? = NO_ENGINE
    override fun uninstall(installed: InstalledModel): String? = NO_ENGINE
    override fun load(installed: InstalledModel): String? = NO_ENGINE
    override fun unload(loaded: LoadedModel): String? = NO_ENGINE

    companion object {
        const val NO_ENGINE = "no on-device engine yet"
        private const val GB = 1024.0 * 1024.0 * 1024.0
    }
}

/**
 * A fleet host that runs Ollama (or anything with its API): GET /api/tags (installed), GET /api/ps (loaded),
 * POST /api/pull, DELETE /api/delete, POST /api/generate with keep_alive -1 / 0 (load / unload). [quantTags] maps 4 / 8 / 16
 * to the tag suffix a pull asks for. The host reports no free disk or RAM, so those stay unknown.
 */
class OllamaRuntime(override val id: String, private val base: String, private val quantTags: Map<Int, String>, private val timeoutMs: Int = 15000) : ModelRuntime {
    override val canManage: Boolean = true

    override fun state(): RuntimeState = runCatching {
        val tags = JSONObject(call("GET", "/api/tags", null, timeoutMs)).optJSONArray("models")
        val ps = JSONObject(call("GET", "/api/ps", null, timeoutMs)).optJSONArray("models")
        val installed = List(tags?.length() ?: 0) { i ->
            val o = tags!!.getJSONObject(i)
            val ref = o.getString("name")
            InstalledModel(ref.substringBefore(':'), ref, bitsOf(o.optJSONObject("details")?.optString("quantization_level").orEmpty()), o.optLong("size") / GB)
        }
        val loaded = List(ps?.length() ?: 0) { i ->
            val o = ps!!.getJSONObject(i)
            val ref = o.getString("name")
            LoadedModel(ref.substringBefore(':'), ref, o.optLong("size") / GB)
        }
        RuntimeState(installed, loaded)
    }.getOrElse { RuntimeState(error = it.message ?: it.javaClass.simpleName) }

    override fun install(model: String, bits: Int): String? {
        val tag = quantTags[bits] ?: return "no tag for $bits-bit"
        return act("POST", "/api/pull", JSONObject().put("name", "$model:$tag").put("stream", false), PULL_TIMEOUT_MS)
    }
    override fun uninstall(installed: InstalledModel): String? = act("DELETE", "/api/delete", JSONObject().put("name", installed.ref), timeoutMs)
    override fun load(installed: InstalledModel): String? = act("POST", "/api/generate", JSONObject().put("model", installed.ref).put("keep_alive", -1), LOAD_TIMEOUT_MS)
    override fun unload(loaded: LoadedModel): String? = act("POST", "/api/generate", JSONObject().put("model", loaded.ref).put("keep_alive", 0), timeoutMs)

    private fun act(method: String, path: String, body: JSONObject, timeout: Int): String? =
        runCatching { call(method, path, body.toString(), timeout); null }.getOrElse { it.message ?: it.javaClass.simpleName }

    private fun call(method: String, path: String, body: String?, timeout: Int): String {
        val c = URL(base.trimEnd('/') + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = timeoutMs; c.readTimeout = timeout
            c.setRequestProperty("Accept", "application/json")
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("HTTP $code" + text.take(120).let { if (it.isBlank()) "" else ": $it" })
            return text
        } finally {
            c.disconnect()
        }
    }

    companion object {
        private const val GB = 1024.0 * 1024.0 * 1024.0
        private const val PULL_TIMEOUT_MS = 30 * 60 * 1000
        private const val LOAD_TIMEOUT_MS = 5 * 60 * 1000

        /** Ollama's quantization_level ("Q4_K_M", "Q8_0", "F16") as bits. */
        fun bitsOf(level: String): Int? = when {
            level.startsWith("Q4", true) -> 4
            level.startsWith("Q8", true) -> 8
            level.startsWith("F16", true) || level.startsWith("BF16", true) -> 16
            else -> null
        }
    }
}
