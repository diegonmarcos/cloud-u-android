package com.diegonmarcos.superapp.rss

import android.util.Base64
import com.diegonmarcos.superapp.BuildConfig
import org.json.JSONObject

/**
 * Human names for ntfy topics, and the address of the advisory channel.
 *
 * ## Why a label table exists at all
 * A snake_case topic is an ADDRESS, not a name. `sec_yara` says nothing about
 * what arrives there, and a screen listing twenty-six of them is an inventory
 * nobody reads — which is the same as not publishing it. The fleet's own
 * registry (`cloud-infra/a_solutions/infra-obs_ntfy/src/build.json::topics`)
 * has carried a human `title` per topic all along; this mirrors it so the app
 * can show the sentence and keep the address as the subtitle.
 *
 * ## Missing labels degrade, they do not hide
 * [labelOf] returns the raw topic when nothing is declared. Same rule as
 * [NtfyScopes]'s last-scope fallback and for the same reason: an unlabelled
 * channel is merely less readable, an omitted one has stopped being watched
 * and nobody finds out. Adding a channel stays a data edit in
 * `build.json::ui.ntfy` (FIRE RULE #6), never a code change.
 */
object NtfyCatalog {

    @Volatile private var cachedLabels: Map<String, String>? = null

    private fun config(): JSONObject = runCatching {
        JSONObject(String(Base64.decode(BuildConfig.UI_NTFY_B64, Base64.NO_WRAP)))
    }.getOrDefault(JSONObject())

    private fun labels(): Map<String, String> {
        cachedLabels?.let { return it }
        val o = config().optJSONObject("labels") ?: JSONObject()
        val out = HashMap<String, String>(o.length())
        for (k in o.keys()) out[k] = o.optString(k)
        cachedLabels = out
        return out
    }

    /** Human title for [topic]; the topic itself when none is declared. */
    fun labelOf(topic: String): String = labels()[topic]?.takeIf { it.isNotBlank() } ?: topic

    @Volatile private var cachedTaxon: Map<String, String>? = null

    private fun taxons(): Map<String, String> {
        cachedTaxon?.let { return it }
        val o = config().optJSONObject("taxon") ?: JSONObject()
        val out = HashMap<String, String>(o.length())
        for (k in o.keys()) out[k] = o.optString(k)
        cachedTaxon = out
        return out
    }

    /**
     * The phone-taxonomy SECTION PREFIX this cloud publisher belongs to —
     * the same one-character vocabulary `ui.phone_sections` declares, so the
     * Notify tabs can narrow a cloud stream by the categories they were
     * built from ("_" Sys, "@" Inboxes & AI, "." Data Apps, …).
     *
     * Cloud messages carry no package and therefore no classifier can be run
     * on them: a topic or an in-app producer name is all there is. Without
     * this map every cloud publisher passed unfiltered and the SuperApp
     * updater showed up on all six tabs — an obviously system-level stream
     * appearing under Inboxes and under Data Apps.
     *
     * Blank when the key is not declared, and a blank prefix is treated as
     * "All tab only": a publisher nobody has categorised should not silently
     * claim a category.
     */
    fun taxonOf(key: String): String = taxons()[key].orEmpty()

    /**
     * The topic carrying out-of-band install/repair advisories.
     *
     * Declared rather than hardcoded because it is the one channel a stranded
     * device is told to look at, and the app that would have to be updated to
     * learn a new address is precisely the app that cannot update. Keeping it
     * in `ui.ntfy` at least means the NEXT build can be pointed elsewhere
     * without touching Kotlin.
     */
    fun advisoryTopic(): String =
        config().optString("advisory_topic").ifBlank { "cloud-sa-notifications" }
}
