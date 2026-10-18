package com.diegonmarcos.cloudbrowser

import android.graphics.Bitmap
import com.diegonmarcos.superapp.browser.BrowserConfig
import com.diegonmarcos.superapp.browser.IncognitoThumbs
import com.diegonmarcos.superapp.browser.TabThumbConfig
import com.diegonmarcos.superapp.browser.TabThumbnails
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.Executor

/** The tab-snapshot cache: put/get, memory eviction, disk bound, delete on close, the incognito policy, the declared config. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TabThumbnailsTest {

    @get:Rule val tmp = TemporaryFolder()

    private val direct = Executor { it.run() }

    /** 100x100 RGB_565 = 20 000 bytes (~19 KiB) in memory. */
    private fun bmp(): Bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.RGB_565).also { it.eraseColor(0xFF3366CC.toInt()) }

    private fun cache(cfg: TabThumbConfig = TabThumbConfig(memoryKb = 64, diskKb = 1024), dir: File = File(tmp.root, "tabs")) =
        TabThumbnails(dir, cfg, direct, direct)

    private fun files(dir: File = File(tmp.root, "tabs")) = dir.listFiles { f -> f.name.endsWith(TabThumbnails.EXT) }.orEmpty()

    @Test
    fun `put then get returns the picture from memory and writes the disk copy`() {
        val c = cache()
        val b = bmp()
        c.put("t1", b)
        assertTrue("memory hit is the very bitmap", c.cached("t1") === b)
        assertEquals(1, files().size)
        assertNotNull(c.get("t1"))
        assertNull(c.get("missing"))
    }

    @Test
    fun `memory evicts least recently used but the disk copy still answers`() {
        val c = cache(TabThumbConfig(memoryKb = 40, diskKb = 1024))   // room for two 19 KiB bitmaps
        c.put("a", bmp()); c.put("b", bmp())
        c.cached("a")                                                  // a is now fresher than b
        c.put("c", bmp())                                              // evicts b
        assertNotNull(c.cached("a"))
        assertNull("b was evicted from memory", c.cached("b"))
        assertNotNull("b is still on disk", c.get("b"))
        assertNotNull("and is promoted back into memory", c.cached("b"))
    }

    @Test
    fun `the disk cache is bounded and drops the oldest file first`() {
        val dir = File(tmp.root, "tabs")
        val c = cache(TabThumbConfig(memoryKb = 4096, diskKb = 8), dir)
        c.put("old", bmp())
        val oneFile = files(dir).single().length()
        assertTrue(oneFile > 0)
        files(dir).single().setLastModified(1_000L)
        // Fill past the cap with distinct, noisy bitmaps so each file is non-trivial.
        for (i in 0 until 12) {
            val b = Bitmap.createBitmap(200, 200, Bitmap.Config.RGB_565)
            for (x in 0 until 200 step 3) for (y in 0 until 200) b.setPixel(x, y, (x * 7919 + y * 104729 + i * 31) or 0xFF000000.toInt())
            c.put("n$i", b)
        }
        val total = files(dir).sumOf { it.length() }
        assertTrue("disk stays within diskKb ($total bytes)", total <= 8 * 1024)
        assertFalse("the oldest picture went first", File(dir, TabThumbnails.key("old") + TabThumbnails.EXT).exists())
    }

    @Test
    fun `delete on close removes the picture from memory and disk`() {
        val c = cache()
        c.put("t1", bmp()); c.put("t2", bmp())
        c.delete("t1")
        assertNull(c.cached("t1"))
        assertNull("not resurrected from disk", c.get("t1"))
        assertEquals(1, files().size)
        assertNotNull(c.get("t2"))
    }

    @Test
    fun `clear forgets every picture`() {
        val c = cache()
        c.put("t1", bmp()); c.put("t2", bmp())
        c.clear()
        assertNull(c.cached("t1")); assertNull(c.get("t2"))
        assertEquals(0, files().size)
    }

    @Test
    fun `incognito skip stores nothing, incognito memory never touches disk`() {
        val skip = cache(TabThumbConfig(memoryKb = 64, incognito = IncognitoThumbs.SKIP))
        skip.put("p", bmp(), incognito = true)
        assertNull(skip.cached("p")); assertEquals(0, files().size)

        val dir = File(tmp.root, "mem")
        val mem = cache(TabThumbConfig(memoryKb = 64, incognito = IncognitoThumbs.MEMORY), dir)
        mem.put("p", bmp(), incognito = true)
        assertNotNull(mem.cached("p")); assertEquals(0, files(dir).size)
        mem.put("n", bmp(), incognito = false)
        assertEquals("a normal tab is still written", 1, files(dir).size)
    }

    @Test
    fun `load delivers asynchronously through the main executor`() {
        val delivered = ArrayList<Bitmap?>()
        val c = cache()
        c.put("t1", bmp())
        c.load("t1") { delivered += it }
        c.load("nope") { delivered += it }
        assertEquals(2, delivered.size)
        assertNotNull(delivered[0]); assertNull(delivered[1])
    }

    @Test
    fun `scale, cache bounds and incognito policy come from the declared config`() {
        val d = TabThumbConfig()
        assertEquals(0.25f, d.scale, 0f)
        assertEquals(IncognitoThumbs.SKIP, d.incognito)
        val cfg = BrowserConfig.parse("""{"tab_thumbnails":{"scale":0.5,"memory_kb":1000,"disk_kb":2000,"quality":55,"incognito":"memory"}}""").tabThumbs
        assertEquals(0.5f, cfg.scale, 0f); assertEquals(1000, cfg.memoryKb); assertEquals(2000, cfg.diskKb)
        assertEquals(55, cfg.quality); assertEquals(IncognitoThumbs.MEMORY, cfg.incognito)
        assertEquals("absent block = defaults", d, BrowserConfig.parse("""{}""").tabThumbs)
    }

    @Test
    fun `the shipped build json declares the block with the quarter scale and the skip policy`() {
        var d: File? = File("").absoluteFile
        while (d != null && !File(d, "ac_cloud-browser/build.json").isFile && !File(d, "build.json").isFile) d = d.parentFile
        val f = File(d, "ac_cloud-browser/build.json").takeIf { it.isFile } ?: File(d, "build.json")
        val ui = JSONObject(f.readText()).getJSONObject("ui").getJSONObject("browser")
        val cfg = TabThumbConfig.parse(ui.getJSONObject("tab_thumbnails"))
        assertEquals(0.25f, cfg.scale, 0f)
        assertEquals(IncognitoThumbs.SKIP, cfg.incognito)
    }
}
