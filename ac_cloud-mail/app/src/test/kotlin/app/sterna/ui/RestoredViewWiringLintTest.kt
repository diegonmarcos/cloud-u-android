package app.sterna.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST. It reads `SternaApp.kt` as TEXT and compares whole trimmed
 */
class RestoredViewWiringLintTest {

    /**
     * THE ORDER IS THE GUARD, which is why this is a block and not two line checks — and the
     */
    @Test fun `the push flag is seeded from the stored view, immediately before the arm`() {
        assertEquals(
            "RootViewModel.refresh() must seed PushController.unifiedInboxVisible from the " +
                "RESTORED view and do it BEFORE arming. The arm reads that flag — inline on the " +
                "worker branch, later and on the service's own coroutine when it goes through " +
                "startForegroundService — while the list's own collector only sets it once the " +
                "NavHost has composed. Seeded after the call, or not at all, a cold start " +
                "reopening on 'All inboxes' arms as if a single folder were on screen (#179).",
            listOf(
                "PushController.unifiedInboxVisible = restoredUnifiedView()",
                "PushController.apply(getApplication(), userInitiated = true)",
            ),
            codeBlock(STERNA_APP, "PushController.unifiedInboxVisible = restoredUnifiedView()", 2),
        )
    }

    /**
     * `switchAccount()` KEEPS ITS OWN `= false`, and it is REDUNDANT — that is the whole point
     */
    @Test fun `switching account still clears the flag itself, before refreshing`() {
        assertEquals(
            "switchAccount() must keep setting PushController.unifiedInboxVisible = false before " +
                "refresh(). ⚠ It is redundant today — refresh() reseeds the flag on the next line, " +
                "and to false, since after setCurrent() the stored view belongs to the departing " +
                "account. It is kept, and pinned, because it costs nothing, states the switch's " +
                "reason at the switch, and covers any future arm that does not go through " +
                "refresh(). Delete it as a decision, not as dead code.",
            listOf(
                "fun switchAccount(id: String) {",
                "accountStore.setCurrent(id)",
                "PushController.unifiedInboxVisible = false",
                "refresh()",
            ),
            codeBlock(STERNA_APP, "fun switchAccount(id: String) {", 4),
        )
    }

    /**
     * `?:`, NEVER `== true`.
     */
    @Test fun `a notification falls back on the restored view when the list is not composed yet`() {
        assertEquals(
            "the notification switch must be told whether the RESTORED view is unified when there " +
                "is no list yet. With `== true` a cold start always answers false, and a tap on a " +
                "notification while 'All inboxes' was the view left behind switches accounts and " +
                "throws the reader into one account's folder (#179).",
            listOf(
                "NotificationAccountSwitch.resolve(",
                "notificationAccountId = target.accountId,",
                "currentAccountId = currentAccountId,",
                "knownAccountIds = accounts.map { it.id },",
                "unifiedView = listViewModel?.state?.value?.unified ?: restoredUnifiedView(),",
                ")?.let(onSwitchAccount)",
            ),
            codeBlock(STERNA_APP, "NotificationAccountSwitch.resolve(", 6),
        )
    }

    /**
     * The answer both rules above spend, and the one line that reads the memory for them.
     */
    @Test fun `the restored view is read once, through the shared adapter`() {
        assertEquals(
            "RootViewModel must expose the restored view as the unified question, answered by " +
                "restoredSelection(accountStore) — the same adapter InboxViewModel seeds its " +
                "selection from. A second hand-rolled read here is how the push arm and the list " +
                "come to disagree about the view the app just reopened on.",
            listOf("fun restoredUnifiedView(): Boolean = restoredSelection(accountStore) is Sel.Unified"),
            codeLines(STERNA_APP).filter { it.startsWith("fun restoredUnifiedView(") },
        )
        assertEquals(
            "MainNavHost must be handed RootViewModel's own answer. A literal { false } here, or " +
                "the parameter dropped and defaulted, restores the defect whole while every rule " +
                "above goes on agreeing with itself.",
            listOf("restoredUnifiedView = viewModel::restoredUnifiedView,"),
            codeLines(STERNA_APP).filter { it.startsWith("restoredUnifiedView = ") },
        )
        assertEquals(
            "MainNavHost's parameter must stay REQUIRED. Given a default — " +
                "`restoredUnifiedView: () -> Boolean = { false },` — the call site above goes on " +
                "passing the right thing and every rule here stays green, while any other caller, " +
                "today's or tomorrow's, silently gets the cold-start answer this branch exists to " +
                "stop trusting. Compared as the whole declaration line.",
            listOf("restoredUnifiedView: () -> Boolean,"),
            codeLines(STERNA_APP).filter { it.startsWith("restoredUnifiedView:") },
        )
    }

    /**
     * [count] consecutive CODE lines of [path] from the single one starting with [prefix], trimmed.
     */
    private fun codeBlock(path: String, prefix: String, count: Int): List<String> {
        val code = codeLines(path)
        val starts = code.indices.filter { code[it].startsWith(prefix) }
        val start = starts.singleOrNull()
            ?: return listOf("«${starts.size} code lines start with `$prefix`»")
        return code.subList(start, minOf(start + count, code.size))
    }

    /** The file's lines, trimmed, without comments or blanks. */
    private fun codeLines(path: String): List<String> {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, path).isFile }
            ?: error("cannot locate the repo root from ${File("").absolutePath}")
        return File(root, path).readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("//") && !it.startsWith("*") && !it.startsWith("/*") }
    }

    private companion object {
        const val STERNA_APP = "app/src/main/kotlin/app/sterna/ui/SternaApp.kt"
    }
}
