package app.sterna.core.jmap.model

import app.sterna.core.jmap.Jmap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [JmapSession.mailAccountIds] (issue #31): every mail-capable account of a login, primary
 * first, degrading to the single primary account on servers that omit accountCapabilities.
 */
class JmapSessionTest {

    private val mailCap = buildJsonObject { }

    private fun account(vararg capabilities: String) = JmapAccount(
        name = "acc",
        accountCapabilities = capabilities.associateWith { mailCap },
    )

    @Test fun listsEveryMailCapableAccountPrimaryFirst() {
        val session = JmapSession(
            apiUrl = "https://mail.example.com/jmap/api/",
            accounts = mapOf(
                "shared" to account(Jmap.MAIL_CAPABILITY),
                "own" to account(Jmap.MAIL_CAPABILITY),
                "contactsOnly" to account("urn:ietf:params:jmap:contacts"),
            ),
            primaryAccounts = mapOf(Jmap.MAIL_CAPABILITY to "own"),
        )

        // The primary leads even though the map lists "shared" first; the contacts-only
        // account is not a mail account and never surfaces.
        assertEquals(listOf("own", "shared"), session.mailAccountIds())
    }

    @Test fun fallsBackToThePrimaryWhenCapabilitiesAreAbsent() {
        val session = JmapSession(
            apiUrl = "https://mail.example.com/jmap/api/",
            accounts = mapOf("only" to JmapAccount(name = "acc")),
            primaryAccounts = mapOf(Jmap.MAIL_CAPABILITY to "only"),
        )

        assertEquals(listOf("only"), session.mailAccountIds())
    }

    @Test fun parsesAccountCapabilitiesFromSessionJson() {
        val json = Json { ignoreUnknownKeys = true }
        val session = json.decodeFromString(
            JmapSession.serializer(),
            """
            {
              "apiUrl": "https://mail.example.com/jmap/api/",
              "primaryAccounts": { "${Jmap.MAIL_CAPABILITY}": "a" },
              "accounts": {
                "a": { "name": "me@example.com",
                       "accountCapabilities": { "${Jmap.MAIL_CAPABILITY}": {} } },
                "b": { "name": "shared@example.com",
                       "accountCapabilities": { "${Jmap.MAIL_CAPABILITY}": {} } }
              }
            }
            """.trimIndent(),
        )

        assertEquals(listOf("a", "b"), session.mailAccountIds())
    }

    // ---- setBatchSize: how many ids one Email/set may carry ----

    private fun sessionAdvertising(core: String?) = JmapSession(
        apiUrl = "https://mail.example.com/jmap/api/",
        capabilities = buildMap {
            put(Jmap.MAIL_CAPABILITY, buildJsonObject { })
            if (core != null) put(Jmap.CORE_CAPABILITY, Json.parseToJsonElement(core).jsonObject)
        },
    )

    @Test fun setBatchSize_usesTheAdvertisedMaxObjectsInSet() {
        // Stalwart's measured value (02/08).
        assertEquals(500, sessionAdvertising("""{"maxObjectsInSet":500}""").setBatchSize())
    }

    @Test fun setBatchSize_respectsAServerSmallerThanOurFallback() {
        // A server's limit is a limit, not a suggestion: 3 means batches of 3, not of 100.
        assertEquals(3, sessionAdvertising("""{"maxObjectsInSet":3}""").setBatchSize())
        assertEquals(1, sessionAdvertising("""{"maxObjectsInSet":1}""").setBatchSize())
    }

    @Test fun setBatchSize_capsAnAbsurdlyLargeAdvertisedValue() {
        assertEquals(500, sessionAdvertising("""{"maxObjectsInSet":10000}""").setBatchSize())
        // Past Int.MAX_VALUE: must be capped, never wrapped back onto the fallback.
        assertEquals(500, sessionAdvertising("""{"maxObjectsInSet":9999999999}""").setBatchSize())
    }

    @Test fun setBatchSize_fallsBackWhenNothingUsableIsAdvertised() {
        assertEquals(100, sessionAdvertising("""{}""").setBatchSize())
        assertEquals(100, sessionAdvertising("""{"maxObjectsInSet":0}""").setBatchSize())
        assertEquals(100, sessionAdvertising("""{"maxObjectsInSet":-7}""").setBatchSize())
        assertEquals(100, sessionAdvertising("""{"maxObjectsInSet":"lots"}""").setBatchSize())
        assertEquals(100, sessionAdvertising("""{"maxObjectsInSet":null}""").setBatchSize())
    }

    @Test fun setBatchSize_fallsBackWhenTheCoreCapabilityIsAbsent() {
        assertEquals(100, sessionAdvertising(null).setBatchSize())
        assertEquals(100, JmapSession(apiUrl = "https://mail.example.com/jmap/api/").setBatchSize())
    }

    // ---- getBatchSize: how many ids one Email/get may ask for ----

    @Test fun getBatchSize_usesTheAdvertisedMaxObjectsInGet() {
        assertEquals(500, sessionAdvertising("""{"maxObjectsInGet":500}""").getBatchSize())
        assertEquals(3, sessionAdvertising("""{"maxObjectsInGet":3}""").getBatchSize())
    }

    @Test fun getBatchSize_capsAndFallsBackLikeItsTwin() {
        assertEquals(500, sessionAdvertising("""{"maxObjectsInGet":10000}""").getBatchSize())
        assertEquals(500, sessionAdvertising("""{"maxObjectsInGet":9999999999}""").getBatchSize())
        assertEquals(100, sessionAdvertising("""{}""").getBatchSize())
        assertEquals(100, sessionAdvertising("""{"maxObjectsInGet":0}""").getBatchSize())
        assertEquals(100, sessionAdvertising("""{"maxObjectsInGet":-7}""").getBatchSize())
        assertEquals(100, sessionAdvertising("""{"maxObjectsInGet":"lots"}""").getBatchSize())
        assertEquals(100, sessionAdvertising(null).getBatchSize())
    }

    @Test fun theTwoLimitsAreReadIndependently() {
        // RFC 8620 §2 advertises them separately and a server may well differ: a get carries whole
        // messages, a set carries ids. Reading one property for both would silently size a call
        // with the other's limit.
        val session = sessionAdvertising("""{"maxObjectsInGet":5,"maxObjectsInSet":300}""")
        assertEquals(5, session.getBatchSize())
        assertEquals(300, session.setBatchSize())
    }

    @Test fun oneLimitAdvertisedAloneDoesNotSizeTheOther() {
        val getOnly = sessionAdvertising("""{"maxObjectsInGet":7}""")
        assertEquals(7, getOnly.getBatchSize())
        assertEquals(100, getOnly.setBatchSize())

        val setOnly = sessionAdvertising("""{"maxObjectsInSet":7}""")
        assertEquals(7, setOnly.setBatchSize())
        assertEquals(100, setOnly.getBatchSize())
    }
}
