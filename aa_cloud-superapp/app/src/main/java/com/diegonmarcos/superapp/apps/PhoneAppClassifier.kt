package com.diegonmarcos.superapp.apps

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

/**
 * Pure function: given an installed Android app's `packageName` + user-
 * facing `label` (and, when the caller can supply it, the [AppMetadata]
 * Android itself publishes about the package), decide which
 * [PhoneFolders.Folder] it belongs to.
 *
 * Algorithm — deterministic + side-effect-free:
 *   1. KEYWORD PASS. Walk folders in declared order (already sorted by
 *      [PhoneFolders.loadFromBuildConfig]). For each folder, walk its
 *      `matchKeywords`. First keyword that matches the app wins; first
 *      matching folder wins overall.
 *   2. METADATA PASS. Only for apps no keyword claimed — see below.
 *   3. Folders with neither keywords nor metadata rules (the `misc`
 *      exile, the `others` sink) are skipped during matching.
 *   4. Still unclaimed apps fall through to the sink folder.
 *
 * Keyword syntax — supports five forms, chosen on first character(s):
 *
 *   pkg:com.foo.bar     exact packageName match (case-insensitive)
 *   pkg^com.foo.        packageName.startsWith() match
 *   lbl:Exact Label     label exact match (case-insensitive)
 *   lbl~substring       label contains substring (min 3 chars after `~`)
 *   <plain string>      legacy substring match on `packageName + " " + label`
 *                       — REQUIRES min length 4 to suppress catastrophic
 *                       false-positives ("bb" matching "bbva" + every package
 *                       containing the letters "bb" anywhere, etc.).
 *
 * Anchored forms (pkg:, pkg^, lbl:, lbl~) are the only way to express a
 * precise match. Plain strings are a fallback for sweeping common app
 * names ("santander", "wireguard") where collision risk is low — never
 * use plain strings shorter than 4 chars.
 *
 * ── Metadata pass ──────────────────────────────────────────────────
 * Keywords are hand-tuned per package, so every app the user installs
 * tomorrow lands in the sink until somebody edits build.json. That is
 * the failure this pass removes: it asks Android what the package
 * declares about itself and routes on THAT, using `match_metadata`
 * rules that live beside `match_keywords` in build.json.
 *
 *   cat:game            ApplicationInfo.category (the developer's own
 *                       declaration in the manifest)
 *   intent:android.intent.category.APP_EMAIL
 *                       a launcher intent category the package resolves
 *                       for — Android's own "this app fills that role"
 *   perm:android.permission.ACTIVITY_RECOGNITION
 *                       a permission declared in the manifest
 *
 * Precedence inside the pass is by SIGNAL STRENGTH, not folder order:
 * category (the app states its own genre) beats intent category (the
 * platform grants it a role) beats permission (we infer from what it
 * asks for). Folder order only breaks ties within one signal. Without
 * that split a storage permission on a game would beat `cat:game`
 * purely because the Storage folder sorts earlier.
 *
 * Keywords always win over metadata: the keyword lists are curated and
 * correct, and metadata is the fallback that keeps an un-curated app
 * off the sink pile — never a way to overrule a decision already made.
 */
object PhoneAppClassifier {

    private const val MIN_PLAIN_KEYWORD_LEN = 4
    private const val MIN_LBL_TILDE_LEN = 3

    private const val CATEGORY_RULE = "cat"
    private const val INTENT_RULE = "intent"
    private const val PERMISSION_RULE = "perm"

    /** Metadata signals from most to least authoritative — see the
     *  precedence note in the class doc. */
    private val METADATA_PRECEDENCE = listOf(CATEGORY_RULE, INTENT_RULE, PERMISSION_RULE)

    fun classify(
        packageName: String,
        label: String,
        folders: List<PhoneFolders.Folder>,
        metadata: AppMetadata = AppMetadata.NONE,
    ): String =
        matchByKeyword(packageName, label, folders)
            ?: matchByMetadata(metadata, folders)
            ?: PhoneFolders.sinkFolderId(folders)

