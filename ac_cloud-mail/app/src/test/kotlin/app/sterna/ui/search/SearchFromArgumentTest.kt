package app.sterna.ui.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * "Search this sender" hands the per-sender screen's address to the search as a nav argument, and
 */
class SearchFromArgumentTest {

    @Test fun `a criterion the user edited beats the one the caller navigated with`() {
        // The rule `q` already follows, and `from` now follows the same one — written once so the
        // two cannot drift. It is only visible after a process death: the nav argument stays
        // attached to the entry for ever, so preferring it would wipe the edited field every time
        // the process came back.
        assertEquals("typed@x", initialCriterion(saved = "typed@x", argument = "news@x"))
        assertEquals("news@x", initialCriterion(saved = null, argument = "news@x"))
        assertEquals(
            "a field cleared on purpose is a saved empty string, and must stay cleared",
            "",
            initialCriterion(saved = "", argument = "news@x"),
        )
        assertEquals("", initialCriterion(saved = null, argument = null))
    }

    @Test fun `both criteria that can arrive by navigation are restored by that one decision`() {
        // SOURCE LINT. `from` diverging from `q` is the failure this whole rule exists for: the
        // brief for it is "parity, not invention", and parity is exactly what a reviewer cannot
        // see — two lines that look alike and read two different keys.
        val body = source("app/sterna/ui/search/SearchViewModel.kt").readText()
        listOf(
            "text = initialCriterion(handle[KEY_TEXT], handle[SEARCH_QUERY_ARG]),",
            "from = initialCriterion(handle[KEY_FROM], handle[SEARCH_FROM_ARG]),",
        ).forEach { line ->
            assertTrue(
                "SearchViewModel.restoreForm must restore the criterion as '$line' — the saved " +
                    "value first, the nav argument second, through the decision a test runs",
                line in body,
            )
        }
    }

    @Test fun `the route carries the argument the ViewModel reads`() {
        // SOURCE LINT, and the expectation is BUILT FROM THE CONSTANT rather than typed out: the
        // route is a separate string literal, and the day someone renames the argument the two
        // stop agreeing silently — the field simply never fills, with nothing anywhere to say so.
        val graph = source("app/sterna/ui/SternaApp.kt").readText()
        assertTrue(
            "the search route must declare the '$SEARCH_FROM_ARG' query argument that " +
                "SearchViewModel reads. Route line was:\n" +
                graph.lines().firstOrNull { "route = \"search" in it },
            "&$SEARCH_FROM_ARG={$SEARCH_FROM_ARG}" in graph,
        )
        assertTrue(
            "…and declare it as a nullable navArgument with a null default, like 'q': without " +
                "that the route only matches when the caller supplies it, and the inbox's " +
                "'search?q=…' stops resolving",
            Regex("""navArgument\("$SEARCH_FROM_ARG"\)\s*\{\s*type = NavType\.StringType; nullable = true; defaultValue = null\s*}""")
                .containsMatchIn(graph),
        )
    }

    @Test fun `the per-sender screen navigates with that argument, and through the guard`() {
        // The other end of the wire. `navigateOnce` is NavHostSourceRulesTest's business; what is
        // pinned here is the ARGUMENT: a navigation to a bare "search" compiles, runs, opens an
        // empty form, and looks like the feature working badly rather than not being wired.
        val graph = source("app/sterna/ui/SternaApp.kt").readText()
        assertTrue(
            "the by-sender screen must open the search on the address, as " +
                "'search?$SEARCH_FROM_ARG=\${Uri.encode(from)}'",
            "\"search?$SEARCH_FROM_ARG=\${Uri.encode(from)}\"" in graph,
        )
        assertTrue(
            "…and MailBySenderScreen must be given that navigation as onOpenSearch",
            "onOpenSearch = { from ->" in graph,
        )
    }

    private companion object {
        val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, "app/src/main/kotlin/app/sterna/ui/SternaApp.kt").isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "the sources as text and needs a working directory inside the checkout",
                )
        }

        fun source(relative: String): File = File(root, "app/src/main/kotlin/$relative")
    }
}
