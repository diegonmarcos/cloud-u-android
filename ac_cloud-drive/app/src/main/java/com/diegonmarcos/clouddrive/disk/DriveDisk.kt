package com.diegonmarcos.clouddrive.disk

import android.content.Context
import com.diegonmarcos.clouddrive.Declarations
import com.diegonmarcos.cloudlib.disk.CacheOwner
import com.diegonmarcos.cloudlib.disk.DiskEngine
import com.diegonmarcos.superapp.updater.cache.ApkCache

/**
 * #813 the ONE Disk Management engine of this process: the page (DiskScreen) and the debug API
 * (/api/disk/<op>) both get it here, so they share the last scan and can never disagree.
 *
 * Fleet packages come from the constellation manifest baked into Declarations (no second list).
 * The Store's APK cache joins the clean as a [CacheOwner] through the appstore contract,
 * libs:updater's [ApkCache]: [ApkCache.plan] counts only APKs whose install is PROVEN and
 * [ApkCache.clearRedundant] deletes only those, so a download still waiting to install is never
 * previewed and never evicted (#625, #812). This engine never deletes inside that directory itself.
 */
object DriveDisk {
    @Volatile private var engine: DiskEngine? = null

    fun engine(ctx: Context): DiskEngine = engine ?: synchronized(this) {
        engine ?: DiskEngine(
            ctx.applicationContext,
            fleet = { Declarations.constellation.map { it.packageName }.filter { it.isNotBlank() } },
            owners = { listOf(StoreApkCache(ctx.applicationContext)) },
        ).also { engine = it }
    }

    class StoreApkCache(private val ctx: Context) : CacheOwner {
        override val id = "store_apk_cache"
        override val label = "Store APK cache"
        override fun preview(): Long = ApkCache.plan(ctx).redundantBytes
        override fun clear(): Long = ApkCache.clearRedundant(ctx).freedBytes
    }
}
