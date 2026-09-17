package com.diegonmarcos.superapp.apps
import com.diegonmarcos.superapp.BuildConfig
import com.diegonmarcos.superapp.battery.EnergyWatchdog
import com.diegonmarcos.superapp.datamanager.AppNetworkProvider
import com.diegonmarcos.superapp.datamanager.AppUsageProvider
import com.diegonmarcos.superapp.updater.Fleet

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Process
import android.util.Base64
import androidx.core.content.ContextCompat
import com.diegonmarcos.superapp.R
import org.json.JSONArray

/**
 * Smart Folders rendered at the bottom of the Home Apps/Phone tab.
 * Orthogonal cross-cut view: apps still belong to their topical
 * folder above; Smart Folders are dynamic filters over the SAME
 * LauncherApps enumeration the page uses.
 *
 * Data source = build.json::ui.phone_smart_folders, baked into
 * BuildConfig.UI_PHONE_SMART_FOLDERS_B64 by app/build.gradle.
 *
 * Rule types:
 *   • pkg_prefix          — any pkg startsWith one of values
 *   • pkg_eq              — any pkg equals one of values
 *   • fleet_kind          — any pkg that is a constellation-fleet member
 *                           whose kind — 'app' or 'lib', taken from the
 *                           CENTRAL classification data/regen.sh →
 *                           constellation-fleet.json →
 *                           BuildConfig.CONSTELLATION_FLEET_B64 — is one of
 *                           values. Splits OUR OWN APKs from the companion
 *                           engine/binding libraries off the registry, never
 *                           a hand-maintained package list held here.
 *   • recent_used         — RANKING. Packages by most-recent use
 *                           (UsageStatsManager.lastTimeUsed), capped at
 *                           `limit`. Needs usage-access.
 *   • active_since        — RANKING. Packages used within the last
 *                           `window_h` hours, most-recent first (optional
 *                           `limit`). Needs usage-access.
 *   • most_opened         — RANKING. Packages by launch count
 *                           (MOVE_TO_FOREGROUND events, 7d), capped at
 *                           `limit`. Needs usage-access.
 *   • top_network         — RANKING. Packages by total bytes rx+tx
 *                           (NetworkStatsManager, 7d), capped at `limit`.
 *                           Needs usage-access.
 *   • top_battery         — RANKING. Packages by estimated mAh
 *                           (libs:battery EnergyWatchdog.perAppEstimate),
 *                           capped at `limit`. Needs usage-access.
 *   • most_used_time      — RANKING. Packages by total foreground time
 *                           (UsageStats.totalTimeInForeground sum, 7d), capped
 *                           at `limit`. Needs usage-access.
 *   All ranking rules degrade to an empty folder (hidden) when the
 *   usage-access grant is missing — see the providers' runCatching.
 *   • install_source_not  — PackageManager.getInstallSourceInfo
 *                           .installingPackageName not in values.
 *                           CONSERVATIVE: requires a CONCRETE non-Play
 *                           installer AND excludes system apps. Null
 *                           installer (Smart-Switch migrations, OEM
 *                           pre-installs whose metadata was stripped)
 *                           is NOT counted as alt-store — too many
 *                           false-positives (Booking, OneNote, Outlook,
 *                           Samsung Notes, Wearable, Contacts, Configs
 *                           all leak in otherwise). System apps
 *                           (ApplicationInfo.FLAG_SYSTEM) are excluded
 *                           regardless of installer. API 30+;
 *                           pre-30 falls back to deprecated
 *                           getInstallerPackageName.
 */
object PhoneSmartFolders {

