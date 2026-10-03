package com.diegonmarcos.cloudlib.disk

/**
 * A cache this engine may clean but does not own the rules of. The engine asks the owner what it
 * WOULD free ([preview]) and lets the owner free it ([clear]) — it never deletes in an owner's
 * directory itself. cloud-drive registers the Store's APK cache this way, through the appstore
 * contract (libs:updater ApkCache.plan / clearRedundant), whose rule is that only a provably
 * installed APK is redundant: a download still waiting to install is never in the preview and
 * never cleared (#625, #812).
 */
interface CacheOwner {
    val id: String
    val label: String
    /** Bytes [clear] would free now; must be what [clear] then reports for an unchanged cache. */
    fun preview(): Long
    /** Free what [preview] counted; returns the bytes really freed. */
    fun clear(): Long
}
