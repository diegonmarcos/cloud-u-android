package com.diegonmarcos.cloudkgstore

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build

/** One installed app of the index: what PackageManager says about it, read on this phone. */
data class PhoneApp(val pkg: String, val label: String, val version: String, val code: Long, val system: Boolean, val installer: String)

/** The permissions it asks for (and whether each is granted) and the components it declares. */
data class PhoneDetail(
    val app: PhoneApp,
    val permissions: List<Permission>,
    val activities: List<String>,
    val services: List<String>,
    val receivers: List<String>,
    val providers: List<String>,
) {
    data class Permission(val name: String, val granted: Boolean)
}

/** The installed apps index (Phone > Apps): every package PackageManager lists, filtered and sorted here; nothing is stored or sent. */
object PhoneApps {
    /** The apps whose label or package contains [q] (case-insensitive); system apps only when [system]; by label. */
    fun filter(all: List<PhoneApp>, q: String, system: Boolean): List<PhoneApp> {
        val t = q.trim().lowercase()
        return all.filter { (system || !it.system) && (t.isEmpty() || it.label.lowercase().contains(t) || it.pkg.lowercase().contains(t)) }
            .sortedWith(compareBy({ it.label.lowercase() }, { it.pkg }))
    }

    /** The permission's own name without the common `android.permission.` prefix, as the detail lists it. */
    fun shortName(permission: String): String = permission.removePrefix("android.permission.")

    /** Granted permissions first, then the rest, each group by name. */
    fun orderedPermissions(p: List<PhoneDetail.Permission>): List<PhoneDetail.Permission> =
        p.sortedWith(compareBy({ !it.granted }, { it.name }))

    @Suppress("DEPRECATION")
    fun load(pm: PackageManager): List<PhoneApp> {
        val all = if (Build.VERSION.SDK_INT >= 33) pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0)) else pm.getInstalledPackages(0)
        return all.map { app(pm, it) }
    }

    @Suppress("DEPRECATION")
    fun detail(pm: PackageManager, pkg: String): PhoneDetail? = runCatching {
        val flags = PackageManager.GET_PERMISSIONS or PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or
            PackageManager.GET_RECEIVERS or PackageManager.GET_PROVIDERS
        val info = if (Build.VERSION.SDK_INT >= 33) pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(flags.toLong())) else pm.getPackageInfo(pkg, flags)
        val asked = info.requestedPermissions.orEmpty()
        val state = info.requestedPermissionsFlags
        PhoneDetail(
            app(pm, info),
            asked.mapIndexed { i, n -> PhoneDetail.Permission(n, state != null && i < state.size && state[i] and PackageInfo.REQUESTED_PERMISSION_GRANTED != 0) },
            info.activities.orEmpty().map { it.name },
            info.services.orEmpty().map { it.name },
            info.receivers.orEmpty().map { it.name },
            info.providers.orEmpty().map { it.name },
        )
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun app(pm: PackageManager, info: PackageInfo): PhoneApp {
        val ai = info.applicationInfo
        val label = ai?.let { runCatching { pm.getApplicationLabel(it).toString() }.getOrNull() }?.takeIf { it.isNotBlank() } ?: info.packageName
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        val system = ai != null && (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        val installer = runCatching {
            if (Build.VERSION.SDK_INT >= 30) pm.getInstallSourceInfo(info.packageName).installingPackageName else pm.getInstallerPackageName(info.packageName)
        }.getOrNull().orEmpty()
        return PhoneApp(info.packageName, label, info.versionName.orEmpty(), code, system, installer)
    }
}
