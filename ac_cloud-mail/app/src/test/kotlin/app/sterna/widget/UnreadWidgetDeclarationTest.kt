package app.sterna.widget

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — same instrument and disclaimer as
 */
class UnreadWidgetDeclarationTest {

    /**
     * The widget polls nothing. Any `updatePeriodMillis` other than 0 asks the system to wake
     */
    @Test fun `every widget asks the system for no periodic update`() {
        val found = INFO_FILES.associate {
            "${it.parentFile.name}/${it.name}" to codeLines(it).filter { l -> "updatePeriodMillis" in l }
        }
        assertEquals(
            "every appwidget-provider must carry exactly the line android:updatePeriodMillis=\"0\" " +
                "— a widget is redrawn when the system asks (placement, boot, app update) and " +
                "must never ask to be woken on a timer. ⭐ This sweeps EVERY appwidget_info_*.xml " +
                "of both resource directories, so a widget added later is covered the day it is " +
                "declared and not the day someone remembers to copy a test — and a declaration " +
                "that loses its res/xml-v31 twin (or gains one nobody wrote) is a missing key here.",
            mapOf(
                "xml/appwidget_info_recent.xml" to listOf(PERIOD),
                "xml/appwidget_info_unread.xml" to listOf(PERIOD),
                "xml-v31/appwidget_info_recent.xml" to listOf(PERIOD),
                "xml-v31/appwidget_info_unread.xml" to listOf(PERIOD),
            ),
            found,
        )
    }

    /**
     * The picker's entry name goes through the build placeholder, never a literal. The test app
     */
    @Test fun `the picker entry is a placeholder, resolved to the string in production and to a distinct label under -PtestApp`() {
        assertEquals(
            "the receiver's android:label must be the widgetLabel placeholder, and the build must " +
                "fill it with @string/widget_unread_title by default and with a recognisable " +
                "literal under -PtestApp. A literal in the manifest, or the same value on both " +
                "branches, gives the widget picker two indistinguishable entries. Expected:\n  " +
                RECEIVER_LABEL,
            listOf(
                RECEIVER_LABEL,
                "manifestPlaceholders[\"widgetLabel\"] = \"@string/widget_unread_title\"",
                "manifestPlaceholders[\"widgetLabel\"] = \"Unread count (test)\"",
            ),
            codeLines(MANIFEST).filter { "android:label=" in it && "widgetLabel" in it } +
                codeLines(GRADLE).filter { "widgetLabel" in it },
        )
    }

    /**
     * AND THE TEST LABEL IS INSIDE `if (testApp)` — WHICH THE RULE ABOVE CANNOT SEE.
     */
    @Test fun `the test app's label lives INSIDE the testApp gate, not merely somewhere in the build`() {
        assertEquals(
            "the -PtestApp overrides must sit inside `if (testApp) {`. Outside it they apply to " +
                "the production build: the widget picker would list \"Unread count (test)\" in " +
                "English for everyone, and the applicationId suffix and versionName suffix would " +
                "ride along with it.",
            listOf(
                "if (testApp) {",
                "applicationIdSuffix = \".test\"",
                "manifestPlaceholders[\"appLabel\"] = \"Cloud Mail (test)\"",
                "manifestPlaceholders[\"widgetLabel\"] = \"Unread count (test)\"",
                "manifestPlaceholders[\"latestWidgetLabel\"] = \"Latest messages (test)\"",
                "versionNameSuffix = \"-test\"",
                "}",
            ),
            blockContaining(GRADLE, "manifestPlaceholders[\"widgetLabel\"] = \"Unread count (test)\"", "if (testApp) {"),
        )
    }

