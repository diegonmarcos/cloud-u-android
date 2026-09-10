package com.diegonmarcos.superapp.apptabs

/**
 * What the Recent Tabs star draws: the pages the owner last opened INSIDE
 * cloud-superapp.
 *
 * This app has two other last-N lists and neither of them is this one:
 *   • Centauri's ring  — the last 9 ANDROID APPS opened on the phone
 *     (onehand/CentaurusStar, UsageStatsManager).
 *   • "Active Apps"    — the system Overview cards, also Android apps
 *     (onehand.recents_menu, `recents:cards`).
 * Both are about the phone's apps. This one is about this app's own
 * navigation history, which is why [pickRecentTabs] drops
 * [AppTabPrefs.Entry.ExternalAppEntry] outright: an Android app launched from
 * the Phone tab is recorded in the same LRU, and letting it through here would
 * leave the owner with three lists that look alike and disagree.
 *
 * No store of its own. The history is already written by
 * [AppTabPrefs] at the single navigation chokepoint, already persisted in
 * SharedPreferences, already newest-first and already de-duplicated by key on
 * write — a second store would be a second truth.
 *
 * Pure: no Android imports, so it is unit-testable on the JVM without a
 * device. Same shape as the Centauri star's `pickRecent`.
 */

/**
 * @param entries newest-first, as [AppTabPrefs.all] returns them.
 * @param isAlive does this entry's destination still exist? Injected because
 *   only the app knows its own section/page catalogue, and that catalogue is
 *   data the owner edits constantly — an entry stored last week can name a page
 *   that was renamed or deleted since, and the star must simply not offer it
 *   rather than navigate into nothing.
 * @param limit how many to draw; the star passes build.json::ui.app_tabs.star_cap.
 */
fun pickRecentTabs(
    entries: List<AppTabPrefs.Entry>,
    isAlive: (AppTabPrefs.Entry) -> Boolean,
    limit: Int,
): List<AppTabPrefs.Entry> {
    if (limit <= 0) return emptyList()
    return entries.asSequence()
        // Android apps are Centauri's subject, not this star's.
        .filterNot { it is AppTabPrefs.Entry.ExternalAppEntry }
        // A page that no longer exists is skipped, never drawn and never opened.
        .filter(isAlive)
        // Belt and braces over AppTabPrefs.push's move-to-top: the list is
        // newest-first, so the first sighting of a key is the most recent visit
        // and every later one is stale. Nine copies of one page is not a list.
        .distinctBy { it.key }
        .take(limit)
        .toList()
}
