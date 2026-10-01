package com.diegonmarcos.clouddrive.configs

import com.diegonmarcos.clouddrive.sync.GitSyncCoordinator
import com.diegonmarcos.cloudlib.auth.AuthDeclaration
import com.diegonmarcos.cloudlib.gitsync.GitAuthChain
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * #735 gh's SIGN-IN REACHES THE CHAIN, executed. Measured on the phone: gh was signed in and
 * listed the account, yet the chain declined "no GitHub credential is on this device" and a
 * private clone had none — the github rung never asked the gh engine. These run the REAL
 * [DriveGitChain.rungs] over the fleet's declared shape (two fleet rungs, then github), with
 * a FAKE engine standing in for Cloud-Lib-Gh, through the REAL [GitAuthChain.resolve], and
 * then the clone's credential rule. Robolectric only for org.json and a Context.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DriveGitChainTest {

    private val ctx = RuntimeEnvironment.getApplication()
    private val id = "vault-git-https"
    private val ghToken = "gho_fake_engine_token"

    private fun rung(id: String, label: String, kind: String, at: Int) =
        AuthDeclaration.GitRung(id, label, kind, at, JSONObject().put("repos_url", "https://git.example.test/api/repos"))

    /** The declared order the phone runs: gitea, fleet, then github. No session: both fleet rungs fall through. */
    private val declared = listOf(
        rung("gitea", "Cloud git", DriveGitChain.RUNG_FLEET, 0),
        rung("fleet", "Cloud fleet", DriveGitChain.RUNG_FLEET, 1),
        rung("github", "GitHub", DriveGitChain.RUNG_GITHUB, 2),
    )

    private fun github(fromGh: () -> String?, held: () -> String) = DriveGitChain.githubAnswer(id, fromGh, held)

    private fun resolve(answer: () -> GitAuthChain.Answer) =
        GitAuthChain.resolve(DriveGitChain.rungs(ctx, declared, session = "", github = answer), store = null, credentialId = id)

    @Test fun aSignedInGhEngineAnswersTheChainsGithubRung() {
        val out = resolve { github(fromGh = { ghToken }, held = { "" }) }
        assertTrue(out.narrative(), out.ok)
        assertEquals("github", out.answeredBy)
        assertEquals(ghToken, out.token)
        // filed under the ONE declared id, by the walker's one setSecret
        assertEquals(id, out.credentialId)
        // never printed: not by the outcome, not by the page's narrative
        assertFalse(out.toString().contains(ghToken))
        assertFalse(out.narrative().contains(ghToken))
    }

    @Test fun aPrivateCloneOfARepoGhListedCarriesGhsCredential() {
        val auth = DriveGitChain.cloneAuth(viaFleet = false) { github(fromGh = { ghToken }, held = { "" }) }
        assertEquals(GitSyncCoordinator.AUTH_HTTPS, auth.kind)
        assertEquals(ghToken, auth.secret)
        assertFalse(auth.toString().contains(ghToken))
    }

    @Test fun noGhSignInAndNothingFiledDeclinesInWordsAndClonesAnonymously() {
        val out = resolve { github(fromGh = { null }, held = { "" }) }
        assertFalse(out.ok)
        assertNull(out.token)
        assertTrue(out.narrative(), out.narrative().contains("sign in on the GitHub card"))
        val auth = DriveGitChain.cloneAuth(viaFleet = false) { github(fromGh = { null }, held = { "" }) }
        assertEquals(GitSyncCoordinator.AUTH_NONE, auth.kind)
        assertEquals("", auth.secret)
    }

    @Test fun aCredentialTheSignInFiledAnswersWhenGhHoldsNone() {
        // the vault import, or a PAT the sign-in delivered, under the declared id
        val out = resolve { github(fromGh = { null }, held = { "ghp_filed_pat" }) }
        assertEquals("github", out.answeredBy)
        assertEquals("ghp_filed_pat", out.token)
    }

    @Test fun ghsLiveCredentialWinsOverAFiledOne() {
        assertEquals(ghToken, resolve { github(fromGh = { ghToken }, held = { "ghp_filed_pat" }) }.token)
    }

    @Test fun aFleetCloneRidesTheSessionAndNeverAsksForTheGithubToken() {
        var asked = false
        val auth = DriveGitChain.cloneAuth(viaFleet = true) { asked = true; github(fromGh = { ghToken }, held = { "" }) }
        assertEquals(GitSyncCoordinator.AUTH_SESSION, auth.kind)
        assertEquals("", auth.secret)
        assertFalse(asked)
    }

    @Test fun noDeclaredIdIsNoImplementationAndGhIsNotAsked() {
        var asked = false
        val answer = DriveGitChain.githubAnswer("", { asked = true; ghToken }, { "" })
        assertTrue(answer is GitAuthChain.Answer.NoImplementation)
        assertFalse(asked)
    }
}
