package com.diegonmarcos.cloudsearch.core.agents

import com.diegonmarcos.cloudsearch.core.Fixtures
import com.diegonmarcos.cloudsearch.core.repoRoot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The client side of the two engine contracts, held equal to the engines' own sources. */
class IpcTest {
    private val cfg = Fixtures.cfg.agents!!
    private val mail = cfg.mail
    private val browser = cfg.browser

    private fun const(file: String, name: String): String {
        val src = File(repoRoot, file).readText()
        return Regex("""const val $name = "([^"]+)"""").find(src)?.groupValues?.get(1) ?: error("$name not in $file")
    }

    private fun strings(file: String, array: String): List<String> {
        val src = File(repoRoot, file).readText()
        val body = Regex("""$array: Array<String> = arrayOf\((.*?)\)""", RegexOption.DOT_MATCHES_ALL).find(src)!!.groupValues[1]
        return Regex(""""([a-z_]+)"""").findAll(body).map { it.groupValues[1] }.toList()
    }

    private val mailContract = "ac_cloud-mail/app/src/main/kotlin/app/sterna/agentapi/AgentMailContract.kt"
    private val browserContract = "ac_cloud-browser/app/src/main/java/com/diegonmarcos/cloudbrowser/agentapi/AgentApiContract.kt"

    @Test fun theMailContractIsTheEnginesOwn() {
        assertEquals(mail.authoritySuffix, const(mailContract, "AUTHORITY_SUFFIX"))
        assertEquals(mail.pathMessages, const(mailContract, "PATH_MESSAGES"))
        assertEquals(mail.pathBody, const(mailContract, "PATH_BODY"))
        assertEquals(mail.paramFrom, const(mailContract, "P_FROM"))
        assertEquals(mail.paramSubject, const(mailContract, "P_SUBJECT"))
        assertEquals(mail.paramSince, const(mailContract, "P_SINCE"))
        assertEquals(mail.paramLimit, const(mailContract, "P_LIMIT"))
        assertEquals(mail.paramAccount, const(mailContract, "P_ACCOUNT"))
        assertEquals(mail.paramId, const(mailContract, "P_ID"))
        assertEquals(mail.permission, const(mailContract, "PERMISSION"))
        assertEquals(mail.messageColumns, strings(mailContract, "MESSAGE_COLUMNS"))
        assertEquals(mail.bodyColumns, strings(mailContract, "BODY_COLUMNS"))
        assertEquals(mail.maxLimit, Regex("""const val MAX_LIMIT = (\d+)""").find(File(repoRoot, mailContract).readText())!!.groupValues[1].toInt())
    }

    @Test fun theBrowserContractIsTheEnginesOwn() {
        assertEquals(browser.authoritySuffix, const(browserContract, "AUTHORITY_SUFFIX"))
        assertEquals(browser.openAction, const(browserContract, "ACTION_OPEN"))
        assertEquals(browser.fetchMethod, const(browserContract, "METHOD_FETCH_TEXT"))
        assertEquals(browser.extraUrl, const(browserContract, "EXTRA_URL"))
        assertEquals(browser.extraGroup, const(browserContract, "EXTRA_GROUP"))
        assertEquals(browser.extraMaxChars, const(browserContract, "EXTRA_MAX_CHARS"))
        assertEquals(browser.permission, const(browserContract, "PERMISSION"))
    }

    @Test fun bothDoorsAreBehindTheConstellationPermission() {
        assertEquals("com.diegonmarcos.cloud.permission.CONSTELLATION_DATA", mail.permission)
        assertEquals(mail.permission, browser.permission)
        val mailManifest = File(repoRoot, "ac_cloud-mail/app/src/main/AndroidManifest.xml").readText()
        val browserManifest = File(repoRoot, "ac_cloud-browser/app/src/main/AndroidManifest.xml").readText()
        assertTrue(mailManifest.contains("android:permission=\"${mail.permission}\""))
        assertTrue(browserManifest.contains("android:permission=\"${browser.permission}\""))
    }

    @Test fun theMessagesUri() {
        val u = Ipc.messagesUri(mail, "com.diegonmarcos.comms.mail", "wg-gesucht.de", "Neue Angebote & mehr", 1700000000000L, 30)
        assertEquals("content://com.diegonmarcos.comms.mail.agentmail/messages?from=wg-gesucht.de&subject=Neue%20Angebote%20%26%20mehr&since=1700000000000&limit=30", u)
    }

    @Test fun blankFiltersAreLeftOutAndTheLimitIsClamped() {
        val u = Ipc.messagesUri(mail, "p", "", "  ", 5L, 9999)
        assertEquals("content://p.agentmail/messages?since=5&limit=200", u)
        assertTrue(Ipc.messagesUri(mail, "p", "", "", 0L, 0).endsWith("limit=1"))
    }

    @Test fun theBodyUri() {
        assertEquals("content://p.agentmail/body?account=a%2Fb&id=m%201", Ipc.bodyUri(mail, "p", "a/b", "m 1"))
    }

    @Test fun theBrowserExtras() {
        assertEquals(mapOf("url" to "https://x.example/", "max_chars" to 6000), Ipc.fetchExtras(browser, "https://x.example/", 6000))
        assertEquals(mapOf("url" to "u", "group" to "House search"), Ipc.openExtras(browser, "u", "House search"))
        assertEquals(mapOf("url" to "u"), Ipc.openExtras(browser, "u", " "))
    }

    @Test fun aMissingColumnIsNamed() {
        assertNull(Ipc.missingColumn(mail.messageColumns, mail.messageColumns))
        assertEquals("from_email", Ipc.missingColumn(mail.messageColumns - "from_email", mail.messageColumns))
        assertFalse(mail.messageColumns.isEmpty())
    }

    @Test fun theAgentsOnlyHaveReadMethods() {
        fun names(c: Class<*>) = c.methods.filter { it.declaringClass == c }.map { it.name }.toSet()
        assertEquals(setOf("messages", "body"), names(MailSource::class.java))
        assertEquals(setOf("text"), names(PageSource::class.java))
        assertEquals(setOf("complete"), names(Llm::class.java))
        assertEquals(setOf("publish"), names(ReportSink::class.java))
    }
}