    /**
     * Both layouts must have text in them, and NOT the same text.
     */
    @Test fun `the picker preview advertises the widget, and the runtime layout says the app is waking up`() {
        assertEquals(
            "res/xml-v31 must preview @layout/widget_unread_preview. Previewing the runtime " +
                "layout puts an ellipsis and the app's name in the widget picker: that is what the " +
                "cell shows before its first draw, and it says nothing about what the widget does.",
            listOf(PREVIEW),
            codeLines(INFO_V31).filter { "android:previewLayout=" in it },
        )
        assertEquals(
            "the preview layout must give both TextViews an android:text — an example figure, and " +
                "the label from a string the nine languages already carry. A preview without text " +
                "is the empty rectangle this rule exists to stop.",
            listOf("android:text=\"12\"", "android:text=\"@string/inbox_all_inboxes\""),
            codeLines(PREVIEW_LAYOUT).filter { it.startsWith("android:text=") },
        )
        assertEquals(
            "the production layout must give both TextViews an android:text too — it is the " +
                "initialLayout, and without it a cell just placed is a blank rectangle until the " +
                "first draw comes back, which on a cold start may be seconds away or never. The " +
                "figure is an ellipsis and the label is the app's own name, translated already.",
            listOf("android:text=\"…\"", "android:text=\"@string/app_name\""),
            codeLines(RUNTIME_LAYOUT).filter { it.startsWith("android:text=") },
        )
        assertEquals(
            "⛔ and the production figure must contain no digit. Whatever is written there is what " +
                "the cell shows before the first draw and after a draw that failed: a number would " +
                "sit there as a plausible, wrong count, and a \"0\" would say the mailbox is empty.",
            emptyList<String>(),
            codeLines(RUNTIME_LAYOUT)
                .filter { it.startsWith("android:text=") }
                .filter { line -> line.any { it.isDigit() } },
        )
    }

    /**
     * THE OTHER HALF OF THE RULE ABOVE, and on its own it looks like its opposite.
     */
    @Test fun `neither layout of the enlarged cell carries any text of its own`() {
        assertEquals(
            "the enlarged counter's layouts must declare no android:text at all. Neither is an " +
                "initialLayout: both are only ever handed over already filled in, so anything " +
                "written in them is drawn by the launcher BEFORE the rule that decides whether an " +
                "account may be named has run — the app-lock fallback included.",
            mapOf(
                "widget_unread_accounts.xml" to emptyList<String>(),
                "widget_unread_account_row.xml" to emptyList<String>(),
            ),
            ENLARGED_LAYOUTS.associate { file ->
                file.name to codeLines(file).filter { it.startsWith("android:text=") }
            },
        )
    }

