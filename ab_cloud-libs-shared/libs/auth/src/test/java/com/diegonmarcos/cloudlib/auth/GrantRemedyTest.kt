package com.diegonmarcos.cloudlib.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * #629 the declared error→remedy mapping, on the JVM.
 *
 * The error it exists for cannot be fixed in code: `device_flow_disabled - Device Flow must be
 * explicitly enabled for this App` is a switch in github.com/settings/apps, because the declared
 * client_id is a GitHub App and GitHub Apps ship with Device Flow off. So the only useful thing
 * the UI can do is name the switch and say that the vault-delivered credential is the supported
 * path — and that wording must come from ONE declared table, which is what these assert.
 */
@RunWith(RobolectricTestRunner::class)
class GrantRemedyTest {

    private val declared = listOf(
        AuthDeclaration.GrantRemedy("device_flow_disabled", "Enable it at github.com/settings/apps; the vault credential needs no browser.", "https://github.com/settings/apps"),
        AuthDeclaration.GrantRemedy("unauthorized_client", "Check the declared client_id.", ""),
    )

    @Test fun theDeclaredRowIsFoundInsideTheProvidersOwnErrorText() {
        val message = "device_flow_disabled - Device Flow must be explicitly enabled for this App"
        val hit = AuthDeclaration.remedyFor(message, declared)
        assertEquals("device_flow_disabled", hit?.match)
    }

    /** The error is kept VERBATIM and the remedy is added — never one instead of the other. */
    @Test fun explainKeepsTheErrorAndAddsTheRemedyAndTheUrl() {
        val message = "device_flow_disabled - Device Flow must be explicitly enabled for this App"
        val text = AuthDeclaration.explain(message, declared)
        assertTrue("the provider's own words survive", text.contains(message))
        assertTrue("the remedy names the switch", text.contains("github.com/settings/apps"))
        assertTrue("and the supported path", text.contains("vault"))
    }

    /** A row with no url adds no dangling line. */
    @Test fun aRemedyWithoutAUrlAddsNoUrlLine() {
        val text = AuthDeclaration.explain("unauthorized_client", declared)
        assertTrue(text.contains("Check the declared client_id."))
        assertTrue("no empty url line", !text.trimEnd().endsWith("\n"))
    }

    /** An unmatched message is shown unchanged: the table never invents a fix. */
    @Test fun anUnmatchedMessageIsUnchanged() {
        assertEquals("connection reset", AuthDeclaration.explain("connection reset", declared))
        assertNull(AuthDeclaration.remedyFor("connection reset", declared))
        assertNull(AuthDeclaration.remedyFor("", declared))
    }

    /**
     * MUTATION, as a test: with the mapping removed the wording collapses back to the bare error —
     * which is precisely the defect, so this pins that the mapping is what carries the remedy.
     */
    @Test fun withNothingDeclaredTheWordingIsTheBareError() {
        val message = "device_flow_disabled - Device Flow must be explicitly enabled for this App"
        assertEquals(message, AuthDeclaration.explain(message, emptyList()))
    }

    /** The declaration this module actually bakes must parse, and must cover the real error. */
    @Test fun theParserReadsTheDeclaredTable() {
        val parsed = AuthDeclaration.parse(
            """{"grant_remedies":[{"match":"device_flow_disabled","remedy":"do the thing","url":"https://x"},{"match":"","remedy":"ignored"}]}""",
        )
        assertEquals(1, parsed.grantRemedies.size)
        assertEquals("device_flow_disabled", parsed.grantRemedies.first().match)
        assertTrue(AuthDeclaration.parse("").grantRemedies.isEmpty())
    }
}
