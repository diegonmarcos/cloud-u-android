package com.diegonmarcos.cloudme

import android.app.Application
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigDecimal

/** The pure half of Buro / LLC user data: the store value, its JSON, and what a form may save. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LedgerModelTest {
    private val def = LedgerDef.parse(JSONObject("""{"collection":"assets","title":"Assets","fields":[
        {"id":"name","label":"Name","type":"text","required":true},
        {"id":"kind","label":"Kind","type":"choice","options":["Property","Vehicle"]},
        {"id":"value","label":"Value","type":"money"},
        {"id":"acquired","label":"Acquired","type":"date"}]}"""))

    @Test fun `a new store is empty and says nothing is seeded`() {
        val d = LedgerData()
        assertTrue(d.rows("assets").isEmpty())
        assertTrue(d.collections().isEmpty())
        assertTrue(LedgerData.fromJson(null).collections().isEmpty())
        assertTrue(LedgerData.fromJson("").collections().isEmpty())
    }

    @Test fun `damaged text is an empty ledger, never a crash`() {
        for (bad in listOf("{", "[]", "null", """{"collections":"x"}""", """{"collections":{"a":5}}""")) {
            assertTrue(bad, LedgerData.fromJson(bad).rows("a").isEmpty())
        }
    }

    @Test fun `records get increasing ids that are not reused after a removal`() {
        var d = LedgerData().added("assets", mapOf("name" to "Flat")).added("assets", mapOf("name" to "Car"))
        assertEquals(listOf("r1", "r2"), d.rows("assets").map { it["id"] })
        d = d.removed("assets", "r2").added("assets", mapOf("name" to "Bike"))
        assertEquals(listOf("Flat", "Bike"), d.rows("assets").map { it["name"] })
        assertEquals("the freed id of the LAST record is reused, an earlier one never", listOf("r1", "r2"), d.rows("assets").map { it["id"] })
        d = d.removed("assets", "r1").added("assets", mapOf("name" to "Boat"))
        assertEquals(listOf("r2", "r3"), d.rows("assets").map { it["id"] })
    }

    @Test fun `collections are independent`() {
        val d = LedgerData().added("assets", mapOf("name" to "Flat")).added("services", mapOf("name" to "Power"))
        assertEquals(1, d.rows("assets").size); assertEquals(1, d.rows("services").size)
        assertEquals(0, d.removed("assets", "r1").rows("assets").size)
        assertEquals(1, d.removed("assets", "r1").rows("services").size)
    }

    @Test fun `the JSON round trip keeps every field`() {
        val d = LedgerData().added("assets", mapOf("name" to "Flat", "value" to "250000.50", "acquired" to "2020-02-29"))
        val back = LedgerData.fromJson(d.toJson())
        assertEquals(d.rows("assets"), back.rows("assets"))
        assertEquals(d.toJson(), back.toJson())
    }

    @Test fun `money accepts a comma and refuses words`() {
        assertEquals(BigDecimal("12.5"), LedgerModel.money(" 12,5 "))
        assertEquals(null, LedgerModel.money("twelve"))
        assertEquals(null, LedgerModel.money(""))
        assertEquals("1234.50 EUR", LedgerModel.fmt(BigDecimal("1234.5"), "EUR"))
    }

    @Test fun `a date is a real calendar day`() {
        assertTrue(LedgerModel.isDate("2024-02-29")); assertFalse(LedgerModel.isDate("2023-02-29"))
        assertTrue(LedgerModel.isDate("2000-02-29")); assertFalse(LedgerModel.isDate("1900-02-29"))
        assertFalse(LedgerModel.isDate("2024-13-01")); assertFalse(LedgerModel.isDate("2024-04-31"))
        assertFalse(LedgerModel.isDate("24-1-1")); assertFalse(LedgerModel.isDate(null))
    }

    @Test fun `validation names each bad field and passes a good record`() {
        assertEquals(setOf("name"), LedgerModel.validate(def, emptyMap()).keys)
        assertEquals(setOf("value", "acquired", "kind"),
            LedgerModel.validate(def, mapOf("name" to "x", "value" to "abc", "acquired" to "2024-02-30", "kind" to "Boat")).keys)
        assertTrue(LedgerModel.validate(def, mapOf("name" to "Flat", "kind" to "Property", "value" to "1,5", "acquired" to "2020-01-31")).isEmpty())
    }

    @Test fun `clean keeps declared fields only, trimmed, blanks dropped`() {
        assertEquals(mapOf("name" to "Flat", "value" to "5"),
            LedgerModel.clean(def, mapOf("name" to " Flat ", "value" to "5", "kind" to "  ", "junk" to "x")))
    }
}
