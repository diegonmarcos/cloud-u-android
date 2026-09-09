package app.sterna.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST. It reads `SternaApp.kt` as TEXT, because the inbox host
 * cannot be composed on the JVM (no Robolectric in this module, and `InboxViewModel` needs the
 * repository). The decision it wires — [paneSplit] — IS executed by [PaneLayoutTest]; what no
 * executing test can see is whether the host hands that decision to `InboxScreen`, and which
 * ViewModel it hands with it (#103).
 *
 * Whole trimmed lines, never `contains` of a fragment: a fragment rule is blind to anything
 * appended to a line — `paneSplit(...)` followed by `?.takeIf { false }` still "contains" the call.
 */
class TwoPaneHostLintTest {

    /**
     * On a two-pane host the list, the reader and the composer must share ONE `InboxViewModel`:
     * the one owned by the "inbox" back-stack entry, which the reader and the composer already
     * resolve through `getBackStackEntry("inbox")`. A `viewModel()` with no owner resolves against
     * the nearest `ViewModelStoreOwner`, i.e. whatever destination is composing — a second store,
     * a second list state. The ONE empty-parens call the file keeps is the root's own default,
     * pinned here as a whole line so that nothing else can hide behind the exemption.
     */
    @Test fun `no destination resolves a ViewModel without naming its owner`() {
        assertEquals(
            "SternaApp.kt must not resolve a ViewModel with `viewModel()` (no owner) anywhere " +
                "but the root's own default parameter. A destination doing so gets the store of " +
                "whichever entry is composing — on the two-pane host, a list state the reader and " +
                "the composer do not share.",
            listOf("viewModel: RootViewModel = viewModel(),"),
            codeLines(STERNA_APP).filter { "viewModel()" in it },
        )
    }

    /**
     * The lines that make the "inbox" destination a two-pane host: the window WIDTH decides
     * (never the orientation), `panes` carries the decision (null = one pane), and the ViewModel
     * handed to the screen is the entry's own. The ARGUMENTS are pinned, not only the calls:
     * `panes = null,` or `detail = null,` at the call site keeps every declaration above intact
     * and gives every window one pane. Since V2 the pane READS the ViewModel's anchor and a tap
     * on a wide window WRITES it (`openInPane`) instead of navigating: `if (false)` there sends
     * every tap to the full-screen route again, with the pane forever on its invitation line.
     */
    @Test fun `the inbox destination decides on the window width and hands over its own ViewModel`() {
        assertTrue(
            "SternaApp.kt must decide the split from the window width, as the whole line " +
                "`val panes = paneSplit(LocalConfiguration.current.screenWidthDp)` — once, at the " +
                "host's level since V3, where the notification effect reads it too.",
            "val panes = paneSplit(LocalConfiguration.current.screenWidthDp)" in codeLines(STERNA_APP),
        )
        val block = inboxBlock()
        for (line in listOf(
            "val inboxViewModel: InboxViewModel = viewModel(entry)",
            "viewModel = inboxViewModel,",
            "panes = panes,",
            "val pane by inboxViewModel.readingPane.collectAsStateWithLifecycle()",
            "if (panes != null) inboxViewModel.openInPane(anchor)",
            "detail = panes?.let {",
            "state = pane,",
        )) {
            assertTrue(
                "the composable(\"inbox\") block of SternaApp.kt must contain, as a whole line, " +
                    "`$line`. Without it the host either does not decide on the window width, or " +
                    "hands InboxScreen a ViewModel that is not the inbox entry's own. Block read:\n" +
                    block.joinToString("\n"),
                line in block,
            )
        }
    }

    /**
     * The other end of the hand-over: InboxScreen must actually WRAP its Scaffold in the split it
     * was handed. `ListDetailPanes(null, detail) {` keeps every declaration above intact, compiles,
     * and gives every window one pane — no executing test can see it, the row is a Composable.
     */
    /**
     * Both taps of the list — a row, and a message inside an inline-expanded conversation — must
     * take the pane on a wide window. One of the two rewritten to navigate compiles, and every
     * whole-line rule above still finds its line in the other.
     */
    @Test fun `both taps of the list open in the pane on a wide window`() {
        val line = "if (panes != null) inboxViewModel.openInPane(anchor)"
        assertEquals(
            "the composable(\"inbox\") block of SternaApp.kt must contain `$line` EXACTLY twice: " +
                "once in onOpenEmail, once in onOpenThreadMessage. Fewer, and one of the two taps " +
                "pushes the full-screen route over the list on a wide window.",
            2,
            inboxBlock().count { it == line },
        )
    }

    /**
     * A tapped notification asks [PaneOrders] — executed by [PaneOrdersTest] — with the SPLIT and
     * the WHOLE stack, posts a lone anchor (no paging context, like the route it replaces), and
     * hands it to the list through the same held one-shot as the notification's folder. The
     * arguments are pinned: `emailOpen(true, …)` gives every window the pane, `emailOpen(…,
     * emptyList())` gives every stack the pane, and an anchor with `src = "list"` would page the
     * pane over a list the message is not in. The hand-over goes through `openFromNotification`,
     * never `openInPane`: only the parked form survives the account switch the same notification
     * asked for (`RefreshFreshnessWiringLintTest` pins both bodies).
     */
    @Test fun `a tapped notification asks PaneOrders with the split and the stack, and holds a lone anchor for the list`() {
        val code = codeLines(STERNA_APP)
        for (line in listOf(
            "when (PaneOrders.emailOpen(panes != null, nav.currentBackStack.value.map { it.destination.route })) {",
            "pendingPaneOpen = MessageAnchor(target.emailId, target.accountId, src = null, index = 0, thread = null)",
            "var pendingPaneOpen by remember { mutableStateOf<MessageAnchor?>(null) }",
            "LaunchedEffect(pendingPaneOpen, listViewModel) {",
            "list.openFromNotification(anchor)",
        )) {
            assertTrue(
                "SternaApp.kt must contain, as a whole line, `$line` — the notification's message " +
                    "either lands in the pane with exactly these arguments, or it is lost on a cold " +
                    "start, or the verdict is no longer the executable rule's.",
                line in code,
            )
        }
    }

    /**
     * The width CONVERSIONS (#103, V4), which no executing test can reach: both live inside a
     * `composable(…)` lambda. [PaneConversionTest] runs the two verdicts; what it cannot see is
     * whether the host asks them, with which arguments, and what it does with the answer.
     *
     * The mutations these lines exist for, every one of which compiles and leaves the rest green:
     *  - either `if (…)` turned into `if (false)`: a rotation with a message open then keeps the
     *    restored reader full screen over a two-pane list, or strands an anchor on a narrow window
     *    where nothing draws it — the message the person was reading, gone;
     *  - `readerToPane(true, stack)`: every reader converts, the one opened from Search included,
     *    which pops the person onto results she then has to leave twice;
     *  - the `stack` read from anything but `nav.currentBackStack`, which is the restored state and
     *    the only thing that knows what is under the reader;
     *  - `MessageScreen` composed in the conversion arm as well (the `return@composable` dropped):
     *    a second `MessageViewModel` settles the same message a second time — a read receipt
     *    offered twice, a second decryption, a message marked read from a screen nobody looked at;
     *  - `withResumed` dropped: a restored entry is not RESUMED until its transition ends, so
     *    `navigateOnce` refuses and the conversion is lost in silence;
     *  - `onEmailOpened` dropped: nothing stages the return-flash, so the row no longer flashes on
     *    the way back out of the reader.
     *
     * What it does NOT cover, because it only asks whether a LINE belongs to the file (or, for
     * the two pinned below, to the conversion arm): the ORDER of these calls, their nesting, and a
     * whole block lifted out of the `withResumed` lambda — all three keep every line present and
     * this test green. The order of `closePane()` and `onEmailOpened` is not a rule anyway (see
     * the comment at that site); the nesting is, and nothing here reads it.
     */
    @Test fun `both width conversions are asked of PaneConversion and played once`() {
        val code = codeLines(STERNA_APP)
        for (line in listOf(
            // portrait -> wide: the restored full-screen reader moves into the pane.
            "val stack = remember(entry) { nav.currentBackStack.value.map { it.destination.route } }",
            "if (PaneConversion.readerToPane(panes != null, stack)) {",
            "inboxViewModel.openInPane(MessageAnchor.fromRoute(emailId, accountId, index, src, threadArg))",
            "entry.navigateOnce { nav.popBackStack() }",
            "Box(Modifier.fillMaxSize())",
            "return@composable",
            // wide -> narrow: the anchor goes back to being a route.
            "val anchor = pane.anchor",
            "if (PaneConversion.paneToReader(panes != null, anchor != null) && anchor != null) {",
            "entry.navigateOnce { nav.navigate(anchor.toRoute(Uri::encode)) }",
            "inboxViewModel.closePane()",
            "inboxViewModel.onEmailOpened(anchor.emailId)",
        )) {
            assertTrue(
                "SternaApp.kt must contain, as a whole line, `$line` — a rotation with a message " +
                    "open either converts it with exactly these arguments, or it converts the " +
                    "wrong readers, or it loses the message.",
                line in code,
            )
        }
        assertEquals(
            "both conversions must wait for their entry to be RESUMED (`entry.lifecycle." +
                "withResumed {`, twice): a restored entry is at most STARTED until its transition " +
                "ends, and navigateOnce evaluated before that drops the conversion without a word.",
            2,
            code.count { it == "entry.lifecycle.withResumed {" },
        )
        for (line in listOf(
            "val inboxEntry = remember(entry) { nav.getBackStackEntry(\"inbox\") }",
            "val inboxViewModel: InboxViewModel = viewModel(inboxEntry)",
        )) {
            assertTrue(
                "the reader -> pane conversion arm of SternaApp.kt must contain, as a whole line, " +
                    "`$line`. The anchor has to be written into the ViewModel owned by the " +
                    "\"inbox\" ENTRY. `viewModel(entry)` compiles, and the file keeps two more " +
                    "copies of both lines further down (the reader's own), so a whole-FILE rule " +
                    "stays green over it — while the anchor would land in a brand-new " +
                    "InboxViewModel belonging to the `message` entry, which the popBackStack() on " +
                    "the next line destroys. Every portrait -> landscape rotation with a message " +
                    "open would then leave the pane on its invitation line, the message gone. " +
                    "Arm read:\n" + conversionBlock().joinToString("\n"),
                line in conversionBlock(),
            )
        }
        assertEquals(
            "the reader must be composed exactly once in the `message` destination — the " +
                "conversion arm returns before it (`return@composable`, once in the file). " +
                "Composed on that pass too, a second MessageViewModel settles the same message a " +
                "second time.",
            1,
            code.count { it == "return@composable" },
        )
    }

    @Test fun `InboxScreen wraps its list in the split it was handed`() {
        assertTrue(
            "InboxScreen.kt must contain, as a whole trimmed line, `ListDetailPanes(panes, detail) {`. " +
                "Any other first argument (null, a constant) leaves the host's decision unused: " +
                "the pane would never appear, and every test above would stay green.",
            "ListDetailPanes(panes, detail) {" in codeLines(INBOX_SCREEN),
        )
    }

    /**
     * The trimmed code lines of the reader -> pane conversion arm: from the `if (` that opens it
     * down to its `return@composable`. Scoped, because the two lines pinned inside it exist twice
     * more further down the same file, where they belong to the reader itself.
     */
    private fun conversionBlock(): List<String> {
        val code = codeLines(STERNA_APP)
        val start = code.indexOf("if (PaneConversion.readerToPane(panes != null, stack)) {")
        assertTrue(
            "SternaApp.kt no longer opens the conversion arm with " +
                "`if (PaneConversion.readerToPane(panes != null, stack)) {`",
            start >= 0,
        )
        val end = (start + 1 until code.size).firstOrNull { code[it] == "return@composable" }
        assertTrue("the conversion arm of SternaApp.kt no longer ends on a `return@composable`", end != null)
        return code.subList(start, end!! + 1)
    }

    /** The trimmed code lines from `composable("inbox") { entry ->` up to the next `composable(`. */
    private fun inboxBlock(): List<String> {
        val code = codeLines(STERNA_APP)
        val start = code.indexOf("composable(\"inbox\") { entry ->")
        assertTrue("SternaApp.kt no longer has a line `composable(\"inbox\") { entry ->`", start >= 0)
        val end = (start + 1 until code.size).firstOrNull { code[it].startsWith("composable(") } ?: code.size
        return code.subList(start, end)
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
        const val INBOX_SCREEN = "app/src/main/kotlin/app/sterna/ui/inbox/InboxScreen.kt"
    }
}
