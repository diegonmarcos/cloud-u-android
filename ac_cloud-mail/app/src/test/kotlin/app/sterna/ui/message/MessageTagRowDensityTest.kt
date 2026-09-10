package app.sterna.ui.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The tags/folders strip under the sender, on the two properties that can honestly be checked
 * without a screen.
 *
 * There is no device in CI and no Compose test rule wired into this module, so NONE of this
 * measures pixels. What it does instead is hold the source to the shape the density work left it
 * in: every tag still reaches the screen, nothing in the strip claims a tap, and the dp literals
 * still add up to the height that was promised. Those are the three ways the fix can silently rot.
 *
 * The one thing deliberately NOT asserted here is what the strip looks like. Whether the pills read
 * as tags rather than as a smear of grey is a question for eyes, and a test that claimed to answer
 * it would be lying.
 */
class MessageTagRowDensityTest {

    // -- 1. no information lost --------------------------------------------------------------------

    /**
     * EVERY TAG STILL GETS DRAWN. This is the property that protects the owner from a "denser"
     * strip that is really just a shorter one: halving the height by showing half the tags would
     * satisfy every number in this file and defeat the entire request.
     *
     * Checked as the absence of a cap rather than the presence of a loop, because there are many
     * ways to drop a tag and only one of them looks like `take`. A `maxLines` on the `FlowRow`, or
     * an overflow other than the default, would clip wrapped lines away just as quietly.
     */
    @Test fun `the strip caps nothing — every tag reaches the screen`() {
        val body = bodyOf("MessageTagRow")
        assertTrue(
            "MessageTagRow must iterate the whole list; instead it reads:\n$body",
            body.contains("for (tag in tags)"),
        )
        listOf("take(", "drop(", "maxLines", "overflow", "filter", "distinct").forEach { cap ->
            assertTrue(
                "MessageTagRow contains `$cap`, which can keep a tag off screen. Density means " +
                    "more data per unit area, never less data. Body:\n$body",
                !body.contains(cap),
            )
        }
    }

    /**
     * AND EACH ONE STILL CARRIES ITS FULL LABEL AND ITS KIND. The pill draws `tag.label` — not a
     * prefix of it, not an initial — and picks its icon off `tag.kind`, so a mailbox is still told
     * apart from a keyword. Losing either would make the strip smaller by making it say less.
     */
    @Test fun `a pill shows the whole label and says which kind it is`() {
        val body = bodyOf("TagPill")
        assertTrue("the pill no longer draws tag.label:\n$body", body.contains("tag.label"))
        assertTrue(
            "the pill no longer distinguishes a mailbox from a keyword:\n$body",
            body.contains("TagKind.MAILBOX -> Icons.Filled.Folder") &&
                body.contains("TagKind.KEYWORD -> Icons.AutoMirrored.Filled.Label"),
        )
        assertTrue(
            "the pill dropped the kind's contentDescription, which is all a screen reader had:\n$body",
            body.contains("R.string.message_tag_mailbox") &&
                body.contains("R.string.message_tag_keyword"),
        )
    }

    // -- 2. why the pill is allowed to be this short -----------------------------------------------

    /**
     * NOTHING IN THE STRIP IS TAPPABLE, WHICH IS THE WHOLE LICENCE FOR A 24dp ROW.
     *
     * A 24dp control would be a bug — Material reserves 48dp under anything with an `onClick` for
     * exactly that reason, and this strip used to pay that 48dp for `AssistChip(enabled = false)`,
     * a chip that suppressed the tap and kept the reservation. Editing tags lives behind the
     * toolbar's tag icon, so there is no target here to protect.
     *
     * If a tap is ever added back to these pills, this test fails, and it should: the height has to
     * go back up with it.
     */
    @Test fun `the strip claims no tap, so it owes no touch target`() {
        val body = bodyOf("MessageTagRow") + bodyOf("TagPill")
        listOf("onClick", "clickable", "AssistChip", "InputChip", "FilterChip", "SuggestionChip")
            .forEach { tappable ->
                assertTrue(
                    "the tag strip now contains `$tappable`. Anything tappable owes a 48dp touch " +
                        "target, and the 24dp pill is only defensible while the strip is inert.",
                    !body.contains(tappable),
                )
            }
    }

    // -- 3. the height actually adds up to half ----------------------------------------------------

