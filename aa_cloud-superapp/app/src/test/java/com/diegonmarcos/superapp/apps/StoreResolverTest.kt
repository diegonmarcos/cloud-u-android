package com.diegonmarcos.superapp.apps

import android.app.Application
import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.superapp.appstore.AppInventory
import com.diegonmarcos.superapp.appstore.ExternalInstall
import com.diegonmarcos.superapp.appstore.FDroidIndex
import com.diegonmarcos.superapp.appstore.PhoneAppActions
import com.diegonmarcos.superapp.appstore.SourceResolver
import com.diegonmarcos.superapp.appstore.StoreBar
import com.diegonmarcos.superapp.appstore.StorePhoneFragment
import com.diegonmarcos.superapp.updater.Fleet
import com.diegonmarcos.superapp.appstore.BuildConfig as StoreBuildConfig
import com.diegonmarcos.superapp.appstore.R as StoreR
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * #571 WE ARE THE STORE — the source resolver, asserted on the DECLARATION it
 * parses, the ACTIONS it derives and the ROWS the Phone tab renders on an
 * empty phone (Robolectric's PackageManager has only this test app).
 *
 * Honesty is the property under test: a Play-only app must show the 'needs
 * Play' badge and a disabled Install, never a download; a Play rung must not
 * be able to carry a URL; a ladder must not rank Play above a direct rung.
 * Each of those is proven by MUTATING the real declaration and watching the
 * parser refuse it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class StoreResolverTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val sources get() = PhoneAppActions.sources(ctx)
    private val cfg get() = PhoneAppActions.resolver(sources)

    private fun playOnly(): SourceResolver.External =
        cfg.apps.values.first { it.needsPlay }
    private fun direct(): SourceResolver.External =
        cfg.apps.values.first { !it.needsPlay && it.hasPlay }

    @Test
    fun `the declaration is honest - ladders follow the order, Play carries nothing, vendor is https`() {
        val c = cfg
        assertEquals(listOf(SourceResolver.KIND_VENDOR, SourceResolver.KIND_FDROID, SourceResolver.KIND_PLAY), c.order)
        assertTrue("no external apps declared", c.apps.isNotEmpty())
        assertTrue("the declaration must hold at least one Play-only app to keep the badge honest", c.apps.values.any { it.needsPlay })
        assertTrue("the declaration must hold at least one app this store can install itself", c.apps.values.any { !it.needsPlay })
        for (app in c.apps.values) {
            val ranks = app.sources.map { c.order.indexOf(it.kind) }
            assertEquals("${app.pkg} ladder out of order", ranks.sorted(), ranks)
            for (s in app.sources) if (s is SourceResolver.Source.Vendor) {
                s.apk?.let { assertTrue("${app.pkg} vendor url not https: $it", it.startsWith("https://")) }
                assertFalse("${app.pkg} fakes a Play download URL", (s.apk ?: "").contains("play.google.com"))
            }
        }
        assertTrue("the Play rung's installer must be a store the #564 map knows",
            sources.getJSONObject("sources").has(c.playInstaller))
        assertEquals("F-Droid signer pin must be a sha256 hex", 64, c.fdroid.getString("cert_sha256").length)
    }

    private fun mutated(edit: (JSONObject) -> Unit): JSONObject = sources.also(edit)

    @Test
    fun `mutation - a URL on a Play rung is refused`() {
        val pkg = playOnly().pkg
        val m = mutated { it.getJSONObject("resolver").getJSONObject("apps").getJSONObject(pkg)
            .getJSONArray("sources").getJSONObject(0).put("apk", "https://example.invalid/fake.apk") }
        assertFalse("a play source with a url parsed", runCatching { SourceResolver.config(m) }.isSuccess)
    }

    @Test
    fun `mutation - Play ranked above a direct rung is refused`() {
        val pkg = direct().pkg
        val m = mutated {
            val arr = it.getJSONObject("resolver").getJSONObject("apps").getJSONObject(pkg).getJSONArray("sources")
            val items = (0 until arr.length()).map { i -> arr.getJSONObject(i) }.reversed()
            it.getJSONObject("resolver").getJSONObject("apps").getJSONObject(pkg).put("sources", JSONArray(items))
        }
        assertFalse("a ladder with Play first parsed", runCatching { SourceResolver.config(m) }.isSuccess)
    }

    @Test
    fun `mutation - a vendor rung without https is refused`() {
        val pkg = cfg.apps.values.first { a -> a.sources.any { it is SourceResolver.Source.Vendor && it.apk != null } }.pkg
        val m = mutated { it.getJSONObject("resolver").getJSONObject("apps").getJSONObject(pkg)
            .getJSONArray("sources").getJSONObject(0).put("apk", "http://example.invalid/plain.apk") }
        assertFalse("a plain-http vendor url parsed", runCatching { SourceResolver.config(m) }.isSuccess)
    }

    @Test
    fun `a Play-only app - Install disabled with the reason, origin is its Play page, never a download`() {
        val app = playOnly()
        val actions = PhoneAppActions.forMissing(ctx, app, null, sources, cfg)
        val install = actions.single { it.kind == PhoneAppActions.Kind.INSTALL }
        assertNotNull("Install must be disabled for a Play-only app", install.disabledReason)
        assertTrue("the reason must name the app", install.disabledReason!!.contains(app.label))
        assertNull("a disabled Install must carry no intent", install.intent)
        val origin = actions.single { it.kind == PhoneAppActions.Kind.ORIGIN }
        assertEquals("the origin must be aimed at the Play installer", cfg.playInstaller, origin.intent!!.`package`)
        assertTrue("the Play page must name the app", origin.intent!!.data.toString().contains(app.pkg))
        assertEquals("ExternalInstall must refuse a Play-only app with the same reason",
            ctx.getString(StoreR.string.store_phone_why_play_only, app.label), ExternalInstall.run(ctx, cfg, app))
    }

    @Test
    fun `a direct app - Install enabled, and an undeclared package resolves to Play alone`() {
        val app = direct()
        val install = PhoneAppActions.forMissing(ctx, app, null, sources, cfg).single { it.kind == PhoneAppActions.Kind.INSTALL }
        assertNull("Install must be enabled for a vendor/F-Droid app", install.disabledReason)
        val unknown = SourceResolver.resolve(cfg, "com.example.nobody.declared")
        assertFalse(unknown.declared)
        assertTrue(unknown.needsPlay)
        assertEquals(listOf(SourceResolver.KIND_PLAY), unknown.sources.map { it.kind })
    }

    @Test
    fun `version names compare numerically and refuse to compare what is not numeric`() {
        assertTrue(SourceResolver.compareNames("156.0.1", "155.9.9")!! > 0)
        assertEquals(0, SourceResolver.compareNames("2026.9.0", "2026.9"))
        assertTrue(SourceResolver.compareNames("1.9.12", "1.13.8")!! < 0)
        assertNull(SourceResolver.compareNames("latest", "1.0"))
        assertNull(SourceResolver.compareNames(null, "1.0"))
    }

    @Test
    fun `an unsigned F-Droid index is rejected, and the streamed lookup reads only sha256 versions`() {
        val jar = File.createTempFile("unsigned", ".jar", ctx.cacheDir)
        ZipOutputStream(jar.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("index-v1.json")); z.write("{}".toByteArray()); z.closeEntry()
        }
        val refused = runCatching { FDroidIndex.verifiedEntry(jar, "index-v1.json", cfg.fdroid.getString("cert_sha256")) }
        assertTrue("an unsigned index was accepted", refused.isFailure)
        assertTrue(refused.exceptionOrNull()!!.message!!.contains("not signed"))

        val index = """{"repo":{"name":"x"},"requests":{},
            "apps":[{"packageName":"a.b","suggestedVersionCode":"12"},{"packageName":"c.d","suggestedVersionCode":"3"}],
            "packages":{"a.b":[{"apkName":"a.b_12.apk","hash":"${"f".repeat(64)}","hashType":"sha256","versionCode":12,"versionName":"1.2","nativecode":["arm64-v8a"]},
                                {"apkName":"a.b_11.apk","hash":"deadbeef","hashType":"md5","versionCode":11,"versionName":"1.1"}],
                        "c.d":[]}}"""
        val e = FDroidIndex.lookup(index.byteInputStream(), "a.b")!!
        assertEquals(12L, e.suggestedVersionCode)
        assertEquals(listOf("a.b_12.apk"), e.versions.map { it.apkName })
        assertEquals(listOf("arm64-v8a"), e.versions.single().nativecode)
        assertNull(FDroidIndex.lookup(index.byteInputStream(), "z.z"))
    }

    @Test
    fun `an import plans a declared direct app to this store, not to the store that installed it`() {
        val app = direct()
        val play = cfg.playInstaller
        val file = AppInventory.toJson(listOf(
            AppInventory.Entry(app.pkg, "1", 1, play, false, null),
            AppInventory.Entry("com.example.foreign", "1", 1, play, false, null)))
        val plan = AppInventory.plan(AppInventory.parse(file), emptySet(), AppInventory.fleetPackages(), sources)
        assertEquals(listOf(app.pkg), plan.direct.map { it.pkg })
        assertEquals(listOf("com.example.foreign"), plan.store.map { it.entry.pkg })
    }

    // ── the rendered page on an empty phone ──────────────────────────────

    private fun texts(v: View): List<TextView> = when (v) {
        is TextView -> listOf(v)
        is ViewGroup -> (0 until v.childCount).flatMap { texts(v.getChildAt(it)) }
        else -> emptyList()
    }

    private fun rendered(): View {
        val f = StorePhoneFragment()
        val act = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        act.supportFragmentManager.beginTransaction().add(android.R.id.content, f).commitNow()
        val root = f.requireView()
        // rows() runs on its own thread and posts back: idle the main looper until a state line lands.
        repeat(200) {
            shadowOf(Looper.getMainLooper()).idle()
            if (texts(root).any { (it.tag as? String)?.startsWith(StorePhoneFragment.STATE_TAG_PREFIX) == true }) return root
            Thread.sleep(25)
        }
        return root
    }

    @Test
    fun `an empty phone renders every fleet app and every declared external app, with honest state lines`() {
        val root = rendered()
        val states = texts(root).filter { (it.tag as? String)?.startsWith(StorePhoneFragment.STATE_TAG_PREFIX) == true }
            .associateBy { (it.tag as String).removePrefix(StorePhoneFragment.STATE_TAG_PREFIX) }
        assertTrue("no rows rendered", states.isNotEmpty())
        val fleetApps = Fleet.parse(StoreBuildConfig.CONSTELLATION_FLEET_B64).filter { it.kind == "app" }
        for (a in fleetApps) assertTrue("fleet app ${a.pkg} has no row on an empty phone", a.pkg in states)
        for (a in cfg.apps.values) assertTrue("declared app ${a.pkg} has no row on an empty phone", a.pkg in states)

        val badge = ctx.getString(StoreR.string.store_phone_badge_needs_play)
        for (a in cfg.apps.values) {
            val line = states.getValue(a.pkg).text.toString()
            if (a.needsPlay) assertTrue("${a.pkg} is Play-only but shows no badge: $line", line.contains(badge))
            else assertFalse("${a.pkg} has a direct rung but shows the Play badge: $line", line.contains(badge))
        }
        val fleetLine = states.getValue(fleetApps.first { it.pkg != ctx.packageName }.pkg).text.toString()
        assertEquals(ctx.getString(StoreR.string.store_phone_state_not_installed, ctx.getString(StoreR.string.store_phone_source_fleet)), fleetLine)

        // The bar's batch verbs are real on this tab now: enabled, with a click.
        val bar = root.findViewWithTag<ViewGroup>(StoreBar.TAG)!!
        val verbs = texts(bar).filter { it.tag is StoreBar.Item }.associateBy { it.tag as StoreBar.Item }
        for (i in listOf(StoreBar.Item.INSTALL, StoreBar.Item.UPDATE)) {
            assertTrue("$i must be enabled on Phone Apps", verbs.getValue(i).isEnabled)
            assertTrue("$i must answer a tap", verbs.getValue(i).hasOnClickListeners())
        }
    }
}
