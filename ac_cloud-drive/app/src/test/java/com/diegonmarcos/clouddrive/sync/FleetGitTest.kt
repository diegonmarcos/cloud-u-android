package com.diegonmarcos.clouddrive.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #669 the fleet clone leg's PURE rules, executed: which URL a fleet-listed repository
 * clones from (the DECLARED order over the rung's template and the listing's own URL),
 * and what a failed fleet clone tells the owner. Both run on the JVM against the exact
 * functions the phone runs; the declaration-side properties (the template is declared,
 * the order is declared, no Kotlin literal) are pinned by test-drive-git-auth-chain.sh.
 */
class FleetGitTest {

    private val template = "https://example.test/{owner}/{name}.git"

    @Test fun declaredOrderResolvesTheTemplateAgainstTheListingItemsOwnOwner() {
        // The item's owner, NEVER a page-declared one — re-templating onto a declared
        // owner is the wrong-leg clone c10 pins.
        assertEquals(
            "https://example.test/diego/cloud-me_data-pub.git",
            FleetGit.cloneUrlFrom(template, listOf("declared", "listed"), "diego", "cloud-me_data-pub", "http://mesh.internal/x.git"),
        )
    }

    @Test fun reorderingTheDeclarationReordersTheAttempt() {
        // The SAME inputs, the OTHER declared order: the listing's own URL wins. The
        // order is load-bearing data, not decoration.
        assertEquals(
            "http://mesh.internal/x.git",
            FleetGit.cloneUrlFrom(template, listOf("listed", "declared"), "diego", "cloud-me_data-pub", "http://mesh.internal/x.git"),
        )
    }

    @Test fun anUnresolvableEntryFallsToTheNextDeclaredOneAndNothingResolvableIsBlank() {
        // No template declared: `declared` cannot resolve, `listed` answers.
        assertEquals(
            "http://mesh.internal/x.git",
            FleetGit.cloneUrlFrom("", listOf("declared", "listed"), "diego", "repo", "http://mesh.internal/x.git"),
        )
        // A template with a blank owner must NOT emit a URL with a literal "{owner}"
        // hole in it — that would dial a nonsense path that 404s as a clean-looking miss.
        assertEquals(
            "",
            FleetGit.cloneUrlFrom(template, listOf("declared"), "", "repo", ""),
        )
        // An unknown source name resolves nothing rather than guessing.
        assertEquals("", FleetGit.cloneUrlFrom(template, listOf("mystery"), "diego", "repo", "http://mesh.internal/x.git"))
    }

    @Test fun aRedirectMidCloneNamesTheGateAndTheSignInThatFixesIt() {
        // JGit words a bounced clone in transport prose; whatever the exact phrasing,
        // the owner must read WHICH thing answered (the gate, not gitea) and what to
        // do next (sign in) — never a bare exception.
        for (why in listOf(
            "https://git.example/info/refs: 302 Found",
            "Redirection blocked: https://auth.example/?rd=...",
            "invalid advertisement of ...",
        )) {
            val explained = FleetGit.explainCloneFailure(why)
            assertTrue(explained, explained.contains("did not satisfy"))
            assertTrue(explained, explained.contains("sign-in"))
            assertTrue("the raw cause must survive: $explained", explained.contains(why))
        }
    }

    @Test fun aRefusalNamesTheFleetItselfNotTheGate() {
        val explained = FleetGit.explainCloneFailure("https://git.example/x.git: 401 Unauthorized")
        assertTrue(explained, explained.contains("refused"))
        assertTrue(explained, !explained.contains("did not satisfy"))
    }

    @Test fun anUnrecognisedFailurePassesThroughUnrewritten() {
        // An invented explanation is worse than a raw one.
        assertEquals("UnknownHostException: git.example", FleetGit.explainCloneFailure("UnknownHostException: git.example"))
    }
}