    /** Folder id claimed by a keyword rule, or null when none matches. */
    private fun matchByKeyword(
        packageName: String,
        label: String,
        folders: List<PhoneFolders.Folder>,
    ): String? {
        val pkg = packageName.lowercase()
        val lbl = label.lowercase()
        val haystack = "$pkg $lbl"
        for (folder in folders) {
            if (folder.matchKeywords.isEmpty()) continue
            for (kw in folder.matchKeywords) {
                if (matches(kw, pkg, lbl, haystack)) return folder.id
            }
        }
        return null
    }

    /** Folder id claimed by a `match_metadata` rule, or null when none
     *  matches (including the "caller supplied no metadata" case). */
    private fun matchByMetadata(
        metadata: AppMetadata,
        folders: List<PhoneFolders.Folder>,
    ): String? {
        if (metadata == AppMetadata.NONE) return null
        for (signal in METADATA_PRECEDENCE) {
            val prefix = "$signal:"
            for (folder in folders) {
                for (rule in folder.matchMetadata) {
                    if (!rule.startsWith(prefix)) continue
                    if (metadataMatches(signal, rule.removePrefix(prefix), metadata)) return folder.id
                }
            }
        }
        return null
    }

    private fun metadataMatches(signal: String, value: String, metadata: AppMetadata): Boolean =
        when (signal) {
            // CATEGORY_UNDEFINED is the "developer said nothing" value, and
            // an undefined category must never match a rule that spelled a
            // category out — otherwise every silent app joins the first
            // cat: folder.
            CATEGORY_RULE -> metadata.category != ApplicationInfo.CATEGORY_UNDEFINED &&
                metadata.category == categoryIdOf(value)
            INTENT_RULE -> metadata.intentCategories.contains(value)
            PERMISSION_RULE -> metadata.permissions.contains(value)
            else -> false
        }

    /** build.json spells categories by the platform's own constant name,
     *  lowercased; the framework only exposes them as ints. */
    private fun categoryIdOf(name: String): Int = when (name) {
        "game" -> ApplicationInfo.CATEGORY_GAME
        "audio" -> ApplicationInfo.CATEGORY_AUDIO
        "video" -> ApplicationInfo.CATEGORY_VIDEO
        "image" -> ApplicationInfo.CATEGORY_IMAGE
        "social" -> ApplicationInfo.CATEGORY_SOCIAL
        "news" -> ApplicationInfo.CATEGORY_NEWS
        "maps" -> ApplicationInfo.CATEGORY_MAPS
        "productivity" -> ApplicationInfo.CATEGORY_PRODUCTIVITY
        "accessibility" -> ApplicationInfo.CATEGORY_ACCESSIBILITY
        else -> ApplicationInfo.CATEGORY_UNDEFINED
    }

    private fun matches(kw: String, pkg: String, lbl: String, haystack: String): Boolean {
        if (kw.isEmpty()) return false
        val k = kw.lowercase()
        return when {
            k.startsWith("pkg:") -> {
                val t = k.removePrefix("pkg:")
                t.isNotEmpty() && pkg == t
            }
            k.startsWith("pkg^") -> {
                val t = k.removePrefix("pkg^")
                t.isNotEmpty() && pkg.startsWith(t)
            }
            k.startsWith("lbl:") -> {
                val t = k.removePrefix("lbl:")
                t.isNotEmpty() && lbl == t
            }
            k.startsWith("lbl~") -> {
                val t = k.removePrefix("lbl~")
                t.length >= MIN_LBL_TILDE_LEN && lbl.contains(t)
            }
            else -> k.length >= MIN_PLAIN_KEYWORD_LEN && haystack.contains(k)
        }
    }

