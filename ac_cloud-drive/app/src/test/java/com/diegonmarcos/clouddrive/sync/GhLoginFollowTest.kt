package com.diegonmarcos.clouddrive.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The card's half of the GitHub sign-in: GhEngine.follow polls the engine, and the one-time code
 * must reach the card (onPrompt) while gh is STILL WAITING, not once gh exits. The engine's half —
 * a real process printing the code and blocking — is libs:gh's GhLoginTest. Here the engine is a
 * script of loginPoll answers, so each assertion says exactly which poll the card was on.
 */
class GhLoginFollowTest {

    private val url = "https://github.com/login/device"

    /** Serves [polls] in order and counts how many the card has consumed. */
    private class Engine(private val polls: List<GhEngine.Poll>) {
        var served = 0
        fun poll(): GhEngine.Poll = polls[minOf(served++, polls.lastIndex)]
    }

    private fun follow(engine: Engine, maxPolls: Int = 50, onPrompt: (String, String) -> Unit): GhEngine.Result {
        var left = maxPolls
        return GhEngine.follow({ engine.poll() }, { left-- > 0 }, onPrompt) {}
    }

    @Test
    fun theCodeReachesTheCardWhileGhIsStillWaiting() {
        val engine = Engine(listOf(
            GhEngine.Poll(running = true),
            GhEngine.Poll(code = "B1D3-EA43", url = url, running = true),
            GhEngine.Poll(code = "B1D3-EA43", url = url, running = true),
            GhEngine.Poll(code = "B1D3-EA43", url = url, running = true),
            GhEngine.Poll(code = "B1D3-EA43", url = url, running = false, exit = 0, output = "✓ Logged in as someone"),
        ))
        val shown = mutableListOf<Triple<String, String, Int>>()
        val r = follow(engine) { code, page -> shown += Triple(code, page, engine.served) }
        assertEquals("the code is shown once, on the poll that first carried it", listOf(Triple("B1D3-EA43", url, 2)), shown)
        assertTrue("…which is before gh's end was even polled", shown.single().third < 5)
        assertTrue(r.ok)
    }

    @Test
    fun aGhThatNeverEndsStillShowedItsCodeAndThenSaysItTimedOut() {
        val engine = Engine(listOf(GhEngine.Poll(code = "B1D3-EA43", url = url, running = true)))
        var shown = ""
        val r = follow(engine, maxPolls = 10) { code, _ -> shown = code }
        assertEquals("B1D3-EA43", shown)
        assertEquals(GhEngine.EXEC_FAILED, r.exitCode)
        assertTrue(r.output, r.output.startsWith("gh sign-in did not end within"))
    }

    @Test
    fun ghsFailureIsItsOwnWordsAndNoCodeIsShown() {
        val dns = "error connecting to github.com\ncheck your internet connection or https://githubstatus.com\n"
        val engine = Engine(listOf(GhEngine.Poll(running = false, exit = 1, output = dns)))
        var prompted = false
        val r = follow(engine) { _, _ -> prompted = true }
        assertFalse(prompted)
        assertEquals(1, r.exitCode)
        assertEquals("error connecting to github.com · check your internet connection or https://githubstatus.com", GhEngine.why(r.output))
    }

    @Test
    fun theDeviceFlowPromptIsNotAReason() {
        val out = listOf(
            "! First copy your one-time code: B1D3-EA43",
            "Open this URL to continue in your web browser: $url",
            "! Failed to copy one-time code to clipboard",
            "expired_token: the device code has expired",
        ).joinToString("\n")
        assertEquals("expired_token: the device code has expired", GhEngine.why(out))
        assertEquals("gh printed nothing", GhEngine.why("\n  \n"))
    }

    @Test
    fun anEngineThatStopsAnsweringIsNamed() {
        val engine = Engine(listOf(GhEngine.Poll(running = true), GhEngine.Poll(error = "com.diegonmarcos.cloudlib.gh did not answer loginPoll")))
        val r = follow(engine) { _, _ -> }
        assertEquals(GhEngine.EXEC_FAILED, r.exitCode)
        assertEquals("com.diegonmarcos.cloudlib.gh did not answer loginPoll", r.output)
    }
}
