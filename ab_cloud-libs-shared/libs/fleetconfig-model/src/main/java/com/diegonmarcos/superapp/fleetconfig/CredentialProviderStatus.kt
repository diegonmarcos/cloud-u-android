package com.diegonmarcos.superapp.fleetconfig

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * Is Cloud Vault Android's DEFAULT passwords/passkeys provider?
 *
 * Vault declares a CredentialProviderService and an AutofillService, but Android only uses them once
 * the user picks Vault in Settings > Passwords, passkeys & autofill. This is the one place that reads
 * that choice and names the settings screen that changes it; SuperApp's Permissions page and Vault's
 * own setup step both call it, so they cannot disagree.
 *
 * Nothing here writes a secure setting: the user makes the choice in Android's own screens.
 * The parsing and the intent selection are pure (strings and ints in, values out) so a JVM test pins
 * them; only [read], [status] and [open] touch the platform.
 */
object CredentialProviderStatus {

    const val VAULT_PACKAGE = "com.diegonmarcos.cloudvault"
    /** The legacy service names the manifest keeps on purpose (Android matches the provider by them). */
    const val CREDENTIAL_SERVICE = "com.x8bit.bitwarden.Autofill.CredentialProviderService"
    const val AUTOFILL_SERVICE = "com.x8bit.bitwarden.Autofill.AutofillService"

    const val KEY_CREDENTIAL_LIST = "credential_service"
    const val KEY_CREDENTIAL_PRIMARY = "credential_service_primary"
    const val KEY_AUTOFILL = "autofill_service"

    const val ACTION_CREDENTIAL_PROVIDER = "android.settings.CREDENTIAL_PROVIDER"
    const val ACTION_REQUEST_SET_AUTOFILL_SERVICE = "android.settings.REQUEST_SET_AUTOFILL_SERVICE"
    const val ACTION_SETTINGS = "android.settings.SETTINGS"
    const val SAMSUNG_GENERAL_MANAGEMENT = "com.android.settings.Settings\$GeneralManagementActivity"

    /** Default = Vault is the preferred one; Enabled = on but another is preferred; Off; Unknown = unreadable here. */
    enum class State(val label: String) {
        DEFAULT("Default ✓"), ENABLED("Enabled, not default"), OFF("Off"), UNKNOWN("Unknown")
    }

    enum class Step { CREDENTIAL, AUTOFILL }

    /** [credential] is null below API 34 (no Credential Manager providers to choose). */
    data class Status(val credential: State?, val autofill: State) {
        val overall: State get() {
            val parts = listOfNotNull(credential, autofill)
            return when {
                parts.all { it == State.DEFAULT } -> State.DEFAULT
                parts.all { it == State.OFF } -> State.OFF
                parts.any { it == State.UNKNOWN } -> State.UNKNOWN
                else -> State.ENABLED
            }
        }
        /** Only a confirmed Default completes the step; "Enabled, not default" does not. */
        val complete: Boolean get() = overall == State.DEFAULT
        /** The screen to open next: the credential provider first, then autofill. */
        val nextStep: Step get() = if (credential != null && credential != State.DEFAULT) Step.CREDENTIAL else Step.AUTOFILL
        fun detail(): String = (listOfNotNull(credential?.let { "Passkeys/passwords: ${it.label}" }) + "Autofill: ${autofill.label}")
            .joinToString(" · ")
    }

    /** A flattened component, "pkg/cls", with a leading "." class expanded against its package. */
    data class Component(val pkg: String, val cls: String)

