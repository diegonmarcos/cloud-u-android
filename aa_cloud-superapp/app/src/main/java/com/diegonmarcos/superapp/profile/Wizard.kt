package com.diegonmarcos.superapp.profile

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.diegonmarcos.cloudlib.auth.SignIn
import com.diegonmarcos.cloudlib.auth.UserRegistry
import com.diegonmarcos.cloudlib.auth.VaultConnect
import com.diegonmarcos.superapp.network.WireGuardPrefs
import com.diegonmarcos.superapp.settings.ConfigsPrefs
import com.diegonmarcos.superapp.updater.Fleet
import org.json.JSONObject

/**
 * #622 THE FLEET WIZARD — the ordered, resumable flow that takes a fresh or
 * half-configured phone to fully configured. It is the engine behind the
 * Profile "setup" tab (rendered by [ProfileFragment.renderWizard]); the tab is
 * also the account center, since step 1 links to the sign-in journey.
 *
 * FULLY DECLARATIVE: the steps, their ORDER, and each step's done-check id and
 * delegation route are DATA — build.json::ui.profile.wizard.steps, baked whole
 * as BuildConfig.UI_PROFILE_WIZARD_B64. Reorder or drop a step there and the
 * wizard follows; nothing about the membership or order of the flow is spelled
 * out here.
 *
 * RESUMABLE + IDEMPOTENT by construction: a step's "done" is NEVER a remembered
 * boolean. [done] RE-MEASURES the actual device state every time it is asked —
 * the #452 rule ("a wizard that remembers success is worse than one that
 * re-checks"). Re-running a completed step cannot break it, and a step undone
 * elsewhere (a permission revoked, an app uninstalled, the tunnel cleared)
 * returns to pending on the next draw.
 *
 * DELEGATION only: every step's [route] points at an EXISTING surface — a
 * `tab:<id>` of the Profile strip or a launcher `page:`/`section:` target. This
 * engine measures and points; it never re-implements sign-in, install, WG
 * import or the cockpit.
 */
object Wizard {

    /** One declared step. [check] selects a real measurement in [done]; [route]
     *  is the existing surface it delegates to (`tab:<id>` of the Profile strip,
     *  or a launcher `page:`/`section:` target; blank = terminal). */
    data class Step(val id: String, val label: String, val check: String, val route: String)

    /** The declared steps, in order. An unparseable blob yields the empty list,
     *  so a broken bake shows no wizard rather than an invented one. */
    fun steps(): List<Step> = runCatching {
        val json = String(android.util.Base64.decode(
            com.diegonmarcos.superapp.BuildConfig.UI_PROFILE_WIZARD_B64, android.util.Base64.NO_WRAP))
        val arr = JSONObject(json).optJSONArray("steps") ?: return emptyList()
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Step(o.getString("id"), o.optString("label", o.getString("id")),
                o.optString("check", o.getString("id")), o.optString("route", ""))
        }
    }.getOrDefault(emptyList())

    /**
     * A step's done-state, RE-MEASURED live from real device state on every
     * call. There is no stored "wizard step done" flag anywhere — the whole
     * point is that this cannot drift from what is actually true on the phone.
     */
    fun done(ctx: Context, check: String): Boolean = when (check) {
        // Signed in: an in-process session OR a durable paired Authelia bearer.
        "identity" -> SignIn.Current.session != null || ConfigsPrefs(ctx).autheliaEmail.isNotBlank()
        // Vault: durably applied at least once, or fetched this session.
        "vault" -> UserRegistry.appliedAt(ctx).isNotBlank() || VaultConnect.Imported.bundle != null
        // Permissions: the same three system reads Configs ▸ Permissions makes.
        "permissions" -> filesAccessGranted() && notificationAccessGranted(ctx) && dumpGranted(ctx)
        // Apps: every DECLARED phone app (kind=app) is present on the device.
        "apps" -> declaredApps().let { it.isNotEmpty() && it.all { app -> installed(ctx, app) } }
        // Repos/Drive: the GitHub token the private-repo clone needs is configured
        // (the clone itself is the cloud-drive app's job, not this app's).
        "repos" -> ConfigsPrefs(ctx).secret(VaultCockpit.SECTION_GIT, VaultCockpit.K_GITHUB_TOKEN).isNotBlank()
        // WireGuard: a tunnel is configured — interface key AND at least one peer.
        "wireguard" -> WireGuardPrefs(ctx).let { it.interfacePrivateKey.isNotBlank() && it.peers().isNotEmpty() }
        // Fleet/services: mesh configured AND vault applied — a COMPOSITE of two
        // real measurements, because no synchronous mesh reachability probe
        // exists to call from here.
        "fleet" -> done(ctx, "wireguard") && done(ctx, "vault")
        // Finish: every other declared step measures done.
        "finish" -> steps().filter { it.check != "finish" }.all { done(ctx, it.check) }
        else -> false
    }

    private fun declaredApps(): List<Fleet.App> =
        Fleet.parse(com.diegonmarcos.superapp.BuildConfig.CONSTELLATION_FLEET_B64).filter { it.kind == "app" }

    private fun installed(ctx: Context, app: Fleet.App): Boolean =
        has(ctx, app.pkg) || (app.altId?.let { has(ctx, it) } ?: false)

    private fun has(ctx: Context, pkg: String): Boolean =
        runCatching { ctx.packageManager.getPackageInfo(pkg, 0) }.isSuccess

    private fun filesAccessGranted(): Boolean =
        Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()

    private fun notificationAccessGranted(ctx: Context): Boolean =
        runCatching { NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName) }
            .getOrDefault(false)

    private fun dumpGranted(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, "android.permission.DUMP") == PackageManager.PERMISSION_GRANTED
}
