// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.emoji

/**
 * #1077: the emoji panel's first page is "Last used" and "Pinned" as ONE page, pinned on top and the recent
 * ones below it. These are the rules of that page, pure so a test pins them.
 */
object EmojiMerge {
    /** Pinned first, then the recent ones that are not pinned (an emoji appears once). */
    fun <T> merge(pinned: List<T>, recent: List<T>): List<T> = pinned + recent.filter { it !in pinned }

    /** The merged page is the recents category; it is also the page every opening starts on. */
    const val FIRST_PAGE_CATEGORY = EmojiCategory.ID_RECENTS

    /** On the merged page a long press pins or unpins the emoji; on every other page it opens the skin-tone / colour picker. */
    fun pinsOnLongPress(categoryId: Int): Boolean = categoryId == EmojiCategory.ID_RECENTS
}

/** #1077: what the in-panel search box sends to the bundled emoji dictionary (names and keywords, offline). */
object EmojiSearchQuery {
    /** The words to look up: lower case, the inline-search colon dropped, blanks and repeats removed. */
    fun terms(raw: String): List<String> =
        raw.lowercase().replace(':', ' ').split(Regex("\\s+")).filter { it.isNotEmpty() }.distinct()
}
