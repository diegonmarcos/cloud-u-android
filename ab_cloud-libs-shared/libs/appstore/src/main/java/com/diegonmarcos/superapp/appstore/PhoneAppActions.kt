package com.diegonmarcos.superapp.appstore

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.diegonmarcos.superapp.updater.Fleet
import org.json.JSONObject

/**
 * Store ▸ Phone Apps (#564): the buttons one row offers, derived from what the
 * device says about the package, never from its name (#102).
 *
 * Every button is drawn. One that cannot work here carries the reason instead
 * of a click that does nothing: Stop needs a shell channel the device may not
 * have paired (#225/#280/#281), Remove cannot touch a system app, Update only
 * exists for an app the Cloud fleet publishes.
 *
 * The origin button comes from the REAL installer (getInstallSourceInfo) looked
 * up in the one declared map, [SOURCES_ASSET]. Ours -> the host's Store ▸ Cloud
 * page, where we update it; a foreign store -> that store's own page for the
 * app; anything else -> App info.
 */
object PhoneAppActions {

    enum class Kind { INSTALL, UPDATE, OPEN, STOP, REMOVE, APP_INFO, ORIGIN }

    /** [intent] null = an in-app verb the fragment runs (Update, Stop).
     *  [disabledReason] non-null = drawn disabled, and tapping shows why. */
    class Action(val kind: Kind, val label: String, val intent: Intent?, val disabledReason: String?)

    const val SOURCES_ASSET = "appstore-install-sources.json"

    fun sources(ctx: Context): JSONObject =
        ctx.assets.open(SOURCES_ASSET).use { JSONObject(it.readBytes().decodeToString()) }

    /** #571 the external-app ladders, from the SAME asset: one map, one file. */
    fun resolver(sources: JSONObject): SourceResolver.Config = SourceResolver.config(sources)

    /**
     * #571 the buttons a row offers for an app the phone does NOT have yet.
     * A fleet app installs through the fleet path; an external app with a
     * direct rung installs through [ExternalInstall]; a Play-only app gets
     * Install disabled with the reason and its Play page as the origin — the
     * deep-link is the official Play path, and nothing here pretends otherwise.
     */
    fun forMissing(ctx: Context, app: SourceResolver.External, fleetApp: Fleet.App?,
                   sources: JSONObject, cfg: SourceResolver.Config): List<Action> {
        fun s(id: Int) = ctx.getString(id)
        val out = mutableListOf<Action>()
        when {
            fleetApp != null -> {
                out += Action(Kind.INSTALL, s(R.string.store_phone_install), null,
                    if (fleetApp.blocked) s(R.string.store_phone_why_unpublished) else null)
                out += hostPage(ctx, sources)
            }
            !app.needsPlay -> {
                out += Action(Kind.INSTALL, s(R.string.store_phone_install), null, null)
                storePage(sources, cfg.playInstaller, app.pkg)?.takeIf { app.hasPlay }
                    ?.let { (label, page) -> out += Action(Kind.ORIGIN, label, page, null) }
            }
            else -> {
                out += Action(Kind.INSTALL, s(R.string.store_phone_install), null,
                    ctx.getString(R.string.store_phone_why_play_only, app.label))
                val (label, page) = storePage(sources, cfg.playInstaller, app.pkg)
                    ?: error("the resolver's play installer is not in the sources map")
                out += Action(Kind.ORIGIN, label, page, null)
            }
        }
        return out
    }

    /** The package that installed [pkg], as the platform recorded it. Null when
     *  nothing was recorded (adb, preinstalled) or the package is gone. */
    fun installerOf(ctx: Context, pkg: String): String? = runCatching {
        val pm = ctx.packageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) pm.getInstallSourceInfo(pkg).installingPackageName
        else @Suppress("DEPRECATION") pm.getInstallerPackageName(pkg)
    }.getOrNull()

    /** Fleet members by every id they can be installed under (package + resigned-stock alt id). */
    fun fleetByPackage(fleet: List<Fleet.App>): Map<String, Fleet.App> =
        fleet.flatMap { app -> listOfNotNull(app.pkg, app.altId).map { it to app } }.toMap()

    fun of(ctx: Context, pkg: String, fleetApp: Fleet.App?, shellReady: Boolean,
           sources: JSONObject = sources(ctx)): List<Action> {
        val pm = ctx.packageManager
        fun s(id: Int) = ctx.getString(id)
        val out = mutableListOf<Action>()
        if (fleetApp != null) out += Action(Kind.UPDATE, s(R.string.store_phone_update), null,
            if (fleetApp.blocked) s(R.string.store_phone_why_unpublished) else null)
        val launch = pm.getLaunchIntentForPackage(pkg)
        out += Action(Kind.OPEN, s(R.string.store_phone_open), launch,
            if (launch == null) s(R.string.store_phone_why_no_launcher) else null)
        out += Action(Kind.STOP, s(R.string.store_phone_stop), null, when {
            pkg == ctx.packageName -> s(R.string.store_phone_why_self)
            !shellReady -> s(R.string.store_phone_why_no_shell)
            else -> null
        })
        val flags = runCatching { pm.getApplicationInfo(pkg, 0).flags }.getOrDefault(0)
        out += Action(Kind.REMOVE, s(R.string.store_phone_remove),
            Intent(Intent.ACTION_DELETE, Uri.fromParts("package", pkg, null)),
            if (flags and ApplicationInfo.FLAG_SYSTEM != 0) s(R.string.store_phone_why_system) else null)
        out += Action(Kind.APP_INFO, s(R.string.store_phone_app_info), appInfo(pkg), null)
        out += origin(ctx, pkg, fleetApp != null, sources)
        return out
    }

    /** Ours = a Cloud fleet member, or installed by this app. The row's
     *  origin button and the #565 export/import both ask this. */
    fun isOurs(ctx: Context, fleetMember: Boolean, installer: String?): Boolean =
        fleetMember || installer == ctx.packageName

    /** [installer]'s own page for [pkg], from the one map: its label and an
     *  intent aimed at the installer, so market:// lands in the store that
     *  installed the app rather than whichever market handler the chooser
     *  prefers. Null = [installer] is not a declared store. */
    fun storePage(sources: JSONObject, installer: String?, pkg: String): Pair<String, Intent>? {
        val store = installer?.let { sources.getJSONObject("sources").optJSONObject(it) } ?: return null
        return store.getString("label") to
            Intent(Intent.ACTION_VIEW, Uri.parse(store.getString("deeplink").replace("{pkg}", pkg))).setPackage(installer)
    }

    /** Ours -> the host's Store ▸ Cloud page, the same target the update notification opens. */
    private fun hostPage(ctx: Context, sources: JSONObject): Action {
        val label = sources.getJSONObject("ours").getString("label")
        val target = AppStoreHost.launchActivity
            ?: return Action(Kind.ORIGIN, label, null, ctx.getString(R.string.store_phone_why_no_host))
        val open = Intent(ctx, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        AppStoreHost.launchExtras.forEach { (k, v) -> open.putExtra(k, v) }
        return Action(Kind.ORIGIN, label, open, null)
    }

    private fun origin(ctx: Context, pkg: String, fleetMember: Boolean, sources: JSONObject): Action {
        val installer = installerOf(ctx, pkg)
        if (isOurs(ctx, fleetMember, installer)) return hostPage(ctx, sources)
        val (label, page) = storePage(sources, installer, pkg)
            ?: return Action(Kind.ORIGIN, sources.getJSONObject("unknown").getString("label"), appInfo(pkg), null)
        return Action(Kind.ORIGIN, label, page, null)
    }

    private fun appInfo(pkg: String) =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null))
}
