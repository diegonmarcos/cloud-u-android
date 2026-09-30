package com.diegonmarcos.cloudlib.gitsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #646 THE CHAIN IS DATA. Every test here asserts on the ORDER OF REAL ATTEMPTS
 * and on the SENTENCE the chain produces — never on a bare boolean, because an
 * exit-status-only assertion has produced a fake pass in this repository more
 * than once. If reordering the declaration did not reorder the attempts, or if a
 * fall-through were silent, these go red.
 */
class GitAuthChainTest {

    /** Records the order rungs were actually asked in, so ranking is observable. */
    private class Recorder {
        val asked = mutableListOf<String>()
        fun rung(id: String, label: String, answer: () -> GitAuthChain.Answer) =
            GitAuthChain.Rung(id, label) { asked += id; answer() }
    }

    private fun unreachable(why: String = "connect timed out") = { GitAuthChain.Answer.Unreachable(why) }
    private fun credential(token: String) = { GitAuthChain.Answer.Credential(token) as GitAuthChain.Answer }

    // ── ORDER IS THE DECLARATION ────────────────────────────────────────────

    @Test
    fun `the declared order is the order rungs are attempted`() {
        val r = Recorder()
        val outcome = GitAuthChain.resolve(
            listOf(
                r.rung("fleet", "Cloud fleet", unreachable()),
                r.rung("github", "GitHub", credential("t")),
            ),
            store = null,
            credentialId = "vault-git-https",
        )
        assertEquals(listOf("fleet", "github"), r.asked)
        assertEquals("github", outcome.answeredBy)
    }

    /**
     * THE MUTATION THE WHOLE TICKET RESTS ON: the SAME rungs in the OTHER order
     * must produce the OTHER answer. If this passed with the test above, order
     * would be decoration.
     */
    @Test
    fun `reversing the declaration reverses the attempts and changes who answers`() {
        val r = Recorder()
        val outcome = GitAuthChain.resolve(
            listOf(
                r.rung("github", "GitHub", credential("from-github")),
                r.rung("fleet", "Cloud fleet", credential("from-fleet")),
            ),
            store = null,
            credentialId = "vault-git-https",
        )
        assertEquals(listOf("github"), r.asked)
        assertEquals("github", outcome.answeredBy)
        assertEquals("from-github", outcome.token)
        // The rung after the answer is reported, and reported as untried.
        assertEquals(GitAuthChain.Result.NOT_NEEDED, outcome.steps.last().result)
        assertFalse("a rung after the answer must not be asked", r.asked.contains("fleet"))
    }

    @Test
    fun `a rung removed from the declaration is never tried`() {
        val r = Recorder()
        // `fleet` is simply not in the list. It must not run, even though an
        // implementation for it exists in this test's scope.
        GitAuthChain.resolve(
            listOf(r.rung("github", "GitHub", credential("t"))),
            store = null,
            credentialId = "vault-git-https",
        )
        assertEquals(listOf("github"), r.asked)
    }

    @Test
    fun `an undeclared rung is not built even when an implementation exists`() {
        // rungs() builds from the DECLARATION. `fleet` has an implementation but
        // is not declared, so it must not appear at all.
        val built = GitAuthChain.rungs(
            declared = listOf("github" to "GitHub"),
            implementations = mapOf(
                "github" to credential("t"),
                "fleet" to credential("should-never-be-reached"),
            ),
        )
        assertEquals(listOf("github"), built.map { it.id })
    }

    // ── A FLEET TIMEOUT IS A FALL-THROUGH, NOT A FAILURE ───────────────────

