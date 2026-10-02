package com.diegonmarcos.cloudnav

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.diegonmarcos.superapp.core.FleetConfig
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #783 — the fleet configuration contract ON A REAL DEVICE (the emulator Test → Cloud Nav boots),
 * where the JVM suite cannot go: the `<package>.fleetconfig` provider merged in from libs:core,
 * and an EncryptedSharedPreferences store opened through the Android Keystore.
 *
 * One phone's configuration is written, exported, the profile is WIPED (every exported store file
 * deleted — the clean phone), the export is imported back through the provider, and the second
 * export must equal the first, store by store, key by key, type by type — the secret included.
 */
@RunWith(AndroidJUnit4::class)
class FleetMigrationTest {

    private val ctx: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun canonical(v: Any?): String = when (v) {
        is JSONObject -> v.keys().asSequence().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(v.opt(it)) }
        is JSONArray -> (0 until v.length()).joinToString(",", "[", "]") { canonical(v.opt(it)) }
        is String -> JSONObject.quote(v)
        else -> v.toString()
    }

    private fun call(method: String, body: JSONObject? = null): JSONObject {
        val extras = body?.let { Bundle().apply { putString(FleetConfig.KEY_JSON, it.toString()) } }
        val out = ctx.contentResolver.call(Uri.parse("content://" + FleetConfig.authority(ctx.packageName)), method, null, extras)
        assertNotNull("the provider answered $method", out)
        out!!.getString(FleetConfig.KEY_ERROR)?.let { throw AssertionError("$method refused: $it") }
        return JSONObject(out.getString(FleetConfig.KEY_JSON)!!)
    }

    @Test fun a_configured_profile_survives_wipe_and_import_through_the_provider() {
        val m = FleetConfig.manifest(ctx)
        val app = m.appByPackage(ctx.packageName)
        assertNotNull("cloud-nav is a declared fleet app", app)

        // The old phone's configuration: plain stores of every value type, and an encrypted one
        // whose secret key migrates (mail_jmap_prefs: config, its password narrowed to secret).
        val old = JSONObject().put("stores", JSONObject()
            .put("cloud_nav_cockpit", JSONObject().put("mode", "drive"))
            .put("maps_basemap_prefs", JSONObject().put("family", "vector").put("three_d", true))
            .put("maps_tracking", JSONObject().put("interval_s", 15).put("min_move_m", 2.5).put("since", 1_700_000_000_123L)
                .put(FleetConfig.TYPES, JSONObject().put("interval_s", "i").put("min_move_m", "f").put("since", "l")))
            .put("mail_jmap_prefs", JSONObject().put("server", "https://mail.example.test").put("password", "pw-783-test")))
        assertTrue(call(FleetConfig.METHOD_IMPORT, old).getInt("written") >= 7)

        val before = call(FleetConfig.METHOD_EXPORT).getJSONObject("stores")
        for (f in listOf("cloud_nav_cockpit", "maps_basemap_prefs", "maps_tracking", "mail_jmap_prefs"))
            assertTrue("$f exported", before.has(f))
        assertEquals("pw-783-test", before.getJSONObject("mail_jmap_prefs").getString("password"))

        // The clean phone.
        for (f in before.keys()) ctx.deleteSharedPreferences(f)
        val wiped = call(FleetConfig.METHOD_EXPORT).getJSONObject("stores")
        for (f in before.keys()) assertFalse("$f is gone after the wipe", wiped.has(f))

        // Apply the declared copy through the provider, as the SuperApp does (restart=false: the
        // instrumentation lives in this process).
        call(FleetConfig.METHOD_IMPORT, JSONObject().put("schema_version", app!!.schema).put("stores", before))
        val after = call(FleetConfig.METHOD_EXPORT).getJSONObject("stores")
        for (f in before.keys())
            assertEquals("$f equal after the migration", canonical(before.getJSONObject(f)), canonical(after.optJSONObject(f)))

        // The app reads them back the way it reads its own settings.
        val t = ctx.getSharedPreferences("maps_tracking", Context.MODE_PRIVATE)
        assertEquals(15, t.getInt("interval_s", -1))
        assertEquals(2.5f, t.getFloat("min_move_m", -1f))
        assertEquals(1_700_000_000_123L, t.getLong("since", -1))
    }
}