    /**
     * THE WHOLE BODY OF `draw`, LINE BY LINE — and it is the only thing standing between this
     */
    @Test fun `the draw decides the total, the lock and the size map exactly once each`() {
        assertEquals(
            "UnreadWidgetDraw.draw must read the account list ONCE, total the breakdown for the " +
                "small layout, read the app lock from the store, and offer the ventilated layout " +
                "at the height its lines need and the plain one at SMALL — the whole of it " +
                "holdable only as text, " +
                "since nothing in this suite can render a RemoteViews (#112).",
            listOf(
                DRAW_SIGNATURE,
                "if (ids.isEmpty()) return",
                "val palette = WidgetThemeReader.palette(app)",
                "val store = app.container.accountStore",
                "val accounts = store.accounts()",
                "val target = UnreadWidgetTap.of(accounts)",
                "val small = tapped(app, renderTotal(app, UnreadWidgetContent.of(accounts, " +
                    "unreadByAccount.values.sum()), palette), target)",
                "val rows = UnreadWidgetContent.byAccount(accounts, unreadByAccount, store.appLockEnabled())",
                "val views =",
                "if (rows.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {",
                "val fontScale = app.resources.configuration.fontScale",
                "val ventilated = SizeF(VENTILATED_WIDTH_DP, ventilatedHeightDp(rows.size, fontScale))",
                "RemoteViews(mapOf(SMALL to small, ventilated to tapped(app, renderAccounts(app, rows, palette), target)))",
                "} else {",
                "small",
                "}",
                "val manager = AppWidgetManager.getInstance(app)",
                "ids.forEach { manager.updateAppWidget(it, views) }",
                "}",
            ),
            blockContaining(
                WIDGET_DRAW,
                "val rows = UnreadWidgetContent.byAccount(",
                DRAW_SIGNATURE,
            ),
        )
        assertEquals(
            "and the two entries themselves. SMALL must stay the widget's DECLARED minimum, or a " +
                "cell at minimum size matches no entry at all. ⛔ The other one is no longer a " +
                "constant beside it: its height is what THIS MANY lines need at THIS font scale, " +
                "read at draw time. A constant there is a promise about how many accounts the " +
                "reader has — at 110dp it held three, and the fourth was cut off by the edge with " +
                "nothing on screen to say so.",
            listOf(
                "val fontScale = app.resources.configuration.fontScale",
                "val ventilated = SizeF(VENTILATED_WIDTH_DP, ventilatedHeightDp(rows.size, fontScale))",
                "private val SMALL = SizeF(110f, 40f)",
            ),
            codeLines(WIDGET_DRAW)
                .filterNot { it.startsWith("*") || it.startsWith("/") }
                .filter {
                    it.startsWith("private val SMALL") || it.startsWith("val fontScale") ||
                        it.startsWith("val ventilated")
                },
        )
        assertEquals(
            "the numbers that height is built from, and their names. ⛔ Both dimensions of the " +
                "ventilated entry must stay STRICTLY above SMALL's 110x40 — 180 > 110, and a " +
                "floor of 41 > 40 — or the launcher's closest-fit may hand three account lines to " +
                "a cell dragged to the widget's own minimum. What each number MEANS is executed " +
                "in UnreadVentilatedHeightTest; this rule only holds them from drifting apart.",
            listOf(
                "internal const val VENTILATED_WIDTH_DP = 180f",
                "private const val CONTAINER_PADDING_DP = 16f",
                "private const val ROW_PADDING_DP = 8f",
                "private const val ROW_TEXT_DP = 20f",
                "private const val ROW_SLACK_DP = 2f",
                "private const val SMALLEST_VENTILATED_HEIGHT_DP = 41f",
            ),
            codeLines(WIDGET_CONTENT).filter {
                it.startsWith("internal const val VENTILATED") || it.startsWith("private const val")
            },
        )
        assertEquals(
            "⛔ AND THE TWO LAYOUTS THOSE NUMBERS WERE MEASURED OFF. A row is 4dp + 14sp + 4dp and " +
                "the container keeps 8dp all round: move any of them and the height asked for is " +
                "wrong by a few dp per line, which is the sliced last line coming back — with " +
                "every rule above still green, since nothing here renders anything. (The lines " +
                "carry their closing bracket because they are pinned WHOLE — a substring rule " +
                "here would be satisfied by a padding of 40dp.) " +
                "⚠ WHAT THIS DOES NOT CATCH, and it is why ROW_SLACK_DP exists: this reads the " +
                "attributes that are WRITTEN in these two files, so it holds them still — it can " +
                "say nothing about what a TextView actually measures, which is the font's own " +
                "line box and moves with the device. A view ADDED to either layout is caught " +
                "here only if it carries one of these attributes.",
            mapOf(
                // The two `wrap_content` heights are as load-bearing as the paddings: they are
                // what makes a row exactly its text's line box plus its own padding, which is the
                // whole of ROW_TEXT_DP + ROW_PADDING_DP. A fixed `48dp` here, or a `match_parent`,
                // and the model is a fiction while every rule above stays green.
                "widget_unread_accounts.xml" to listOf(
                    "android:layout_height=\"match_parent\"",
                    "android:padding=\"8dp\">",
                    "android:layout_height=\"wrap_content\"",
                ),
                "widget_unread_account_row.xml" to listOf(
                    "android:layout_height=\"wrap_content\"",
                    "android:paddingTop=\"4dp\"",
                    "android:paddingBottom=\"4dp\">",
                    "android:layout_height=\"wrap_content\"",
                    "android:textSize=\"14sp\" />",
                    "android:layout_height=\"wrap_content\"",
                    "android:layout_marginStart=\"8dp\"",
                    "android:textSize=\"14sp\"",
                ),
            ),
            ENLARGED_LAYOUTS.associate { file ->
                file.name to codeLines(file).filter {
                    it.startsWith("android:padding") || it.startsWith("android:textSize=") ||
                        it.startsWith("android:layout_margin") || it.startsWith("android:lineSpacing") ||
                        it.startsWith("android:minHeight") || it.startsWith("android:layout_height")
                }
            },
        )
        assertEquals(
            "SMALL must be exactly the minWidth/minHeight res/xml declares, in both variants.",
            mapOf(
                "xml/appwidget_info_unread.xml" to listOf(MIN_HEIGHT, MIN_WIDTH),
                "xml-v31/appwidget_info_unread.xml" to listOf(MIN_HEIGHT, MIN_WIDTH),
            ),
            INFO_FILES.filter { it.name == "appwidget_info_unread.xml" }.associate { file ->
                "${file.parentFile.name}/${file.name}" to
                    codeLines(file).filter { it.startsWith("android:minWidth=") || it.startsWith("android:minHeight=") }
            },
        )
    }