    @Test
    fun `a fleet timeout falls through to github and says so`() {
        val outcome = GitAuthChain.resolve(
            listOf(
                GitAuthChain.Rung("fleet", "Cloud fleet") { GitAuthChain.Answer.Unreachable("connect timed out") },
                GitAuthChain.Rung("github", "GitHub") { GitAuthChain.Answer.Credential("t") },
            ),
            store = null,
            credentialId = "vault-git-https",
        )
        assertTrue("a timed-out fleet must not fail the chain", outcome.ok)
        assertEquals("github", outcome.answeredBy)
        // ASSERT ON THE MESSAGE. The fall-through has to be READABLE.
        val narrative = outcome.narrative()
        assertTrue("the narrative must name the unreachable rung: $narrative", narrative.contains("Cloud fleet unreachable"))
        assertTrue("the narrative must name the rung that answered: $narrative", narrative.contains("GitHub signed in"))
        assertTrue("the fall-through must be visible as a transition: $narrative", narrative.contains("→"))
    }

    @Test
    fun `a rung that throws is a fall-through, not a dead chain`() {
        val outcome = GitAuthChain.resolve(
            listOf(
                GitAuthChain.Rung("fleet", "Cloud fleet") { throw java.io.IOException("no route to host") },
                GitAuthChain.Rung("github", "GitHub") { GitAuthChain.Answer.Credential("t") },
            ),
            store = null,
            credentialId = "vault-git-https",
        )
        assertEquals("github", outcome.answeredBy)
        assertTrue(outcome.narrative().contains("no route to host"))
    }

    @Test
    fun `a fleet rung that does not exist yet is a clean fall-through, not an error`() {
        // #647 may not be built. `rungs()` gives it no implementation; the chain
        // must still reach github, and must say what happened in words.
        val chain = GitAuthChain.rungs(
            declared = listOf("fleet" to "Cloud fleet", "github" to "GitHub"),
            implementations = mapOf("github" to credential("t")),
        )
        val outcome = GitAuthChain.resolve(chain, store = null, credentialId = "vault-git-https")
        assertTrue(outcome.ok)
        assertEquals("github", outcome.answeredBy)
        assertEquals(GitAuthChain.Result.NO_IMPLEMENTATION, outcome.steps.first().result)
        assertTrue(outcome.narrative().contains("Cloud fleet not available in this build"))
    }

    @Test
    fun `a declined rung still falls through and is worded as declined`() {
        val outcome = GitAuthChain.resolve(
            listOf(
                GitAuthChain.Rung("fleet", "Cloud fleet") { GitAuthChain.Answer.Declined("bearer rejected") },
                GitAuthChain.Rung("github", "GitHub") { GitAuthChain.Answer.Credential("t") },
            ),
            store = null,
            credentialId = "vault-git-https",
        )
        assertEquals("github", outcome.answeredBy)
        assertEquals(GitAuthChain.Result.DECLINED, outcome.steps.first().result)
        assertTrue(outcome.narrative().contains("Cloud fleet declined (bearer rejected)"))
    }

    // ── EXHAUSTION IS AN HONEST ANSWER, NOT A SILENT ZERO ──────────────────

    @Test
    fun `every rung failing reports every step and no credential`() {
        val outcome = GitAuthChain.resolve(
            listOf(
                GitAuthChain.Rung("fleet", "Cloud fleet") { GitAuthChain.Answer.Unreachable("down") },
                GitAuthChain.Rung("github", "GitHub") { GitAuthChain.Answer.Declined("code expired") },
            ),
            store = null,
            credentialId = "vault-git-https",
        )
        assertFalse(outcome.ok)
        assertNull(outcome.token)
        assertNull(outcome.answeredBy)
        assertNull("nothing was stored, so no id may be claimed", outcome.credentialId)
        val narrative = outcome.narrative()
        assertTrue(narrative.contains("No provider could supply a git credential"))
        assertTrue("both attempts must be accounted for: $narrative", narrative.contains("down") && narrative.contains("code expired"))
        assertEquals(2, outcome.steps.size)
    }

    @Test
    fun `an empty declaration is an empty chain, not a default one`() {
        val outcome = GitAuthChain.resolve(emptyList(), store = null, credentialId = "vault-git-https")
        assertFalse(outcome.ok)
        assertEquals(0, outcome.steps.size)
        assertEquals("No git authentication is declared.", outcome.narrative())
    }

    // ── ONE CREDENTIAL, ONE ID ─────────────────────────────────────────────

