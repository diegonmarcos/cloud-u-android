package com.diegonmarcos.clouddrive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #629 the seed's verdict, on the JVM. The device held NINE of twelve repositories while the
 * worker had reported success, so the assertions here are about exactly that shape: a pass that
 * did not reach every declared repository, or that failed on one, is INCOMPLETE and says so.
 */
class SeedReportTest {

    private fun ok(name: String) = SeedOutcome(name, SeedOutcome.SEEDED, "/store/git/$name")

    @Test fun everyDeclaredRepositorySeededIsComplete() {
        val report = SeedReport(3, listOf(ok("a"), ok("b"), SeedOutcome("c", SeedOutcome.PRESENT, "/store/git/c")))
        assertTrue(report.complete)
        assertEquals(3, report.present.size)
        assertTrue(report.tally().contains("3/3"))
    }

    /** THE MEASURED DEFECT: nine of twelve, the tail deferred. It must NOT read as complete. */
    @Test fun aTruncatedPassIsIncompleteAndNamesTheTail() {
        val done = (1..9).map { ok("repo-$it") }
        val tail = listOf("front-assets-cdn", "front-data", "front-diegonmarcos")
            .map { SeedOutcome(it, SeedOutcome.DEFERRED, "the worker was stopped before this repository was reached") }
        val report = SeedReport(12, done + tail)
        assertFalse("nine of twelve is not a finished store", report.complete)
        assertEquals(3, report.resumable.size)
        tail.forEach { assertTrue(report.tally().contains(it.name)) }
        assertTrue(report.tally().contains("9/12"))
    }

    /** One failed clone never hides the other eleven, and never passes as done. */
    @Test fun oneFailureIsReportedAndBlocksTheVerdict() {
        val report = SeedReport(2, listOf(ok("a"), SeedOutcome("b", SeedOutcome.FAILED, "connection reset")))
        assertFalse(report.complete)
        assertTrue(report.text().contains("failed b — connection reset"))
        assertTrue("the surviving clone is still reported", report.text().contains("seeded a"))
    }

    /** A repository missing from the outcomes at all is a defect, not a pass — no silent skip. */
    @Test fun aMissingOutcomeIsNotComplete() {
        assertFalse(SeedReport(12, listOf(ok("a"))).complete)
    }

    /** A repository the manifest cannot clone is settled, not resumable: retrying cannot help. */
    @Test fun anUndeclarableRepositoryDoesNotLoopForever() {
        val report = SeedReport(1, listOf(SeedOutcome("x", SeedOutcome.UNDECLARED, "no upstream instance declares a clone URL")))
        assertTrue(report.complete)
    }

    /** Every declared repository appears in the text, one line each. */
    @Test fun theReportHasOneLinePerRepositoryPlusTheTally() {
        val report = SeedReport(2, listOf(ok("a"), ok("b")))
        assertEquals(3, report.lines().size)
    }

    /**
     * #683 THE MEASURED DEFECT, second round: front-diegonmarcos, declared public but held
     * private upstream, failed its anonymous clone and the pass promised "will retry" — a retry
     * that can never succeed on a device with no credential. Wanting a credential is TERMINAL
     * (no retry loop), but the tally must still name the repository, say the way out, and must
     * NEVER call the store complete.
     */
    @Test fun aMissingCredentialIsTerminalNotARetry() {
        val eleven = (1..11).map { ok("repo-$it") }
        val starving = SeedOutcome(
            "front-diegonmarcos", SeedOutcome.NEEDS_CREDENTIAL,
            "Authentication is required but no CredentialsProvider has been registered — the vault import or a fleet sign-in delivers one",
        )
        val report = SeedReport(12, eleven + starving)
        assertTrue("a retry cannot mint a credential — the pass must be terminal", report.complete)
        assertEquals(0, report.resumable.size)
        assertEquals(1, report.blocked.size)
        assertFalse("a blocked repository must not be promised a retry", report.tally().contains("will retry"))
        assertFalse("a store missing a repository must not read as complete", report.tally().contains("complete"))
        assertTrue(report.tally().contains("front-diegonmarcos"))
        assertTrue(report.tally().contains("credential"))
        assertTrue("the blocked repository is not one of the present", report.tally().contains("11/12"))
    }

    /** #683 a genuinely transient failure beside a blocked repository still retries — for ITSELF. */
    @Test fun aTransientFailureBesideABlockedRepositoryStillRetries() {
        val report = SeedReport(3, listOf(
            ok("a"),
            SeedOutcome("b", SeedOutcome.FAILED, "connection reset"),
            SeedOutcome("c", SeedOutcome.NEEDS_CREDENTIAL, "no credential on this device"),
        ))
        assertFalse(report.complete)
        assertTrue(report.tally().contains("will retry: b"))
    }

    /** #683 the worker's structural-vs-transient split matches JGit's measured wording. */
    @Test fun theAuthDemandMatchesTheMeasuredTransportWording() {
        assertTrue(StoreSeedWorker.authDemanded("Authentication is required but no CredentialsProvider has been registered"))
        assertTrue(StoreSeedWorker.authDemanded("https://github.com/x/y.git: not authorized"))
        assertTrue(StoreSeedWorker.authDemanded("HTTP 401 Unauthorized"))
        assertFalse("a network blip must stay a transient FAILED", StoreSeedWorker.authDemanded("connection reset"))
        assertFalse(StoreSeedWorker.authDemanded("Read timed out"))
    }
}
