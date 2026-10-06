package com.diegonmarcos.superapp.fleetconfig

import android.content.Context
import android.os.Bundle
import org.json.JSONObject

/**
 * #873 THE SETUP CONTRACT: `<package>.fleetsetup`, served by every fleet app ([SetupProvider]),
 * driven by Cloud Account's Fleet Setup. Four methods, JSON in and out:
 *
 *   describe          -> {"setup":1,"app","package","schema_version","declared":bool,
 *                         "stores":[{"name","kind","class","files":[…],"served":bool,"why"?,
 *                                    "keys":{"<key>":"config|secret|device"}}]}
 *   export  [store]   -> {"app","stores":{"<store>":{"<file>":{…values, "_types":{…}}}}}
 *                        migrating keys only; a secret key only to a caller the authorizer allows
 *   apply   <store>   body {"file"?:"<file>","values":{…,"_types":{…}},"remove":[…]}
 *                     -> {"store","file","ok":bool,"written":n,"keys":{"<key>":{"ok":bool,"why"?}}}
 *                        validated against describe, ATOMIC per store: one refused key writes none
 *   status            -> {"setup":1,"last_apply":{"store","file","at","ok","written"}|null,"stores":n,"served":n}
 *
 * The declaration is fleet-config.json, baked into every APK at build time (libs:fleetconfig-model's
 * assets), so a store is only ever applied if this build declared it.
 */
object SetupContract {
    const val VERSION = 1
    const val AUTHORITY_SUFFIX = ".fleetsetup"
    const val PERMISSION = "com.diegonmarcos.cloud.permission.CONSTELLATION_DATA"

    const val METHOD_DESCRIBE = "describe"
    const val METHOD_EXPORT = "export"
    const val METHOD_APPLY = "apply"
    const val METHOD_STATUS = "status"

    const val KEY_JSON = "json"
    const val KEY_ERROR = "error"
    /** apply extra: the app restarts once the reply is out, so nothing cached outlives the write. */
    const val KEY_RESTART = "restart"

    fun authority(pkg: String) = pkg + AUTHORITY_SUFFIX

    sealed class Reply {
        data class Ok(val json: JSONObject) : Reply()
        data class Unreachable(val why: String) : Reply()
        data class Refused(val why: String) : Reply()
    }

    /** The caller side: one provider call to [pkg]'s setup endpoint. BLOCKS; call off the main thread. */
    fun call(ctx: Context, pkg: String, method: String, store: String? = null, body: JSONObject? = null, restart: Boolean = false): Reply {
        try {
            val client = ctx.contentResolver.acquireUnstableContentProviderClient(authority(pkg))
                ?: return Reply.Unreachable("no ${authority(pkg)}: not installed, or a build older than the setup contract")
            try {
                val extras = Bundle().apply {
                    body?.let { putString(KEY_JSON, it.toString()) }
                    if (restart) putBoolean(KEY_RESTART, true)
                }
                val b = client.call(method, store, extras) ?: return Reply.Unreachable("$method returned nothing")
                b.getString(KEY_ERROR)?.let { return Reply.Refused(it) }
                return Reply.Ok(JSONObject(b.getString(KEY_JSON) ?: return Reply.Refused("no body")))
            } finally {
                client.close()
            }
        } catch (t: Throwable) {
            return Reply.Unreachable("${t.javaClass.simpleName}: ${t.message.orEmpty().take(120)}")
        }
    }
}
