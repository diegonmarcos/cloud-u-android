package app.sterna.widget

import app.sterna.widget.DeclarationSource.file
import app.sterna.widget.DeclarationSource.functionBody
import app.sterna.widget.DeclarationSource.kotlinLines
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — the same instrument and the same disclaimer as
 */
class RecentMailWidgetPushWiringLintTest {

    /**
     * The whole block, and every line of it carries a defect if it is changed:
     */
    @Test fun `AppContainer collects the latest-messages push, gated on ITS OWN widget being placed`() {
        assertEquals(
            "AppContainer.init must launch RecentMailWidgetPush.redraws over " +
                "RecentMailWidgetPresence.placed(appContext) and accountStore.accountsFlow, reading " +
                "observeRecentUnifiedInbox(RecentMailWidgetDraw.ROW_LIMIT), and collect it by " +
                "forgetting the held snapshot and then refreshing. Dropped, the cell stops " +
                "following the mail the user is reading; ungated, every install pays for a widget " +
                "it does not have; without the forget(), a slow read redraws the stale rows; " +
                "without accountsFlow, the colour dots stay frozen when an account with no recent " +
                "mail arrives or leaves.",
            listOf(
                "appScope.launch {",
                "RecentMailWidgetPush.redraws(RecentMailWidgetPresence.placed(appContext), accountStore.accountsFlow) {",
                "mailRepository.observeRecentUnifiedInbox(RecentMailWidgetDraw.ROW_LIMIT)",
                "}.collect {",
                "RecentMailWidgetDraw.forget()",
                "runCatching { RecentMailWidgetDraw.refresh(appContext) }",
                ".rethrowIfCancelled()",
                ".onFailure { Log.w(\"Sterna\", \"latest-messages widget not redrawn; the cell keeps its rows\", it) }",
                "}",
                "}",
            ),
            blockContaining(STERNA_APPLICATION, "RecentMailWidgetPush.redraws(", "appScope.launch {"),
        )
    }

    /**
     * The two widgets keep TWO collections, and this is the rule that says so out loud.
     */
    @Test fun `each widget has its own collection, and neither gate is the other's`() {
        val lines = kotlinLines(STERNA_APPLICATION)
        assertEquals(
            "AppContainer.init must open exactly one push collection per widget, each on its own " +
                "presence, and each with its own account arm. A single shared collection ties one " +
                "widget's freshness to whether the other one is placed; and both need " +
                "accountStore.accountsFlow, because both are drawn differently above and below " +
                "AllInboxesView.existsFor — the dots on one, the label and the tap target on the " +
                "other — and neither read carries the account count.",
            listOf(
                "UnreadWidgetPush.redraws(UnreadWidgetPresence.placed(appContext), accountStore.accountsFlow) {",
                "RecentMailWidgetPush.redraws(RecentMailWidgetPresence.placed(appContext), accountStore.accountsFlow) {",
            ),
            lines.filter { it.startsWith("UnreadWidgetPush.redraws") || it.startsWith("RecentMailWidgetPush.redraws") },
        )
    }

    /**
     * THE SEED, whole lines — the gate's third writer, and the one nothing else in this
     */
    @Test fun `the seed asks about THIS widget's cells, and reads the answer the right way round`() {
        assertEquals(
            "RecentMailWidgetPresence.placed must seed itself from RecentMailWidgetDraw.placedIds " +
                "through anyWidgetPlaced, unnegated. A negation shuts the gate for everyone who " +
                "has this widget and opens it for everyone who has not; a missing seed leaves a " +
                "cell placed before the last reboot following nothing at all.",
            listOf(
                "fun placed(context: Context): StateFlow<Boolean> {",
                "if (!seeded) {",
                "seeded = true",
                "set(anyWidgetPlaced(RecentMailWidgetDraw.placedIds(context)))",
                "}",
                "return state",
            ),
            functionBody(PROVIDER, "fun placed(context: Context)"),
        )
    }

    /**
     * The whole brace-balanced block that opens with [opener] and contains [marker] — the launch
     */
    private fun blockContaining(file: File, marker: String, opener: String): List<String> {
        val lines = kotlinLines(file)
        val at = lines.indexOfFirst { marker in it }
        if (at < 0) return emptyList()
        val start = (at downTo 0).firstOrNull { lines[it] == opener }
            ?: error("no `$opener` above `$marker` in ${file.name}")
        val block = mutableListOf<String>()
        var depth = 0
        for (i in start until lines.size) {
            val line = lines[i]
            block += line
            depth += line.count { it == '{' } - line.count { it == '}' }
            if (depth == 0) return block
        }
        error("`$opener` in ${file.name} never closes")
    }

    private companion object {
        val STERNA_APPLICATION = file("app/src/main/kotlin/app/sterna/SternaApplication.kt")

        /** Holds `RecentMailWidgetPresence` as well as the provider itself. */
        val PROVIDER = file("app/src/main/kotlin/app/sterna/widget/RecentMailWidgetProvider.kt")
    }
}
