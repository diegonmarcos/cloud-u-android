package com.diegonmarcos.cloudsearch.core

import com.diegonmarcos.cloudsearch.core.tax.Payslip
import kotlin.math.pow

/**
 * The calculators a vertical declares (build.json::search.calculators). [run] is the one
 * dispatch: test/test-search-shell.sh holds every declared calculator id to a branch here, and
 * CalculatorsTest holds every declared output id to a value [run] produces.
 */
object Calculators {
    data class Result(val values: Map<String, Double>, val warnings: List<String>)

    fun run(cfg: SearchConfig, id: String, given: Map<String, Double>): Result {
        val calc = cfg.calculators[id] ?: throw IllegalArgumentException("no calculator '$id'")
        val v = calc.fields.associate { it.id to (given[it.id] ?: it.default) }
        fun f(k: String): Double = v[k] ?: throw IllegalArgumentException("calculator $id declares no field '$k'")
        return when (id) {
            "rent_vs_buy" -> Result(
                rentVsBuy(
                    rent = f("rent"), rentIncreasePct = f("rent_increase_pct"), price = f("price"), downPayment = f("down_payment"),
                    ratePct = f("rate_pct"), loanYears = f("loan_years").toInt(), horizonYears = f("horizon_years").toInt(),
                    purchaseCostsPct = f("purchase_costs_pct"), maintenancePct = f("maintenance_pct"),
                    appreciationPct = f("appreciation_pct"), investReturnPct = f("invest_return_pct"),
                ),
                emptyList(),
            )
            "max_rent" -> Result(maxRent(f("net_income"), f("ratio_pct"), f("rent")), emptyList())
            "payslip" -> Payslip.compute(
                Payslip.Input(
                    grossMonthly = f("gross"), taxClass = f("tax_class").toInt(), church = f("church") != 0.0,
                    churchRatePct = f("church_rate_pct"), kvzPct = f("kvz_pct"), children = f("children").toInt(),
                    childless = f("childless") != 0.0, childAllowances = f("child_allowances"), saxony = f("saxony") != 0.0,
                    u1Pct = f("u1_pct"), u2Pct = f("u2_pct"),
                ),
                cfg.social,
            ).let { Result(it.values, it.warnings) }
            else -> throw IllegalArgumentException("calculator '$id' is declared but nothing computes it")
        }
    }

    /** The level annuity that repays [loan] over [months] at the monthly rate [r]. */
    fun annuity(loan: Double, r: Double, months: Int): Double = when {
        loan <= 0 || months <= 0 -> 0.0
        r == 0.0 -> loan / months
        else -> loan * r / (1 - (1 + r).pow(-months))
    }

    /**
     * Rent vs buy, month by month over [horizonYears]: the buyer pays the annuity and maintenance
     * (a share of the home's current value), the renter pays a rent that rises once a year. Whoever
     * pays less in a month invests the difference, and the renter invests the buyer's upfront cash
     * (down payment + purchase costs) from day one, all at [investReturnPct] a year. At the end:
     * buyer = home value − debt left + portfolio, renter = portfolio.
     */
    fun rentVsBuy(
        rent: Double, rentIncreasePct: Double, price: Double, downPayment: Double, ratePct: Double, loanYears: Int,
        horizonYears: Int, purchaseCostsPct: Double, maintenancePct: Double, appreciationPct: Double, investReturnPct: Double,
    ): Map<String, Double> {
        val loan = (price - downPayment).coerceAtLeast(0.0)
        val r = ratePct / 100 / 12
        val pay = annuity(loan, r, loanYears * 12)
        val costs = price * purchaseCostsPct / 100
        val growth = (1 + investReturnPct / 100).pow(1.0 / 12) - 1
        var balance = loan
        var monthRent = rent
        var renter = downPayment + costs
        var buyer = 0.0
        var rentTotal = 0.0
        var paid = 0.0
        var interestTotal = 0.0
        var maintenanceTotal = 0.0
        for (m in 1..horizonYears * 12) {
            val interest = balance * r
            val payment = if (balance > 0) minOf(pay, balance + interest) else 0.0
            balance = (balance - (payment - interest)).coerceAtLeast(0.0)
            if (payment > 0) interestTotal += interest
            val value = price * (1 + appreciationPct / 100).pow((m - 1) / 12.0)
            val maintenance = value * maintenancePct / 100 / 12
            paid += payment
            maintenanceTotal += maintenance
            rentTotal += monthRent
            renter *= 1 + growth
            buyer *= 1 + growth
            val diff = payment + maintenance - monthRent
            if (diff > 0) renter += diff else buyer -= diff
            if (m % 12 == 0) monthRent *= 1 + rentIncreasePct / 100
        }
        val home = price * (1 + appreciationPct / 100).pow(horizonYears.toDouble())
        val buyerNet = home - balance + buyer
        return linkedMapOf(
            "monthly_payment" to pay,
            "rent_total" to rentTotal,
            "buy_total" to downPayment + costs + paid + maintenanceTotal,
            "interest_paid" to interestTotal,
            "home_value" to home,
            "remaining_debt" to balance,
            "buyer_net_worth" to buyerNet,
            "renter_net_worth" to renter,
            "advantage_buy" to buyerNet - renter,
            "price_to_rent" to if (rent > 0) price / (rent * 12) else 0.0,
        )
    }

    /** The highest warm rent [ratioPct] of a net income allows, and how a given rent sits against it. */
    fun maxRent(netIncome: Double, ratioPct: Double, rent: Double): Map<String, Double> {
        val max = netIncome * ratioPct / 100
        return linkedMapOf(
            "max_rent" to max,
            "rent_share_pct" to if (netIncome > 0) rent / netIncome * 100 else 0.0,
            "headroom" to max - rent,
        )
    }
}
