package com.diegonmarcos.clouddrive.sync

import android.content.Context
import com.diegonmarcos.clouddrive.sync.store.StoreRoute
import com.diegonmarcos.cloudlib.rclone.RcloneRunner
import com.diegonmarcos.cloudlib.sharedstore.StoreAccess
import com.diegonmarcos.cloudlib.sharedstore.StorePaths
import java.util.UUID

/**
 * Sync's rclone engine is another APK (another uid) and cannot open the store in cld.termux's
 * private home. A job leg that names the store is staged through shared storage: copied in (and
 * back) by File I/O when this app shares the store's uid, else inside cld.termux (CLOUD_EXEC).
 * Installed once by [install]; the legs themselves are planned by libs:shared-store ([StoreLegs]).
 */
object RcloneStoreLegs {

    fun install(ctx: Context) {
        val app = ctx.applicationContext
        RcloneRunner.legStaging = object : RcloneRunner.LegStaging {
            private fun legs(): StoreLegs? {
                val route = StoreRoute.of(app)
                if (!route.viaTermux) return null
                val copier = if (route.mode == StorePaths.Mode.DIRECT) StoreLegs.FileCopier()
                    else ExecCopier(app)
                return StoreLegs(StoreAccess.paths, route.userId, copier)
            }

            override fun refusal(args: List<String>): String? {
                val l = legs()
                val route = StoreRoute.of(app)
                val touches = args.drop(1).take(2).any { route.inStore(it) }
                return when {
                    !touches -> null
                    l == null -> "the shared store is in Cloud Terminal Termux (cld.termux), which is not installed or does not allow this app: open it once, then retry"
                    else -> l.refusal(args)
                }
            }

            override fun begin(args: List<String>): RcloneRunner.Staged? {
                val l = legs() ?: return null
                val plan = l.plan(args, UUID.randomUUID().toString().take(8)) ?: return null
                return RcloneRunner.Staged(plan.args, { l.pull(plan) }, { rc -> l.finish(plan, rc) })
            }
        }
    }
}
