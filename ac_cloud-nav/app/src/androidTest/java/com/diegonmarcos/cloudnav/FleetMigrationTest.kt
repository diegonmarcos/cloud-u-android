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
import org.junit.After
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #783 — the fleet configuration contract ON A REAL DEVICE (the emulator Test → Cloud Nav boots),
 * where the JVM suite cannot go: the `<package>.fleetconfig` provider merged in from libs:core,
 * and an EncryptedSharedPreferences store opened through the Android Keystore.
 *
 * One phone's configuration is written, exported, the profile is WIPED (every exported store file
 * deleted — the clean phone), the export is imported back through the provider, and the second
 * export must equal the first, store by store, key by key, type by type.
 *
 * cloud-nav keeps no encrypted store, so it ships no cipher (libs:core takes security-crypto
 * compileOnly): an import naming an encrypted store must be refused for that file alone, on the
 * device, without taking the rest of the import — or the app — down with it.
 *
 * #796 the manifest is no longer in cloud-nav's APK: it travels with every call, as it does from
 * the SuperApp. This test is the caller: it reads the copy the installed Cloud-Lib-Fleetconfig
 * carries (#855) and hands it over on each call; `hello` must say so.
 */
@RunWith(AndroidJUnit4::class)
class FleetMigrationTest {

    private val ctx: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** The fleet manifest, handed over as text (#825: the policy that parses it runs in
     *  Cloud-Lib-Fleetconfig, so this test names no class of it). #855 read out of the installed
     *  engine APK, which carries it (libs:fleetconfig-model); empty when the engine is absent,
     *  and then every test is skipped by [snapshot]. */
    private val manifest: String by lazy {
        runCatching {
            ctx.createPackageContext(ENGINE, 0).assets.open(FleetConfig.ASSET).bufferedReader().use { it.readText() }
        }.getOrDefault("{}")
    }

    /** cloud-nav's declared schema_version, or null when the manifest does not declare it. */
    private fun schema(): Int? = JSONObject(manifest).optJSONObject("apps")?.let { apps ->
        apps.keys().asSequence().map { apps.getJSONObject(it) }.firstOrNull { it.optString("package") == ctx.packageName }
    }?.optInt("schema_version", 1)

    private fun canonical(v: Any?): String = when (v) {
        is JSONObject -> v.keys().asSequence().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(v.opt(it)) }
        is JSONArray -> (0 until v.length()).joinToString(",", "[", "]") { canonical(v.opt(it)) }
        is String -> JSONObject.quote(v)
        else -> v.toString()
    }

    private fun call(method: String, body: JSONObject? = null): JSONObject {
        val extras = Bundle().apply {
            putString(FleetConfig.KEY_MANIFEST, manifest)
            body?.let { putString(FleetConfig.KEY_JSON, it.toString()) }
        }
        val out = ctx.contentResolver.call(Uri.parse("content://" + FleetConfig.authority(ctx.packageName)), method, null, extras)
        assertNotNull("the provider answered $method", out)
        out!!.getString(FleetConfig.KEY_ERROR)?.let { throw AssertionError("$method refused: $it") }
        return JSONObject(out.getString(FleetConfig.KEY_JSON)!!)
    }

    /** The app's real configuration before a test: restored after it, so the render tests that
     *  share this emulator see cloud-nav exactly as they would have (a leaked cockpit mode once
     *  hid the Explored tab from ExploredRenderTest). */
    private lateinit var saved: JSONObject

    private companion object { const val ENGINE = "com.diegonmarcos.cloudlib.fleetconfig" }

    @Before fun snapshot() {
        // #825 the policy is Cloud-Lib-Fleetconfig's: on an emulator that was not given the
        // engine APK, the provider says so, and there is nothing of this app's to test.
        val probe = ctx.contentResolver.call(Uri.parse("content://" + FleetConfig.authority(ctx.packageName)), FleetConfig.METHOD_EXPORT, null,
            Bundle().apply { putString(FleetConfig.KEY_MANIFEST, manifest) })
        Assume.assumeFalse("Cloud-Lib-Fleetconfig is not installed on this device",
            probe?.getString(FleetConfig.KEY_ERROR).orEmpty().startsWith("Cloud-Lib-Fleetconfig"))
        saved = call(FleetConfig.METHOD_EXPORT).getJSONObject("stores")
    }

    @After fun restore() {
        // A skipped test (no engine on this device) snapshotted nothing, and must restore nothing:
        // JUnit runs @After even when @Before stopped on an assumption.
        if (!::saved.isInitialized) return
        for (f in call(FleetConfig.METHOD_EXPORT).getJSONObject("stores").keys()) ctx.deleteSharedPreferences(f)
        if (saved.length() > 0) call(FleetConfig.METHOD_IMPORT, JSONObject().put("stores", saved))
    }

    @Test fun the_handshake_says_the_manifest_comes_from_the_app_itself() {
        val h = ctx.contentResolver.call(Uri.parse("content://" + FleetConfig.authority(ctx.packageName)), FleetConfig.METHOD_HELLO, null, null)
        val r = JSONObject(h!!.getString(FleetConfig.KEY_JSON)!!)
        assertEquals(FleetConfig.CONTRACT, r.getInt(FleetConfig.KEY_CONTRACT))
        // #873 libs:fleetconfig-model bakes fleet-config.json into every app, so the app answers from
        // its own copy; a caller's manifest (the calls above) still wins when one travels with the call.
        assertEquals("self", r.getString(FleetConfig.KEY_MANIFEST))
        // A call that brings no manifest is therefore answered from the app's own, not refused.
        val bare = ctx.contentResolver.call(Uri.parse("content://" + FleetConfig.authority(ctx.packageName)), FleetConfig.METHOD_EXPORT, null, null)
        assertFalse("export without a manifest is answered from the app's own", bare!!.getString(FleetConfig.KEY_ERROR).orEmpty().startsWith("no manifest"))
    }

    @Test fun a_configured_profile_survives_wipe_and_import_through_the_provider() {
        val schema = schema()
        assertNotNull("cloud-nav is a declared fleet app", schema)

        // The old phone's configuration: plain stores of every value type.
        val old = JSONObject().put("stores", JSONObject()
            .put("cloud_nav_cockpit", JSONObject().put("mode", "drive"))
            .put("maps_basemap_prefs", JSONObject().put("family", "vector").put("three_d", true))
            .put("maps_tracking", JSONObject().put("interval_s", 15).put("min_move_m", 2.5).put("since", 1_700_000_000_123L)
                .put(FleetConfig.TYPES, JSONObject().put("interval_s", "i").put("min_move_m", "f").put("since", "l"))))
        assertEquals(6, call(FleetConfig.METHOD_IMPORT, old).getInt("written"))

        val before = call(FleetConfig.METHOD_EXPORT).getJSONObject("stores")
        for (f in listOf("cloud_nav_cockpit", "maps_basemap_prefs", "maps_tracking"))
            assertTrue("$f exported", before.has(f))

        // The clean phone.
        for (f in before.keys()) ctx.deleteSharedPreferences(f)
        val wiped = call(FleetConfig.METHOD_EXPORT).getJSONObject("stores")
        for (f in before.keys()) assertFalse("$f is gone after the wipe", wiped.has(f))

        // Apply the declared copy through the provider, as the SuperApp does (restart=false: the
        // instrumentation lives in this process).
        call(FleetConfig.METHOD_IMPORT, JSONObject().put("schema_version", schema!!).put("stores", before))
        val after = call(FleetConfig.METHOD_EXPORT).getJSONObject("stores")
        for (f in before.keys())
            assertEquals("$f equal after the migration", canonical(before.getJSONObject(f)), canonical(after.optJSONObject(f)))

        // The app reads them back the way it reads its own settings.
        val t = ctx.getSharedPreferences("maps_tracking", Context.MODE_PRIVATE)
        assertEquals(15, t.getInt("interval_s", -1))
        assertEquals(2.5f, t.getFloat("min_move_m", -1f))
        assertEquals(1_700_000_000_123L, t.getLong("since", -1))
    }

    @Test fun an_encrypted_store_in_an_app_without_the_cipher_is_refused_alone() {
        val r = call(FleetConfig.METHOD_IMPORT, JSONObject().put("stores", JSONObject()
            .put("mail_jmap_prefs", JSONObject().put("server", "https://mail.example.test"))
            .put("cloud_nav_cockpit", JSONObject().put("mode", "walk"))))
        assertEquals("cannot open", r.getJSONObject("files").getJSONObject("mail_jmap_prefs").getString("error"))
        assertEquals(1, r.getInt("written"))
        assertEquals("walk", ctx.getSharedPreferences("cloud_nav_cockpit", Context.MODE_PRIVATE).getString("mode", null))
        // And an export on the same app still answers.
        assertTrue(call(FleetConfig.METHOD_EXPORT).getJSONObject("stores").has("cloud_nav_cockpit"))
    }
}
