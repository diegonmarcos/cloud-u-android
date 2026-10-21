// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.inputlogic

/**
 * #1082: can backspace revert the last auto-correction? The word we committed is remembered in
 * LastComposedWord, but something else may rewrite the field after the commit (the grammar fixer
 * capitalises "must" to "Must" right after the separator). The old check threw a RuntimeException
 * on any difference, in every debug build. Now a difference in case only is fine (same length, the
 * revert deletes the same characters) and any other difference means "do not revert, backspace
 * normally". No Android classes here: it is unit tested on the JVM.
 */
object RevertCommitCheck {
    /**
     * The text actually in the field where [committed] was committed, or null when the field no
     * longer holds that word (not even ignoring case). [beforeCursor] is the text before the cursor
     * (at least committed + separator long); the separator is the last [separatorLength] chars.
     */
    @JvmStatic
    fun actualCommittedWord(committed: CharSequence, separatorLength: Int, beforeCursor: CharSequence?): CharSequence? {
        if (beforeCursor == null) return null
        val end = beforeCursor.length - separatorLength
        val start = end - committed.length
        if (start < 0 || end > beforeCursor.length) return null
        val actual = beforeCursor.subSequence(start, end)
        return if (actual.toString().equals(committed.toString(), ignoreCase = true)) actual else null
    }
}
