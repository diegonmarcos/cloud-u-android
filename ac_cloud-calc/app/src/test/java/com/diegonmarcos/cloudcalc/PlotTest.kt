package com.diegonmarcos.cloudcalc

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * #1336 the plots: every formula's plot spec, the safe expression parser (accepts the whitelist, rejects everything else, evaluates
 * known values), the Graph mode in the strip, the pi category, the counts, and "Plot my formula". Runs from app/.
 */
@RunWith(RobolectricTestRunner::class)
class PlotTest {
    private val build = JSONObject(File("../build.json").readText())
    private val cfg = Latex.parseConfig(build.getJSONObject("latex").toString())
    private val catalog = Latex.parseCatalog(File("../data/assets/" + cfg.catalog).readText())
    private val vectors = JSONObject(File("../data/latex/expr-vectors.json").readText())

    private fun eval(expr: String, vars: Map<String, Double> = emptyMap()): Double = PlotExpr.compile(expr, vars.keys).eval(vars)
    private fun rejected(expr: String, vars: Collection<String> = listOf("x")): Boolean =
        try { PlotExpr.compile(expr, vars); false } catch (e: PlotExpr.ExprException) { true }

    // ── every formula has a plot ────────────────────────────────────────────────────────────────────────────────

    @Test fun `every formula has a plot spec with a 2D and a 3D view, defaults inside their range, and a caption`() {
        assertEquals(125, catalog.formulas.size)
        catalog.formulas.forEach { f ->
            val p = f.plot
            assertTrue("${f.id} has no plot", p != null)
            p!!
            assertTrue("${f.id}: caption", p.caption.isNotBlank() && !p.caption.contains('\n'))
            assertTrue("${f.id}: variables", p.vars.isNotEmpty())
            p.vars.forEach { v -> assertTrue("${f.id}.${v.symbol} default ${v.default} in [${v.min}, ${v.max}]", v.default >= v.min && v.default <= v.max && v.min < v.max && v.step > 0) }
            assertTrue("${f.id}: 2D layers", p.json.getJSONObject("d2").getJSONArray("layers").length() > 0)
            assertTrue("${f.id}: 3D layers", p.json.getJSONObject("d3").getJSONArray("layers").length() > 0)
            assertEquals(f.id, emptyList<String>(), Latex.plotProblems(p))
        }
        assertEquals(emptyList<String>(), Latex.problems(catalog))
    }

    @Test fun `animation and wave variables are declared variables, every wave exposes a frequency or amplitude`() {
        val animated = catalog.formulas.mapNotNull { it.plot?.anim?.let { a -> it to a } }
        assertTrue(animated.size >= 10)
        animated.forEach { (f, a) ->
            val v = f.plot!!.vars.first { it.symbol == a.variable }
            assertEquals(v.min, a.min, 0.0); assertEquals(v.max, a.max, 0.0)
        }
        val waves = catalog.formulas.mapNotNull { f -> f.plot?.wave?.let { f to it } }
        assertTrue(waves.size >= 8)
        waves.forEach { (f, w) -> assertTrue(f.id, w.freq != null || w.amp != null) }
        assertTrue(catalog.formulas.first { it.id == "the-classical-wave-equation" }.plot!!.wave!!.freq == "f")
    }

    @Test fun `a plot is a function or an honest illustration, and the counts hold`() {
        val ill = catalog.formulas.filter { it.plot!!.illustration }
        val fn = catalog.formulas.filter { !it.plot!!.illustration }
        assertEquals(125, ill.size + fn.size)
        assertTrue("illustrations ${ill.size}", ill.size in 8..40)
        listOf("euler-s-polyhedral-formula", "euler-s-identity", "stokes-theorem", "the-divergence-gauss-ostrogradsky-theorem").forEach { id ->
            assertTrue(id, catalog.formulas.first { it.id == id }.plot!!.illustration)
        }
        assertFalse(catalog.formulas.first { it.id == "the-normal-gaussian-distribution" }.plot!!.illustration)
    }

