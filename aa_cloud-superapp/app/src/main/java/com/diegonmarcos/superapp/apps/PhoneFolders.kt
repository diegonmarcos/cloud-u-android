package com.diegonmarcos.superapp.apps
import com.diegonmarcos.superapp.BuildConfig

import android.util.Base64
import org.json.JSONArray

/**
 * Folder taxonomy for the Phone tab of Home Apps. Captured 1:1 from
 * the user's One UI Home and baked into BuildConfig from
 * `build.json::ui.phone_folders`. Order is conveyed by both:
 *   • the explicit `order` field (a two-digit-block string, stable
 *     lexicographic sort), and
 *   • the leading prefix character on the label (_# > _ > - > .# > .a
 *     > .b > >) which is the user's launcher-naming sort convention —
 *     preserved verbatim so the UI reads identically to native One UI.
 *
 * Add a folder via build.json + rebuild — no Kotlin edits needed.
 * Adjust `match_keywords` to tune the [PhoneAppClassifier], and
 * `match_metadata` to let a folder claim apps by what Android says
 * about them instead of by a hand-written package list.
 *
 * `match_keywords` covers THIRD-PARTY packages only. Our own apps are
 * classified from `build.json::ui.external_apps`, whose entries name the
 * folder they belong to, so their identity and their taxonomy are one
 * record and cannot drift apart — see [constellationKeywordsByFolder].
 */
object PhoneFolders {
    data class Folder(
        val id: String,
        val order: String,
        val label: String,
        val matchKeywords: List<String>,
        /** Metadata rules (`cat:`/`intent:`/`perm:`) this folder claims
         *  apps with when no keyword matched. Kept verbatim — the values
         *  are Android constant names, which are case-sensitive, unlike
         *  the keywords above. */
        val matchMetadata: List<String> = emptyList(),
        /** When `true`, PhoneAppsFragment shows this folder in the grid
         *  even if it currently holds zero apps. Used for hand-curated
         *  buckets like the Misc exile that the user wants visible as an
         *  empty placeholder so they can drop apps into it later. */
        val pinned: Boolean = false,
        /** Explicit catch-all marker. The first folder with `sink: true`
         *  receives every app that matches no folder — deterministic,
         *  unlike "first empty-keyword folder" which depends on order
         *  when several empty-keyword buckets exist. */
        val sink: Boolean = false,
    )

    /**
     * Folders in display order — by `order`, then `id` as a tie-breaker, with
     * the constellation's own packages folded into the folder each of them
     * names.
     *
     * The two reads are caught separately on purpose. An unreadable
     * `ui.external_apps` costs the constellation apps their classification;
     * an unreadable `ui.phone_folders` costs EVERY app its classification, and
     * a surface that filters by section shows nothing at all. Letting the
     * first failure produce the second would trade a bad outcome for the worst
     * one available.
     */
    fun loadFromBuildConfig(): List<Folder> {
        val declared = parseDeclaredFolders()
        val ours = runCatching { constellationKeywordsByFolder() }.getOrDefault(emptyMap())
        return declared
            .map { folder ->
                val extra = ours[folder.id] ?: return@map folder
                folder.copy(matchKeywords = folder.matchKeywords + extra)
            }
            .sortedWith(compareBy({ it.order }, { it.id }))
    }

    private fun parseDeclaredFolders(): List<Folder> = runCatching {
        val json = String(Base64.decode(BuildConfig.UI_PHONE_FOLDERS_B64, Base64.NO_WRAP))
        val arr = JSONArray(json)
        (0 until arr.length()).map { idx ->
            val o = arr.getJSONObject(idx)
            val kw = o.optJSONArray("match_keywords")
            val kws = if (kw == null) emptyList() else
                (0 until kw.length()).map { kw.getString(it).lowercase() }
            val md = o.optJSONArray("match_metadata")
            val mds = if (md == null) emptyList() else
                (0 until md.length()).map { md.getString(it) }
            Folder(
                id            = o.optString("id"),
                order         = o.optString("order", "99"),
                label         = o.optString("label"),
                matchKeywords = kws,
                matchMetadata = mds,
                pinned        = o.optBoolean("pin", false),
                sink          = o.optBoolean("sink", false),
            )
        }
    }.getOrDefault(emptyList())

    /**
     * folder id → the `pkg:` keywords `build.json::ui.external_apps` declares
     * for it, one per package of every entry naming that folder.
     *
     * Our own apps used to be written down twice: their identity here, and an
     * identical `pkg:` keyword over in `ui.phone_folders` that did the
     * classifying. Nothing tied the two copies together, so an app could be
     * installable and sectionless at once — which is how the mail app was
     * pruned from every Notify tab on 2026-09-09. The identity entry now names
     * its folder and the keyword is derived, so there is one edit to make and
     * no second edit to forget.
     */
    private fun constellationKeywordsByFolder(): Map<String, List<String>> {
        val json = String(Base64.decode(BuildConfig.EXTERNAL_APPS_B64, Base64.NO_WRAP))
        val arr = JSONArray(json)
        val out = HashMap<String, MutableList<String>>()
        for (idx in 0 until arr.length()) {
            val entry = arr.getJSONObject(idx)
            val folderId = entry.optString("folder")
            if (folderId.isEmpty()) continue
            val keywords = out.getOrPut(folderId) { mutableListOf() }
            for (field in PACKAGE_FIELDS) {
                entry.optString(field).takeIf { it.isNotEmpty() }?.let { keywords += "pkg:${it.lowercase()}" }
            }
            entry.optJSONObject("forks")?.let { forks ->
                val ids = forks.keys()
                while (ids.hasNext()) {
                    forks.optString(ids.next()).takeIf { it.isNotEmpty() }
                        ?.let { keywords += "pkg:${it.lowercase()}" }
                }
            }
        }
        return out.mapValues { it.value.distinct() }
    }

    /** Every field of an `ui.external_apps` entry that holds a package name.
     *  All three are classified, not just the hub: `alt_package` is the
     *  resigned stock build actually installed on the device, and
     *  `install_package` is what the APK we ship arrives as. Classifying only
     *  one of them would leave the other in the sink. */
    private val PACKAGE_FIELDS = listOf("hub_package", "alt_package", "install_package")

    /** id of the sink folder for apps that neither a keyword nor a
     *  metadata rule claimed. Prefers the explicit `sink: true` folder
     *  ("Others"); falls back to the first folder with no rules at all,
     *  then the literal "misc".
     *
     *  The fallback deliberately requires BOTH rule lists to be empty: a
     *  folder that only classifies by metadata is a real destination,
     *  not a bucket, and inheriting the sink role would silently hand it
     *  every unclassified app on the device. */
    fun sinkFolderId(folders: List<Folder>): String =
        folders.firstOrNull { it.sink }?.id
            ?: folders.firstOrNull { it.matchKeywords.isEmpty() && it.matchMetadata.isEmpty() }?.id
            ?: "misc"
}
