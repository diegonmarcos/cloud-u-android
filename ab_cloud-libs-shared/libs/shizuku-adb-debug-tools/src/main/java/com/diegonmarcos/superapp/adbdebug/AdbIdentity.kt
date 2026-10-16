package com.diegonmarcos.superapp.adbdebug

/**
 * WHO an app presents itself as to adbd and to the rest of its own ADB state: the label in the phone's
 * Wireless-debugging "paired devices" list, the certificate subject of its key, the key alias, the process
 * name of its local server. Derived from the HOST app's build.json (name + android.application_id, baked into
 * [BuildConfig] by this lib's build.gradle from the consuming app's root), never typed here: Cloud Store pairs as
 * "cloud-store", the SuperApp as "cloud-superapp", whatever app links the lib as itself.
 *
 * Pure (no Android): [AdbIdentityTest] holds the derivation per host app.
 */
data class AdbIdentity(val appName: String, val applicationId: String) {

    /** The pairing / "connections" label adbd lists for this app's key. */
    val deviceName: String get() = appName

    /** X.500 subject of the self-signed key, so a certificate dump names its app too. */
    val certSubject: String get() = "CN=$appName, OU=adbdebug, O=diegonmarcos"

    /** Alias of the key inside this app's own PKCS12 (the file is app-private, so the alias is scoped by the sandbox as well). */
    val keyAlias: String get() = ALIAS

    /** App-private SharedPreferences holding pairing, mode and server token; one file per app sandbox, named here once. */
    val prefsName: String get() = PREFS

    /** True when [packageName] is the app this identity describes (a state read for another package is refused by [AdbScope]). */
    fun owns(packageName: String): Boolean = packageName == applicationId

    companion object {
        const val ALIAS = "identity"
        const val PREFS = "adb_shell"

        /**
         * The identity of the running host. [name] / [applicationId] are build.json's; a blank name falls back to the
         * last segment of the application id, and a blank id to [runtimePackage], so a host that declares nothing still
         * presents ITSELF and never another app.
         */
        fun derive(name: String?, applicationId: String?, runtimePackage: String): AdbIdentity {
            val id = applicationId?.trim().orEmpty().ifBlank { runtimePackage }
            val label = name?.trim().orEmpty().ifBlank { id.substringAfterLast('.') }
            return AdbIdentity(label, id)
        }

        /** This build's host, from the BuildConfig the lib was compiled into. */
        fun host(runtimePackage: String): AdbIdentity = derive(BuildConfig.HOST_APP_NAME, BuildConfig.HOST_APP_ID, runtimePackage)
    }
}

/**
 * Per-app scoping of live ADB state (the Store bar's channel probe, the cached status): one slot, owned by ONE
 * package. A read for another package finds nothing, so an app never serves (or refreshes) a status that was
 * measured for a different app's connection, keys or server.
 */
class AdbScope<T> {
    private var owner: String? = null
    private var value: T? = null

    @Synchronized fun peek(forPackage: String): T? = if (owner == forPackage) value else null

    @Synchronized fun get(forPackage: String, make: () -> T): T {
        if (owner == forPackage) value?.let { return it }
        return make().also { owner = forPackage; value = it }
    }

    /** Runs [f] on whatever the slot holds for its owner (this process is one app, so that is its own value). */
    @Synchronized fun current(f: (T) -> Unit) { value?.let(f) }

    @Synchronized fun clear() { owner = null; value = null }
}
