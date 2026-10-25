package com.diegonmarcos.cloudme

import java.math.BigDecimal
import java.math.RoundingMode

/** A derived report: a table with an optional total row. Every cell is already formatted text. */
data class ReportTable(val title: String, val note: String, val columns: List<String>, val rows: List<List<String>>, val total: List<String>? = null)

/**
 * The derived reports of Buro and LLC (declared per page in `ledger.reports`). They are functions of the owner's
 * records and of [today] (`yyyy-MM-dd`), nothing else: no seed rows, no feed. With no records they return no rows,
 * and the page says so. Cashflow amounts are signed: negative is money out, positive is money in.
 */
object LedgerReports {
    const val BUDGET_VS_ACTUAL = "budget_vs_actual"
    const val ACCOUNTS_VS_CASHFLOW = "accounts_vs_cashflow"
    const val PORTFOLIO = "portfolio"
    const val INCOME = "income"

    private val ZERO = BigDecimal.ZERO

    fun build(id: String, d: LedgerData, today: String, currency: String): ReportTable? = when (id) {
        BUDGET_VS_ACTUAL -> budgetVsActual(d, today, currency)
        ACCOUNTS_VS_CASHFLOW -> accountsVsCashflow(d, today, currency)
        PORTFOLIO -> portfolio(d, currency)
        INCOME -> income(d, today, currency)
        else -> null
    }

    private fun key(s: String?) = s.orEmpty().trim().lowercase()
    private fun sum(xs: Iterable<BigDecimal>) = xs.fold(ZERO) { a, b -> a + b }
    private fun m(r: Rec, k: String) = LedgerModel.money(r[k]) ?: ZERO
    private fun f(v: BigDecimal, c: String) = LedgerModel.fmt(v, c)
    private fun pct(part: BigDecimal, whole: BigDecimal) =
        if (whole.signum() == 0) "-" else part.multiply(BigDecimal(100)).divide(whole, 0, RoundingMode.HALF_UP).toPlainString() + "%"

    /** Budgeting: each budget line's monthly limit against what the cashflow spent in [today]'s month under that category. */
    fun budgetVsActual(d: LedgerData, today: String, c: String): ReportTable {
        val month = today.take(7)
        val spent = d.rows("cashflow").filter { it["date"].orEmpty().startsWith(month) && m(it, "amount").signum() < 0 }
            .groupBy { key(it["category"]) }.mapValues { (_, rs) -> sum(rs.map { m(it, "amount").negate() }) }
        val lines = d.rows("budget").groupBy { key(it["category"]) }
        val rows = lines.map { (k, rs) ->
            val limit = sum(rs.map { m(it, "limit") }); val used = spent[k] ?: ZERO
            listOf(rs.first()["category"].orEmpty().trim(), f(limit, c), f(used, c), f(limit - used, c), pct(used, limit))
        }
        val unbudgeted = spent.filterKeys { it !in lines }.map { (k, v) ->
            listOf(d.rows("cashflow").first { key(it["category"]) == k }["category"].orEmpty().ifBlank { "(no category)" }, f(ZERO, c), f(v, c), f(v.negate(), c), "-")
        }
        val all = rows + unbudgeted
        val limit = sum(lines.values.flatten().map { m(it, "limit") }); val used = sum(spent.values)
        return ReportTable("Budget vs cashflow, $month", "Spent is the money out of the cashflow in the month, per category; categories with no budget line show a zero limit.",
            listOf("Category", "Limit", "Spent", "Left", "Used"), all,
            if (all.isEmpty()) null else listOf("Total", f(limit, c), f(used, c), f(limit - used, c), pct(used, limit)))
    }

    /** Accounts vs Cashflow: each account's opening balance plus the cashflow dated on or after its as-of date, and the month's in and out. */
    fun accountsVsCashflow(d: LedgerData, today: String, c: String): ReportTable {
        val month = today.take(7)
        val cash = d.rows("cashflow")
        val known = d.rows("accounts").map { key(it["name"]) }.toSet()
        fun row(name: String, opening: BigDecimal, asOf: String, mine: List<Rec>): List<String> {
            val since = mine.filter { it["date"].orEmpty() >= asOf }
            val inM = sum(since.filter { it["date"].orEmpty().startsWith(month) }.map { m(it, "amount") }.filter { it.signum() > 0 })
            val outM = sum(since.filter { it["date"].orEmpty().startsWith(month) }.map { m(it, "amount") }.filter { it.signum() < 0 }.map { it.negate() })
            return listOf(name, f(opening, c), f(sum(since.map { m(it, "amount") }), c), f(opening + sum(since.map { m(it, "amount") }), c), f(inM, c), f(outM, c))
        }
        val rows = d.rows("accounts").map { a -> row(a["name"].orEmpty(), m(a, "opening"), a["as_of"].orEmpty(), cash.filter { key(it["account"]) == key(a["name"]) }) } +
            cash.filter { key(it["account"]) !in known }.groupBy { key(it["account"]) }.map { (_, rs) ->
                row(rs.first()["account"].orEmpty().ifBlank { "(no account)" }, ZERO, "", rs) }
        val total = if (rows.isEmpty()) null else listOf("Total") + (1..5).map { i -> f(sum(rows.map { LedgerModel.money(it[i].removeSuffix(" $c")) ?: ZERO }), c) }
        return ReportTable("Accounts vs cashflow, $month", "Balance = opening balance + cashflow since the account's as-of date. A cashflow line naming an account that is not listed shows as its own row with a zero opening.",
            listOf("Account", "Opening", "Net cashflow", "Balance", "In (month)", "Out (month)"), rows, total)
    }

    /** Portfolio: cost, value, gain and share of the total, per holding. */
    fun portfolio(d: LedgerData, c: String): ReportTable {
        val h = d.rows("holdings")
        val value = sum(h.map { m(it, "value") }); val cost = sum(h.map { m(it, "cost") })
        val rows = h.map { r -> listOf(r["name"].orEmpty(), r["kind"].orEmpty(), f(m(r, "cost"), c), f(m(r, "value"), c), f(m(r, "value") - m(r, "cost"), c), pct(m(r, "value"), value)) }
        return ReportTable("Portfolio", "Gain = value - cost; share is of the total value.", listOf("Holding", "Kind", "Cost", "Value", "Gain", "Share"), rows,
            if (rows.isEmpty()) null else listOf("Total", "", f(cost, c), f(value, c), f(value - cost, c), "100%"))
    }

    /** LLC income: the monthly gross of employment contracts still running, and side-hustle income this month and this year. */
    fun income(d: LedgerData, today: String, c: String): ReportTable {
        val live = d.rows("employment").filter { it["end"].isNullOrBlank() || it["end"].orEmpty() >= today }
        val side = d.rows("side_income")
        val rows = buildList {
            if (live.isNotEmpty()) add(listOf("Employed, monthly gross", f(sum(live.map { m(it, "gross_monthly") }), c)))
            if (side.isNotEmpty()) {
                add(listOf("Side hustles, ${today.take(7)}", f(sum(side.filter { it["date"].orEmpty().startsWith(today.take(7)) }.map { m(it, "amount") }), c)))
                add(listOf("Side hustles, ${today.take(4)}", f(sum(side.filter { it["date"].orEmpty().startsWith(today.take(4)) }.map { m(it, "amount") }), c)))
            }
        }
        return ReportTable("Income", "Employment counts while its end date is blank or not yet passed.", listOf("Source", "Amount"), rows)
    }
}