    data class Rule(
        val type: String,
        val values: List<String>,
        val limit: Int = 0,
        val windowH: Int = 0,
    ) {
        fun matches(ctx: Context, app: PhoneApp): Boolean = when (type) {
            "pkg_prefix" -> values.any { app.packageName.startsWith(it) }
            "pkg_eq"     -> values.any { app.packageName == it }
            "fleet_kind" -> {
                // Split our own fleet APKs off the existing central
                // classification: kind=app (real apps) vs kind=lib (companion
                // engine/binding APKs). Driven from constellation-fleet.json
                // (baked from each APK's own build.json::release.kind), never
                // a hand-maintained package list here — a newly-declared app
                // or lib lands in the right folder without this file moving.
                val kind = fleetKindOf(app.packageName)
                kind != null && values.contains(kind)
            }
            "install_source_in" -> {
                // Show ONLY apps whose install-source-of-record (installing
                // OR initiating package) is one of `values` — bucket apps by
                // the specific store/installer that owns their updates.
                // Verified against real on-device values via
                // /api/phone/classify (installing/initiating/system dump).
                val srcs = installSourcesOf(ctx, app.packageName)
                srcs.any { values.contains(it) }
            }
            "install_source_not" -> {
                if (isSystemApp(ctx, app.packageName)) false
                else {
                    // Show ONLY apps whose source we KNOW and where NONE of the
                    // source fields (installing + initiating) is a first-party
                    // store. Checking both fields stops Play apps leaking when
                    // the installing package differs from the initiating one
                    // (common after updates / restores).
                    val srcs = installSourcesOf(ctx, app.packageName)
                    srcs.isNotEmpty() && srcs.none { values.contains(it) }
                }
            }
            // recently_installed is a ranking rule (sort + take), handled in
            // [SmartFolder.select], not a per-app predicate.
            else -> false
        }

        private fun isSystemApp(ctx: Context, pkg: String): Boolean = runCatching {
            val ai = ctx.packageManager.getApplicationInfo(pkg, 0)
            (ai.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
        }.getOrDefault(false)

        /** The concrete (non-null) install-source package names for [pkg] —
         *  installingPackageName AND initiatingPackageName on API 30+, or the
         *  single deprecated installer pre-30. Empty = unknown source. */
        private fun installSourcesOf(ctx: Context, pkg: String): Set<String> = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val info = ctx.packageManager.getInstallSourceInfo(pkg)
                setOfNotNull(info.installingPackageName, info.initiatingPackageName)
            } else {
                @Suppress("DEPRECATION")
                setOfNotNull(ctx.packageManager.getInstallerPackageName(pkg))
            }
        }.getOrDefault(emptySet())

        /** Package → constellation fleet kind, decoded once from the central
         *  classification baked by data/regen.sh (each APK's own
         *  build.json::release.kind, default "app") → constellation-fleet.json
         *  → BuildConfig.CONSTELLATION_FLEET_B64. Empty when the classification
         *  is absent or unparseable — every fleet_kind folder then matches
         *  nothing (degrades to empty, like the usage-ranked rules without the
         *  usage-access grant) rather than inventing a split. */
        private val fleetKindByPackage: Map<String, String> by lazy {
            Fleet.parse(BuildConfig.CONSTELLATION_FLEET_B64).associate { it.pkg to it.kind }
        }

