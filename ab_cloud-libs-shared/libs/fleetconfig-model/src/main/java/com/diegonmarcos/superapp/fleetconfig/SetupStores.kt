package com.diegonmarcos.superapp.fleetconfig

import android.content.Context
import android.content.SharedPreferences
import java.io.File

/**
 * #873 How a declared store is read and written. Every migrating store the fleet manifest
 * (fleet-config.json) declares for an app is served by the DEFAULT handler when its kind is
 * `prefs` or `encrypted`; an app whose store is something else (a DataStore, a Room table, a
 * file, or a prefs file with a rule of its own) registers a [SetupHandler] under the store's
 * declared name, once, in its Application.onCreate:
 *
 *     SetupStores.register("mail_settings", MyMailHandler)
 *
 * A store with neither is reported `served: false` by `describe` and refused by `apply`, and
 * cloud-android-fleet-setup-guard.py fails the build unless that gap is declared with a reason.
 */
object SetupStores {

    /** Reads and writes one file of one declared store. [apply] is atomic: all of [set]/[remove] or nothing. */
    interface SetupHandler {
        /** Every value the file holds (typed), or null when it does not exist yet. */
        fun read(ctx: Context, file: String): Map<String, Any?>?
        /** Commits [set] and [remove] together; false = nothing was written. */
        fun write(ctx: Context, file: String, set: Map<String, Any?>, remove: Set<String>): Boolean
    }

    /** Who may do what to which key; the default is [defaultAuthorizer]. Cloud Account swaps in its grant table. */
    fun interface Authorizer { fun allow(ctx: Context, caller: String, op: String, store: String, key: String, secret: Boolean): Boolean }

    const val OP_EXPORT = "export"
    const val OP_APPLY = "apply"

    /** Fleet apps that may read and write another app's SECRET keys without a grant: the account manager and the launcher. */
    val TRUSTED = setOf("com.diegonmarcos.cloudaccount", "com.diegonmarcos.superapp")

    val defaultAuthorizer = Authorizer { ctx, caller, _, _, _, secret -> !secret || caller == ctx.packageName || caller in TRUSTED }

    @Volatile var authorizer: Authorizer = defaultAuthorizer

    private val handlers = java.util.concurrent.ConcurrentHashMap<String, SetupHandler>()

    fun register(store: String, handler: SetupHandler) { handlers[store] = handler }
    fun registered(store: String): Boolean = handlers.containsKey(store)
    fun unregister(store: String) { handlers.remove(store) }

    /** The handler for [store] (declared [kind]): the registered one, else the default for a portable kind, else null. */
    fun handlerFor(store: String, kind: String): SetupHandler? =
        handlers[store] ?: if (kind == "prefs" || kind == "encrypted") PrefsHandler(kind) else null

    /** The default: SharedPreferences, or EncryptedSharedPreferences through the app's own default MasterKey. */
    class PrefsHandler(private val kind: String) : SetupHandler {
        override fun read(ctx: Context, file: String): Map<String, Any?>? = open(ctx, file, false)?.all

        override fun write(ctx: Context, file: String, set: Map<String, Any?>, remove: Set<String>): Boolean {
            val p = open(ctx, file, true) ?: return false
            val ed = p.edit()
            for (k in remove) ed.remove(k)
            for ((k, v) in set) when (v) {
                is Boolean -> ed.putBoolean(k, v)
                is Int -> ed.putInt(k, v)
                is Long -> ed.putLong(k, v)
                is Float -> ed.putFloat(k, v)
                is Set<*> -> ed.putStringSet(k, v.map { it.toString() }.toSet())
                else -> ed.putString(k, v.toString())
            }
            return ed.commit()
        }

        /** Never creates a file unless [create]: an absent plain store reads as null, an absent encrypted one is not opened (opening one writes its keyset). */
        private fun open(ctx: Context, file: String, create: Boolean): SharedPreferences? {
            val app = ctx.applicationContext
            return when (kind) {
                "prefs" -> app.getSharedPreferences(file, Context.MODE_PRIVATE).takeIf { create || it.all.isNotEmpty() }
                "encrypted" ->
                    if (!create && !File(app.dataDir, "shared_prefs/$file.xml").isFile) null
                    else androidx.security.crypto.EncryptedSharedPreferences.create(
                        app, file,
                        androidx.security.crypto.MasterKey.Builder(app).setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM).build(),
                        androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                        androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                    )
                else -> null
            }
        }
    }
}
