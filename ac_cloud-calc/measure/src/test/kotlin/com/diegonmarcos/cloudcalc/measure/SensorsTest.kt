package com.diegonmarcos.cloudcalc.measure

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class SensorsTest {
    private val eps = 1e-6

    private fun base(sensors: String = BASE_SENSORS, bands: String = BASE_BANDS, conversions: String = BASE_CONVERSIONS) =
        """{"conversions":$conversions,"bands":$bands,"sensors":$sensors}"""

    private fun invalid(json: String, fragment: String) {
        try {
            Sensors.parse(json)
            fail("expected the catalog to be rejected for: $fragment")
        } catch (e: IllegalArgumentException) {
            assertTrue("message '${e.message}' should mention '$fragment'", e.message!!.contains(fragment))
        }
    }

    // ── parse ──────────────────────────────────────────────────────────────────────────────────

    @Test fun `a valid catalog parses with every field and the defaults`() {
        val c = Sensors.parse(base())
        assertEquals(listOf("accel", "pressure", "temp", "humid", "gps"), c.sensors.map { it.id })
        val a = c.sensor("accel")!!
        assertEquals("motion", a.group)
        assertEquals("sensor", a.source)
        assertEquals(1, a.androidType)
        assertEquals("m/s²", a.unit)
        assertEquals("vector", a.display)
        assertEquals(listOf("x", "y", "z"), a.axes)
        assertEquals(2, a.decimals)
        assertEquals("", a.permission)
        assertEquals(1.0, a.rawScale, 0.0)
        assertEquals(listOf("m/s²", "g"), a.units)
        assertEquals("total", a.derived.single().id)
        assertEquals("Total", a.derived.single().label)
        assertEquals("norm", a.derived.single().fn)
        assertEquals(2, a.derived.single().decimals)
        assertEquals(emptyList<Int>(), a.derived.single().inputs)
        assertEquals("note", a.doc)
        val g = c.sensor("gps")!!
        assertEquals(listOf("°", "m/s"), g.axisUnits)
        assertEquals(listOf(5, 1), g.axisDecimals)
        assertEquals("ACCESS_FINE_LOCATION", g.permission)
        val t = c.sensor("temp")!!
        assertEquals(0.1, t.rawScale, 0.0)
        val h = c.sensor("humid")!!.derived.single()
        assertEquals(listOf("temp"), h.needs)
        assertEquals(mapOf("k" to 2.5, "factor" to 1.5), h.args)
        assertEquals("lvl", h.band)
        assertEquals(listOf(0, 1), c.sensor("pressure")!!.derived.single().inputs)
    }

    @Test fun `conversions and bands parse, a null max is open-ended, a missing offset is zero`() {
        val c = Sensors.parse(base())
        assertEquals(Sensors.Conversion("°C", "°F", 1.8, 32.0), c.conversions.first { it.to == "°F" })
        assertEquals(Sensors.Conversion("m/s²", "g", 0.5, 0.0), c.conversions.first { it.to == "g" })
        assertEquals(listOf(Sensors.Band(10.0, "low"), Sensors.Band(Double.MAX_VALUE, "high")), c.bands["lvl"])
    }

    @Test fun `groups lists each group once in declaration order and inGroup filters`() {
        val c = Sensors.parse(base())
        assertEquals(listOf("motion", "position", "environment"), c.groups())
        assertEquals(listOf("temp", "humid"), c.inGroup("environment").map { it.id })
        assertEquals(emptyList<Sensors.Decl>(), c.inGroup("nowhere"))
        assertNull(c.sensor("nope"))
    }

    @Test fun `scaling, axis units and axis decimals fall back to the sensor's own`() {
        val c = Sensors.parse(base())
        assertEquals(listOf(21.5, 20.0), c.sensor("temp")!!.scaled(doubleArrayOf(215.0, 200.0)).toList())
        val g = c.sensor("gps")!!
        assertEquals("°", g.unitOfAxis(0))
        assertEquals("m/s", g.unitOfAxis(1))
        assertEquals("m/s", g.unitOfAxis(7))
        assertEquals(5, g.decimalsOfAxis(0))
        assertEquals(1, g.decimalsOfAxis(1))
        assertEquals(1, g.decimalsOfAxis(9))
        val a = c.sensor("accel")!!
        assertEquals("m/s²", a.unitOfAxis(0))
        assertEquals(2, a.decimalsOfAxis(0))
        val blank = Sensors.parse(base(sensors = BASE_SENSORS.replace("\"axis_units\":[\"°\",\"m/s\"]", "\"axis_units\":[\"\",\"\"]"))).sensor("gps")!!
        assertEquals("m/s", blank.unitOfAxis(0))
    }

    @Test fun `format is locale independent and never negative in places`() {
        assertEquals("3.14", Sensors.format(3.14159, 2))
        assertEquals("3", Sensors.format(3.14159, 0))
        assertEquals("3", Sensors.format(3.14159, -2))
        assertEquals("1013.2", Sensors.format(1013.25, 1))
    }

    // ── validation ─────────────────────────────────────────────────────────────────────────────

    @Test fun `a catalog with a defect is refused for the right reason`() {
        invalid(base(sensors = BASE_SENSORS.replace("\"id\":\"pressure\"", "\"id\":\"accel\"")), "duplicate sensor id accel")
        invalid(base(sensors = BASE_SENSORS.replace("\"source\":\"gnss\"", "\"source\":\"radar\"")), "gps source radar is unknown")
        invalid(base(sensors = BASE_SENSORS.replace("\"android_type\":1,", "")), "accel is a hardware sensor without an android_type")
        invalid(base(sensors = BASE_SENSORS.replace("\"display\":\"scalar\",\"decimals\":1,\"units\":[\"hPa\"", "\"display\":\"blob\",\"decimals\":1,\"units\":[\"hPa\"")), "pressure display blob is unknown")
        invalid(base(sensors = BASE_SENSORS.replace("ACCESS_FINE_LOCATION", "CALENDAR")), "gps permission CALENDAR is unknown")
        invalid(base(sensors = BASE_SENSORS.replace("\"axes\":[\"x\",\"y\",\"z\"],", "")), "accel shows axes but declares none")
        invalid(base(sensors = BASE_SENSORS.replace("\"axis_units\":[\"°\",\"m/s\"]", "\"axis_units\":[\"°\"]")), "gps axis_units and axes differ in length")
        invalid(base(sensors = BASE_SENSORS.replace("\"axis_decimals\":[5,1]", "\"axis_decimals\":[5]")), "gps axis_decimals and axes differ in length")
        invalid(base(sensors = BASE_SENSORS.replace("\"raw_scale\":0.1", "\"raw_scale\":0")), "temp raw_scale is zero")
        invalid(base(sensors = BASE_SENSORS.replace("\"units\":[\"hPa\",\"kPa\"]", "\"units\":[\"hPa\",\"furlongs\"]")), "pressure cannot convert hPa to furlongs")
        invalid(base(sensors = BASE_SENSORS.replace("\"fn\":\"norm\"", "\"fn\":\"teleport\"")), "accel.total uses unknown formula teleport")
        invalid(base(sensors = BASE_SENSORS.replace("\"band\":\"lvl\"", "\"band\":\"nope\"")), "humid.dew uses unknown band nope")
        invalid(base(sensors = BASE_SENSORS.replace("\"needs\":[\"temp\"]", "\"needs\":[\"ghost\"]")), "humid.dew needs unknown sensor ghost")
        invalid(base(sensors = BASE_SENSORS.replace("\"group\":\"motion\"", "\"group\":\"\"")), "accel has no group")
        invalid(base(bands = """{"lvl":[]}"""), "band lvl is empty")
        invalid(base(bands = """{"lvl":[{"max":10,"label":"a"},{"max":10,"label":"b"}]}"""), "band lvl is not ascending")
        invalid(base(bands = """{"lvl":[{"max":10,"label":"a"},{"max":5,"label":"b"}]}"""), "band lvl is not ascending")
    }

    @Test fun `a valid catalog has no validation errors and a rotation or gnss without axes is refused`() {
        assertEquals(emptyList<String>(), Sensors.validate(Sensors.parse(base())))
        invalid(base(sensors = BASE_SENSORS.replace("\"display\":\"scalar\",\"decimals\":1,\"units\":[\"hPa\"", "\"display\":\"rotation\",\"decimals\":1,\"units\":[\"hPa\"")), "pressure shows axes but declares none")
        invalid(base(sensors = BASE_SENSORS.replace("\"axes\":[\"lat\",\"speed\"],", "")), "gps shows axes but declares none")
    }

    // ── derive ─────────────────────────────────────────────────────────────────────────────────

    @Test fun `derive uses all the values, picks by index, appends the needed sensor's first value and puts a band into words`() {
        val c = Sensors.parse(base())
        val total = c.sensor("accel")!!.derived.single()
        assertEquals(5.0, Sensors.derive(c, total, doubleArrayOf(3.0, 4.0, 0.0)) { null }!!.number, eps)
        assertNull(Sensors.derive(c, total, doubleArrayOf(3.0, 4.0, 0.0)) { null }!!.words)
        val picked = c.sensor("pressure")!!.derived.single()
        assertEquals(5.0, Sensors.derive(c, picked, doubleArrayOf(3.0, 4.0, 99.0)) { null }!!.number, eps)
        assertNull("an input index beyond the values gives nothing", Sensors.derive(c, picked, doubleArrayOf(3.0)) { null })
        val dew = c.sensor("humid")!!.derived.single()
        val v = Sensors.derive(c, dew, doubleArrayOf(40.0)) { id -> if (id == "temp") doubleArrayOf(20.0, 99.0) else null }!!
        assertEquals("scale is the first value times the factor 1.5", 60.0, v.number, eps)
        assertEquals("high", v.words)
        assertNull("the needed sensor has no reading", Sensors.derive(c, dew, doubleArrayOf(40.0)) { null })
        assertNull("the needed sensor reported nothing", Sensors.derive(c, dew, doubleArrayOf(40.0)) { doubleArrayOf() })
    }

    @Test fun `derive gives nothing when the formula cannot answer`() {
        val c = Sensors.parse(base())
        val total = c.sensor("accel")!!.derived.single()
        assertNull(Sensors.derive(c, total, doubleArrayOf()) { null })
    }

    // ── units and bands ────────────────────────────────────────────────────────────────────────

    @Test fun `units convert forward, backward, identically and refuse the undeclared`() {
        val t = Sensors.parse(base()).conversions
        assertEquals(1.0, Units.convert(t, 2.0, "m/s²", "g")!!, eps)       // factor 0.5 in the fixture
        assertEquals(4.0, Units.convert(t, 2.0, "g", "m/s²")!!, eps)
        assertEquals(212.0, Units.convert(t, 100.0, "°C", "°F")!!, eps)
        assertEquals(100.0, Units.convert(t, 212.0, "°F", "°C")!!, eps)
        assertEquals(7.5, Units.convert(t, 7.5, "lx", "lx")!!, 0.0)
        assertNull(Units.convert(t, 1.0, "°C", "furlongs"))
        assertNull(Units.convert(t, 1.0, "furlongs", "°C"))
        assertNull("a zero factor cannot be inverted", Units.convert(listOf(Sensors.Conversion("a", "b", 0.0, 1.0)), 1.0, "b", "a"))
        assertEquals(1.0, Units.convert(listOf(Sensors.Conversion("a", "b", 0.0, 1.0)), 5.0, "a", "b")!!, 0.0)
    }

    @Test fun `a band names the first range at or above the value, else the last`() {
        val b = listOf(Sensors.Band(10.0, "A"), Sensors.Band(20.0, "B"))
        assertEquals("A", Bands.label(b, 5.0))
        assertEquals("A", Bands.label(b, 10.0))
        assertEquals("B", Bands.label(b, 10.0001))
        assertEquals("B", Bands.label(b, 20.0))
        assertEquals("B", Bands.label(b, 25.0))
        assertEquals("A", Bands.label(b, -100.0))
        assertNull(Bands.label(emptyList(), 1.0))
    }

    // ── formulas ───────────────────────────────────────────────────────────────────────────────

    private fun f(fn: String, vararg v: Double, args: Map<String, Double> = emptyMap()) = Formulas.apply(fn, v, args)

    @Test fun `every named formula exists and an unknown one answers nothing`() {
        assertEquals(
            setOf("identity", "scale", "norm", "g_force", "tilt_deg", "altitude_from_pressure", "dew_point", "heat_index", "absolute_humidity", "signal_percent", "azimuth_deg", "pitch_deg", "roll_deg"),
            Formulas.names,
        )
        assertNull(f("teleport", 1.0))
        Formulas.names.forEach { f(it, 1.0) }   // none throws on too few values: it answers null
    }

    @Test fun `identity scale norm and g force`() {
        assertEquals(7.0, f("identity", 7.0, 9.0)!!, 0.0)
        assertNull(f("identity"))
        assertEquals(10.0, f("scale", 4.0, args = mapOf("factor" to 2.0, "offset" to 2.0))!!, 0.0)
        assertEquals(4.0, f("scale", 4.0)!!, 0.0)
        assertEquals(3.0, f("scale", 4.0, args = mapOf("offset" to -1.0))!!, 0.0)
        assertNull(f("scale"))
        assertEquals(5.0, f("norm", 3.0, 4.0)!!, eps)
        assertEquals(13.0, f("norm", 3.0, 4.0, 12.0)!!, eps)
        assertNull(f("norm"))
        assertEquals(1.0, f("g_force", 0.0, 0.0, 9.80665)!!, eps)
        assertEquals(2.0, f("g_force", 0.0, 2 * 9.80665, 0.0)!!, eps)
        assertNull(f("g_force"))
    }

    @Test fun `tilt is the angle from the screen normal`() {
        assertEquals(0.0, f("tilt_deg", 0.0, 0.0, 9.8)!!, eps)
        assertEquals(90.0, f("tilt_deg", 0.0, 9.8, 0.0)!!, eps)
        assertEquals(180.0, f("tilt_deg", 0.0, 0.0, -9.8)!!, eps)
        assertEquals(60.0, f("tilt_deg", 0.0, Math.sqrt(3.0) / 2, 0.5)!!, 1e-4)
        assertNull(f("tilt_deg", 0.0, 0.0, 0.0))
        assertNull(f("tilt_deg", 0.0, 0.0))
        assertEquals(0.0, f("tilt_deg", 0.0, 0.0, 1e-8 + 1.0)!!, eps)
    }

    @Test fun `barometric altitude`() {
        assertEquals(0.0, f("altitude_from_pressure", 1013.25)!!, 1e-9)
        assertEquals(988.6465627, f("altitude_from_pressure", 900.0)!!, 1e-4)
        assertEquals(0.0, f("altitude_from_pressure", 1000.0, args = mapOf("sea_level_hpa" to 1000.0))!!, 1e-9)
        assertTrue(f("altitude_from_pressure", 1100.0)!! < 0.0)
        assertNull(f("altitude_from_pressure", 0.0))
        assertNull(f("altitude_from_pressure", -5.0))
        assertNull(f("altitude_from_pressure", 900.0, args = mapOf("sea_level_hpa" to 0.0)))
        assertNull(f("altitude_from_pressure"))
    }

    @Test fun `dew point heat index and absolute humidity take humidity first, then temperature`() {
        assertEquals(16.693149, f("dew_point", 60.0, 25.0)!!, 1e-5)
        assertEquals(9.255175, f("dew_point", 50.0, 20.0)!!, 1e-5)
        assertEquals(20.0, f("dew_point", 100.0, 20.0)!!, 1e-9)
        assertNull(f("dew_point", 0.0, 20.0))
        assertNull(f("dew_point", 60.0))
        assertEquals(40.409274, f("heat_index", 70.0, 32.0)!!, 1e-5)
        assertEquals(20.0, f("heat_index", 50.0, 20.0)!!, 0.0)
        assertEquals(26.863173, f("heat_index", 40.0, 27.0)!!, 1e-5)
        assertNull(f("heat_index", 50.0))
        assertEquals(8.639091, f("absolute_humidity", 50.0, 20.0)!!, 1e-5)
        assertEquals(24.283319, f("absolute_humidity", 80.0, 30.0)!!, 1e-5)
        assertNull(f("absolute_humidity", 50.0))
    }

    @Test fun `signal quality is a clamped percentage`() {
        val a = mapOf("min" to -100.0, "max" to -50.0)
        assertEquals(50.0, f("signal_percent", -75.0, args = a)!!, eps)
        assertEquals(0.0, f("signal_percent", -120.0, args = a)!!, eps)
        assertEquals(100.0, f("signal_percent", -40.0, args = a)!!, eps)
        assertEquals(50.0, f("signal_percent", -75.0)!!, eps)
        assertEquals(50.0, f("signal_percent", -95.0, args = mapOf("min" to -120.0, "max" to -70.0))!!, eps)
        assertNull(f("signal_percent", -75.0, args = mapOf("min" to -50.0, "max" to -50.0)))
        assertNull(f("signal_percent"))
    }

    @Test fun `orientation from a rotation vector`() {
        val s = Math.sqrt(0.5)
        assertEquals(270.0, f("azimuth_deg", 0.0, 0.0, s, s)!!, 1e-6)
        assertEquals(270.0, f("azimuth_deg", 0.0, 0.0, s)!!, 1e-6)       // w rebuilt from x, y, z
        assertEquals(90.0, f("azimuth_deg", 0.0, 0.0, -s, s)!!, 1e-6)
        assertEquals(0.0, f("azimuth_deg", 0.0, 0.0, 0.0, 1.0)!!, 1e-9)
        val h = Math.toRadians(15.0)
        assertEquals(-30.0, f("pitch_deg", Math.sin(h), 0.0, 0.0, Math.cos(h))!!, 1e-6)
        assertEquals(30.0, f("roll_deg", 0.0, Math.sin(h), 0.0, Math.cos(h))!!, 1e-6)
        assertEquals(0.0, f("pitch_deg", 0.0, 0.0, 0.0, 1.0)!!, 1e-9)
        assertEquals(0.0, f("roll_deg", 0.0, 0.0, 0.0, 1.0)!!, 1e-9)
        assertNull(f("azimuth_deg", 0.0, 0.0))
        assertNull(f("pitch_deg", 0.0))
        assertNull(f("roll_deg"))
        assertNull(Formulas.rotationMatrix(doubleArrayOf(1.0, 2.0)))
        val m = Formulas.rotationMatrix(doubleArrayOf(0.0, 0.0, 0.0, 1.0))!!
        listOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0).forEachIndexed { i, e -> assertEquals("element $i", e, m[i], 1e-12) }
        assertEquals(1.0, Formulas.rotationMatrix(doubleArrayOf(0.0, 0.0, 0.0))!![0], 0.0)
        // an over-unit vector cannot make a NaN: w is clamped to zero
        assertEquals(false, Formulas.rotationMatrix(doubleArrayOf(1.0, 1.0, 1.0))!!.any { it.isNaN() })
    }

    @Test fun `heading is tilt compensated magnetic north`() {
        assertEquals(0.0, Formulas.heading(0.0, 0.0, 9.81, 0.0, 30.0, -40.0)!!, 1e-6)
        assertEquals(270.0, Formulas.heading(0.0, 0.0, 9.81, 30.0, 0.0, -40.0)!!, 1e-6)
        assertEquals(180.0, Formulas.heading(0.0, 0.0, 9.81, 0.0, -30.0, -40.0)!!, 1e-6)
        assertEquals(90.0, Formulas.heading(0.0, 0.0, 9.81, -30.0, 0.0, -40.0)!!, 1e-6)
        assertNull("field parallel to gravity", Formulas.heading(0.0, 0.0, 9.81, 0.0, 0.0, -40.0))
        assertNull("no gravity", Formulas.heading(0.0, 0.0, 0.0, 0.0, 30.0, -40.0))
        // tilting the phone about its y axis (gravity gains an x part, the field turns with it) must not move the heading
        val tilt = Math.toRadians(30.0)
        val g = doubleArrayOf(9.81 * Math.sin(tilt), 0.0, 9.81 * Math.cos(tilt))
        val m = doubleArrayOf(-40.0 * Math.sin(tilt), 30.0, -40.0 * Math.cos(tilt))
        assertEquals(0.0, Screen.between(Formulas.heading(g[0], g[1], g[2], m[0], m[1], m[2])!!, 0.0), 1e-6)
        assertEquals(0.0, Formulas.wrap360(360.0), 0.0)
        assertEquals(350.0, Formulas.wrap360(-10.0), eps)
        assertEquals(10.0, Formulas.wrap360(730.0), eps)
        assertEquals(5.0, Formulas.norm(doubleArrayOf(3.0, 4.0)), eps)
    }

    // ── helpers for shaking, metal, steps and the screen ───────────────────────────────────────

    @Test fun `vibration is measured about the window's own mean`() {
        assertNull(Vibration.stats(emptyList()))
        val s = Vibration.stats(listOf(9.0, 11.0, 9.0, 11.0))!!
        assertEquals(10.0, s.mean, eps)
        assertEquals(1.0, s.rms, eps)
        assertEquals(1.0, s.peak, eps)
        assertEquals(4, s.count)
        val one = Vibration.stats(listOf(9.81))!!
        assertEquals(0.0, one.rms, eps)
        assertEquals(0.0, one.peak, eps)
        val spike = Vibration.stats(listOf(0.0, 0.0, 0.0, 0.0, 12.0))!!
        assertEquals(2.4, spike.mean, eps)
        assertEquals(4.8, spike.rms, eps)
        assertEquals(9.6, spike.peak, eps)
    }

    @Test fun `the window keeps only its newest samples and can be cleared`() {
        val w = Vibration.Window(3)
        assertNull(w.stats())
        listOf(100.0, 1.0, 2.0, 3.0).forEach { w.add(it) }
        assertEquals(3, w.count)
        assertEquals(2.0, w.stats()!!.mean, eps)
        w.clear()
        assertEquals(0, w.count)
        assertNull(w.stats())
        w.add(5.0)
        assertEquals(1, w.count)
    }

    @Test fun `the baseline is the mean of the first samples and then fixed`() {
        val b = Baseline(3)
        assertFalse(b.ready)
        assertNull(b.value)
        assertNull(b.delta(50.0))
        b.add(40.0); b.add(50.0)
        assertFalse(b.ready)
        assertNull(b.delta(50.0))
        b.add(60.0)
        assertTrue(b.ready)
        assertEquals(50.0, b.value!!, eps)
        b.add(1000.0)
        assertEquals(50.0, b.value!!, eps)
        assertEquals(12.5, b.delta(62.5)!!, eps)
        assertEquals(-5.0, b.delta(45.0)!!, eps)
        b.reset()
        assertFalse(b.ready)
        assertNull(b.value)
        b.add(10.0); b.add(10.0); b.add(10.0)
        assertEquals(10.0, b.value!!, eps)
    }

    @Test fun `cadence is steps per minute over the recent window`() {
        assertNull(Cadence.perMinute(emptyList(), 10_000))
        assertNull(Cadence.perMinute(listOf(9_000L), 10_000))
        assertEquals(90.0, Cadence.perMinute(listOf(8_000L, 8_500L, 9_000L, 10_000L), 10_000)!!, eps)
        assertEquals(60.0, Cadence.perMinute(listOf(1_000L, 2_000L, 3_000L), 3_000, windowMs = 5_000)!!, eps)
        assertNull("steps older than the window do not count", Cadence.perMinute(listOf(1_000L, 2_000L), 60_000))
        assertNull("two steps at the same instant have no rate", Cadence.perMinute(listOf(5_000L, 5_000L), 5_000))
        assertNull("steps in the future are ignored", Cadence.perMinute(listOf(20_000L, 21_000L), 10_000))
        assertEquals("an event exactly a window old still counts", 40.0, Cadence.perMinute(listOf(0L, 1_000L, 3_000L), 10_000, windowMs = 10_000)!!, eps)
        assertEquals(60.0, Cadence.perMinute(listOf(0L, 1_000L), 10_000, windowMs = 10_000)!!, eps)
        assertNull(Cadence.perMinute(listOf(-1L, 0L), 10_000, windowMs = 5_000))
    }

    @Test fun `screen geometry converts between millimetres and pixels and measures angles`() {
        assertEquals(160.0, Screen.mmToPx(25.4, 160.0), eps)
        assertEquals(25.4, Screen.pxToMm(160.0, 160.0), eps)
        assertEquals(63.5, Screen.pxToMm(Screen.mmToPx(63.5, 403.0), 403.0), eps)
        assertEquals(0.0, Screen.angleDeg(0.0, 0.0, 10.0, 0.0), eps)
        assertEquals(90.0, Screen.angleDeg(0.0, 0.0, 0.0, -10.0), eps)          // up on screen
        assertEquals(180.0, Screen.angleDeg(0.0, 0.0, -10.0, 0.0), eps)
        assertEquals(270.0, Screen.angleDeg(0.0, 0.0, 0.0, 10.0), eps)          // down on screen
        assertEquals(45.0, Screen.angleDeg(5.0, 5.0, 10.0, 0.0), eps)
        assertEquals(10.0, Screen.between(350.0, 0.0), eps)
        assertEquals(10.0, Screen.between(0.0, 350.0), eps)
        assertEquals(180.0, Screen.between(0.0, 180.0), eps)
        assertEquals(90.0, Screen.between(10.0, 100.0), eps)
        assertEquals(0.0, Screen.between(720.0, 0.0), eps)
    }

    // ── the real catalog ───────────────────────────────────────────────────────────────────────

    @Test fun `build json's own catalog parses and covers every sensor the owner listed`() {
        val bj = org.json.JSONObject(File("../build.json").readText())
        val c = Sensors.parse(bj.getJSONObject("sensors").toString())
        val ids = c.sensors.map { it.id }.toSet()
        listOf(
            "accelerometer", "gyroscope", "magnetometer", "gravity", "linear_acceleration", "rotation_vector", "pressure", "ambient_light",
            "proximity", "ambient_temperature", "humidity", "step_counter", "step_detector", "significant_motion", "heart_rate", "hinge_angle",
            "gnss", "battery_temperature", "battery_voltage", "wifi_signal", "cell_signal", "nfc",
        ).forEach { assertTrue("$it is declared", it in ids) }
        assertEquals(setOf("motion", "position", "environment", "activity", "device"), c.groups().toSet())
        val types = c.sensors.filter { it.source == "sensor" }.map { it.androidType }
        assertEquals("a hardware type is declared once", types.size, types.toSet().size)
    }

    @Test fun `build json's conversions are the physics they say`() {
        val c = Sensors.parse(org.json.JSONObject(File("../build.json").readText()).getJSONObject("sensors").toString())
        fun conv(v: Double, a: String, b: String) = Units.convert(c.conversions, v, a, b)!!
        assertEquals(1.0, conv(9.80665, "m/s²", "g"), 1e-9)
        assertEquals(180.0, conv(Math.PI, "rad/s", "°/s"), 1e-9)
        assertEquals(1.0, conv(100.0, "µT", "G"), 1e-9)
        assertEquals(101.325, conv(1013.25, "hPa", "kPa"), 1e-9)
        assertEquals(760.0, conv(1013.25, "hPa", "mmHg"), 0.01)
        assertEquals(29.92, conv(1013.25, "hPa", "inHg"), 0.01)
        assertEquals(9.29, conv(100.0, "lx", "fc"), 0.01)
        assertEquals(1.0, conv(2.54, "cm", "in"), 1e-9)
        assertEquals(98.6, conv(37.0, "°C", "°F"), 1e-9)
        assertEquals(310.15, conv(37.0, "°C", "K"), 1e-9)
        assertEquals(36.0, conv(10.0, "m/s", "km/h"), 1e-9)
        assertEquals(22.37, conv(10.0, "m/s", "mph"), 0.01)
        assertEquals(19.44, conv(10.0, "m/s", "kn"), 0.01)
        assertEquals(3.7, conv(3700.0, "mV", "V"), 1e-9)
        assertEquals(3.2808, conv(1.0, "m", "ft"), 1e-4)
    }

    @Test fun `build json's bands say what the owner would expect`() {
        val c = Sensors.parse(org.json.JSONObject(File("../build.json").readText()).getJSONObject("sensors").toString())
        fun band(name: String, v: Double) = Bands.label(c.bands.getValue(name), v)
        assertEquals("Very dark", band("light_level", 2.0))
        assertEquals("Indoor", band("light_level", 200.0))
        assertEquals("Direct sun", band("light_level", 90000.0))
        assertEquals("Near", band("proximity_state", 0.0))
        assertEquals("Far", band("proximity_state", 5.0))
        assertEquals("Off body", band("offbody", 0.0))
        assertEquals("On body", band("offbody", 1.0))
        assertEquals("N", band("compass8", 0.0))
        assertEquals("NE", band("compass8", 45.0))
        assertEquals("E", band("compass8", 90.0))
        assertEquals("SE", band("compass8", 135.0))
        assertEquals("S", band("compass8", 180.0))
        assertEquals("SW", band("compass8", 225.0))
        assertEquals("W", band("compass8", 270.0))
        assertEquals("NW", band("compass8", 315.0))
        assertEquals("N", band("compass8", 350.0))
        assertEquals("Still", band("shake_class", 0.01))
        assertEquals("Severe", band("shake_class", 10.0))
        assertEquals("Nothing nearby", band("metal_class", 1.0))
        assertEquals("Strong metal or a magnet", band("metal_class", 500.0))
    }

    @Test fun `build json's derived quantities compute from realistic readings`() {
        val c = Sensors.parse(org.json.JSONObject(File("../build.json").readText()).getJSONObject("sensors").toString())
        fun derived(sensor: String, id: String, vararg v: Double, lookup: (String) -> DoubleArray? = { null }) =
            Sensors.derive(c, c.sensor(sensor)!!.derived.first { it.id == id }, v, lookup)
        assertEquals(1.0, derived("accelerometer", "total", 0.0, 0.0, 9.80665)!!.number, 1e-9)
        assertEquals(988.6, derived("pressure", "altitude", 900.0)!!.number, 0.1)
        assertEquals("Daylight", derived("ambient_light", "level", 5000.0)!!.words)
        assertEquals("Near", derived("proximity", "state", 0.0)!!.words)
        assertEquals(75.0, derived("step_counter", "distance", 100.0)!!.number, 1e-9)
        assertEquals(4.0, derived("step_counter", "energy", 100.0)!!.number, 1e-9)
        assertEquals(50.0, derived("wifi_signal", "quality", -75.0)!!.number, 1e-9)
        assertEquals(50.0, derived("cell_signal", "quality", -95.0)!!.number, 1e-9)
        assertEquals(16.69, derived("humidity", "dew_point", 60.0) { doubleArrayOf(25.0) }!!.number, 0.01)
        assertNull(derived("humidity", "dew_point", 60.0))
        assertEquals(270.0, derived("rotation_vector", "azimuth", 0.0, 0.0, Math.sqrt(0.5), Math.sqrt(0.5))!!.number, 1e-6)
        assertEquals("On body", derived("offbody_detect", "worn", 1.0)!!.words)
        assertEquals(31.5, c.sensor("battery_temperature")!!.scaled(doubleArrayOf(315.0))[0], 1e-9)
    }

    companion object {
        const val BASE_CONVERSIONS = """[{"from":"m/s²","to":"g","factor":0.5},{"from":"°C","to":"°F","factor":1.8,"offset":32},{"from":"hPa","to":"kPa","factor":0.1}]"""
        const val BASE_BANDS = """{"lvl":[{"max":10,"label":"low"},{"max":null,"label":"high"}]}"""
        const val BASE_SENSORS = """[
          {"id":"accel","label":"Accelerometer","group":"motion","source":"sensor","android_type":1,"unit":"m/s²","display":"vector","axes":["x","y","z"],"units":["m/s²","g"],"_doc":"note",
           "derived":[{"id":"total","label":"Total","fn":"norm","unit":"m/s²"}]},
          {"id":"pressure","label":"Barometer","group":"position","source":"sensor","android_type":6,"unit":"hPa","display":"scalar","decimals":1,"units":["hPa","kPa"],
           "derived":[{"id":"picked","label":"Picked","fn":"norm","inputs":[0,1],"unit":"hPa"}]},
          {"id":"temp","label":"Temperature","group":"environment","source":"battery_temperature","unit":"°C","display":"scalar","decimals":1,"raw_scale":0.1,"units":["°C","°F"]},
          {"id":"humid","label":"Humidity","group":"environment","source":"sensor","android_type":12,"unit":"%","display":"scalar",
           "derived":[{"id":"dew","label":"Dew","fn":"scale","needs":["temp"],"args":{"k":2.5,"factor":1.5},"band":"lvl"}]},
          {"id":"gps","label":"GPS","group":"position","source":"gnss","unit":"m/s","display":"gnss","permission":"ACCESS_FINE_LOCATION","decimals":1,
           "axes":["lat","speed"],"axis_units":["°","m/s"],"axis_decimals":[5,1]}
        ]"""
    }
}
