package com.diegonmarcos.cloudbrowser

import com.diegonmarcos.superapp.browser.download.DlCodec
import com.diegonmarcos.superapp.browser.download.DlJob
import com.diegonmarcos.superapp.browser.download.DlMagnet
import com.diegonmarcos.superapp.browser.download.DlPolicy
import com.diegonmarcos.superapp.browser.download.DlSeedRule
import com.diegonmarcos.superapp.browser.download.DlState
import com.diegonmarcos.superapp.browser.download.DlTorrentBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Magnet parsing and the interception rules: which links and downloads become torrents (a magnet:
 * in shouldOverrideUrlLoading, a .torrent / application/x-bittorrent in the DownloadListener), plus
 * the seeding limits and the torrent fields' persistence.
 */
class TorrentMagnetTest {
    // The webtorrent test vector "Sintel" (Blender Foundation, CC-BY), the classic legal test magnet.
    private val sintel = "magnet:?xt=urn:btih:08ada5a7a6183aae1e09d831df6748d566095a10&dn=Sintel" +
        "&tr=udp%3A%2F%2Fexplodie.org%3A6969&tr=wss%3A%2F%2Ftracker.webtorrent.dev"

    @Test fun `a hex magnet parses to its info-hash, name and trackers`() {
        val m = DlMagnet.parse(sintel)!!
        assertEquals("08ada5a7a6183aae1e09d831df6748d566095a10", m.infoHash)
        assertEquals("Sintel", m.name)
        assertEquals(listOf("udp://explodie.org:6969", "wss://tracker.webtorrent.dev"), m.trackers)
        assertEquals("Sintel", DlTorrentBridge.displayName(sintel))
    }

    @Test fun `a base32 info-hash is decoded to the same hex`() {
        // 08ada5a7... in RFC 4648 base32
        val b32 = "BCW2LJ5GDA5K4HQJ3AY56Z2I2VTASWQQ"
        assertEquals("08ada5a7a6183aae1e09d831df6748d566095a10", DlMagnet.parse("magnet:?xt=urn:btih:$b32")!!.infoHash)
    }

    @Test fun `a BitTorrent v2 magnet is accepted, anything else is not a torrent`() {
        val v2 = "magnet:?xt=urn:btmh:1220" + "ab".repeat(32)
        assertEquals("1220" + "ab".repeat(32), DlMagnet.parse(v2)!!.infoHash)
        assertNull(DlMagnet.parse("magnet:?xt=urn:sha1:ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"))
        assertNull(DlMagnet.parse("magnet:?dn=no-topic"))
        assertNull(DlMagnet.parse("https://example.org/?xt=urn:btih:08ada5a7a6183aae1e09d831df6748d566095a10"))
        assertNull(DlMagnet.parse("magnet:?xt=urn:btih:nothex"))
    }

    @Test fun `the WebView intercept recognises magnets and torrent downloads only`() {
        assertTrue(DlMagnet.isMagnet(sintel))
        assertTrue(DlMagnet.isMagnet("  MAGNET:?xt=urn:btih:08ada5a7a6183aae1e09d831df6748d566095a10"))
        assertFalse(DlMagnet.isMagnet("https://magnet.example/"))
        assertFalse(DlMagnet.isMagnet(null))
        assertTrue(DlMagnet.isTorrentFile("https://x.org/debian-13.iso.torrent", null, null))
        assertTrue(DlMagnet.isTorrentFile("https://x.org/f.TORRENT?sig=1#a", null, null))
        assertTrue(DlMagnet.isTorrentFile("https://x.org/download?id=7", "application/x-bittorrent", null))
        assertTrue(DlMagnet.isTorrentFile("https://x.org/download?id=7", null, "attachment; filename=\"ubuntu.torrent\""))
        assertFalse(DlMagnet.isTorrentFile("https://x.org/debian-13.iso", "application/octet-stream", null))
        assertFalse(DlMagnet.isTorrentFile("https://x.org/torrent-guide.pdf", "application/pdf", null))
    }

    @Test fun `seeding stops at the ratio or the time cap, never when both are 0`() {
        val gb = 1L shl 30
        assertTrue(DlSeedRule.reached(DlPolicy(seedRatio = 100), uploaded = gb, size = gb, seedingSec = 0))
        assertFalse(DlSeedRule.reached(DlPolicy(seedRatio = 150), uploaded = gb, size = gb, seedingSec = 0))
        assertTrue(DlSeedRule.reached(DlPolicy(seedMinutes = 30), uploaded = 0, size = gb, seedingSec = 1800))
        assertFalse(DlSeedRule.reached(DlPolicy(), uploaded = 10 * gb, size = gb, seedingSec = 999_999))
    }

    @Test fun `torrent rows and the policy survive a restart`() {
        val j = DlJob(id = "a", url = sintel, fileName = "Sintel", kind = DlJob.KIND_TORRENT, file = File("/x/a/torrent").path,
            infoHash = "08ada5a7a6183aae1e09d831df6748d566095a10", selectedFiles = listOf(0, 2), sequential = true, seed = true,
            state = DlState.RUNNING, total = 10, downloaded = 4)
        val back = DlCodec.fromJson(DlCodec.toJson(listOf(j))).single()
        assertEquals(j, back)
        val p = DlPolicy(parallel = 2, wifiOnly = true, allowMetered = false, speedLimitKbps = 500, segments = 6,
            sharedStore = true, seed = true, seedRatio = 200, seedMinutes = 90, uploadKbps = 64)
        assertEquals(p, DlPolicy.fromJson(p.toJson().toString()))
        assertEquals(DlPolicy(), DlPolicy.fromJson("garbage"))
    }
}
