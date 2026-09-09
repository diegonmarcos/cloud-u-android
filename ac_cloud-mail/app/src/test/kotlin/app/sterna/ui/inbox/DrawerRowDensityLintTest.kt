package app.sterna.ui.inbox

import app.sterna.ui.DRAWER_ROW_HEIGHT_DP
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
 * So the number is pinned here, in both directions, along with the MECHANISM that delivers it. A
 * future change that improves something else and inflates the rows on the way past fails here.
 */
class DrawerRowDensityLintTest {

    @Test
    fun `a drawer row is denser than Material's own row and no shorter than a tap target`() {
        assertTrue(
            "The drawer row height must be strictly under Material's " +
                "${MATERIAL_DRAWER_ROW_HEIGHT_DP}dp `ActiveIndicatorHeight`, or the app is paying " +
                "the token in full and the cap is buying nothing. Found ${DRAWER_ROW_HEIGHT_DP}dp.",
            DRAWER_ROW_HEIGHT_DP < MATERIAL_DRAWER_ROW_HEIGHT_DP,
        )
        assertTrue(
            "The drawer row height must not go under ${MIN_TOUCH_TARGET_DP}dp. The folder-options " +
                "IconButton measures itself at that height through " +
                "`minimumInteractiveComponentSize`, which reports max(content, 48dp) and IGNORES " +
                "the constraints handed to it — so a shorter row does not make a smaller button, " +
                "it makes a button drawn outside its own row. Found ${DRAWER_ROW_HEIGHT_DP}dp.",
            DRAWER_ROW_HEIGHT_DP >= MIN_TOUCH_TARGET_DP,
        )
    }

    @Test
    fun `a folder name that still wraps is not clipped by the shorter row`() {
        // Three labels are ACCEPTED as wrapping (test/drawer-folder-labels.json::known_wrapping):
        // an ellipsis would leave a folder whose name cannot be read. Capping the row is only
        // legitimate while two line boxes still fit inside the cap.
        val twoLines = 2 * LABEL_LARGE_LINE_HEIGHT_DP
        assertTrue(
            "A wrapped folder label draws two labelLarge line boxes — " +
                "2 x ${LABEL_LARGE_LINE_HEIGHT_DP}dp = ${twoLines}dp. The row cap is " +
                "${DRAWER_ROW_HEIGHT_DP}dp, which is less, so the names deliberately allowed to " +
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
        val text = INBOX_SCREEN.readText()
            .replace(Regex("""//[^\n]*"""), " ")
            .replace(Regex("""\s+"""), " ")
        val found = Regex("""val drawerRowModifier = Modifier [^;]*?\)\)""").find(text)
        checkNotNull(found) {
            "InboxScreen.kt is expected to declare `val drawerRowModifier = Modifier…` once, in " +
                "DrawerContent — the one place the sidebar's row height is set. It is not there, " +
                "so this rule is guarding nothing until the declaration is repaired."
        }
        return found.value.trim()
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

        /**
         * Material's `labelLarge` line height, which is what a drawer label draws in: the folder
         * labels set `fontSize` only, so the line box stays at the typography's own 20sp.
         */
        private const val LABEL_LARGE_LINE_HEIGHT_DP = 20

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
    }
}
