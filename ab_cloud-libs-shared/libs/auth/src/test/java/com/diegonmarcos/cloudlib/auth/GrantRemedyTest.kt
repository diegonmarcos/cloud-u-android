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
 * A device-grant error whose fix is NOT in the code has to be worded, and that wording must come
 * from ONE declared table rather than from a Fragment — which is what these assert. #641 the rows
 * this was born for are gone with the provider they explained (the GitHub App's
 * `device_flow_disabled` dead end, deleted rather than documented), so the fixture below is a
 * plain table of its own: the MECHANISM is what is under test, and Google's device grant still
 * needs it.
 */
@RunWith(RobolectricTestRunner::class)
class GrantRemedyTest {

    private val declared = listOf(
        AuthDeclaration.GrantRemedy("expired_token", "The code expired before it was approved. Start again, or use the vault config import, which carries the credential already.", "https://example.invalid/help"),
        AuthDeclaration.GrantRemedy("unauthorized_client", "Check the declared client_id.", ""),
    )

    @Test fun theDeclaredRowIsFoundInsideTheProvidersOwnErrorText() {
        val message = "expired_token - the device code has expired"
        val hit = AuthDeclaration.remedyFor(message, declared)
        assertEquals("expired_token", hit?.match)
    }

    /** The error is kept VERBATIM and the remedy is added — never one instead of the other. */
    @Test fun explainKeepsTheErrorAndAddsTheRemedyAndTheUrl() {
        val message = "expired_token - the device code has expired"
        val text = AuthDeclaration.explain(message, declared)
        assertTrue("the provider's own words survive", text.contains(message))
        assertTrue("the remedy says what to do", text.contains("Start again"))
        assertTrue("the url is added", text.contains("https://example.invalid/help"))
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
        val message = "expired_token - the device code has expired"
        assertEquals(message, AuthDeclaration.explain(message, emptyList()))
    }

    /** The declaration this module actually bakes must parse, and a row matching nothing is dropped. */
    @Test fun theParserReadsTheDeclaredTable() {
        val parsed = AuthDeclaration.parse(
            """{"grant_remedies":[{"match":"expired_token","remedy":"do the thing","url":"https://x"},{"match":"","remedy":"ignored"}]}""",
        )
        assertEquals(1, parsed.grantRemedies.size)
        assertEquals("expired_token", parsed.grantRemedies.first().match)
        assertTrue(AuthDeclaration.parse("").grantRemedies.isEmpty())
    }
}
