package com.diegonmarcos.cloudbrowser

import com.diegonmarcos.superapp.browser.AcAddress
import com.diegonmarcos.superapp.browser.AcCard
import com.diegonmarcos.superapp.browser.AcChoice
import com.diegonmarcos.superapp.browser.AcDocument
import com.diegonmarcos.superapp.browser.AcEmail
import com.diegonmarcos.superapp.browser.AcFieldSig
import com.diegonmarcos.superapp.browser.AcLogin
import com.diegonmarcos.superapp.browser.AcMemoryEntry
import com.diegonmarcos.superapp.browser.AcName
import com.diegonmarcos.superapp.browser.AcPhone
import com.diegonmarcos.superapp.browser.AcProfile
import com.diegonmarcos.superapp.browser.AcProposal
import com.diegonmarcos.superapp.browser.AcVaultItem
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** #900 NEVER LOG SECRETS: nothing that holds personal data or a secret prints it, and no autocomplete source can log. */
class AcNoSecretLogTest {

    private val lib = File("../../ab_cloud-libs-shared/libs/browser/src/main/java/com/diegonmarcos/superapp/browser")

    private fun noneOf(s: String, vararg secrets: String) = secrets.forEach { assertFalse("'$it' leaked into: $s", s.contains(it)) }

    @Test
    fun `every model that holds a secret or personal data prints none of it`() {
        val pw = "Sup3r-Secret-PW"; val num = "4111111111111111"; val cvc = "987"; val street = "Calle Falsa"; val email = "ana@example.test"
        noneOf(AcLogin("id1", "example.com", "ana-user", pw).toString(), pw, "ana-user")
        noneOf(AcCard("c1", "Mine", "Ana Garcia", num, "05", "2029", cvc).toString(), num, "4111", cvc, "Ana Garcia")
        noneOf(AcVaultItem.Login("example.com", "ana-user", pw).toString(), pw, "ana-user")
        noneOf(AcVaultItem.Card(num, "Ana Garcia", "05", "2029", cvc).toString(), num, cvc, "Ana Garcia")
        noneOf(AcProposal.Password("example.com", "ana-user", pw, null, false).toString(), pw, "ana-user")
        noneOf(AcProposal.CardSave(AcCard(number = num, holder = "Ana Garcia", cvc = cvc)).toString(), num, cvc, "Ana Garcia")
        noneOf(AcMemoryEntry(origin = "a.example", formSig = "f", fieldKey = "kundennr", value = "K-48213").toString(), "K-48213")
        noneOf(AcName(given = "Ana", family = "Garcia").toString(), "Ana", "Garcia")
        noneOf(AcAddress(street = street, number = "1", city = "Madrid", postal = "28001").toString(), street, "Madrid", "28001")
        noneOf(AcPhone(value = "+34 600").toString(), "+34 600"); noneOf(AcEmail(value = email).toString(), email)
        noneOf(AcDocument(number = "00000000T").toString(), "00000000T")
        val profile = AcProfile(id = "p", label = "Ana", names = listOf(AcName(given = "Ana")), emails = listOf(AcEmail(value = email)), addresses = listOf(AcAddress(street = street)))
        noneOf(profile.toString(), email, street, "Ana G")
        noneOf(AcProposal.ProfileRecord("name", name = AcName(given = "Ana", family = "Garcia")).toString(), "Ana", "Garcia")
        noneOf(AcChoice("p", mapOf("name" to "n1")).toString(), "n1")
        noneOf(AcFieldSig(name = "kundennr").let { "$it" }.replace("kundennr", ""), pw)
    }

    @Test
    fun `no autocomplete source file can log, print or throw a value`() {
        val files = lib.listFiles { f -> f.name.startsWith("Ac") && f.name.endsWith(".kt") }.orEmpty()
        assertTrue("the autocomplete sources were found from ${lib.absolutePath}", files.size >= 8)
        val bad = Regex("""\bLog\.[dviwe]\(|\bprintln\(|\bprint\(|printStackTrace|Timber\.|System\.(out|err)|Logger""")
        files.forEach { f ->
            val code = f.readText().lines().filterNot { it.trim().startsWith("//") || it.trim().startsWith("*") || it.trim().startsWith("/*") }.joinToString("\n")
            assertFalse("${f.name} logs or prints", bad.containsMatchIn(code))
        }
    }

    @Test
    fun `the page engine logs nothing and keeps nothing in the page`() {
        val js = File("../../ab_cloud-libs-shared/libs/browser/src/main/assets/browser/ac_engine.js").readText()
        val code = js.lines().filterNot { it.trim().startsWith("//") }.joinToString("\n")
        assertFalse(Regex("""console\.|localStorage|sessionStorage|indexedDB|document\.cookie|alert\(|XMLHttpRequest|fetch\(|sendBeacon""").containsMatchIn(code))
    }

    @Test
    fun `the engine reports values only on submit, never on focus`() {
        val js = File("../../ab_cloud-libs-shared/libs/browser/src/main/assets/browser/ac_engine.js").readText()
        val focus = js.substring(js.indexOf("function onFocus"), js.indexOf("// ── submit"))
        assertFalse("focus reports signals, not values", focus.contains("valueOf(") || focus.contains(".value") || focus.contains("snapshot("))
        assertTrue(js.contains("s.v = v.slice("))
    }
}