    @Test fun `plot problems name a bad default, a forbidden expression and an undeclared variable`() {
        val good = catalog.formulas.first { it.id == "the-pythagorean-theorem" }.plot!!
        val json = JSONObject(good.json.toString())
        json.getJSONArray("vars").getJSONObject(0).put("default", 99.0)
        assertTrue(Latex.plotProblems(Latex.parsePlot(json)).any { it.contains("outside") })
        val evil = JSONObject(good.json.toString())
        evil.getJSONObject("d2").getJSONArray("layers").getJSONObject(0).put("y", "eval(a)")
        assertTrue(Latex.plotProblems(Latex.parsePlot(evil)).any { it.contains("unknown function eval") })
        val unknown = JSONObject(good.json.toString())
        unknown.getJSONObject("d3").getJSONArray("layers").getJSONObject(0).put("z", "a+q")
        assertTrue(Latex.plotProblems(Latex.parsePlot(unknown)).any { it.contains("unknown identifier q") })
        val nocap = JSONObject(good.json.toString()).put("caption", " ")
        assertTrue(Latex.plotProblems(Latex.parsePlot(nocap)).any { it.contains("caption") })
    }

    // ── the safe parser ─────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `the conformance vectors evaluate to their values and every reject is rejected`() {
        val accept = vectors.getJSONArray("accept")
        for (i in 0 until accept.length()) {
            val a = accept.getJSONObject(i)
            val vars = a.getJSONObject("vars").let { o -> o.keys().asSequence().associateWith { o.getDouble(it) } }
            val want = a.getDouble("expect")
            val got = eval(a.getString("expr"), vars)
            val tol = maxOf(a.getDouble("tol"), 1e-10) * maxOf(1.0, Math.abs(want))
            assertEquals(a.getString("expr"), want, got, tol)
        }
        val reject = vectors.getJSONArray("reject")
        assertTrue(reject.length() >= 20)
        for (i in 0 until reject.length()) {
            val r = reject.getJSONObject(i)
            val vars = (0 until r.getJSONArray("vars").length()).map { r.getJSONArray("vars").getString(it) }
            assertTrue(r.getString("expr"), rejected(r.getString("expr"), vars))
        }
    }

    @Test fun `known values - the Gaussian at zero, the Leibniz partial sum at n = 1000, Gamma of one half`() {
        assertEquals(1 / Math.sqrt(2 * Math.PI), eval("1/(s*sqrt(2*pi))*exp(-(x-m)^2/(2*s^2))", mapOf("x" to 0.0, "m" to 0.0, "s" to 1.0)), 1e-12)
        assertEquals(Math.PI, eval("4*sum(k,0,999,(-1)^k/(2*k+1))"), 1.5e-3)
        assertEquals(Math.PI, eval("4*sum(k,0,n-1,(-1)^k/(2*k+1))", mapOf("n" to 1000.0)), 1.5e-3)
        assertEquals(Math.PI, eval("gamma(0.5)^2"), 1e-12)
        assertEquals(Math.PI, eval("16/sum(k,0,19,(42*k+5)*comb(2*k,k)^3/2^(12*k))"), 1e-12)
        assertEquals(-4.0, eval("-2^2"), 0.0)
        assertEquals(512.0, eval("2^3^2"), 0.0)
        assertEquals(25.0, eval("primepi(100)"), 0.0)
    }

    @Test fun `the parser accepts the whitelist`() {
        PlotExpr.functions.filter { it != "sum" && it != "prod" && it != "if" }.forEach { f ->
            val arity = when (f) {
                "atan2", "pow", "hypot", "min", "max", "mod", "comb", "cfan" -> 2
                "clamp", "agma", "agmb", "logistic" -> 3
                else -> 1
            }
            val call = f + "(" + (1..arity).joinToString(",") { "0.5" } + ")"
            assertFalse(call, rejected(call))
        }
        assertFalse(rejected("sum(k,1,10,k)"))
        assertFalse(rejected("prod(k,1,5,k)"))
        assertFalse(rejected("if(x>1,1,2)"))
        assertFalse(rejected("x<=2"))
        assertFalse(rejected("pi+e+tau"))
        assertFalse(rejected("1.5e-3*x"))
    }

    @Test fun `the parser rejects everything else - identifiers, calls, property access, statements`() {
        listOf("eval(1)", "x.y", "constructor", "__proto__", "toString(1)", "sin", "1+", "y+1", "window", "this", "x=1", "x;1", "Math.sin(1)", "x[0]", "x**2", "import(1)", "sum(k,1,3,j)", "sum(x,1,3,x)", "sin(1,2)", "(1", "1)", "sin()")
            .forEach { assertTrue(it, rejected(it)) }
        // a call to a name that is a variable, and a variable named like a function, are not functions
        assertTrue(rejected("x(2)"))
        assertTrue(rejected("pi(2)"))
    }

    @Test fun `a runaway sum is cut off, not run`() {
        val v = eval("sum(k,1,1000000,1)")
        assertTrue(v.isNaN())
        assertTrue(eval("sum(a,1,500,sum(b,1,500,1))").isNaN())
    }

    // ── the Graph mode and the strip ────────────────────────────────────────────────────────────────────────────

    @Test fun `the Graph mode is declared and the strip reads Library, Edit, Graph`() {
        val modes = Declarations.modesOf("latex")
        assertEquals(listOf("Library", "Edit", "Graph"), modes.map { it.label })
        assertEquals("latex_graph", modes.last().kind)
        assertEquals("chart", modes.last().icon)
        assertEquals(listOf("both", "2d", "3d"), cfg.graph.modes)
        assertTrue(catalog.formulas.any { it.id == cfg.graph.defaultFormula })
        assertEquals("CalcPlot", cfg.graph.bridge)
        (listOf(cfg.graph.viewer) + cfg.graph.scripts).forEach { assertTrue(it, File("../data/assets/$it").isFile) }
    }

    // ── pi ──────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `the pi category holds at least 20 formulas and references Euler's identity, Basel and Chudnovsky`() {
        val pi = catalog.categories.last()
        assertEquals("pi", pi.id)
        val own = catalog.formulas.filter { it.category == "pi" }
        assertTrue("own pi entries ${own.size}", own.size >= 20)
        assertEquals(25, own.size)
        assertEquals((101..125).toList(), own.map { it.number })
        assertEquals(listOf("euler-s-identity", "the-basel-problem-euler-s-sum", "the-chudnovsky-algorithm-for"), pi.includes)
        val listed = Latex.filter(catalog, emptyList(), emptySet(), "pi", "", cfg)
        assertEquals(28, listed.size)
        assertTrue(listed.any { it.number == 15 })
        // the owner's list is not copied: Chudnovsky is still ONE formula
        assertEquals(1, catalog.formulas.count { it.latex.contains("640320") })
        listOf("Leibniz", "Nilakantha", "Wallis", "Viète", "Machin", "Bailey", "Ramanujan", "Gauss–Legendre", "Gaussian integral", "Buffon", "Monte Carlo", "Stirling", "arctangent", "arcsine", "Lambert", "Madhava").forEach { name ->
            assertTrue(name, own.any { it.title.contains(name) })
        }
    }

    // ── Plot my formula ─────────────────────────────────────────────────────────────────────────────────────────

    private fun conv(tex: String) = LatexPlot.convert(tex, cfg.graph)
    private fun ok(tex: String) = conv(tex) as? LatexPlot.Conversion.Ok ?: error("not plottable: $tex -> ${conv(tex)}")
    private fun why(tex: String) = (conv(tex) as? LatexPlot.Conversion.Unsupported)?.reason ?: error("converted: $tex")

    @Test fun `an Editor formula with one or two free variables becomes a plot expression`() {
        assertEquals("x^(2)", ok("x^2").expr)
        assertEquals(listOf("x"), ok("y = x^2").free)
        assertEquals("sin(x)", ok("f(x) = \\sin x").expr)
        assertEquals("sin(2*x)", ok("\\sin 2x").expr)
        assertEquals("sin(x)*cos(x)", ok("\\sin x \\cos x").expr)
        assertEquals("((1)/(x))", ok("\\frac{1}{x}").expr)
        assertEquals("sqrt(x^(2)+y^(2))", ok("\\sqrt{x^2+y^2}").expr)
        assertEquals(listOf("x", "y"), ok("\\sqrt{x^2+y^2}").free)
        assertEquals(listOf("x", "a"), ok("a \\sin x").free)
        assertEquals("2*pi*x", ok("2\\pi x").expr)
        assertEquals("e^(-x^(2))", ok("e^{-x^2}").expr)
        assertEquals("abs(x)", ok("|x|").expr)
        assertEquals("(sin(x))^(2)", ok("\\sin^2 x").expr)
        assertEquals("log10(x)", ok("\\log x").expr)
        assertEquals("fact(x)", ok("x!").expr)
        assertEquals(listOf("theta"), ok("\\sin\\theta").free)
        // every converted expression is accepted by the parser against its own variables
        listOf("x^2", "\\sin x", "\\frac{1}{x}", "\\sqrt{x^2+y^2}", "\\frac{\\sin x}{x}", "e^{-x^2}", "\\ln|x|", "x^3-2x+1").forEach { tex ->
            val c = ok(tex)
            assertFalse(tex, rejected(c.expr, c.free))
        }
    }

    @Test fun `a formula that cannot be plotted says why`() {
        assertTrue(why("\\sum_{k=1}^{n} k").isNotEmpty())
        assertTrue(why("\\int_0^1 x\\,dx").isNotEmpty())
        assertTrue(why("2+2").contains("free variable"))
        assertTrue(why("a x + b y + c z").contains("at most 2"))
        assertTrue(why("a_1 x").contains("subscript"))
        assertTrue(why("x^2 = y^2").isNotEmpty())
        assertTrue(why("x = \\pm 1").isNotEmpty())
        assertTrue(why("").contains("nothing"))
        assertTrue(why("\\frac{1}{x").contains("unbalanced"))
        assertTrue(why("\\foo x").contains("\\foo"))
        assertTrue(why("y = x + y").contains("both sides"))
    }

    @Test fun `the plot of a converted formula is a valid spec in 2D and 3D, with a dummy axis for one variable`() {
        val one = LatexPlot.spec(ok("\\sin x"), "My sine", "caption", cfg.graph.custom)
        assertEquals(emptyList<String>(), Latex.plotProblems(one))
        assertEquals(listOf("x", cfg.graph.custom.dummy), one.vars.map { it.symbol })
        assertEquals(cfg.graph.custom.trigRange, one.vars.first().max, 0.0)
        val two = LatexPlot.spec(ok("x^2+y"), "Two", "caption", cfg.graph.custom)
        assertEquals(emptyList<String>(), Latex.plotProblems(two))
        assertEquals(listOf("x", "y"), two.vars.map { it.symbol })
        assertEquals(cfg.graph.custom.range, two.vars.first().max, 0.0)
        two.vars.forEach { assertTrue(it.default >= it.min && it.default <= it.max) }
    }

    @Test fun `no formula or plot expression is spelled in the plot Kotlin`() {
        val dir = "src/main/java/com/diegonmarcos/cloudcalc"
        listOf("LatexPlot.kt", "ui/LatexScreens.kt").forEach { name ->
            val code = File("$dir/$name").readText().replace(Regex("//.*"), "")
            listOf("Pythagorean", "Schr", "Maxwell", "euler-s-identity", "plots.json").forEach { assertFalse("$name spells $it", code.contains(it)) }
        }
    }
}
