package app.sterna.core.data.mail

import android.content.Context

/**
 * The versions whose arrival needs the cached bodies gone: 168 for the unsubscribe headers, 170 for
 */
const val BODY_CACHE_PURGE_VERSION = 168

const val BODY_CACHE_PURGE_VERSION_2 = 170

const val BODY_CACHE_PURGE_VERSION_3 = 172

/** Crossing ANY of them purges once. Nothing here is derived from the current build number. */
private val BODY_CACHE_PURGE_VERSIONS =
    listOf(BODY_CACHE_PURGE_VERSION, BODY_CACHE_PURGE_VERSION_2, BODY_CACHE_PURGE_VERSION_3)

/**
 * `openMessage` serves a cached body BEFORE reading the message, and the cache is a serialised
 * `Email`, so a row written short of a field never gains it. A threshold is CROSSED, not equalled.
 */
fun bodyCachePurgeVersion(purgedForVersion: Int, currentVersion: Int): Int? =
    currentVersion.takeIf {
        BODY_CACHE_PURGE_VERSIONS.any { threshold ->
            purgedForVersion < threshold && it >= threshold
        }
    }

/** Bodies ONLY. The version is recorded AFTER the purge succeeds, so a failure is retried. */
class BodyCachePurge(context: Context) {
    private val prefs =
        context.applicationContext.getSharedPreferences("body_cache_upgrade", Context.MODE_PRIVATE)

    suspend fun onceForVersion(currentVersion: Int, purge: suspend () -> Unit) {
        val toRecord = bodyCachePurgeVersion(prefs.getInt(KEY_PURGED_FOR, 0), currentVersion) ?: return
        purge()
        prefs.edit().putInt(KEY_PURGED_FOR, toRecord).apply()
    }

    private companion object {
        const val KEY_PURGED_FOR = "purgedForVersion"
    }
}
