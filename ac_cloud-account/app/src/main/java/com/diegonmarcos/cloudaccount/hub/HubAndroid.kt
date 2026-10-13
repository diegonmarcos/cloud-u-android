package com.diegonmarcos.cloudaccount.hub

import android.content.Context
import com.diegonmarcos.superapp.profile.AccountFleet
import com.diegonmarcos.superapp.profile.AccountModel
import com.diegonmarcos.superapp.profile.FleetSetup
import com.diegonmarcos.superapp.profile.SetupPlan
import com.diegonmarcos.superapp.profile.said
import com.diegonmarcos.superapp.settings.AccountVault
import com.diegonmarcos.superapp.settings.ConfigsPrefs
import org.json.JSONObject

/**
 * The hub's Android side: where the pure pieces ([Providers], [TokenImport], [AppProfiles], [SetupAll]) meet the
 * account vault and the fleet. Every credential stays in AccountVault's Connections (the keystore-backed
 * ConfigsPrefs file the account already keeps); nothing here logs, and nothing here reads another app's secret.
 */
object HubAndroid {

    fun connections(ctx: Context): JSONObject = AccountVault(ctx).connections()

    fun put(ctx: Context): (String, Any) -> Unit { val v = AccountVault(ctx); return { p, x -> v.putConnection(p, x) } }

    /** The stored value at [path]: only ever called through [RevealGate] or a provider test. */
    fun value(ctx: Context, path: String): String? = AccountVault(ctx).connection(path)?.toString()

    /** Removes one credential (and its expiry sibling); empty parents go with it. */
    fun remove(ctx: Context, path: String) {
        val v = AccountVault(ctx)
        val root = v.connections()
        fun drop(o: JSONObject, segs: List<String>): Boolean {
            if (segs.size == 1) { o.remove(segs[0]); o.remove(segs[0] + "_expires"); return o.length() == 0 }
            val child = o.optJSONObject(segs[0]) ?: return false
            if (drop(child, segs.drop(1))) o.remove(segs[0])
            return o.length() == 0
        }
        drop(root, path.split('.'))
        v.prefs.json = root.toString()
    }

    fun providerTarget(ctx: Context, p: Provider): ProviderSetup = ProviderSetup(p, { connections(ctx) }, HttpProbe)

    /** The provider rows Home and Secrets draw: the core four plus every provider the account holds something for. */
    fun providers(ctx: Context): List<Provider> = Providers.secrets(connections(ctx)).filter { (p, s) -> p.core || s.isNotEmpty() }.keys.toList()

    fun apps(): List<AppProfiles.App> = runCatching { AccountFleet.fleetApps().map { AppProfiles.App(it.id, it.label, it.pkg) } }.getOrDefault(emptyList())

    fun plan(ctx: Context): SetupPlan.Plan? = runCatching {
        FleetSetup.plan(ctx, ConfigsPrefs(ctx).json, AccountModel.get(ctx).shown(), AccountVault(ctx).appConfigs())
    }.getOrNull()

    fun appRows(ctx: Context, plan: SetupPlan.Plan?): List<AppProfiles.Row> =
        AppProfiles.rows(apps(), { FleetSetup.installed(ctx, it) }, AccountVault(ctx).appConfigs()) { id -> plan?.of(id)?.items?.size ?: 0 }

    private val ENDPOINT = Regex("(?i)(url|server|endpoint|host)$")

    fun appTarget(ctx: Context, app: AppProfiles.App, plan: SetupPlan.Plan?): AppSetup = AppSetup(
        id = "app:${app.id}", label = app.label,
        installed = { FleetSetup.installed(ctx, app.pkg) },
        endpoints = { plan?.of(app.id)?.items.orEmpty().filter { ENDPOINT.containsMatchIn(it.key) }.map { "${it.store}.${it.key}" } },
        push = {
            val ap = plan?.of(app.id)
            if (ap == null || ap.items.isEmpty()) true to "nothing to push for this app"
            else FleetSetup.runApp(ap, FleetSetup.installed(ctx, app.pkg), FleetSetup.transport(ctx)).let { it.ok to said(it.line()).text }
        },
    )

    /** Home ▸ Setup all: every provider first (the apps need their credentials), then every fleet app, in order. */
    fun allTargets(ctx: Context): List<SetupTarget> {
        val plan = plan(ctx)
        return providers(ctx).map { providerTarget(ctx, it) } + apps().map { appTarget(ctx, it, plan) }
    }
}
