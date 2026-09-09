package app.sterna.ui

import app.sterna.core.data.settings.SwipeAction
import app.sterna.ui.inbox.SWIPE_CONFIG_UNLOADED
import app.sterna.ui.message.CONFIRM_LINKS_UNLOADED
import app.sterna.ui.message.PLAIN_TEXT_UNLOADED_FOR_IMAGES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Three settings that DRIVE AN ACTION rather than merely show a value, and the value each one
 */
class UnloadedSettingDefaultsTest {

    // -- 1. the values themselves, executed -------------------------------------------------------

    @Test fun `neither swipe direction acts before the stored actions are known`() {
        assertEquals(
            "the swipe-right action carried while the setting is still being read must be NONE: " +
                "a swipe that lands in that window would otherwise run an action the reader may " +
                "have turned off",
            SwipeAction.NONE,
            SWIPE_CONFIG_UNLOADED.right,
        )
        assertEquals(
            "same for swipe-left, and this is the destructive one: DELETE here is a message in " +
                "the Trash because DataStore was a frame late",
            SwipeAction.NONE,
            SWIPE_CONFIG_UNLOADED.left,
        )
    }

    @Test fun `a link tapped before the stored setting is known is confirmed`() {
        assertTrue(
            "the waiting value for confirmLinks must be true. false opens the tapped URL straight " +
                "away, which is the side that cannot be taken back: the page is fetched, the " +
                "tracking parameter is honoured, and no dialog ever appeared",
            CONFIRM_LINKS_UNLOADED,
        )
    }

    /**
     * The third one (#149), and the one that is TWO answers to one unread value: what is rendered,
     */
    @Test fun `an unknown reading mode counts as text for the remote images`() {
        assertTrue(
            "the waiting value the image decision reads must be true — 'as if plain text', i.e. no " +
                "remote image at all. false lets one frame fetch the images of a sender who is " +
                "already on the allowlist while the reading mode is still unknown, and a tracking " +
                "pixel that has fired cannot be un-fired. What is RENDERED in that window is the " +
                "opposite call and stays on the stored default: see plainTextForBody",
            PLAIN_TEXT_UNLOADED_FOR_IMAGES,
        )
    }

    // -- 2. the wiring, as a whole-line source lint -----------------------------------------------

    @Test fun `swipeConfig starts on the inert pair and nothing else`() {
        assertBlock(
            INBOX_VIEW_MODEL,
            listOf(
                "val swipeConfig: StateFlow<SwipeConfig> =",
                "combine(settings.swipeRightAction, settings.swipeLeftAction) { right, left ->",
                "SwipeConfig(right, left)",
                "}.stateIn(",
                "scope = viewModelScope,",
                "started = SharingStarted.WhileSubscribed(5_000),",
                "initialValue = SWIPE_CONFIG_UNLOADED,",
                ")",
            ),
            "the swipe configuration's stateIn",
        )
    }

    @Test fun `confirmLinks starts on the asking side and nothing else`() {
        assertBlock(
            MESSAGE_VIEW_MODEL,
            listOf(
                "val confirmLinks = settings.confirmLinks.stateIn(",
                "scope = viewModelScope,",
                "started = SharingStarted.WhileSubscribed(5_000),",
                "initialValue = CONFIRM_LINKS_UNLOADED,",
                ")",
            ),
            "the link-confirmation stateIn",
        )
    }

    /**
     * The reading-mode setting reaches the reader UNRESOLVED — as a nullable, with `null` meaning
     */
    @Test fun `the reader takes the reading-mode setting unresolved, null and all`() {
        assertBlock(
            MESSAGE_VIEW_MODEL,
            listOf(
                "val plainTextSetting: StateFlow<Boolean?> = settings.plainText.stateIn(",
                "scope = viewModelScope,",
                "started = SharingStarted.WhileSubscribed(5_000),",
                "initialValue = null,",
                ")",
            ),
            "the reading-mode setting's stateIn",
        )
    }

    /**
     * The negative screen for that one: the reader must not name the stored default at all. Its own
     */
    @Test fun `the repository's reading-mode default is not restated in the message view model`() {
        val offenders = codeLines(MESSAGE_VIEW_MODEL).filter { "PLAIN_TEXT_DEFAULT" in it }
        assertEquals(
            "MessageViewModel must not name PLAIN_TEXT_DEFAULT. Its waiting value is null, which is " +
                "the only thing that lets the reader tell 'not read yet' from 'read, and off' — the " +
                "two the body and the images answer differently. Found:\n" + offenders.joinToString("\n"),
            emptyList<String>(),
            offenders,
        )
    }

    /**
     * Both reading sites of the pager — the fixed toolbar's menu and the page's body — resolve the
     */
    @Test fun `both reader sites resolve the setting into two separate answers`() {
        listOf(
            "val plainTextSetting by viewModel.plainTextSetting.collectAsStateWithLifecycle()",
            "val plainText = plainTextForBody(plainTextOverride, plainTextSetting)",
            "val imageMode = plainTextForImages(plainTextOverride, plainTextSetting)",
        ).forEach { line -> assertLineCount(MESSAGE_SCREEN, line, 2, "the reader's reading-mode wiring") }
    }