    /** Parses a settings value: one component or a ':'-separated list. Malformed entries are dropped, never guessed. */
    fun parseComponents(raw: String?): List<Component> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(':').mapNotNull { e ->
            val t = e.trim()
            val i = t.indexOf('/')
            if (i <= 0 || i != t.lastIndexOf('/') || i == t.length - 1) return@mapNotNull null
            val pkg = t.substring(0, i)
            var cls = t.substring(i + 1)
            if (cls.startsWith(".")) cls = pkg + cls
            if (pkg.any { it.isWhitespace() } || cls.any { it.isWhitespace() }) null else Component(pkg, cls)
        }
    }

    private fun isVault(c: Component, pkg: String, cls: String) = c.pkg == pkg && c.cls == cls

    /**
     * Credential Manager state from the two secure settings. [primary]/[list] are null when they could
     * not be read from this uid: that is UNKNOWN, never a guess.
     */
    fun credentialState(primary: String?, list: String?, pkg: String = VAULT_PACKAGE): State {
        if (primary == null && list == null) return State.UNKNOWN
        if (parseComponents(primary).any { isVault(it, pkg, CREDENTIAL_SERVICE) }) return State.DEFAULT
        if (parseComponents(list).any { isVault(it, pkg, CREDENTIAL_SERVICE) }) return State.ENABLED
        // Neither names Vault. If a value was unreadable we cannot tell "someone else" from "hidden".
        return if (primary == null || list == null) State.UNKNOWN else State.OFF
    }

    /** Autofill is a single selected service: Vault, or it is Off. */
    fun autofillState(selected: String?, pkg: String = VAULT_PACKAGE): State {
        if (selected == null) return State.UNKNOWN
        return if (parseComponents(selected).any { isVault(it, pkg, AUTOFILL_SERVICE) }) State.DEFAULT else State.OFF
    }

    fun statusOf(sdk: Int, primary: String?, list: String?, autofill: String?, pkg: String = VAULT_PACKAGE) =
        Status(if (sdk >= 34) credentialState(primary, list, pkg) else null, autofillState(autofill, pkg))

    /** The Permissions row exists only while Vault is installed. */
    fun rowVisible(vaultInstalled: Boolean) = vaultInstalled

    /** One settings intent, as data: [action], optional [data] URI, optional explicit [component]. */
    data class IntentSpec(val action: String?, val data: String? = null, val component: Pair<String, String>? = null)

    /** Ordered candidates for a step: the first one the device can resolve wins; the general settings is last. */
    fun intentCandidates(step: Step, sdk: Int, manufacturer: String, pkg: String = VAULT_PACKAGE): List<IntentSpec> {
        val uri = "package:$pkg"
        val first = when {
            step == Step.CREDENTIAL && sdk >= 34 -> IntentSpec(ACTION_CREDENTIAL_PROVIDER, uri)
            else -> IntentSpec(ACTION_REQUEST_SET_AUTOFILL_SERVICE, uri)
        }
        val out = mutableListOf(first)
        if (step == Step.CREDENTIAL && sdk >= 34) out += IntentSpec(ACTION_CREDENTIAL_PROVIDER)
        if (manufacturer.equals("samsung", ignoreCase = true))
            out += IntentSpec(null, component = "com.android.settings" to SAMSUNG_GENERAL_MANAGEMENT)
        out += IntentSpec(ACTION_SETTINGS)
        return out
    }

    // ── platform edges ──────────────────────────────────────────────────

    /** null = not readable from this uid; "" = readable and unset. */
    private fun read(ctx: Context, key: String): String? =
        try { Settings.Secure.getString(ctx.contentResolver, key) ?: "" } catch (_: Throwable) { null }

    fun isInstalled(ctx: Context, pkg: String = VAULT_PACKAGE): Boolean =
        try {
            if (Build.VERSION.SDK_INT >= 33) ctx.packageManager.getPackageInfo(pkg, android.content.pm.PackageManager.PackageInfoFlags.of(0))
            else @Suppress("DEPRECATION") ctx.packageManager.getPackageInfo(pkg, 0)
            true
        } catch (_: Throwable) { false }

    /**
     * Live status for [pkg]. From Vault itself pass [ownAutofillEnabled] (AutofillManager's
     * hasEnabledAutofillServices, which only answers about the caller): it confirms the autofill half
     * even where the secure setting is hidden from the app.
     */
    fun status(ctx: Context, pkg: String = VAULT_PACKAGE, ownAutofillEnabled: Boolean? = null): Status {
        var autofill = read(ctx, KEY_AUTOFILL)
        if (ownAutofillEnabled == true) autofill = "$pkg/$AUTOFILL_SERVICE"
        else if (ownAutofillEnabled == false && autofill == null) autofill = ""
        return statusOf(Build.VERSION.SDK_INT, read(ctx, KEY_CREDENTIAL_PRIMARY), read(ctx, KEY_CREDENTIAL_LIST), autofill, pkg)
    }

    /** Opens the screen for [step]; falls through the candidates, general Settings last. Returns what opened. */
    fun open(ctx: Context, step: Step, pkg: String = VAULT_PACKAGE): IntentSpec? {
        for (c in intentCandidates(step, Build.VERSION.SDK_INT, Build.MANUFACTURER, pkg)) {
            val i = Intent().apply {
                c.action?.let { action = it }
                c.data?.let { data = Uri.parse(it) }
                c.component?.let { component = ComponentName(it.first, it.second) }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try { ctx.startActivity(i); return c } catch (_: Throwable) { /* next candidate */ }
        }
        return null
    }
}