    /**
     * Read the metadata [packages] declare, but only the parts some
     * folder actually asks about — a build.json with no `perm:` rule
     * never pays for a GET_PERMISSIONS lookup, and the intent-category
     * probes cost one PackageManager query per DECLARED category rather
     * than per app.
     */
    fun metadataFor(
        ctx: Context,
        folders: List<PhoneFolders.Folder>,
        packages: Collection<String>,
    ): Map<String, AppMetadata> {
        val rules = folders.flatMapTo(mutableSetOf()) { it.matchMetadata }
        val wantsCategory = rules.any { it.startsWith("$CATEGORY_RULE:") }
        val wantsPermissions = rules.any { it.startsWith("$PERMISSION_RULE:") }
        val intentCategories = rules
            .filter { it.startsWith("$INTENT_RULE:") }
            .map { it.removePrefix("$INTENT_RULE:") }
        if (!wantsCategory && !wantsPermissions && intentCategories.isEmpty()) return emptyMap()

        val pm = ctx.packageManager
        val wanted = packages.toSet()
        if (wanted.isEmpty()) return emptyMap()

        val intentsByPackage = HashMap<String, MutableSet<String>>()
        for (category in intentCategories) {
            val probe = Intent(Intent.ACTION_MAIN).addCategory(category)
            val resolved = runCatching { pm.queryIntentActivities(probe, 0) }.getOrNull().orEmpty()
            for (info in resolved) {
                val pkg = info.activityInfo?.packageName ?: continue
                if (pkg in wanted) intentsByPackage.getOrPut(pkg) { mutableSetOf() }.add(category)
            }
        }

        return wanted.associateWith { pkg ->
            AppMetadata(
                category = if (!wantsCategory) ApplicationInfo.CATEGORY_UNDEFINED else runCatching {
                    pm.getApplicationInfo(pkg, 0).category
                }.getOrDefault(ApplicationInfo.CATEGORY_UNDEFINED),
                intentCategories = intentsByPackage[pkg].orEmpty(),
                permissions = if (!wantsPermissions) emptySet() else runCatching {
                    pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS)
                        .requestedPermissions?.toSet().orEmpty()
                }.getOrDefault(emptySet()),
            )
        }
    }

    /** Group a list of installed apps into a folderId → apps map.
     *  Apps inside each folder are sorted alphabetically by label.
     *
     *  Pass [ctx] to enable the metadata pass. Metadata is resolved ONLY
     *  for the apps the keyword pass left unclaimed, because that set is
     *  a handful on a curated device while the full launcher list is
     *  hundreds — reading PackageManager for all of them would cost a
     *  visible stall for answers we already have. */
    fun groupByFolder(
        apps: List<PhoneApp>,
        folders: List<PhoneFolders.Folder>,
        ctx: Context? = null,
    ): Map<String, List<PhoneApp>> {
        val grouped = LinkedHashMap<String, MutableList<PhoneApp>>()
        for (f in folders) grouped[f.id] = mutableListOf()

        val keywordIds = apps.map { matchByKeyword(it.packageName, it.label, folders) }
        val unclaimed = apps.filterIndexed { i, _ -> keywordIds[i] == null }.map { it.packageName }
        val metadata = if (ctx != null && unclaimed.isNotEmpty()) {
            metadataFor(ctx, folders, unclaimed)
        } else {
            emptyMap()
        }

        val sinkId = PhoneFolders.sinkFolderId(folders)
        for ((i, app) in apps.withIndex()) {
            val folderId = keywordIds[i]
                ?: metadata[app.packageName]?.let { matchByMetadata(it, folders) }
                ?: sinkId
            (grouped[folderId] ?: grouped.getOrPut(sinkId) { mutableListOf() }).add(app)
        }
        return grouped.mapValues { (_, v) -> v.sortedBy { it.label.lowercase() } }
    }
}

/**
 * What Android publishes about an installed package, reduced to the
 * three signals [PhoneAppClassifier] routes on. [AppMetadata.NONE] means
 * "the caller had no Context" — distinct from "read it, found nothing",
 * and the classifier skips the metadata pass entirely for it.
 */
data class AppMetadata(
    val category: Int = ApplicationInfo.CATEGORY_UNDEFINED,
    val intentCategories: Set<String> = emptySet(),
    val permissions: Set<String> = emptySet(),
) {
    companion object {
        val NONE = AppMetadata()
    }
}

/** Lightweight DTO for an installed launchable Android app — populated
 *  by [PhoneAppsFragment] from `LauncherApps.getActivityList(...)`. */
data class PhoneApp(
    val packageName: String,
    val activityComponent: android.content.ComponentName,
    val label: String,
    val icon: android.graphics.drawable.Drawable?,
    val user: android.os.UserHandle,
    /** PackageManager firstInstallTime (epoch ms); 0 if unknown. Drives the
     *  "New Apps" smart folder (recently_installed). */
    val firstInstallTime: Long = 0L,
)