    /**
     * THE ARITHMETIC, PINNED TO THE SOURCE.
     *
     * What the strip cost before, per line of tags, measured off material3 1.3.1 rather than
     * guessed: `AssistChip` delegates to `Chip`, `Chip` wraps its content in `Surface(onClick = …)`,
     * and that `Surface` puts `minimumInteractiveComponentSize()` in the modifier chain AHEAD of
     * `clickable(enabled = …)` — so the 48dp reservation applied even at `enabled = false`. Plus the
     * strip's own 8dp bottom padding: 48 + 8 = 56dp for one line.
     *
     * What it costs now: a `labelLarge` line box (`LabelLargeLineHeight` = 20sp) is the tallest
     * thing in the pill, since the icon is 14dp, so the pill is 20 + 2 × its vertical padding, and
     * the strip adds its bottom padding on top.
     *
     * Both figures are COMPUTED FROM dp LITERALS, not observed on a screen. The point of computing
     * the second one from the file rather than writing `28` here is that padding is what creeps
     * back: this fails the moment someone puffs the pill out again.
     */
    @Test fun `one line of tags costs at most half of what it used to`() {
        val strip = bodyOf("MessageTagRow")
        val pill = bodyOf("TagPill")

        // The model only holds while labelLarge is the tallest thing in the pill.
        assertTrue(
            "the pill's label is no longer labelLarge, so the 20dp line box below is wrong:\n$pill",
            pill.contains("style = MaterialTheme.typography.labelLarge"),
        )
        assertTrue(
            "the pill's icon is no longer smaller than the 20dp line box, so it now sets the " +
                "height and the model below is wrong:\n$pill",
            dpIn(pill, """Modifier\.size\((\d+)\.dp\)""") < LABEL_LARGE_LINE_HEIGHT_DP,
        )

        val pillVerticalPadding = dpIn(pill, """vertical = (\d+)\.dp""")
        val stripBottomPadding = dpIn(strip, """bottom = (\d+)\.dp""")
        val after = LABEL_LARGE_LINE_HEIGHT_DP + 2 * pillVerticalPadding + stripBottomPadding

        assertEquals(
            "the computed single-line height moved: 20dp label + 2 × ${pillVerticalPadding}dp " +
                "pill padding + ${stripBottomPadding}dp strip padding",
            28,
            after,
        )
        assertTrue(
            "one line of tags computes to ${after}dp, and half of the old ${BEFORE_ONE_LINE_DP}dp " +
                "is ${BEFORE_ONE_LINE_DP / 2}dp. The owner asked for half, twice over on this app.",
            after <= BEFORE_ONE_LINE_DP / 2,
        )
    }

    private companion object {

        /** `AssistChipTokens.ContainerHeight` was 32dp, but `Surface` reserved 48dp regardless. */
        const val BEFORE_ONE_LINE_DP = 48 + 8

        /** `TypeScaleTokens.LabelLargeLineHeight`, material3 1.3.1. */
        const val LABEL_LARGE_LINE_HEIGHT_DP = 20

        const val MESSAGE_SCREEN_PATH = "app/src/main/kotlin/app/sterna/ui/message/MessageScreen.kt"

        val MESSAGE_SCREEN: File by lazy {
            val root = generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, MESSAGE_SCREEN_PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "$MESSAGE_SCREEN_PATH, because layout is what it is about",
                )
            File(root, MESSAGE_SCREEN_PATH)
        }

        val SOURCE: String by lazy { MESSAGE_SCREEN.readText() }

        /**
         * The body of a private composable, braces balanced, so an assertion about "the tag strip"
         * cannot accidentally be satisfied by something else in a 5000-line file.
         */
        fun bodyOf(name: String): String {
            val declaration = SOURCE.indexOf("private fun $name(")
            check(declaration >= 0) { "$name is gone from $MESSAGE_SCREEN_PATH" }
            val open = SOURCE.indexOf('{', SOURCE.indexOf(')', declaration))
            check(open >= 0) { "cannot find the opening brace of $name" }
            var depth = 0
            for (i in open until SOURCE.length) {
                when (SOURCE[i]) {
                    '{' -> depth++
                    '}' -> if (--depth == 0) return SOURCE.substring(open, i + 1)
                }
            }
            error("braces never balance after $name")
        }

        /** The single dp figure [pattern] captures, insisting there is exactly one to be sure of. */
        fun dpIn(body: String, pattern: String): Int {
            val hits = Regex(pattern).findAll(body).map { it.groupValues[1].toInt() }.toList()
            check(hits.size == 1) {
                "expected exactly one `$pattern` to read a dp value from, found $hits in:\n$body"
            }
            return hits.single()
        }
    }
}
