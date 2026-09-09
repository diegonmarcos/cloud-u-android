package com.diegonmarcos.superapp.apps

import android.content.Context

/**
 * packageName → phone-taxonomy FOLDER, and from the folder its SECTION.
 *
 * phone/apps sorts folders into sections ("Tools · Data Apps", "Services ·
 * Buro", …) and a folder joins its section through the single prefix character
 * its label starts with. That rule already existed, but only inside the
 * launcher's own rendering, so no other surface could ask the one useful
 * question: which section is this app in?
 *
 * Apps RSS needs exactly that, to filter the notification stream by the same
 * taxonomy the user organised their apps with. Both sides read the same
 * build.json, so a section added there is filterable the moment it ships —
 * there is no second list to keep in sync.
 *
 * The FOLDER is exposed alongside the section because a surface that segments
 * notifications by KIND needs the kinds themselves, and `ui.phone_folders`
 * already is that list. Deriving the kinds here is what stops a fourth
 * parallel taxonomy being written beside the three that already read this one.
 */
object PhoneTaxonomy {

    /** The central classification, in declared order. */
    val folders: List<PhoneFolders.Folder> by lazy { PhoneFolders.loadFromBuildConfig() }

    private val labelById: Map<String, String> by lazy { folders.associate { it.id to it.label } }
    private val sinkId: String by lazy { PhoneFolders.sinkFolderId(folders) }

    /** Memoised per package — a notification stream re-renders on every filter
     *  tap and every arriving notification, and classify() walks every folder's
     *  keyword list, which is pure waste to repeat for a package we just saw.
     *  The memo is what keeps the metadata path affordable too: the
     *  PackageManager reads below happen at most once per package. */
    private val cache = HashMap<String, String>()

    /** Human-readable name of a folder id — the KIND, as the user named it in
     *  build.json. Blank for an id no folder declares. */
    fun folderLabelOf(folderId: String): String = labelById[folderId].orEmpty()

    /**
     * Section prefix character of the folder [packageName] classifies into, or
     * "" for an app landing in a prefix-less folder (Misc, Others) — those
     * belong to no section and so are only ever shown by an "All" choice.
     *
     * Pass [ctx] to let an app the keyword rules do not know reach its folder
     * through what Android declares about it — the same metadata pass the
     * launcher grid uses. Without it the answer is keyword-only, so a freshly
     * installed app filters as sectionless until somebody edits build.json:
     * the notification stream and the grid would disagree about the same app.
     */
    fun sectionPrefixOf(packageName: String, label: String, ctx: Context? = null): String =
        prefixOfFolder(folderIdOf(packageName, label, ctx))

    /** Folder id [packageName] classifies into, or "" when the central list
     *  did not load at all. Same [ctx] contract as [sectionPrefixOf]. */
    fun folderIdOf(packageName: String, label: String, ctx: Context? = null): String {
        if (packageName.isBlank() || folders.isEmpty()) return ""
        cache[packageName]?.let { return it }
        return resolve(ctx, mapOf(packageName to label))[packageName].orEmpty()
    }

    /**
     * Classify a whole feed at once, before anything asks about a single
     * package.
     *
     * Batching is not an optimisation here, it is what makes the metadata pass
     * usable at all on this path. [PhoneAppClassifier.metadataFor] probes each
     * DECLARED intent category once for the whole set it is handed; asked one
     * package at a time it would repeat every probe per notification, on the
     * thread that is drawing the card. That cost is the reason this lookup was
     * called without a Context in the first place, so removing it is part of
     * removing the defect and not a separate tidy-up.
     */
    fun prime(ctx: Context, labelsByPackage: Map<String, String>) {
        resolve(ctx, labelsByPackage)
    }

    private fun prefixOfFolder(folderId: String): String {
        val first = labelById[folderId]?.firstOrNull() ?: return ""
        return if (first.isLetterOrDigit()) "" else first.toString()
    }

    /** packageName → folder id for every entry of [labelsByPackage], filling
     *  the memo on the way. */
    private fun resolve(ctx: Context?, labelsByPackage: Map<String, String>): Map<String, String> {
        if (folders.isEmpty()) return emptyMap()
        val missing = labelsByPackage.filterKeys { it.isNotBlank() && !cache.containsKey(it) }
        if (missing.isNotEmpty()) {
            // KEYWORD PASS, for the whole batch. classify() with no metadata
            // returns the sink for anything no keyword claimed, and no keyword
            // can ever name the sink (matchByKeyword skips rule-less folders),
            // so "landed on the sink" IS "unclaimed" — the same split
            // groupByFolder makes before it pays for a PackageManager read.
            val byKeyword = missing.mapValues {
                PhoneAppClassifier.classify(it.key, it.value, folders)
            }
            val unclaimed = byKeyword.filterValues { it == sinkId }.keys
            val metadata: Map<String, AppMetadata> =
                if (ctx == null || unclaimed.isEmpty()) emptyMap()
                else PhoneAppClassifier.metadataFor(ctx, folders, unclaimed)
            for ((pkg, keywordId) in byKeyword) {
                val id = if (keywordId != sinkId) keywordId else PhoneAppClassifier.classify(
                    pkg, missing[pkg].orEmpty(), folders, metadata[pkg] ?: AppMetadata.NONE)
                // A context-less answer about an UNCLAIMED package is
                // provisional: the `match_metadata` rules that would have
                // placed it never ran. Memoising it would freeze that wrong
                // answer for the life of the process, so one caller asking
                // without a Context would make every later caller wrong too —
                // the same defect as the missing Context, only harder to see.
                if (ctx != null || keywordId != sinkId) cache[pkg] = id
            }
        }
        return labelsByPackage.keys.associateWith { cache[it] ?: sinkId }
    }
}
