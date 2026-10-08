package com.diegonmarcos.superapp.profile

import android.Manifest
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.diegonmarcos.superapp.adbdebug.AdbPairingService
import com.diegonmarcos.superapp.adbdebug.HostShell
import com.diegonmarcos.superapp.adbdebug.ShellChannel
import com.diegonmarcos.superapp.adbdebug.ShellChannels
import com.diegonmarcos.superapp.adbdebug.WirelessDebugging
import com.diegonmarcos.superapp.appstore.BuildConfig
import com.diegonmarcos.superapp.updater.Fleet
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Setup ▸ runbook, the engine (account redesign spec 4.6): an ordered list of steps that make this
 * phone match the loaded profile. Each step is `(id, check, run)`; the page (a later slot) only
 * draws them, the debug API (`/api/account/runbook`) drives them.
 *
 * The ids and their order are DATA: build.json::ui.account.runbook.steps (BuildConfig.UI_ACCOUNT_B64,
 * the same blob ForgeClient.Decl reads). An id left out is a disabled step. A host that declares no
 * runbook (SuperApp) gets the spec's eight.
 *
 * Implemented here: `shell` (Account's own uid-2000 channel through [HostShell]) and `store` (install
 * Cloud Store from the fleet release over that channel). The other six answer TODO until their slot
 * lands. Every check and run BLOCKS (network, adb, package manager): call off the main thread.
 * States carry names and short reasons only, never a value.
 */
class SetupRunbook(private val ctx: Context) {

    sealed class State(val name: String, val detail: String) {
        class Todo(detail: String) : State("TODO", detail)
        class Running(detail: String) : State("RUNNING", detail)
        class Done(detail: String) : State("DONE", detail)
        class Failed(reason: String) : State("FAILED", reason)
        class Already(detail: String) : State("ALREADY", detail)

        fun json(): JSONObject = JSONObject().put("state", name).put("detail", detail)
    }

    class Step(val id: String, val check: () -> State, val run: () -> State)

    /** The declared steps, in order. [allowPrompt]: may `store` fall back to the system install prompt. */
    fun steps(allowPrompt: Boolean = false): List<Step> = declaredIds().map { id ->
        when (id) {
            SHELL -> Step(id, { checkShell() }, { runShell() })
            STORE -> Step(id, { checkStore() }) { runStore(allowPrompt) }
            else -> Step(id, { notHere() }, { notHere() })
        }
    }

    fun step(id: String, allowPrompt: Boolean = false): Step? = steps(allowPrompt).firstOrNull { it.id == id }

    /** Every step's check, in order (the dry run). */
    fun dry(): JSONObject {
        val arr = JSONArray()
        for (s in steps()) arr.put(guard { s.check() }.json().put("id", s.id))
        return JSONObject().put("steps", arr)
    }

    /** Run ONE step. Unknown or undeclared ids answer FAILED with the declared list. */
    fun run(id: String, allowPrompt: Boolean = false): JSONObject {
        val s = step(id, allowPrompt)
            ?: return State.Failed("no step '$id' (declared: ${declaredIds().joinToString(",")})").json().put("id", id)
        return guard { s.run() }.json().put("id", s.id)
    }

    // ── shell ────────────────────────────────────────────────────────────

    private fun checkShell(): State {
        val ch = ShellChannels.active(ctx)
        if (ch != null) return State.Already("channel ${ch.name()} up; ${grantLine()}")
        return State.Todo(
            if (WirelessDebugging.isOn(ctx)) "Wireless debugging on, no channel (not paired yet, or keys did not reconnect)"
            else "Wireless debugging off, no channel")
    }

    private fun runShell(): State {
        ShellChannels.active(ctx)?.let { ch ->
            selfGrant(ch)
            return State.Already("channel ${ch.name()} up; ${grantLine()}")
        }
        // Stored keys from an earlier pairing: one reconnect (turns Wireless debugging on first when we may).
        val (ok, msg) = HostShell.reconnect(ctx, 1)
        if (ok) {
            ShellChannels.active(ctx)?.let { selfGrant(it) }
            return State.Done("reconnected with the stored pairing ($msg); ${grantLine()}")
        }
        if (!WirelessDebugging.isOn(ctx))
            return State.Failed("Wireless debugging is off: Settings > Developer options > Wireless debugging, " +
                "turn it on (stay on Wi-Fi), then run this step again")
        AdbPairingService.start(ctx)
        val notes = if (notificationsBlocked()) " Notifications are blocked for this app: allow them first, " +
            "the code field lives in the notification." else ""
        return State.Running("pairing started: in Wireless debugging tap 'Pair device with pairing code' and type " +
            "the 6-digit code into this app's notification; the channel and the WRITE_SECURE_SETTINGS self-grant " +
            "follow on their own.$notes")
    }

