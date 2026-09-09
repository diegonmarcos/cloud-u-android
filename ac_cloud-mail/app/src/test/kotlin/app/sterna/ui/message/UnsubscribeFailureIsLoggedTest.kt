package app.sterna.ui.message

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SOURCE RULE, AND THE WEAKEST TEST IN THIS LOT — kept because the alternative is no check at all
 * and the rule is real.
 *
 * `MessageViewModel.unsubscribe()` catches everything its coroutine can throw and turns it into
 * one sentence on screen: "Unsubscribe failed". That sentence is the same for a refused
 * `enqueueSend`, a missing identity, a locked database and a bug — so unless the throwable is
 * written somewhere, the reason it failed exists nowhere at all: not in logcat, not in a report,
 * not in a bug the maintainer could act on. Every comparable catch in this project logs.
 *
 * And a `CancellationException` must go straight back out. It is not a failure: it is the page
 * being closed, the pager moving on, the ViewModel being cleared. Caught like the rest, it
 * paints "Unsubscribe failed" on the way out of a screen nobody is looking at any more, and —
 * worse — swallows the cancellation the coroutine machinery was in the middle of propagating.
 *
 * Why not a behaviour test: [MessageViewModel] is an `AndroidViewModel` that reaches for the
 * application container, and this module has neither Robolectric nor an instrumentation harness
 * (junit and coroutines-test, nothing else). Read this as "these two lines are in that catch".
 */
class UnsubscribeFailureIsLoggedTest {

    @Test
    fun `a failed unsubscribe is written down, and a cancellation is not a failure`() {
        val body = unsubscribeFunctionBody()

        assertTrue(
            "unsubscribe() no longer re-throws CancellationException: closing the reader mid-send " +
                "would be reported to the reader as a failed unsubscribe",
            Regex("""catch\s*\(\s*(\w+):\s*CancellationException\s*\)\s*\{[^}]*throw\s+\1""")
                .containsMatchIn(body),
        )
        assertTrue(
            "unsubscribe() swallows its throwable without logging it — the reason a list was not " +
                "left would exist nowhere. Expected an android.util.Log call carrying the throwable.",
            Regex("""Log\.[wet]\(\s*"[^"]+",[^)]*,\s*t\s*\)""").containsMatchIn(body),
        )
    }

    /** The text of `fun unsubscribe()`, up to the next member declared at class indentation. */
    private fun unsubscribeFunctionBody(): String {
        val source = File(
            repoRoot,
            "app/src/main/kotlin/app/sterna/ui/message/MessageViewModel.kt",
        ).readText()
        val start = source.indexOf("fun unsubscribe()")
        assertTrue("MessageViewModel has no unsubscribe() any more", start > 0)
        val end = Regex("""\n {4}(private )?(suspend )?fun \w""").find(source, start + 1)
            ?.range?.first ?: source.length
        return source.substring(start, end)
    }

    private companion object {
        val repoRoot: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, "app/src/main/res/values/strings.xml").isFile }
                ?: error("cannot locate the checkout from ${File("").absolutePath}")
        }
    }
}