    /**
     * The negative screen — a `contains`, deliberately: it looks for what must be absent.
     */
    @Test fun `the repository's swipe defaults are not restated in the inbox view model`() {
        val offenders = codeLines(INBOX_VIEW_MODEL).filter { "SwipeAction.TOGGLE_READ" in it || "SwipeAction.DELETE" in it }
        assertEquals(
            "InboxViewModel must not name the stored swipe defaults at all. They belong to " +
                "SettingsRepository, which is where the stored value is read against them; a copy " +
                "here is one that can drift, and seeded into a stateIn it is the defect itself. " +
                "Found:\n" + offenders.joinToString("\n"),
            emptyList<String>(),
            offenders,
        )
    }

    // -- 3. the screens take the waiting value as it comes, without reseeding it -------------------

    @Test fun `the inbox takes the swipe actions as the view model publishes them`() {
        assertSoleLine(
            INBOX_SCREEN,
            "val swipe by viewModel.swipeConfig.collectAsStateWithLifecycle()",
            "the inbox's collection of swipeConfig",
            "viewModel.swipeConfig",
        )
    }

    @Test fun `the reader takes the link setting as the view model publishes it`() {
        assertSoleLine(
            MESSAGE_SCREEN,
            "val confirmLinks by viewModel.confirmLinks.collectAsStateWithLifecycle()",
            "the reader's collection of confirmLinks",
            "viewModel.confirmLinks",
        )
    }

    // -- reading the sources ----------------------------------------------------------------------

    /**
     * Pins one WHOLE line that must appear in [file] EXACTLY ONCE.
     */
    private fun assertSoleLine(file: File, expected: String, what: String, near: String) {
        val lines = codeLines(file)
        val nearby = lines.filter { near in it }
        assertEquals(
            "$what must appear in ${file.name} exactly once and be written EXACTLY as:\n" +
                "  $expected\n" +
                "An argument added here — collectAsStateWithLifecycle(initialValue = …) — compiles, " +
                "since a StateFlow is also a Flow, and reinstates the very default the constant " +
                "exists to remove, one file past every other rule in this test. Lines mentioning " +
                "'$near':\n" + nearby.joinToString("\n").ifEmpty { "(none at all)" },
            1,
            lines.count { it == expected },
        )
    }

    /**
     * Pins one WHOLE line that must appear in [file] exactly [times] times.
     */
    private fun assertLineCount(file: File, expected: String, times: Int, what: String) {
        val lines = codeLines(file)
        val head = expected.substringBefore(" =").substringBefore(" by")
        assertEquals(
            "$what: the line\n  $expected\nmust appear in ${file.name} exactly $times times, " +
                "written EXACTLY like that. Anything appended to it — an `?: false`, a second " +
                "argument — compiles and is invisible to a `contains`. Lines starting '$head':\n" +
                lines.filter { it.startsWith(head) }.joinToString("\n").ifEmpty { "(none at all)" },
            times,
            lines.count { it == expected },
        )
    }

    /** Locates [expected]'s first line in [file] and compares the block that follows it, whole. */
    private fun assertBlock(file: File, expected: List<String>, what: String) {
        val lines = codeLines(file)
        val at = lines.indexOfFirst { it == expected.first() }
        check(at >= 0) {
            "no '${expected.first()}' in ${file.name} — $what is gone or was reshaped, and this " +
                "lint must be taught the new shape rather than left green over a block it never read"
        }
        val found = lines.subList(at, minOf(at + expected.size, lines.size))
        val mismatches = expected.indices.mapNotNull { i ->
            val actual = found.getOrNull(i)
            if (actual == expected[i]) null
            else "line ${i + 1} of $what: expected '${expected[i]}' but found '$actual'"
        }
        assertEquals(
            "$what is pinned WHOLE, line by line and in order. Nothing in this module can run it. " +
                "Mismatches:\n" + mismatches.joinToString("\n"),
            emptyList<String>(),
            mismatches,
        )
    }

    /** [file]'s lines, trimmed, comment-only lines dropped so no rule can be satisfied by prose. */
    private fun codeLines(file: File): List<String> = file.readLines().map { it.trim() }.filterNot {
        it.isEmpty() || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*")
    }

    private companion object {
        private const val INBOX_VIEW_MODEL_PATH =
            "app/src/main/kotlin/app/sterna/ui/inbox/InboxViewModel.kt"

        /** Repo root, walked up from the module's working directory. */
        val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, INBOX_VIEW_MODEL_PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this lint reads " +
                        "the sources as text and needs a working directory inside the checkout",
                )
        }

        val INBOX_VIEW_MODEL: File by lazy { File(root, INBOX_VIEW_MODEL_PATH) }
        val MESSAGE_VIEW_MODEL: File by lazy {
            File(root, "app/src/main/kotlin/app/sterna/ui/message/MessageViewModel.kt")
        }
        val INBOX_SCREEN: File by lazy {
            File(root, "app/src/main/kotlin/app/sterna/ui/inbox/InboxScreen.kt")
        }
        val MESSAGE_SCREEN: File by lazy {
            File(root, "app/src/main/kotlin/app/sterna/ui/message/MessageScreen.kt")
        }
    }
}
