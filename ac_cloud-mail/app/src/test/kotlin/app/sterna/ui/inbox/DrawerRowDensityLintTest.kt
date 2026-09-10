package app.sterna.ui.inbox

import app.sterna.ui.DRAWER_FOLDER_MENU_TAP_SIZE_DP
import app.sterna.ui.DRAWER_ROW_HEIGHT_DP
import app.sterna.ui.FOLDER_LABEL_LINE_HEIGHT_SP
import app.sterna.ui.MATERIAL_DRAWER_ROW_HEIGHT_DP
import app.sterna.ui.MIN_TOUCH_TARGET_DP
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT PLUS ARITHMETIC — the guard on how DENSE the folder sidebar is.
 *
 * This exists because the sidebar has already been made sparse once, by a change whose subject was
 * something else entirely (e8eb57108, which stopped folder names wrapping). Nothing in that commit
 * set a height: Material's `NavigationDrawerTokens.ActiveIndicatorHeight` was imposing 56dp per row
 * the whole time, and half the rows had simply been filling it with a second line of label. Take
 * the second line away and the 56dp is still charged — for 28 folders, on a phone.
 *
 * It then had to be made denser TWICE MORE (#211, #244) because the first two attempts lowered a
 * number that was not the binding constraint. Two things held the row at 48dp no matter what the
 * cap said, and neither was a padding value: an `IconButton` in the badge slot reserving
 * `minimumInteractiveComponentSize`, and a label whose line box was still Material's 20sp leading
 * because only `fontSize` had been overridden. So this file pins the MECHANISMS as well as the
 * number — a future change that restores either one puts the 16dp back per row while every
 * arithmetic assertion here still passes, which is exactly how this rotted twice.
 */
class DrawerRowDensityLintTest {

    @Test
    fun `a drawer row is denser than Material's own row`() {
        assertTrue(
            "The drawer row height must be strictly under Material's " +
                "${MATERIAL_DRAWER_ROW_HEIGHT_DP}dp `ActiveIndicatorHeight`, or the app is paying " +
                "the token in full and the cap is buying nothing. Found ${DRAWER_ROW_HEIGHT_DP}dp.",
            DRAWER_ROW_HEIGHT_DP < MATERIAL_DRAWER_ROW_HEIGHT_DP,
        )
    }

    /**
     * THE ROW IS UNDER MATERIAL'S TOUCH RESERVATION, AND IS ONLY ALLOWED TO BE BY THE TEST BELOW.
     *
     * This assertion used to read `DRAWER_ROW_HEIGHT_DP >= MIN_TOUCH_TARGET_DP`, and it was true,
     * and it was the reason the sidebar stayed sparse through two rounds of fixing: 48dp was
     * recorded as an immovable floor because an `IconButton` was charging it. The floor moved when
     * the button did. Asserted in the other direction now so that quietly restoring the reservation
     * — which would take the row back to 48dp — fails here rather than shipping.
     */
    @Test
    fun `the row costs less than the touch target an IconButton would have reserved`() {
        assertTrue(
            "The drawer row is ${DRAWER_ROW_HEIGHT_DP}dp and Material reserves " +
                "${MIN_TOUCH_TARGET_DP}dp under anything tappable. A row at or above that figure " +
                "means the reservation is back — almost certainly an `IconButton` returned to a " +
                "folder row — and the row is paying 24dp of blank to draw a 24dp glyph again.",
            DRAWER_ROW_HEIGHT_DP < MIN_TOUCH_TARGET_DP,
        )
        assertTrue(
            "The row must still hold the folder-options tap box " +
                "(${DRAWER_FOLDER_MENU_TAP_SIZE_DP}dp). A row shorter than its own control does " +
                "not shrink the control, it draws it outside the row.",
            DRAWER_ROW_HEIGHT_DP >= DRAWER_FOLDER_MENU_TAP_SIZE_DP,
        )
    }

    /**
     * WHY THE ROW MAY BE UNDER 48dp: nothing in a folder row reserves a touch target any more.
     *
     * The folder-options control is a sized, clipped, clickable `Icon` rather than an `IconButton`,
     * because `IconButton` applies `minimumInteractiveComponentSize()` unconditionally and that
     * modifier IGNORES the constraints handed to it — the row's `heightIn(max = …)` could not
     * contain it. This is the same structural fix the reading view's tag strip took (a01045f34).
     */
    @Test
    fun `no folder row reserves a touch target it cannot contain`() {
        val badge = folderRowBadgeSlot()
        assertTrue(
            "The folder row's badge slot contains `IconButton`, which reserves " +
                "${MIN_TOUCH_TARGET_DP}dp whatever the row's cap says and silently undoes the " +
                "row height. Use a sized clickable Icon, as the chevron beside it does. Found:\n$badge",
            !badge.contains("IconButton"),
        )
        assertTrue(
            "The folder-options tap box must be sized from DRAWER_FOLDER_MENU_TAP_SIZE_DP, so that " +
                "the row height and the width budget in DRAWER_FOLDER_ROW_CHROME_DP are computed " +
                "from the same figure the screen draws. Found:\n$badge",
            badge.contains("size(DRAWER_FOLDER_MENU_TAP_SIZE_DP.dp)"),
        )
        assertTrue(
            "The folder-options control lost its contentDescription. Shrinking the target is a " +
                "density decision; removing the only thing a screen reader had is not. Found:\n$badge",
            badge.contains("R.string.inbox_folder_options"),
        )
    }

    /**
     * WHY THE ROW MAY BE 32dp: the label's LINE BOX came down with its font size.
     *
     * `labelLarge.copy(fontSize = 12.sp)` leaves the 20sp line box in place, so the row was holding
     * two 20dp boxes — 40dp — for text drawn at 12sp. Setting the line height is what let the cap
     * reach 32. If a later edit drops `lineHeight` the box silently returns to 20sp, the wrapped
     * name below is clipped, and no number in this file would have noticed.
     */
    @Test
    fun `the label sets its line height and not only its font size`() {
        val label = drawerLabelDeclaration()
        assertTrue(
            "DrawerLabel must set `lineHeight = FOLDER_LABEL_LINE_HEIGHT_SP.sp`. Overriding only " +
                "`fontSize` keeps Material's 20sp leading for 12sp text, which is the 4sp per line " +
                "that held a 40dp floor under the sidebar. Found:\n$label",
            label.contains("lineHeight = FOLDER_LABEL_LINE_HEIGHT_SP.sp"),
        )
    }

    @Test
    fun `a folder name that still wraps is not clipped by the shorter row`() {
        // One label is ACCEPTED as wrapping (test/drawer-folder-labels.json::known_wrapping): an
        // ellipsis would leave a folder whose name cannot be read. Capping the row is only
        // legitimate while two line boxes still fit inside the cap. Computed from the line-height
        // constant, never from a literal — the literal is what goes stale when the font changes.
        val twoLines = 2 * FOLDER_LABEL_LINE_HEIGHT_SP
        assertTrue(
            "A wrapped folder label draws two line boxes — " +
                "2 x ${FOLDER_LABEL_LINE_HEIGHT_SP}dp = ${twoLines}dp. The row cap is " +
                "${DRAWER_ROW_HEIGHT_DP}dp, which is less, so the name deliberately allowed to " +
                "wrap would be cut in half instead. Either raise the cap or stop allowing wrapping " +
                "— but do not ship a folder whose second line is sliced off.",
            twoLines <= DRAWER_ROW_HEIGHT_DP,
        )
    }

    @Test
    fun `the row height reaches the rows as a maximum, because a minimum cannot beat Material's`() {
        val declaration = drawerRowModifierDeclaration()
        assertEquals(
            "The shared drawer row modifier must cap the row with `heightIn(max = ...)` fed by " +
                "`drawerRowHeight(...)`. This is not a style preference: Material applies its own " +
                "`heightIn(min = ${MATERIAL_DRAWER_ROW_HEIGHT_DP}.dp)` INSIDE " +
                "`NavigationDrawerItem`, and `heightIn` enforces the constraints coming in — so a " +
                "`min` written here is raised back to the token and silently buys nothing, while " +
                "a `max` is honoured. A change that reads as harmless and swaps one for the other " +
                "restores every dp of the gap. The declaration found was:\n$declaration",
            EXPECTED_ROW_MODIFIER_DECLARATION,
            declaration,
        )
    }

    // -- reading the source ------------------------------------------------------------------------

    /** The `val drawerRowModifier = …` declaration in DrawerContent, whole, whitespace collapsed. */
    private fun drawerRowModifierDeclaration(): String {
        val found = Regex("""val drawerRowModifier = Modifier [^;]*?\)\)""").find(FLAT)
        checkNotNull(found) {
            "InboxScreen.kt is expected to declare `val drawerRowModifier = Modifier…` once, in " +
                "DrawerContent — the one place the sidebar's row height is set. It is not there, " +
                "so this rule is guarding nothing until the declaration is repaired."
        }
        return found.value.trim()
    }

    /**
     * The `DrawerLabel` declaration and the style it builds, whitespace collapsed.
     *
     * A fixed window rather than balanced braces because the declaration is an expression body with
     * no braces to balance. It is generous enough to hold the whole `Text(...)` call and short
     * enough that it cannot reach the next composable; a `lineHeight` moved outside it reads here as
     * absent, which fails — the safe direction.
     */
    private fun drawerLabelDeclaration(): String {
        val start = FLAT.indexOf(LABEL_ANCHOR)
        check(start >= 0) {
            "$LABEL_ANCHOR is gone from $INBOX_SCREEN_PATH. Every drawer row draws its label " +
                "through it, so its absence means this rule and the sidebar shell tester both " +
                "guard nothing until it is repaired."
        }
        return FLAT.substring(start, (start + LABEL_WINDOW_CHARS).coerceAtMost(FLAT.length))
    }

    /**
     * The folder row's `badge = …` slot: from the badge parameter to the `selected =` that follows
     * it. Scoped to that span rather than searched for across the file, so an `IconButton` in the
     * account switcher at the top of the same sheet cannot fail a rule about folder rows — and so
     * one returning to the badge cannot hide behind the file's other legitimate uses.
     */
    private fun folderRowBadgeSlot(): String {
        val start = FLAT.indexOf(BADGE_ANCHOR)
        check(start >= 0) {
            "cannot find `$BADGE_ANCHOR` in $INBOX_SCREEN_PATH — the folder row's badge slot has " +
                "been restructured, and this rule is inert until the anchor is repaired."
        }
        val end = FLAT.indexOf(BADGE_END_ANCHOR, start)
        check(end >= 0) { "the folder row's badge slot is not followed by `$BADGE_END_ANCHOR`" }
        return FLAT.substring(start, end)
    }

    companion object {
        /**
         * The shared modifier, whole. Compared as the WHOLE declaration rather than searched for a
         * substring, so that adding vertical padding to it — the other way to undo the density —
         * fails here too.
         */
        private const val EXPECTED_ROW_MODIFIER_DECLARATION =
            "val drawerRowModifier = Modifier .padding(horizontal = 12.dp) " +
                ".heightIn(max = drawerRowHeight(LocalDensity.current))"

        private const val LABEL_ANCHOR = "private fun DrawerLabel(text: String)"

        /** Comfortably past the closing paren of DrawerLabel's `Text(...)`, nowhere near the next
         *  declaration — measured on the flattened source, where the whole body is under 200 chars. */
        private const val LABEL_WINDOW_CHARS = 400

        private const val BADGE_ANCHOR = "badge = if (mailbox.role !in watchMenuHiddenRoles)"
        private const val BADGE_END_ANCHOR = "selected = mailbox.id == ui.selectedMailboxId"

        private const val INBOX_SCREEN_PATH =
            "app/src/main/kotlin/app/sterna/ui/inbox/InboxScreen.kt"

        private val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, INBOX_SCREEN_PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "the source as text and needs a working directory inside the checkout",
                )
        }

        private val INBOX_SCREEN: File by lazy { File(root, INBOX_SCREEN_PATH) }

        /** The source with comments stripped and whitespace collapsed: a rule must match the CODE,
         *  never a comment that happens to quote the thing the rule forbids. */
        private val FLAT: String by lazy {
            INBOX_SCREEN.readText()
                .replace(Regex("""//[^\n]*"""), " ")
                .replace(Regex("""\s+"""), " ")
        }
    }
}
