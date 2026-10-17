package com.diegonmarcos.cloudbrowser.torrent

import android.content.Context
import android.content.Intent
import com.diegonmarcos.superapp.browser.download.DlMagnet
import com.diegonmarcos.superapp.browser.download.DlQueue
import com.diegonmarcos.superapp.browser.download.DlService

/**
 * A magnet: link or a .torrent file another app hands cloud-browser (the manifest's VIEW filters):
 * it goes straight into the download queue, and the intent is turned into "open the Downloads page".
 */
object TorrentIntents {
    /** True when [intent] carried a torrent (now queued); the caller must not load it as a page. */
    fun consume(ctx: Context, intent: Intent?): Boolean {
        val data = intent?.data ?: return false
        if (intent.action != Intent.ACTION_VIEW) return false
        val q = DlQueue.get(ctx)
        if (q.torrent == null) return false
        val s = data.toString()
        val added = when {
            DlMagnet.isMagnet(s) -> q.addTorrent(s, null, "") != null
            data.scheme == "content" || data.scheme == "file" -> {
                if (!DlMagnet.isTorrentFile(data.lastPathSegment, intent.type ?: ctx.contentResolver.getType(data), null) &&
                    intent.type != "application/octet-stream") return false
                val bytes = runCatching {
                    ctx.contentResolver.openInputStream(data)?.use { it.readBytes().takeIf { b -> b.size <= DlQueue.MAX_TORRENT_FILE } }
                }.getOrNull() ?: return false
                q.addTorrent(null, bytes, "") != null
            }
            else -> false
        }
        if (added) intent.putExtra(DlService.EXTRA_OPEN_DOWNLOADS, true)
        return added
    }
}
