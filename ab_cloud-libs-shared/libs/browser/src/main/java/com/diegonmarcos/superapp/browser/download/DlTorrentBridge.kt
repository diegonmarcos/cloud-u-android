package com.diegonmarcos.superapp.browser.download

import android.content.Context
import android.content.pm.PackageManager
import java.net.URLDecoder

/** One file inside a torrent, for the per-file list and the file picker. */
data class DlTorrentFile(val index: Int, val path: String, val size: Long, val progress: Float, val selected: Boolean)

/**
 * The seam between libs:browser's download manager and a BitTorrent engine. libs:browser ships
 * no native code (it is compiled into cloud-superapp and cloud-vault too), so the engine lives in
 * the app that wants it: cloud-browser names its implementation in its manifest
 * (`<meta-data android:name="com.diegonmarcos.superapp.browser.TORRENT_BRIDGE" android:value="<class>"/>`)
 * and [load] instantiates it with the application Context. No meta-data = no torrents, and the
 * browser hands magnet links to other apps as before.
 *
 * The engine runs inside the same process as [DlQueue] and therefore under [DlService], the one
 * download foreground service; it reports back through [DlQueue.torrentUpdate].
 */
interface DlTorrentBridge {
    fun add(job: DlJob, torrentBytes: ByteArray?)
    fun pause(id: String)
    fun resume(id: String)
    fun remove(id: String, deleteFiles: Boolean)
    /** Stop transfers but keep the session's resume data (service stopping, network not allowed). */
    fun suspend()
    /** True while a finished torrent is still seeding (keeps the service up). */
    fun seeding(): Boolean
    fun applyPolicy(p: DlPolicy)
    fun files(id: String): List<DlTorrentFile>
    fun setFiles(id: String, selected: Set<Int>)
    fun setSequential(id: String, on: Boolean)
    fun setSeed(id: String, on: Boolean)
    /** Shut the session down after writing every torrent's resume data. */
    fun shutdown()

    companion object {
        const val META = "com.diegonmarcos.superapp.browser.TORRENT_BRIDGE"

        fun load(ctx: Context): DlTorrentBridge? = runCatching {
            val ai = ctx.packageManager.getApplicationInfo(ctx.packageName, PackageManager.GET_META_DATA)
            val cls = ai.metaData?.getString(META) ?: return null
            Class.forName(cls).getConstructor(Context::class.java).newInstance(ctx.applicationContext) as DlTorrentBridge
        }.getOrNull()

        fun displayName(magnet: String?): String? = magnet?.let { DlMagnet.parse(it)?.name }
    }
}

/** A parsed magnet link: the info-hash (hex, lower case), its display name and trackers. */
data class DlMagnet(val infoHash: String, val name: String?, val trackers: List<String>) {
    companion object {
        fun isMagnet(url: String?) = url?.trim()?.startsWith("magnet:?", ignoreCase = true) == true

        /** Does this download look like a .torrent file (URL, MIME type or Content-Disposition name)? */
        fun isTorrentFile(url: String?, mime: String?, disposition: String?): Boolean =
            mime?.lowercase()?.startsWith("application/x-bittorrent") == true ||
                url?.substringBefore('?')?.substringBefore('#')?.endsWith(".torrent", ignoreCase = true) == true ||
                DlFormat.dispositionName(disposition)?.endsWith(".torrent", ignoreCase = true) == true

        /** null when [uri] is not a magnet with a BitTorrent v1 (btih) or v2 (btmh) topic. */
        fun parse(uri: String): DlMagnet? {
            if (!isMagnet(uri)) return null
            val params = uri.trim().substringAfter('?').split('&').mapNotNull {
                val k = it.substringBefore('=', "")
                if (k.isEmpty()) null else k.lowercase() to runCatching { URLDecoder.decode(it.substringAfter('='), "UTF-8") }.getOrDefault(it.substringAfter('='))
            }
            val xt = params.filter { it.first == "xt" || it.first.matches(Regex("""xt\.\d+""")) }.map { it.second }
            val hash = xt.firstNotNullOfOrNull { t ->
                when {
                    t.startsWith("urn:btih:", true) -> btih(t.substring(9))
                    t.startsWith("urn:btmh:", true) -> t.substring(9).lowercase().takeIf { it.matches(Regex("[0-9a-f]{68}")) }
                    else -> null
                }
            } ?: return null
            return DlMagnet(hash, params.firstOrNull { it.first == "dn" }?.second?.takeIf { it.isNotBlank() },
                params.filter { it.first == "tr" || it.first.matches(Regex("""tr\.\d+""")) }.map { it.second })
        }

        /** 40 hex digits as they are, or 32 base32 characters decoded to hex. */
        private fun btih(v: String): String? = when {
            v.matches(Regex("[0-9a-fA-F]{40}")) -> v.lowercase()
            v.matches(Regex("[A-Za-z2-7]{32}")) -> base32ToHex(v.uppercase())
            else -> null
        }

        private fun base32ToHex(s: String): String {
            val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
            var bits = 0; var value = 0
            val out = StringBuilder()
            for (c in s) {
                value = (value shl 5) or alphabet.indexOf(c); bits += 5
                if (bits >= 8) { out.append("%02x".format((value shr (bits - 8)) and 0xff)); bits -= 8 }
            }
            return out.toString()
        }
    }
}
