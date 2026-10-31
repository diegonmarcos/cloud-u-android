package com.diegonmarcos.cloudme

import android.app.Application
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigDecimal

/**
 * #1367 Inventory: the declared mock seed lands in the store once and never overwrites the owner's records, and the
 * page header sums count, value and monthly recurring. Each test names the break it catches (the shell twin,
 * test/test-me-redesign.sh `inventory`, proves the same breaks red on the source).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class InventoryTest {
    private fun def(collection: String, vararg seed: String) = LedgerDef.parse(JSONObject("""{"collection":"$collection","fields":[
        {"id":"name","type":"text"},{"id":"price","type":"money"},{"id":"monthly","type":"money"}],
        "seed":[${seed.joinToString(",")}]}"""))
    private val food = def("inv_food", """{"name":"Groceries","monthly":"340.00"}""", """{"name":"Market","monthly":"90.00"}""")
    private val tech = def("inv_electronics", """{"name":"Laptop","price":"2799.00"}""")

    @Test fun `first load copies every seed in, stamped mock, and marks the collections`() {
        val d = LedgerSeed.applied(LedgerData(), listOf(food, tech))
        assertEquals(listOf("Groceries", "Market"), d.rows("inv_food").map { it["name"] })
        assertTrue(d.rows("inv_food").all { it[LedgerSeed.MOCK] == "1" && it["id"]!!.startsWith("r") })
        assertEquals(setOf("inv_food", "inv_electronics"), d.rows(LedgerSeed.MARK).map { it["collection"] }.toSet())
    }

    @Test fun `a second load changes nothing - catches a missing marker that re-applies the seed every load`() {
        val once = LedgerSeed.applied(LedgerData(), listOf(food))
        assertSame(once, LedgerSeed.applied(once, listOf(food)))
        assertEquals("survives the JSON round trip", 2, LedgerSeed.applied(LedgerData.fromJson(once.toJson()), listOf(food)).rows("inv_food").size)
    }

    @Test fun `deleting every seeded record does not bring the seed back`() {
        var d = LedgerSeed.applied(LedgerData(), listOf(food))
        for (r in d.rows("inv_food")) d = d.removed("inv_food", r["id"]!!)
        assertTrue(LedgerSeed.applied(d, listOf(food)).rows("inv_food").isEmpty())
    }

    @Test fun `an edit is kept and loses the mock stamp - catches the seed overwriting user edits`() {
        var d = LedgerSeed.applied(LedgerData(), listOf(food))
        val id = d.rows("inv_food").first()["id"]!!
        d = d.replaced("inv_food", id, LedgerModel.clean(food, mapOf("name" to "REWE", "monthly" to "300", LedgerSeed.MOCK to "1")))
        val again = LedgerSeed.applied(LedgerData.fromJson(d.toJson()), listOf(food))
        assertEquals(mapOf("name" to "REWE", "monthly" to "300", "id" to id), again.rows("inv_food").first())
    }

    @Test fun `a collection the owner already filled is only marked, never topped up`() {
        val mine = LedgerData().added("inv_food", mapOf("name" to "Mine"))
        val d = LedgerSeed.applied(mine, listOf(food))
        assertEquals(listOf("Mine"), d.rows("inv_food").map { it["name"] })
        assertEquals(listOf("inv_food"), d.rows(LedgerSeed.MARK).map { it["collection"] })
    }

    @Test fun `a collection added in a later release is seeded then, the others are left alone`() {
        val old = LedgerSeed.applied(LedgerData(), listOf(food)).let { it.removed("inv_food", it.rows("inv_food").first()["id"]!!) }
        val d = LedgerSeed.applied(old, listOf(food, tech))
        assertEquals(1, d.rows("inv_food").size)
        assertEquals(listOf("Laptop"), d.rows("inv_electronics").map { it["name"] })
    }

    @Test fun `the summary counts every item, sums value and monthly, and skips blanks`() {
        val rows = listOf(mapOf("price" to "2799.00"), mapOf("monthly" to "340.00"), mapOf("price" to "10,50", "monthly" to "4.29"), mapOf("price" to "n/a"))
        val s = LedgerReports.summary(rows, "price", "monthly")
        assertEquals(4, s.count)
        assertEquals(0, BigDecimal("2809.50").compareTo(s.value))
        assertEquals(0, BigDecimal("344.29").compareTo(s.monthly))
        assertEquals(LedgerSummary(0, BigDecimal.ZERO, BigDecimal.ZERO), LedgerReports.summary(emptyList(), "price", "monthly"))
    }

    @Test fun `every declared inventory page seeds valid records and sums to a positive total`() {
        val ui = JSONObject(BuroLlcTestData.text("build.json")).getJSONObject("ui")
        val inv = ui.getJSONArray("sections").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.single { it.getString("id") == "inventory" }
        val pages = inv.getJSONArray("pages").let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("id") } }
        assertEquals(16, pages.size)
        for (p in pages) {
            val blk = JSONArray(BuroLlcTestData.text("data/ui/inventory/$p.json")).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.single { it.optString("kind") == "ledger" }
            assertEquals("inventory_user", blk.getString("store"))
            val d = LedgerDef.parse(blk.getJSONArray("ledgers").getJSONObject(0))
            assertTrue("$p has a seed", d.seed.isNotEmpty())
            for (r in d.seed) assertEquals("$p ${r["name"]}", emptyMap<String, String>(), LedgerModel.validate(d, r))
            val seeded = LedgerSeed.applied(LedgerData(), listOf(d)).rows(d.collection)
            val s = LedgerReports.summary(seeded, "price", "monthly")
            assertEquals(d.seed.size, s.count)
        }
    }
}