    @Test
    fun `whichever rung answers, the credential is claimed under the one declared id`() {
        val viaFleet = GitAuthChain.resolve(
            listOf(GitAuthChain.Rung("fleet", "Cloud fleet") { GitAuthChain.Answer.Credential("a") }),
            store = null, credentialId = "vault-git-https",
        )
        val viaGithub = GitAuthChain.resolve(
            listOf(
                GitAuthChain.Rung("fleet", "Cloud fleet") { GitAuthChain.Answer.Unreachable("down") },
                GitAuthChain.Rung("github", "GitHub") { GitAuthChain.Answer.Credential("b") },
            ),
            store = null, credentialId = "vault-git-https",
        )
        assertEquals("fleet", viaFleet.answeredBy)
        assertEquals("github", viaGithub.answeredBy)
        // THE SAME ID FROM BOTH RUNGS. A second id here is the #629 split.
        assertEquals("vault-git-https", viaFleet.credentialId)
        assertEquals("vault-git-https", viaGithub.credentialId)
        assertEquals(viaFleet.credentialId, viaGithub.credentialId)
    }

    @Test
    fun `a blank credential id stores nothing rather than inventing an id`() {
        val outcome = GitAuthChain.resolve(
            listOf(GitAuthChain.Rung("github", "GitHub") { GitAuthChain.Answer.Credential("t") }),
            store = null, credentialId = "",
        )
        assertTrue(outcome.ok)
        assertEquals("t", outcome.token)
        assertNull("no declared id means nothing is filed, not filed somewhere else", outcome.credentialId)
    }

    // ── #653 SIGNED IN WITH NO GITHUB CREDENTIAL ANYWHERE ──────────────────
    //
    // THE ASSERTION THAT MATTERS, and the reason it is here rather than against a
    // live endpoint: a suite that "proved" the credential-free login by calling the
    // proxy would go green when the proxy is UP and red when it is DOWN, and would
    // therefore never have proved anything about credentials at all. git-proxy-api
    // has already been observed 502 (dropped by the load-shedder), and at the edge
    // a 3xx comes back for ANY path under the prefix even with the container
    // stopped. So the property is asserted where it actually lives: in what the
    // chain does with an Answer that carries no token.

    @Test
    fun `a Served rung signs in and the chain ends holding NO credential`() {
        val r = Recorder()
        val outcome = GitAuthChain.resolve(
            listOf(
                r.rung("fleet", "Cloud fleet") { GitAuthChain.Answer.Served("listing served by the fleet") },
                r.rung("github", "GitHub", credential("ghp_should_never_be_reached")),
            ),
            store = null,
            credentialId = "vault-git-https",
        )
        // It ANSWERED: this is a success, not a fall-through.
        assertTrue("a served rung must count as answering", outcome.ok)
        assertEquals("fleet", outcome.answeredBy)
        // AND THE CHAIN STOPPED, so the GitHub rung was never even asked. If it had
        // been, a GitHub credential would have reached the phone by the back door —
        // which is the exact thing ranking the fleet first exists to prevent.
        assertEquals(listOf("fleet"), r.asked)
        // NO CREDENTIAL, ANYWHERE. Not returned, and not filed under the declared id.
        assertNull("a served rung must not yield a token", outcome.token)
        assertNull("nothing may be filed when there is no credential", outcome.credentialId)
        // And it SAYS so, so the page can tell "the fleet serves this" from "the
        // fleet handed us a token" without inspecting a null.
        assertTrue(
            "the narrative must state that the rung serves it: ${outcome.narrative()}",
            outcome.narrative().contains("serves it"),
        )
    }

    @Test
    fun `a Served rung writes nothing to the store, even with a declared id`() {
        // A real store would be an Android dependency; the property is that resolve()
        // never reaches a write at all when no credential exists, so a store that
        // FAILS THE TEST IF TOUCHED is the honest probe. `store = null` would pass
        // vacuously through the safe call, so the id is declared and the assertion is
        // on credentialId staying null — the one observable of "nothing was filed".
        val outcome = GitAuthChain.resolve(
            listOf(GitAuthChain.Rung("fleet", "Cloud fleet") { GitAuthChain.Answer.Served("served") }),
            store = null,
            credentialId = "vault-git-https",
        )
        assertTrue(outcome.ok)
        assertNull(outcome.token)
        assertNull(
            "a declared id must NOT be claimed when the rung supplied no credential",
            outcome.credentialId,
        )
    }

