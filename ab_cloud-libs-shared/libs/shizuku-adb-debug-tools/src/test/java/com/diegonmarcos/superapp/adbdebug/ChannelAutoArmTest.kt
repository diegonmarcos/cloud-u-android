package com.diegonmarcos.superapp.adbdebug

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** The channel arms itself only when the app's switch says so; the default per app is build.json data. */
class ChannelAutoArmTest {

    @Test fun declaredDefaultComesFromTheBlock() {
        assertFalse(ChannelAutoArm.declaredDefault("""{"default_on":false,"needs":[]}"""))
        assertTrue(ChannelAutoArm.declaredDefault("""{"default_on":true}"""))
    }

    @Test fun anAppThatDeclaresNothingKeepsTheOldBehaviour() {
        assertTrue(ChannelAutoArm.declaredDefault(null))
        assertTrue(ChannelAutoArm.declaredDefault("{}"))
        assertTrue(ChannelAutoArm.declaredDefault("not json"))
    }

    @Test fun theUsersExplicitChoiceBeatsTheDefault() {
        val off = """{"default_on":false}"""
        val on = """{"default_on":true}"""
        assertFalse(ChannelAutoArm.resolve(null, off))
        assertTrue(ChannelAutoArm.resolve(true, off))
        assertTrue(ChannelAutoArm.resolve(null, on))
        assertFalse(ChannelAutoArm.resolve(false, on))
    }

    /** SuperApp ships OFF, Cloud Store ships ON, read from their own build.json. */
    @Test fun defaultPerApp() {
        val repo = generateSequence(File("").absoluteFile) { it.parentFile }.firstOrNull { File(it, "aa_cloud-superapp/build.json").exists() }
        assumeTrue("not run from a full checkout", repo != null)
        fun def(app: String) = ChannelAutoArm.declaredDefault(
            JSONObject(File(repo, "$app/build.json").readText()).getJSONObject("privileged_channel").toString())
        assertFalse(def("aa_cloud-superapp"))
        assertTrue(def("ac_cloud-store"))
        assertEquals(true, def("ac_cloud-account"))
    }
}
