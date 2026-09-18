package com.diegonmarcos.superapp.floatingnav

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import androidx.core.app.NotificationCompat
import com.diegonmarcos.superapp.notificationcenter.BadgeCustomization
import com.diegonmarcos.superapp.notificationcenter.BadgeDeclaration
import com.diegonmarcos.superapp.notificationcenter.BadgeServices
import com.diegonmarcos.superapp.notificationcenter.PhoneNotificationListenerService
import com.diegonmarcos.superapp.R

/**
 * "Cloud SuperApp - Notification Center Media Session" — a rich media-control
 * notification that mirrors + drives whatever is actually playing on the
 * device. It reads the active MediaSession via MediaSessionManager (needs the
 * app's NotificationListener access) and forwards Prev / Play-Pause / Next to
 * that session's transport controls. Album art + title/artist are the real
 * metadata; MediaStyle is linked to the live session token for the full
 * media-player rendering. When nothing is playing, the notification is removed.
 */
class MediaProxy(private val ctx: Context) {

    private var lastKey: String? = null

    private fun nm() = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    /** Whether the app's NotificationListener is enabled — REQUIRED for
     *  getActiveSessions to read the playing media (a separate grant from the
     *  POST_NOTIFICATIONS permission the other notifications use). */
    fun listenerEnabled(): Boolean =
        androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)

    /** The active controller — the playing one if any, else the first. Null
     *  without notification-listener access or when nothing is playing. */
    fun activeController(): MediaController? {
        if (!listenerEnabled()) return null
        return runCatching {
            val msm = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val comp = ComponentName(ctx, PhoneNotificationListenerService::class.java)
            val list = msm.getActiveSessions(comp)
            list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list.firstOrNull()
        }.getOrNull()
    }

    /** Forward a `media:*` action to the active session's transport. */
    fun transport(target: String) {
        val c = activeController() ?: return
        when (target) {
            "media:playpause" ->
                if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause()
                else c.transportControls.play()
            "media:next" -> c.transportControls.skipToNext()
            "media:prev" -> c.transportControls.skipToPrevious()
        }
        lastKey = null // force a re-render on the next refresh
    }

    /** The `media_now_playing` entry of build.json::ui.notification_center —
     *  this badge's own declaration, which is where its persistence now comes
     *  from instead of from the playback state. */
    private val decl: BadgeDeclaration.Badge?
        get() = BadgeServices.declared.firstOrNull { it.id == BADGE_ID }

    /** #515: persistence is a DECLARED property of the badge, not an
     *  expression over the transport. It used to be `setOngoing(playing)`,
     *  which meant the badge was ongoing only while audio was actually
     *  playing and evaporated the moment it stopped — so "our media player
     *  notification center" disappeared every time a track ended, which is
     *  not what a persistent badge does. */
    private fun persistent(): Boolean =
        decl?.let { BadgeCustomization.isPersistent(ctx, it) } ?: false

    private fun showWhenIdle(): Boolean =
        decl?.let { BadgeCustomization.bool(ctx, it, "show_when_idle") } ?: false

    /** (Re)post the media notification for the current session, or cancel it. */
    fun refresh() {
        val c = activeController()
        if (c == null) {
            // A persistent badge holds its place with an idle state rather
            // than vanishing — vanishing is indistinguishable from broken,
            // which is exactly how this one got reported as missing.
            if (persistent() && showWhenIdle()) postIdle() else { nm().cancel(NOTIF_MEDIA); lastKey = null }
            return
        }
        ensureChannel()
        val md = c.metadata
        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() } ?: "Now playing"
        val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: ""
        val playing = c.playbackState?.state == PlaybackState.STATE_PLAYING
        val key = "$title|$artist|$playing"
        if (key == lastKey) return // no change → don't re-post (avoid flicker)
        lastKey = key
        val art = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)

        val b = NotificationCompat.Builder(ctx, MEDIA_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setColor(0xFF0A0A0A.toInt())
            .setContentTitle(title)
            .setContentText(artist)
            .setSubText("Cloud SA - Media")
            .setOnlyAlertOnce(true)
            // #515: declared, not derived from `playing`. See [persistent].
            .setOngoing(persistent() || playing)
            .setGroup("nc_media") // own group → not auto-bundled with the others
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        if (art != null) b.setLargeIcon(art)
        b.addAction(android.R.drawable.ic_media_previous, "Prev", pi("media:prev"))
        b.addAction(
            if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
            if (playing) "Pause" else "Play", pi("media:playpause"),
        )
        b.addAction(android.R.drawable.ic_media_next, "Next", pi("media:next"))
        // NOTE: deliberately NOT calling setMediaSession(token). Linking our
        // notification to the playing app's session token makes Android MERGE
        // it into that app's existing media card (so our distinct "NC Media"
        // entry never appears). Without it, this is a standalone media-styled
        // notification in the list, which is what we want here.
        b.setStyle(
            androidx.media.app.NotificationCompat.MediaStyle().setShowActionsInCompactView(0, 1, 2),
        )
        runCatching { nm().notify(NOTIF_MEDIA, b.build()) }
    }

    /**
     * The badge with nothing to mirror. Same channel, same id, same subtext —
     * so the shade entry stays put across a track ending rather than being
     * torn down and rebuilt — but it says plainly that nothing is playing
     * instead of showing a stale last track as if it were live.
     */
    private fun postIdle() {
        ensureChannel()
        val key = "idle"
        if (key == lastKey) return
        lastKey = key
        val b = NotificationCompat.Builder(ctx, MEDIA_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setColor(0xFF0A0A0A.toInt())
            .setContentTitle(ctx.getString(R.string.badge_media_idle_title))
            .setContentText(ctx.getString(R.string.badge_media_idle_text))
            .setSubText("Cloud SA - Media")
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setGroup("nc_media")
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        // Play is the only transport that means anything with no session; the
        // other two would be buttons that silently do nothing.
        b.addAction(android.R.drawable.ic_media_play, "Play", pi("media:playpause"))
        runCatching { nm().notify(NOTIF_MEDIA, b.build()) }
    }

    fun cancel() { runCatching { nm().cancel(NOTIF_MEDIA) }; lastKey = null }

    /** Media transport keeps the shade OPEN (unlike the Main quick actions), so
     *  it's a plain service PendingIntent, not the close-shade trampoline. */
    private fun pi(target: String): PendingIntent = PendingIntent.getService(
        ctx, target.hashCode(),
        Intent(ctx, FloatingNavService::class.java)
            .setAction(FloatingNavService.ACTION_NAV).putExtra(FloatingNavService.EXTRA_TARGET, target),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (nm().getNotificationChannel(MEDIA_CHANNEL_ID) == null) {
                nm().createNotificationChannel(NotificationChannel(
                    MEDIA_CHANNEL_ID, "Media controls", NotificationManager.IMPORTANCE_LOW,
                ).apply { description = "Cloud SuperApp media controls for the active session."; setShowBadge(false) })
            }
        }
    }

    companion object {
        const val NOTIF_MEDIA = 0xF3
        private const val MEDIA_CHANNEL_ID = "floating_nav_media"

        /** This badge's id in build.json::ui.notification_center.producers.
         *  The declaration is the contract; this is the key into it. */
        const val BADGE_ID = "media_now_playing"
    }
}