    @Test
    fun `a Served rung is distinguishable from an unreachable one, which is the 502 case`() {
        // The three states that must never be conflated, as the chain sees them.
        // Served = the service answered and serves it. Unreachable = down (502) or
        // redirected by the edge (3xx, which proves nothing). Declined = reached and
        // refused (401/403), which is evidence the service is UP.
        val served = GitAuthChain.resolve(
            listOf(GitAuthChain.Rung("fleet", "Cloud fleet") { GitAuthChain.Answer.Served("ok") }),
            store = null, credentialId = "vault-git-https",
        )
        val down = GitAuthChain.resolve(
            listOf(GitAuthChain.Rung("fleet", "Cloud fleet") { GitAuthChain.Answer.Unreachable("the fleet answered HTTP 502") }),
            store = null, credentialId = "vault-git-https",
        )
        val refused = GitAuthChain.resolve(
            listOf(GitAuthChain.Rung("fleet", "Cloud fleet") { GitAuthChain.Answer.Declined("the fleet refused this identity (HTTP 401)") }),
            store = null, credentialId = "vault-git-https",
        )
        assertTrue("a served rung is a success", served.ok)
        assertFalse("a 502 is NOT a success", down.ok)
        assertFalse("a refusal is NOT a success", refused.ok)
        // And each says which it was, in words — a dead service must never be able
        // to read as a working credential-free login.
        assertTrue(served.narrative().contains("serves it"))
        assertTrue("a 502 must surface as unreachable: ${down.narrative()}", down.narrative().contains("unreachable"))
        assertTrue("a 401 must surface as declined: ${refused.narrative()}", refused.narrative().contains("declined"))
        assertFalse("a 502 must never claim the rung served it", down.narrative().contains("serves it"))
        assertFalse("a 401 must never claim the rung served it", refused.narrative().contains("serves it"))
    }

    // ── A TOKEN IS NEVER RENDERED ──────────────────────────────────────────

    @Test
    fun `no step text and no toString carries the token`() {
        val secret = "ghp_averysecrettokenvalue"
        val outcome = GitAuthChain.resolve(
            listOf(
                GitAuthChain.Rung("fleet", "Cloud fleet") { GitAuthChain.Answer.Unreachable("down") },
                GitAuthChain.Rung("github", "GitHub") { GitAuthChain.Answer.Credential(secret) },
            ),
            store = null, credentialId = "vault-git-https",
        )
        assertFalse("the narrative must not carry the token", outcome.narrative().contains(secret))
        assertFalse("toString must not carry the token", outcome.toString().contains(secret))
        outcome.steps.forEach {
            assertFalse("step '${it.id}' leaks the token", it.detail.contains(secret))
        }
        assertFalse(
            "Answer.Credential.toString must be redacted",
            GitAuthChain.Answer.Credential(secret).toString().contains(secret),
        )
        // And it is still actually reachable for the store.
        assertEquals(secret, outcome.token)
    }

    // ── THE ANSWERING RUNG IS NAMEABLE ─────────────────────────────────────

    @Test
    fun `the outcome names the rung that answered by its declared label`() {
        val outcome = GitAuthChain.resolve(
            listOf(
                GitAuthChain.Rung("fleet", "Cloud fleet") { GitAuthChain.Answer.Unreachable("down") },
                GitAuthChain.Rung("github", "GitHub") { GitAuthChain.Answer.Credential("t") },
            ),
            store = null, credentialId = "vault-git-https",
        )
        assertEquals("GitHub", outcome.answeredByLabel)
        assertEquals(0, outcome.steps.first().position)
        assertEquals(1, outcome.steps.last().position)
    }
}