        /** The fleet kind of [pkg] per the central classification, or null when
         *  it is not a constellation member. */
        private fun fleetKindOf(pkg: String): String? = fleetKindByPackage[pkg]
    }

    data class SmartFolder(
        val id: String,
        val title: String,
        val rule: Rule,
        val group: String? = null,
        /** Opts this folder OUT of the merged page's master exclusion, so
         *  its [select] filters over the FULL launchable enumeration rather
         *  than the list that already had the constellation's own packages
         *  stripped. Declared in build.json::ui.phone_smart_folders as
         *  `include_constellation: true`, next to the folder it applies to —
         *  Cloud Apps and Cloud Libs are the two whose entire content IS
         *  the fleet's own APKs. Every other folder leaves it false and is
         *  filtered exactly as before. */
        val includeConstellation: Boolean = false,
    ) {
        /** True when this folder's rule selects the constellation's LIBRARY
         *  packages (`fleet_kind` with value "lib"). Such a folder's subject
         *  is the INSTALLED lib set from [installedLibSlots], not the
         *  launchable enumeration — a lib APK ships no launcher activity, so
         *  it can never appear in [PhoneAppClassifier]'s universe, and a
         *  folder that filtered that universe would always come up empty
         *  (#474). Declared here off the rule data, never a second field to
         *  drift. */
        val selectsInstalledLibs: Boolean
            get() = rule.type == "fleet_kind" && rule.values.contains("lib")

        /** The apps this smart folder shows, from the master [apps] list.
         *  Predicate rules filter; the ranking rules (recently_installed +
         *  the usage/network/battery ones) order a provider's ranked
         *  package list against `apps` and cap at `limit`. */
        fun select(ctx: Context, apps: List<PhoneApp>): List<PhoneApp> {
            val cap = if (rule.limit > 0) rule.limit else 7
            return when (rule.type) {
                "recently_installed" -> apps
                    .filter { it.firstInstallTime > 0L }
                    .sortedByDescending { it.firstInstallTime }
                    .take(if (rule.limit > 0) rule.limit else 12)
                "recent_used" -> rankToApps(AppUsageProvider.recentUsed(ctx), apps).take(cap)
                "active_since" -> {
                    val ranked = rankToApps(
                        AppUsageProvider.activeSince(ctx, if (rule.windowH > 0) rule.windowH else 48),
                        apps,
                    )
                    if (rule.limit > 0) ranked.take(rule.limit) else ranked
                }
                "most_opened" -> rankToApps(AppUsageProvider.mostOpened(ctx), apps).take(cap)
                "most_used_time" -> rankToApps(AppUsageProvider.mostUsedTime(ctx), apps).take(cap)
                "top_network" -> rankToApps(AppNetworkProvider.topByBytes(ctx), apps).take(cap)
                "top_battery" -> rankToApps(
                    EnergyWatchdog.perAppEstimate(ctx).map { it.pkg }, apps).take(cap)
                else -> apps.filter { rule.matches(ctx, it) }
            }
        }

        /** Map a provider's ranked package list onto the launchable master
         *  [apps], preserving rank order and dropping packages not in the
         *  set (uninstalled, non-launchable, profile-filtered, or our own). */
        private fun rankToApps(ranked: List<String>, apps: List<PhoneApp>): List<PhoneApp> {
            if (ranked.isEmpty()) return emptyList()
            val byPkg = apps.associateBy { it.packageName }
            return ranked.mapNotNull { byPkg[it] }
        }
    }

    fun loadFromBuildConfig(): List<SmartFolder> = runCatching {
        val json = String(Base64.decode(BuildConfig.UI_PHONE_SMART_FOLDERS_B64, Base64.DEFAULT))
        val arr = JSONArray(json)
        val out = mutableListOf<SmartFolder>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            val title = o.optString("title")
            val ruleObj = o.optJSONObject("rule") ?: continue
            if (id.isBlank() || title.isBlank()) continue
            val type = ruleObj.optString("type")
            if (type.isBlank()) continue
            val values = mutableListOf<String>()
            ruleObj.optJSONArray("values")?.let { valuesArr ->
                for (j in 0 until valuesArr.length()) {
                    val v = valuesArr.optString(j)
                    if (v.isNotBlank()) values.add(v)
                }
            }
            val limit = ruleObj.optInt("limit", 0)
            val windowH = ruleObj.optInt("window_h", 0)
            // Predicate rules need `values`; limit-ranked rules need a
            // positive `limit`; active_since needs a positive `window_h`.
            val valid = when (type) {
                "recently_installed", "recent_used",
                "most_opened", "top_network", "top_battery", "most_used_time" -> limit > 0
                "active_since" -> windowH > 0
                else -> values.isNotEmpty()
            }
            if (!valid) continue
            val group = o.optString("group").takeIf { it.isNotBlank() }
            val includeConstellation = o.optBoolean("include_constellation", false)
            out.add(
                SmartFolder(
                    id, title, Rule(type, values, limit, windowH), group, includeConstellation,
                ),
            )
        }
        out
    }.getOrDefault(emptyList())

    /**
     * The installed constellation LIBRARY packages as [PhoneApp] tiles.
     *
     * A lib APK (kind=="lib" in the fleet manifest) ships NO launcher
     * activity, so it never appears in the launchable enumeration the other
     * smart folders filter over — which is exactly why the Cloud Libs folder
     * used to render empty even after #468 (#474). Its membership is built
     * HERE instead: the central fleet manifest (data/regen.sh →
     * constellation-fleet.json → BuildConfig.CONSTELLATION_FLEET_B64)
     * intersected with the packages actually on the device.
     *
     * THE INSTALLED CHECK IS THE HONEST FILTER. A lib declared in the
     * manifest but NOT installed must not be invented as present, so a
     * package that PackageManager cannot find contributes nothing to the
     * folder. The Cloud Libs folder therefore renders the INSTALLED SUBSET,
     * and that fewer-entries result is correct behaviour, not a bug to paper
     * over.
     *
     * Icons: the package's own application icon when it has one, otherwise
     * the ONE shared fallback [R.drawable.ic_cloud_lib] — built exactly once
     * here, never re-declared per view.
     *
     * Labels are the manifest's canonical `cloud-lib-{name}` (landed at the
     * single declaration in data/regen.sh), so the tile and the store cannot
     * disagree. [activityComponent] is null, which the renderer reads as
     * "cannot be launched — tap opens this lib's Constellation entry".
     */
    fun installedLibSlots(ctx: Context): List<PhoneApp> {
        val me = Process.myUserHandle()
        val pm = ctx.packageManager
        return Fleet.parse(BuildConfig.CONSTELLATION_FLEET_B64)
            .filter { it.kind == "lib" && !it.blocked }
            .mapNotNull { app ->
                val info = runCatching { pm.getPackageInfo(app.pkg, 0) }.getOrNull()
                    ?: return@mapNotNull null // declared but NOT installed → not invented (#280)
                val icon = runCatching { pm.getApplicationIcon(app.pkg) }.getOrNull()
                    ?: ContextCompat.getDrawable(ctx, R.drawable.ic_cloud_lib)
                PhoneApp(
                    packageName       = app.pkg,
                    activityComponent = null, // a lib has no launcher activity — not launchable
                    label             = app.label, // canonical cloud-lib-{name} from the manifest
                    icon              = icon,
                    user              = me,
                    firstInstallTime  = runCatching { info.firstInstallTime }.getOrDefault(0L),
                )
            }
    }
}