    /** `pm grant <self> WRITE_SECURE_SETTINGS` over the live channel, so adb_wifi_enabled survives a reboot. */
    private fun selfGrant(ch: ShellChannel) {
        if (HostShell.canWriteSecureSettings(ctx)) return
        runCatching { ch.exec(ctx, "pm grant ${ctx.packageName} android.permission.WRITE_SECURE_SETTINGS 2>&1") }
        HostShell.keepWirelessDebuggingOn(ctx)
    }

    private fun grantLine() =
        if (HostShell.canWriteSecureSettings(ctx)) "WRITE_SECURE_SETTINGS held (Wireless debugging re-armed at boot)"
        else "WRITE_SECURE_SETTINGS not held (Wireless debugging will not come back after a reboot)"

    private fun notificationsBlocked(): Boolean = Build.VERSION.SDK_INT >= 33 &&
        ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

    // ── store ────────────────────────────────────────────────────────────

    private fun storeInfo(): PackageInfo? = runCatching { ctx.packageManager.getPackageInfo(STORE_PKG, 0) }.getOrNull()

    private fun checkStore(): State =
        storeInfo()?.let { State.Already("Cloud Store ${it.versionName} installed") }
            ?: State.Todo("Cloud Store not installed")

    private fun runStore(allowPrompt: Boolean): State {
        storeInfo()?.let { return State.Already("Cloud Store ${it.versionName} installed") }
        val app = Fleet.parse(BuildConfig.CONSTELLATION_FLEET_B64).firstOrNull { it.pkg == STORE_PKG }
            ?: return State.Failed("Cloud Store ($STORE_PKG) is not in this build's fleet manifest")
        val ch = ShellChannels.active(ctx)
        if (ch == null && !allowPrompt)
            return State.Failed("no shell channel: run step 'shell' first (pair Wireless debugging); " +
                "the system install prompt is the fallback only when asked for (prompt=1)")
        val apk = Fleet.download(ctx, app)
        // The fleet key is the key this app is signed with: refuse anything else before it reaches pm.
        val theirs = signers(ctx.packageManager.getPackageArchiveInfo(apk.file.path, signingFlags()))
        val ours = signers(runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, signingFlags()) }.getOrNull())
        if (theirs.isEmpty()) return State.Failed("cannot read the downloaded APK's signer; not installed")
        if (ours.isEmpty()) return State.Failed("cannot read this app's own signer to compare; not installed")
        if (theirs.intersect(ours).isEmpty())
            return State.Failed("downloaded APK is not signed with the fleet key " +
                "(${theirs.first().take(12)} vs ${ours.first().take(12)}); not installed")
        val via = Fleet.commit(ctx, app, apk)
        storeInfo()?.let { return State.Done("Cloud Store ${it.versionName} installed via $via (${apk.evidence})") }
        return if (ch == null) State.Running("handed to the system installer ($via): confirm the prompt on screen")
               else State.Failed("install over $via finished but $STORE_PKG is still not installed")
    }

    @Suppress("DEPRECATION")
    private fun signingFlags(): Int =
        if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

    @Suppress("DEPRECATION")
    private fun signers(pi: PackageInfo?): Set<String> {
        pi ?: return emptySet()
        val sigs = if (Build.VERSION.SDK_INT >= 28) {
            val si = pi.signingInfo ?: return emptySet()
            if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
        } else pi.signatures
        return sigs.orEmpty().map { s ->
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
        }.toSet()
    }

    // ── declaration ──────────────────────────────────────────────────────

    private fun notHere() = State.Todo("not implemented in this slot")

    private inline fun guard(f: () -> State): State =
        try { f() } catch (t: Throwable) { State.Failed("${t.javaClass.simpleName}: ${t.message ?: "no message"}") }

    fun declaredIds(): List<String> = declared(com.diegonmarcos.superapp.account.BuildConfig.UI_ACCOUNT_B64)

    companion object {
        const val SHELL = "shell"
        const val STORE = "store"
        const val STORE_PKG = "com.diegonmarcos.cloudstore"
        /** Spec 4.6, in order: the default when the host declares no runbook. */
        val SPEC_STEPS = listOf("connected", "profile", SHELL, STORE, "apps", "configs", "perms", "verified")

        /** Step ids from the host's ui.account blob (base64 JSON); [SPEC_STEPS] when it declares none. */
        fun declared(uiAccountB64: String): List<String> {
            val o = runCatching {
                JSONObject(String(java.util.Base64.getDecoder().decode(uiAccountB64)).ifBlank { "{}" })
            }.getOrDefault(JSONObject())
            val arr = o.optJSONObject("runbook")?.optJSONArray("steps") ?: return SPEC_STEPS
            return (0 until arr.length()).map { arr.optString(it).trim() }.filter { it.isNotEmpty() }.distinct()
        }
    }
}
