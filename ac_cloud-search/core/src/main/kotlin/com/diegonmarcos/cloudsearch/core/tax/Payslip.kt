package com.diegonmarcos.cloudsearch.core.tax

import com.diegonmarcos.cloudsearch.core.SearchConfig
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * A German monthly payslip, 2026: gross → net for the employee and gross → total cost for the
 * employer. The wage tax, solidarity surcharge and church-tax base come from the BMF's own PAP
 * 2026 ([Lohnsteuer2026], generated from the ministry's XML); the social-insurance contributions
 * apply build.json::search.social_2026 (SV-Rechengrößenverordnung 2026), whose limits and rates
 * PayslipTest holds equal to the ones the PAP itself carries.
 *
 * Not modelled, and reported as a warning instead of a wrong number: the Übergangsbereich
 * (midijob) and minijob contribution rules, accident insurance (BG, employer- and trade-specific).
 */
object Payslip {
    data class Input(
        val grossMonthly: Double,
        val taxClass: Int,
        val church: Boolean,
        val churchRatePct: Double,
        val kvzPct: Double,
        val children: Int,
        val childless: Boolean,
        val childAllowances: Double,
        val saxony: Boolean,
        val u1Pct: Double,
        val u2Pct: Double,
    )

    data class Result(val values: Map<String, Double>, val warnings: List<String>)

    private val HUNDRED = BigDecimal(100)

    private fun bd(d: Double): BigDecimal = BigDecimal.valueOf(d)
    private fun cents(x: BigDecimal): BigDecimal = x.setScale(2, RoundingMode.HALF_UP)
    private fun pct(base: BigDecimal, pct: BigDecimal): BigDecimal = cents(base.multiply(pct).divide(HUNDRED))

    /** Contribution reductions in the care insurance: one per child from the second, at most [SearchConfig.Social.pvMaxReductions]. */
    fun pvReductions(children: Int, max: Int): Int = (children - 1).coerceIn(0, max)

    fun compute(i: Input, s: SearchConfig.Social): Result {
        val gross = cents(bd(i.grossMonthly))
        val pap = Lohnsteuer2026().apply {
            LZZ = 2
            RE4 = gross.multiply(HUNDRED)
            STKL = i.taxClass
            KVZ = bd(i.kvzPct)
            PVZ = if (i.childless) 1 else 0
            PVA = BigDecimal(pvReductions(i.children, s.pvMaxReductions))
            PVS = if (i.saxony) 1 else 0
            R = if (i.church) 1 else 0
            ZKF = bd(i.childAllowances)
            main()
        }
        val lohnsteuer = pap.LSTLZZ.divide(HUNDRED)
        val soli = pap.SOLZLZZ.divide(HUNDRED)
        val church = if (i.church) pap.BK.multiply(bd(i.churchRatePct)).divide(HUNDRED).divide(HUNDRED).setScale(2, RoundingMode.DOWN) else BigDecimal.ZERO

        val kvBase = gross.min(bd(s.bbgKvPvYear).divide(BigDecimal(12), 2, RoundingMode.HALF_UP))
        val rvBase = gross.min(bd(s.bbgRvAvYear).divide(BigDecimal(12), 2, RoundingMode.HALF_UP))
        val two = BigDecimal(2)
        val kvHalf = bd(s.kvGeneralPct).add(bd(i.kvzPct)).divide(two)
        val reductions = BigDecimal(pvReductions(i.children, s.pvMaxReductions))
        val pvEmployee = (if (i.saxony) bd(s.pvSaxonyEmployeePct) else bd(s.pvPct).divide(two))
            .add(if (i.childless) bd(s.pvChildlessPct) else BigDecimal.ZERO)
            .subtract(bd(s.pvPerChildPct).multiply(reductions))
        val pvEmployer = if (i.saxony) bd(s.pvSaxonyEmployerPct) else bd(s.pvPct).divide(two)

        val kv = pct(kvBase, kvHalf)
        val rv = pct(rvBase, bd(s.rvPct).divide(two))
        val av = pct(rvBase, bd(s.avPct).divide(two))
        val pv = pct(kvBase, pvEmployee)
        val employeeSocial = kv + rv + av + pv

        val eKv = pct(kvBase, kvHalf)
        val eRv = pct(rvBase, bd(s.rvPct).divide(two))
        val eAv = pct(rvBase, bd(s.avPct).divide(two))
        val ePv = pct(kvBase, pvEmployer)
        val eIns = pct(rvBase, bd(s.insolvencyPct))
        val eU1 = pct(rvBase, bd(i.u1Pct))
        val eU2 = pct(rvBase, bd(i.u2Pct))
        val employerSocial = eKv + eRv + eAv + ePv + eIns + eU1 + eU2

        val net = gross - lohnsteuer - soli - church - employeeSocial
        val warnings = buildList {
            if (i.grossMonthly <= s.minijobLimitMonth) add("minijob")
            else if (i.grossMonthly <= s.midijobUpperMonth) add("midijob")
        }
        val v = linkedMapOf(
            "company_cost" to gross + employerSocial,
            "employer_social" to employerSocial,
            "employer_kv" to eKv, "employer_rv" to eRv, "employer_av" to eAv, "employer_pv" to ePv,
            "employer_insolvency" to eIns, "employer_u1" to eU1, "employer_u2" to eU2,
            "gross" to gross,
            "employee_social" to employeeSocial,
            "kv" to kv, "rv" to rv, "av" to av, "pv" to pv,
            "lohnsteuer" to lohnsteuer, "soli" to soli, "church" to church,
            "net" to net,
        )
        return Result(v.mapValues { it.value.toDouble() }, warnings)
    }
}
