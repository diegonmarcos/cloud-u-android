package com.diegonmarcos.superapp.apps

import com.diegonmarcos.superapp.apps.CalcHandoff.Target
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Notify calculator action targets Cloud Calc, then Cloud Store, never the system app. */
class CalcHandoffTest {

    @Test fun `the target is Cloud Calc's package`() {
        assertEquals("com.diegonmarcos.cloudcalc", CalcHandoff.PKG)
    }

    @Test fun `it agrees with the package Cloud Calc is built as`() {
        val gradle = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "ac_cloud-calc/app/build.gradle") }.firstOrNull { it.exists() }
        if (gradle != null) assertTrue(gradle.readText().contains("applicationId '${CalcHandoff.PKG}'"))
    }

    @Test fun `installed Cloud Calc wins over everything`() {
        assertEquals(Target.CLOUD_CALC, CalcHandoff.decide(calcInstalled = true, storeInstalled = true))
        assertEquals(Target.CLOUD_CALC, CalcHandoff.decide(calcInstalled = true, storeInstalled = false))
    }

    @Test fun `without Cloud Calc, Cloud Store opens when installed`() {
        assertEquals(Target.CLOUD_STORE, CalcHandoff.decide(calcInstalled = false, storeInstalled = true))
    }

    @Test fun `with neither, SuperApp's own Store installs it`() {
        assertEquals(Target.SUPERAPP_STORE, CalcHandoff.decide(calcInstalled = false, storeInstalled = false))
    }

    @Test fun `no route ever lands on the system calculator`() {
        for (c in listOf(true, false)) for (s in listOf(true, false))
            assertNotEquals(null, CalcHandoff.decide(c, s))
        assertEquals("cloud", CalcHandoff.STORE_TAB)
    }
}
