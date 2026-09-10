package com.diegonmarcos.superapp.apps

import android.content.Context
import android.util.Base64
import com.diegonmarcos.superapp.BuildConfig
import org.json.JSONArray

/**
 * packageName -> CHANNEL CLASS, for Projects ▸ Inboxes.
 *
 * The page used to carry one card per app, so "is there anything for me in my
 * messages" had to be asked once per card and re-asked every time an app was
 * installed. It now carries one card per class — Mail, Chat, Messenger, RSS —
 * and this object is the only thing that decides which class a notification
 * belongs to.
 *
 * THE MEMBERSHIP IS NOT IN THIS FILE AND MUST NEVER BE. It is read from
 * `build.json::ui.inbox_classes`, baked into BuildConfig exactly the way
 * [PhoneFolders] reads `ui.phone_folders`. Classifying a newly installed app is
 * a one-line data edit, not a Kotlin edit — a `when (packageName)` here would
 * start rotting the day it was written and would put the owner's own phone
 * behind a release to reclassify one app.
 *
 * WHY A SECOND CLASSIFICATION EXISTS AT ALL, given that [PhoneTaxonomy] already
 * maps a package to a folder: the folder taxonomy cannot express the split the
 * owner asked for. `prod_chat` holds Mattermost and Rocket.Chat next to
 * WhatsApp, Telegram, Messenger and ntfy, and Chat and Messenger have to be
 * separate cards. So a class inherits from folders WHERE IT CAN — mail declares
 * `folders: [prod_mail]` and writes down no packages at all, which is what keeps
 * this from becoming a second copy of the mail roster — and names packages only
 * where the folders genuinely cannot answer.
 *
 * THE CATCH-ALL IS THE POINT. Four curated classes are not a catch-all, and the
 * page they replaced was. The class flagged `catch_all` receives every package
 * the others do not claim, so an app nobody has classified still reaches the
 * screen the owner checks. A screen that silently drops notifications is worse
 * than the per-app screen it replaced.
 */
object InboxClasses {

    /**
     * One card's worth of membership.
     *
     * @param folders  `ui.phone_folders` ids whose every member joins this class.
     * @param packages exact package ids, lowercased on load so the comparison
     *                 matches [PhoneAppClassifier]'s own case-insensitive rule —
     *                 Slack ships as `com.Slack`, and a case-sensitive lookup
     *                 would file it nowhere while looking perfectly correct.
     * @param source   "" for the phone notification feed, "c3ntfy" for a class
     *                 whose material is the ntfy channel stream instead.
     * @param catchAll receives every package no other class claims.
     * @param noteRes  name of a string resource the card prints under its
     *                 summary. Exists so a class can qualify its own numbers in
     *                 data rather than through a branch on the id in Kotlin —
     *                 mail uses it to say that it counts mail NOTIFICATIONS and
     *                 not mail, which is the difference between a smaller true
     *                 claim and a larger false one.
     */
    data class InboxClass(
        val id: String,
        val folders: List<String> = emptyList(),
        val packages: Set<String> = emptySet(),
        val source: String = "",
        val catchAll: Boolean = false,
        val noteRes: String = "",
    )

    /** The declared classes, in declared order — which is card order. */
    val classes: List<InboxClass> by lazy { loadFromBuildConfig() }

    private val byId: Map<String, InboxClass> by lazy { classes.associateBy { it.id } }

    /** The class declared under [id], or null when this build declares none. */
    fun byId(id: String): InboxClass? = byId[id]

    /** The catch-all, or null when the data declares none. Null is a real and
     *  reportable state: it means an unclassified app has nowhere to land, and
     *  the card says so rather than drawing a confident empty list. */
    val catchAll: InboxClass? by lazy { classes.firstOrNull { it.catchAll } }

    /**
     * Class id [packageName] belongs to, or "" when nothing claims it and no
     * catch-all is declared.
     *
     * Precedence is exact-package first, then folder, then the catch-all. An
     * explicit package therefore lifts one app out of an otherwise-inherited
     * folder without disturbing the folder — Trello sits in `.Calendar & Tasks`
     * for the launcher grid and on the Chat card here, and both are right,
     * because the grid and this page are answering different questions.
     *
     * [ctx] is passed through to [PhoneTaxonomy] so an app the keyword rules do
     * not know can still reach its folder through what Android publishes about
     * it, exactly as every other surface that classifies this feed does.
     */
    fun classIdOf(packageName: String, label: String, ctx: Context? = null): String {
        if (packageName.isBlank()) return ""
        val pkg = packageName.lowercase()
        classes.firstOrNull { pkg in it.packages }?.let { return it.id }
        // Only ask the folder taxonomy once, and only if some class inherits
        // folders at all — folderIdOf walks every folder's keyword list and can
        // reach PackageManager, which is pure waste on a page whose classes are
        // all package-declared.
        if (classes.any { it.folders.isNotEmpty() }) {
            val folder = PhoneTaxonomy.folderIdOf(packageName, label, ctx)
            if (folder.isNotEmpty()) {
                classes.firstOrNull { folder in it.folders }?.let { return it.id }
            }
        }
        return catchAll?.id.orEmpty()
    }

    /** True when [packageName] would be drawn by the class declared as [classId].
     *  The catch-all answers true for anything the other classes do not claim,
     *  which is exactly what stops a notification going missing. */
    fun claims(classId: String, packageName: String, label: String, ctx: Context? = null): Boolean =
        classId.isNotEmpty() && classIdOf(packageName, label, ctx) == classId

    /** An unreadable list costs this page every card, so the failure is caught
     *  and reported as an empty list rather than thrown into a fragment that
     *  cannot do anything about it — the same contract [PhoneFolders] keeps. */
    private fun loadFromBuildConfig(): List<InboxClass> = runCatching {
        val json = String(Base64.decode(BuildConfig.UI_INBOX_CLASSES_B64, Base64.NO_WRAP))
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { idx ->
            val o = arr.optJSONObject(idx) ?: return@mapNotNull null
            val id = o.optString("id")
            // An entry with no id cannot be named by a panel, so it could never
            // be drawn; keeping it would only let it swallow packages from the
            // catch-all and make them invisible.
            if (id.isBlank()) return@mapNotNull null
            InboxClass(
                id       = id,
                folders  = o.optJSONArray("folders").toStringList(),
                packages = o.optJSONArray("packages").toStringList().map { it.lowercase() }.toSet(),
                source   = o.optString("source", ""),
                catchAll = o.optBoolean("catch_all", false),
                noteRes  = o.optString("note_res", ""),
            )
        }
    }.getOrDefault(emptyList())

    private fun JSONArray?.toStringList(): List<String> =
        if (this == null) emptyList()
        else (0 until length()).mapNotNull { optString(it).takeIf { s -> s.isNotBlank() } }
}
