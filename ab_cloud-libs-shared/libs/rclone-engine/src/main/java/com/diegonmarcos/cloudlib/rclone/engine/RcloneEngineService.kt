package com.diegonmarcos.cloudlib.rclone.engine

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.util.Log
import com.diegonmarcos.cloudlib.rclone.IRcloneEngine
import com.diegonmarcos.cloudlib.sysdns.ResolverProxy
import org.json.JSONObject
import java.io.File

/**
 * [RcloneCalls] behind libs:rclone's IRcloneEngine, shipped in Cloud-Lib-Rclone-Engine.apk (#871).
 * Cloud Drive binds this through the contract module's client and compiles nothing of this module,
 * so an edit here ships this APK alone.
 *
 * ONE CALL = ONE RCLONE RUN (or one step of a job). The CLIENT owns the rclone.conf and sends it
 * with every call; this process keeps none of it between calls. Jobs live in this PROCESS
 * ([EngineState]), not in the service instance, so they outlive a bind; the client holds its bind
 * for as long as it watches one.
 *
 * A PUBLISHED CONTRACT: [methodNames] may only grow; an answer that changes shape ships under a
 * new method name and a higher CONTRACT in the manifest.
 */
class RcloneEngineService : Service() {

    fun methodNames(): Array<String> = arrayOf(INFO, RUN, OUT_CHUNK, JOB_START, JOB_POLL, JOB_CANCEL)

    fun dispatch(method: String, request: String): String = when (method) {
        INFO -> calls().info()
        RUN -> calls().run(JSONObject(request))
        OUT_CHUNK -> calls().outChunk(JSONObject(request))
        JOB_START -> calls().jobStart(JSONObject(request))
        JOB_POLL -> calls().jobPoll(JSONObject(request))
        JOB_CANCEL -> calls().jobCancel(JSONObject(request))
        else -> throw IllegalArgumentException("unknown method: $method")
    }

    private fun calls(): RcloneCalls = EngineState.calls(applicationContext)

    private val binder = object : IRcloneEngine.Stub() {
        override fun call(method: String?, request: String?): String =
            runCatching { dispatch(method.orEmpty(), request.orEmpty()) }
                // Never let an exception cross the binder: on the far side it is a bare
                // RemoteException with nothing a user could be shown.
                .getOrElse { t -> JSONObject().put("ok", false).put("error", t.message ?: t.toString()).toString() }

        override fun methods(): Array<String> = methodNames()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private companion object {
        const val INFO = "info"
        const val RUN = "run"
        const val OUT_CHUNK = "out_chunk"
        const val JOB_START = "job_start"
        const val JOB_POLL = "job_poll"
        const val JOB_CANCEL = "job_cancel"
    }
}

/** What outlives a service instance: the exec (its job table) and the one name-resolving proxy. */
internal object EngineState {
    @Volatile private var calls: RcloneCalls? = null

    fun calls(context: Context): RcloneCalls = calls ?: synchronized(this) {
        calls ?: build(context).also { calls = it }
    }

    private fun build(context: Context): RcloneCalls {
        val binary = File(context.applicationInfo.nativeLibraryDir, BuildConfig.RCLONE_JNI_NAME)
        val exec = RcloneExec(binary, File(context.cacheDir, "rclone"), File(context.cacheDir, "rclone-conf")) { childEnv() }
        return RcloneCalls(exec, BuildConfig.RCLONE_VERSION) { if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager() else true }
    }

    /**
     * #741 rclone is a linux Go build (cgo off): left alone it looks names up in an /etc/resolv.conf
     * the app does not have and finds no CA roots. Every rclone child gets the fleet's one bridge to
     * Android's resolver (libs:sysdns) as HTTPS_PROXY, so a remote's name resolves where the
     * SuperApp's DNS menu decides, and Android's CA stores as SSL_CERT_DIR.
     */
    private fun childEnv(): Map<String, String> = buildMap {
        put("SSL_CERT_DIR", ResolverProxy.CA_DIRS)
        net?.let { put("HTTPS_PROXY", it.url) }
    }

    /** One proxy per process, any host a remote names, any port, never cut for quiet (rclone-binary.json::sandbox). */
    private val net: ResolverProxy? by lazy {
        runCatching { ResolverProxy("rclone", hosts = null, port = null, idleMs = BuildConfig.RCLONE_PROXY_IDLE_MS) { Log.i("RcloneEngine", it) } }
            .onFailure { Log.e("RcloneEngine", "rclone-net: loopback proxy would not start; rclone cannot resolve names", it) }
            .getOrNull()
    }
}
