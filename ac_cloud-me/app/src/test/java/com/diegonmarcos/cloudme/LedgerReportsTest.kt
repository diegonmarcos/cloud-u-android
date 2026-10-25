package com.diegonmarcos.cloudme

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The derived reports: budget vs cashflow, accounts vs cashflow, portfolio, income. Functions of typed records and the date. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LedgerReportsTest {
    private val today = "2026-10-25"
    private fun data(vararg entries: Pair<String, List<Rec>>) = entries.fold(LedgerData()) { d, (c, rs) -> rs.fold(d) { a, r -> a.added(c, r) } }
    private fun cash(date: String, account: String, category: String, amount: String): Rec =
        mapOf("date" to date, "account" to account, "category" to category, "amount" to amount)

    @Test fun `with no records every report is empty, not zero-filled`() {
        for (id in listOf(LedgerReports.BUDGET_VS_ACTUAL, LedgerReports.ACCOUNTS_VS_CASHFLOW, LedgerReports.PORTFOLIO, LedgerReports.INCOME)) {
            val t = LedgerReports.build(id, LedgerData(), today, "EUR")!!
            assertTrue(id, t.rows.isEmpty()); assertNull(id, t.total)
        }
        assertNull(LedgerReports.build("nope", LedgerData(), today, "EUR"))
    }

    @Test fun `budget vs cashflow compares each limit with the month's spending in that category`() {
        val d = data(
            "budget" to listOf(mapOf("category" to "Food", "limit" to "400"), mapOf("category" to "Rent", "limit" to "1200")),
            "cashflow" to listOf(
                cash("2026-10-03", "Bank", "Food", "-120.50"), cash("2026-10-20", "Bank", "food ", "-79.50"),
                cash("2026-09-30", "Bank", "Food", "-999"),     // another month: ignored
                cash("2026-10-01", "Bank", "Salary", "3000"),   // money in: not spending
                cash("2026-10-05", "Bank", "Travel", "-60"),    // spending with no budget line
            ),
        )
        val t = LedgerReports.budgetVsActual(d, today, "EUR")
        assertEquals(listOf("Category", "Limit", "Spent", "Left", "Used"), t.columns)
        assertEquals(listOf("Food", "400.00 EUR", "200.00 EUR", "200.00 EUR", "50%"), t.rows[0])
        assertEquals(listOf("Rent", "1200.00 EUR", "0.00 EUR", "1200.00 EUR", "0%"), t.rows[1])
        assertEquals(listOf("Travel", "0.00 EUR", "60.00 EUR", "-60.00 EUR", "-"), t.rows[2])
        assertEquals(listOf("Total", "1600.00 EUR", "260.00 EUR", "1340.00 EUR", "16%"), t.total)
    }

    @Test fun `accounts vs cashflow adds the cashflow since each account's as-of date to its opening balance`() {
        val d = data(
            "accounts" to listOf(mapOf("name" to "Bank", "opening" to "1000", "as_of" to "2026-10-01"), mapOf("name" to "Cash", "opening" to "50", "as_of" to "2026-01-01")),
            "cashflow" to listOf(
                cash("2026-09-15", "Bank", "Food", "-500"),    // before the as-of date: already inside the opening balance
                cash("2026-10-02", "Bank", "Salary", "300"), cash("2026-10-10", "bank", "Food", "-100"),
                cash("2026-10-11", "Cash", "Food", "-20"),
                cash("2026-10-12", "Wallet", "Food", "-5"),    // an account nobody listed
            ),
        )
        val t = LedgerReports.accountsVsCashflow(d, today, "EUR")
        assertEquals(listOf("Bank", "1000.00 EUR", "200.00 EUR", "1200.00 EUR", "300.00 EUR", "100.00 EUR"), t.rows[0])
        assertEquals(listOf("Cash", "50.00 EUR", "-20.00 EUR", "30.00 EUR", "0.00 EUR", "20.00 EUR"), t.rows[1])
        assertEquals(listOf("Wallet", "0.00 EUR", "-5.00 EUR", "-5.00 EUR", "0.00 EUR", "5.00 EUR"), t.rows[2])
        assertEquals(listOf("Total", "1050.00 EUR", "175.00 EUR", "1225.00 EUR", "300.00 EUR", "125.00 EUR"), t.total)
    }

    @Test fun `portfolio derives gain and share from cost and value`() {
        val d = data("holdings" to listOf(
            mapOf("name" to "Fund", "kind" to "Fund", "cost" to "1000", "value" to "1500"),
            mapOf("name" to "Coin", "kind" to "Crypto", "cost" to "500", "value" to "500")))
        val t = LedgerReports.portfolio(d, "EUR")
        assertEquals(listOf("Fund", "Fund", "1000.00 EUR", "1500.00 EUR", "500.00 EUR", "75%"), t.rows[0])
        assertEquals(listOf("Coin", "Crypto", "500.00 EUR", "500.00 EUR", "0.00 EUR", "25%"), t.rows[1])
        assertEquals(listOf("Total", "", "1500.00 EUR", "2000.00 EUR", "500.00 EUR", "100%"), t.total)
    }

    @Test fun `income counts running contracts and this month and year of side hustles`() {
        val d = data(
            "employment" to listOf(
                mapOf("employer" to "A", "gross_monthly" to "4000"),
                mapOf("employer" to "B", "gross_monthly" to "1000", "end" to "2026-12-31"),
                mapOf("employer" to "C", "gross_monthly" to "9000", "end" to "2026-10-24")),   // ended yesterday
            "side_income" to listOf(
                mapOf("venture" to "x", "date" to "2026-10-02", "amount" to "250"),
                mapOf("venture" to "x", "date" to "2026-03-02", "amount" to "100"),
                mapOf("venture" to "x", "date" to "2025-10-02", "amount" to "7")))
        val t = LedgerReports.income(d, today, "EUR")
        assertEquals(listOf("Employed, monthly gross", "5000.00 EUR"), t.rows[0])
        assertEquals(listOf("Side hustles, 2026-10", "250.00 EUR"), t.rows[1])
        assertEquals(listOf("Side hustles, 2026", "350.00 EUR"), t.rows[2])
    }
}
