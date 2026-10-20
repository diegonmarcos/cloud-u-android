package com.diegonmarcos.cloudlib.gitsync.engine

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.IBinder
import com.diegonmarcos.cloudlib.gitsync.IGitSyncEngine
import org.json.JSONObject
import java.io.File

/**
 * [GitCalls] behind libs:git-sync's IGitSyncEngine, shipped in Cloud-Lib-Git-Sync-Engine.apk (#871).
 * Cloud Drive binds this through the contract module's client and compiles nothing of this module, so an
 * edit here (or a JGit bump) ships this APK alone.
 *
 * ONE CALL = ONE GIT VERB on one repository. The CLIENT owns the registry and the credentials and sends
 * the credentials with every call; this process keeps none of them between calls.
 *
 * A PUBLISHED CONTRACT: [methodNames] may only grow; an answer that changes shape ships under a
 * new method name and a higher CONTRACT in the manifest.
 */
class GitSyncEngineService : Service() {

    fun methodNames(): Array<String> = arrayOf(INFO, GIT, CHUNK)

    fun dispatch(method: String, request: String): String = when (method) {
        INFO -> calls().info()
        GIT -> calls().git(JSONObject(request))
        CHUNK -> calls().chunk(JSONObject(request))
        else -> throw IllegalArgumentException("unknown method: $method")
    }

    private fun calls(): GitCalls = EngineState.calls(applicationContext)

    private val binder = object : IGitSyncEngine.Stub() {
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
        const val GIT = "git"
        const val CHUNK = "chunk"
    }
}

/** What outlives a service instance: the one [GitCalls] (its pending long results). */
internal object EngineState {
    @Volatile private var calls: GitCalls? = null

    fun calls(context: Context): GitCalls = calls ?: synchronized(this) {
        calls ?: GitCalls(File(context.cacheDir, "git-scratch"), BuildConfig.JGIT_VERSION) {
            if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager() else true
        }.also { calls = it }
    }
}
