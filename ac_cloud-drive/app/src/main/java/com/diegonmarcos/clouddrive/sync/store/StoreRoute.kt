package com.diegonmarcos.clouddrive.sync.store

import android.content.Context
import com.diegonmarcos.cloudlib.sharedstore.StoreAccess
import com.diegonmarcos.cloudlib.sharedstore.StorePaths

/**
 * #1315 THE ONE DECISION of how this app reaches the shared store, and THE ONE PATH FORM. Every Kotlin model of the Git
 * layer carries the HOST form (`/data/user/<u>/<owner>/files/home/<store>/...`); the GUEST form (`/root/<store>/...`,
 * what cld.termux's shell sees) exists only inside a script, translated here and nowhere else. [mode] is libs:shared-store's
 * runtime answer (DIRECT = same uid, EXEC = CLOUD_EXEC, NONE = no cld.termux); [viaTermux] says a script can run at all.
 * Pure over [StorePaths], JVM-tested (StoreRouteTest); [of] reads the live answer.
 */
class StoreRoute(val mode: StorePaths.Mode, private val paths: StorePaths, val userId: Int) {

    /** cld.termux answers: a script can run in the guest (DIRECT or EXEC; the engine APKs can never open the store themselves). */
    val viaTermux: Boolean get() = StorePaths.viaOwner(mode)

    /** The store's root on the host. */
    val hostRoot: String = paths.hostRoot(userId)

    /** The store's root in the guest (`/root/<store>`). */
    val guestRoot: String = paths.guestPath(hostRoot, userId) ?: error("the store root has no guest form")

    /** [hostPath] is the store or inside it (both host aliases; `..` refused). */
    fun inStore(hostPath: String): Boolean = paths.guestPath(hostPath.trimEnd('/').ifEmpty { "/" }, userId) != null

    /** The guest twin of [hostPath], or null when it is not in the store. */
    fun guest(hostPath: String): String? = paths.guestPath(hostPath.trimEnd('/').ifEmpty { "/" }, userId)

    /** The guest twin of [hostPath]; a path outside the store is a programming error and throws with the path. */
    fun guestOrThrow(hostPath: String): String = guest(hostPath) ?: error("$hostPath is not inside the store")

    /** The host twin of [guestPath] (the inverse of [guest]), or null when it is not under the guest root. */
    fun host(guestPath: String): String? {
        val g = guestPath.trimEnd('/')
        return when {
            g == guestRoot -> hostRoot
            g.startsWith("$guestRoot/") -> if (g.split('/').any { it == ".." }) null else hostRoot + g.removePrefix(guestRoot)
            else -> null
        }
    }

    companion object {
        /** The live route of this process: libs:shared-store's mode, paths and user. Cheap (a stat and a permission check). */
        fun of(ctx: Context): StoreRoute = StoreRoute(StoreAccess.mode(ctx), StoreAccess.paths, StoreAccess.userId())
    }
}
