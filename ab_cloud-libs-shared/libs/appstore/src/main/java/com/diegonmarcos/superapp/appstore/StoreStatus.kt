package com.diegonmarcos.superapp.appstore

import android.content.Context
import com.diegonmarcos.superapp.adbdebug.ControlStatus
import com.diegonmarcos.superapp.adbdebug.EmbeddedAdbChannel
import com.diegonmarcos.superapp.adbdebug.PackageVerifier
import com.diegonmarcos.superapp.adbdebug.ShellChannels
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * The Store bar's two status controls, measured rather than inferred. The rules
 * (mapping, probe timeout, cache) are [ControlStatus]'s and unit-tested there; this
 * is the device glue: a cached live `id` round trip over whichever shell channel is
 * up, always run off the main thread, plus the Play Protect read.
 */
internal object StoreStatus {

    @Volatile private var probe: ControlStatus.Probe? = null
    @Volatile private var ppViaShell: ControlStatus.Tri? = null
    private val running = AtomicBoolean(false)

    private fun probe(ctx: Context): ControlStatus.Probe = probe ?: synchronized(this) {
        probe ?: ControlStatus.Probe(object : ControlStatus.Backend {
            val app = ctx.applicationContext
            override fun paired() = EmbeddedAdbChannel.everPaired(app)
            override fun id(): Pair<String, String?>? =
                ShellChannels.active(app)?.let { it.name() to it.exec(app, "id") }
        }).also { probe = it }
    }

    fun channel(): ControlStatus.ChannelStatus? = probe?.peek()
    fun fresh(): Boolean = probe?.fresh() == true

    /** Cheap provider read; falls back to the last shell-read answer when the provider has none. */
    fun playProtect(ctx: Context): ControlStatus.Tri {
        val t = ControlStatus.playProtect(PackageVerifier.reading(ctx))
        return if (t == ControlStatus.Tri.UNKNOWN) ppViaShell ?: t else t
    }

    fun invalidate() { probe?.invalidate(); ppViaShell = null }

    /** Re-probe off the main thread (single flight), then [done] (call-site posts to the UI). */
    fun refresh(ctx: Context, done: () -> Unit) {
        if (!running.compareAndSet(false, true)) return
        val app = ctx.applicationContext
        thread(name = "store-status-probe") {
            try {
                val ch = probe(app).get(force = true)
                if (ControlStatus.playProtect(PackageVerifier.reading(app)) == ControlStatus.Tri.UNKNOWN &&
                    ch.state == ControlStatus.Channel.UP) {
                    ShellChannels.active(app)?.let {
                        ppViaShell = ControlStatus.playProtect(PackageVerifier.readingViaShell(app, it))
                    }
                }
            } finally { running.set(false) }
            done()
        }
    }
}
