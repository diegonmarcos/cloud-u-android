package app.sterna.core.data.account

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * SOURCE LINT, same instrument and disclaimer as [StoredViewStoreWiringTest]: it reads
 */
class RefreshFreshnessStoreWiringTest {

    @Test fun `the getter reads the freshness key, and nothing else`() {
        assertEquals(
            "AccountStore.refreshFreshness no longer reads KEY_FRESHNESS straight out of the " +
                "preferences. It is called from the InboxViewModel CONSTRUCTOR (the cold start is " +
                "#178's own case) and nothing executes it: pointed at another key it answers a " +
                "string decodeFreshness drops, the register reads empty for ever, and every refresh " +
                "the guard was meant to skip fires again with the suite green.",
            listOf("fun refreshFreshness(): String? = prefs.getString(KEY_FRESHNESS, null)"),
            block("fun refreshFreshness(", 1),
        )
    }

    @Test fun `the setter writes every register it is handed`() {
        assertEquals(
            "AccountStore.setRefreshFreshness no longer writes unconditionally to KEY_FRESHNESS. " +
                "It is called after every successful refresh and must overwrite: a guard in front " +
                "of the write freezes the register on its first value, so folders stay 'fresh' long " +
                "after they went stale, and a wrong key overwrites data this register does not own.",
            listOf(
                "fun setRefreshFreshness(value: String?) = " +
                    "prefs.edit().putString(KEY_FRESHNESS, value).apply()",
            ),
            block("fun setRefreshFreshness(", 1),
        )
    }

    @Test fun `the register has a key of its own`() {
        assertEquals(
            "KEY_FRESHNESS changed value. It shares a preferences file with KEY_CURRENT " +
                "(\"current\"), KEY_VIEW (\"current_view\") and the account list: pointed at any of " +
                "them it destroys data it does not own — the current account, lost across a cold " +
                "start, or the drawer memory. Changing it also forgets the register of every " +
                "install that has one, which is harmless (one extra refresh) but is a decision.",
            listOf("const val KEY_FRESHNESS = \"refresh_freshness\""),
            block("const val KEY_FRESHNESS", 1),
        )
    }

    /**
     * [count] consecutive CODE lines of `AccountStore.kt` from the single one starting with
     */
    private fun block(prefix: String, count: Int): List<String> {
        val path = "core/data/src/main/kotlin/app/sterna/core/data/account/AccountStore.kt"
        val root = generateSequence(java.io.File("").absoluteFile) { it.parentFile }
            .firstOrNull { java.io.File(it, path).isFile }
            ?: error("cannot locate the repo root from ${java.io.File("").absolutePath}")
        val code = java.io.File(root, path).readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("//") && !it.startsWith("*") && !it.startsWith("/*") }
        val starts = code.indices.filter { code[it].startsWith(prefix) }
        val start = starts.singleOrNull()
            ?: return listOf("«${starts.size} code lines start with `$prefix`»")
        return code.subList(start, minOf(start + count, code.size))
    }
}
