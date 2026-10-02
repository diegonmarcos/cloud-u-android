package com.diegonmarcos.cloudsearch.core

import com.diegonmarcos.cloudsearch.core.tax.Lohnsteuer2026
import com.diegonmarcos.cloudsearch.core.tax.Payslip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * The payslip against the BMF's own answers: every row of fixtures/bmf-lohnsteuer-2026.tsv was
 * computed by the ministry's PAP 2026 calculator (its external test interface), so a mistranslated
 * statement in the generated Lohnsteuer2026 shows up as a wrong cent here.
 */
class PayslipTest {
    private val social = Fixtures.cfg.social

    @Test fun everyBmfGoldenRowMatchesToTheCent() {
        val lines = Fixtures.text("bmf-lohnsteuer-2026.tsv").lines().filter { it.isNotBlank() && !it.startsWith("#") }
        val head = lines.first().split("\t")
        val rows = lines.drop(1).map { l -> head.zip(l.split("\t")).toMap() }
        assertTrue("the golden table is empty", rows.size >= 16)
        for (r in rows) {
            val p = Lohnsteuer2026().apply {
                LZZ = r.getValue("LZZ").toInt()
                RE4 = BigDecimal(r.getValue("RE4"))
                STKL = r.getValue("STKL").toInt()
                KVZ = BigDecimal(r.getValue("KVZ"))
                PVZ = r.getValue("PVZ").toInt()
                PVA = BigDecimal(r.getValue("PVA"))
                PVS = r.getValue("PVS").toInt()
                R = r.getValue("R").toInt()
                ZKF = BigDecimal(r.getValue("ZKF"))
                KRV = r.getValue("KRV").toInt()
                PKV = r.getValue("PKV").toInt()
                main()
            }
            val what = "row $r"
            assertEquals(what, BigDecimal(r.getValue("LSTLZZ")).toLong(), p.LSTLZZ.toLong())
            assertEquals(what, BigDecimal(r.getValue("SOLZLZZ")).toLong(), p.SOLZLZZ.toLong())
            assertEquals(what, BigDecimal(r.getValue("BK")).toLong(), p.BK.toLong())
        }
    }

    @Test fun declaredSocialLimitsAreThePapsOwn() {
        val p = Lohnsteuer2026().apply { LZZ = 2; RE4 = BigDecimal(400000); main() }
        assertEquals(0, BigDecimal.valueOf(social.bbgRvAvYear).compareTo(p.BBGRVALV))
        assertEquals(0, BigDecimal.valueOf(social.bbgKvPvYear).compareTo(p.BBGKVPV))
        assertEquals(0, BigDecimal.valueOf(social.rvPct).divide(BigDecimal(200)).compareTo(p.RVSATZAN))
        assertEquals(0, BigDecimal.valueOf(social.avPct).divide(BigDecimal(200)).compareTo(p.AVSATZAN))
    }

    private fun input(gross: Double, church: Boolean = false, children: Int = 0, childless: Boolean = true, saxony: Boolean = false,
                      u1: Double = 0.0, u2: Double = 0.0) =
        Payslip.Input(gross, 1, church, 9.0, 2.9, children, childless, 0.0, saxony, u1, u2)

    @Test fun fiveThousandClassOneChildlessByHand() {
        val v = Payslip.compute(input(5000.0), social).values
        assertEquals(437.50, v.getValue("kv"), 0.001)
        assertEquals(465.00, v.getValue("rv"), 0.001)
        assertEquals(65.00, v.getValue("av"), 0.001)
        assertEquals(120.00, v.getValue("pv"), 0.001)
        assertEquals(1087.50, v.getValue("employee_social"), 0.001)
        assertEquals(782.41, v.getValue("lohnsteuer"), 0.001) // BMF row 500000 / I / KVZ 2.90 / PVZ 1
        assertEquals(0.0, v.getValue("soli"), 0.001)
        assertEquals(0.0, v.getValue("church"), 0.001)
        assertEquals(3130.09, v.getValue("net"), 0.001)
        assertEquals(437.50, v.getValue("employer_kv"), 0.001)
        assertEquals(90.00, v.getValue("employer_pv"), 0.001)
        assertEquals(7.50, v.getValue("employer_insolvency"), 0.001)
        assertEquals(1065.00, v.getValue("employer_social"), 0.001)
        assertEquals(6065.00, v.getValue("company_cost"), 0.001)
        assertEquals(5000.0, v.getValue("gross"), 0.001)
    }

    @Test fun churchTaxIsTheRateOfThePapBaseRoundedDown() {
        // BK 782.41 € (BMF row 500000 / I / R 1) at 9 % = 70.4169 → 70.41
        assertEquals(70.41, Payslip.compute(input(5000.0, church = true), social).values.getValue("church"), 0.001)
    }

    @Test fun contributionsStopAtTheCeilings() {
        val v = Payslip.compute(input(9000.0), social).values
        assertEquals(508.59, v.getValue("kv"), 0.001)   // 5,812.50 × 8.75 %
        assertEquals(785.85, v.getValue("rv"), 0.001)   // 8,450 × 9.3 %
        assertEquals(109.85, v.getValue("av"), 0.001)   // 8,450 × 1.3 %
        assertEquals(139.50, v.getValue("pv"), 0.001)   // 5,812.50 × 2.4 %
        assertEquals(2212.75, v.getValue("lohnsteuer"), 0.001)
        assertEquals(61.51, v.getValue("soli"), 0.001)
    }

    @Test fun careInsuranceChildrenSaxonyAndLevies() {
        val kids = Payslip.compute(input(4000.0, children = 3, childless = false), social).values
        assertEquals(52.00, kids.getValue("pv"), 0.001)            // 4,000 × (1.8 − 2 × 0.25) %
        val sax = Payslip.compute(input(4000.0, saxony = true), social).values
        assertEquals(116.00, sax.getValue("pv"), 0.001)            // 4,000 × (2.3 + 0.6) %
        assertEquals(52.00, sax.getValue("employer_pv"), 0.001)    // 4,000 × 1.3 %
        val levies = Payslip.compute(input(4000.0, u1 = 1.5, u2 = 0.5), social).values
        assertEquals(60.00, levies.getValue("employer_u1"), 0.001)
        assertEquals(20.00, levies.getValue("employer_u2"), 0.001)
    }

    @Test fun pvReductionsCountFromTheSecondChildCapped() {
        assertEquals(0, Payslip.pvReductions(0, 4))
        assertEquals(0, Payslip.pvReductions(1, 4))
        assertEquals(1, Payslip.pvReductions(2, 4))
        assertEquals(4, Payslip.pvReductions(9, 4))
    }

    @Test fun lowWagesAreFlaggedNotModelled() {
        assertEquals(listOf("minijob"), Payslip.compute(input(603.0), social).warnings)
        assertEquals(listOf("midijob"), Payslip.compute(input(603.01), social).warnings)
        assertEquals(listOf("midijob"), Payslip.compute(input(2000.0), social).warnings)
        assertEquals(emptyList<String>(), Payslip.compute(input(2000.01), social).warnings)
    }
}