    /**
     * The weight goes to the NAME, and only to the name — the one line of the row layout that
     */
    @Test fun `the enlarged row gives its weight to the name, so the figure is never what is cut`() {
        assertEquals(
            "in widget_unread_account_row.xml the NAME must be the weighted, zero-width column " +
                "and the figure must be wrap_content with no weight. Reversed or removed, a long " +
                "account name pushes the unread figure off the end of the line on a narrow screen.",
            listOf(
                "android:layout_width=\"match_parent\"",
                "android:id=\"@+id/widget_unread_account_name\"",
                "android:layout_width=\"0dp\"",
                "android:layout_weight=\"1\"",
                "android:id=\"@+id/widget_unread_account_count\"",
                "android:layout_width=\"wrap_content\"",
            ),
            codeLines(ROW_LAYOUT).filter {
                it.startsWith("android:layout_width=") || it.startsWith("android:layout_weight=") ||
                    it.startsWith("android:id=")
            },
        )
    }

    /**
     * The file's lines, comments removed WHOLE — an `<!-- … -->` block is cut out before the split,
     */
    private fun codeLines(file: File): List<String> = file.readText()
        .replace(XML_COMMENT, "")
        .lines()
        .map { it.trim() }
        .filter { it.isNotBlank() && !it.startsWith("//") }

    /**
     * The whole brace-balanced block that opens with [opener] and contains [marker] — so a rule can
     */
    private fun blockContaining(file: File, marker: String, opener: String): List<String> {
        val lines = codeLines(file)
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
        /** Written with the character rather than an escape, so what is compared stays readable. */
        const val DOLLAR = '$'

        val XML_COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)

        const val PERIOD = "android:updatePeriodMillis=\"0\""

        const val PREVIEW = "android:previewLayout=\"@layout/widget_unread_preview\""

        val RECEIVER_LABEL = "android:label=\"$DOLLAR{widgetLabel}\">"

        /** Repo root, found by walking up from the module's working directory. */
        val ROOT: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, "app/src/main/AndroidManifest.xml").isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "the declaration as text and needs a working directory inside the checkout",
                )
        }

        val MANIFEST: File by lazy { File(ROOT, "app/src/main/AndroidManifest.xml") }
        val GRADLE: File by lazy { File(ROOT, "app/build.gradle.kts") }

        /**
         * EVERY widget declaration the app ships, both resource directories, found rather than
         */
        val INFO_FILES: List<File> by lazy {
            listOf("xml", "xml-v31")
                .flatMap { dir ->
                    (File(ROOT, "app/src/main/res/$dir").listFiles() ?: emptyArray<File>()).toList()
                        .filter { it.name.startsWith("appwidget_info_") && it.name.endsWith(".xml") }
                }
                .also { if (it.isEmpty()) error("no appwidget_info_*.xml under app/src/main/res — were they moved?") }
                .sortedBy { "${it.parentFile.name}/${it.name}" }
        }

        /** The COUNTER's Android 12+ declaration — the preview rule below is about that widget. */
        val INFO_V31: File by lazy {
            INFO_FILES.first { it.parentFile.name == "xml-v31" && it.name == "appwidget_info_unread.xml" }
        }

        /**
         * Pinned whole, `suspend` INCLUDED. The keyword is what buys the colour read: dropped, the
         */
        const val DRAW_SIGNATURE =
            "suspend fun draw(app: Application, ids: IntArray, unreadByAccount: Map<String, Int>) {"

        val RUNTIME_LAYOUT: File by lazy { layout("widget_unread") }
        val PREVIEW_LAYOUT: File by lazy { layout("widget_unread_preview") }

        const val MIN_WIDTH = "android:minWidth=\"110dp\""

        const val MIN_HEIGHT = "android:minHeight=\"40dp\""

        val WIDGET_DRAW: File by lazy {
            File(ROOT, "app/src/main/kotlin/app/sterna/widget/UnreadWidgetDraw.kt")
        }

        /** Where the size rule that can be RUN lives — the constants behind the ventilated entry. */
        val WIDGET_CONTENT: File by lazy {
            File(ROOT, "app/src/main/kotlin/app/sterna/widget/UnreadWidgetContent.kt")
        }

        val ROW_LAYOUT: File by lazy { layout("widget_unread_account_row") }

        /** The counter's ENLARGED layout and its row — the two the launcher only ever gets filled in. */
        val ENLARGED_LAYOUTS: List<File> by lazy {
            listOf(layout("widget_unread_accounts"), layout("widget_unread_account_row"))
        }

        private fun layout(name: String): File =
            File(ROOT, "app/src/main/res/layout/$name.xml").also {
                if (!it.isFile) error("the widget layout is not at ${it.path} — was it moved?")
            }
    }
}
