package com.diegonmarcos.cloudlib.mounts.engine

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.IBinder
import com.diegonmarcos.cloudlib.mounts.IMountsEngine
import org.json.JSONObject
import java.io.File

/**
 * [MountCalls] behind libs:mounts' IMountsEngine, shipped in Cloud-Lib-Mounts-Engine.apk (#871). Cloud Drive
 * binds this through the contract module's client and compiles nothing of this module, so an edit here (or a
 * library bump) ships this APK alone.
 *
 * ONE CALL = ONE STEP on one connection. The CLIENT owns the mount list and the credentials and sends the
 * credentials with the call that opens a connection; this process keeps the authenticated connection between
 * calls, by handle, in [EngineState] (so it outlives a bind) and nothing else.
 *
 * A PUBLISHED CONTRACT: [methodNames] may only grow; an answer that changes shape ships under a
 * new method name and a higher CONTRACT in the manifest.
 */
class MountsEngineService : Service() {

    fun methodNames(): Array<String> = arrayOf(INFO, OPEN, FS, CHUNK, CLOSE)

    fun dispatch(method: String, request: String): String = when (method) {
        INFO -> calls().info()
        OPEN -> calls().open(JSONObject(request))
        FS -> calls().fs(JSONObject(request))
        CHUNK -> calls().chunk(JSONObject(request))
        CLOSE -> calls().close(JSONObject(request))
        else -> throw IllegalArgumentException("unknown method: $method")
    }

    private fun calls(): MountCalls = EngineState.calls(applicationContext)

    private val binder = object : IMountsEngine.Stub() {
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
        const val OPEN = "open"
        const val FS = "fs"
        const val CHUNK = "chunk"
        const val CLOSE = "close"
    }
}

/** What outlives a service instance: the one [MountCalls] (its open connections and pending long results). */
internal object EngineState {
    @Volatile private var calls: MountCalls? = null

    fun calls(context: Context): MountCalls = calls ?: synchronized(this) {
        calls ?: MountCalls(File(context.cacheDir, "mount-scratch"), BuildConfig.MOUNTS_LIBS,
            filesAccess = { if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager() else true },
        ).also { calls = it }
    }
}
