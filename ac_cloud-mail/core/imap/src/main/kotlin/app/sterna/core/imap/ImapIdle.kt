package app.sterna.core.imap

import java.io.Closeable

/**
 * Holds an IMAP connection in IDLE on a mailbox to push new-mail notifications.
 */
class ImapIdleConnection(
    private val client: ImapClient,
    private val config: MailServerConfig,
    private val mailbox: String,
    private val onChanged: () -> Unit,
    private val onClosed: () -> Unit,
) : Closeable {
    @Volatile private var running = true
    private var session: ImapSession? = null
    private val thread = Thread({ run() }, "imap-idle").apply { isDaemon = true; start() }

    private fun run() {
        try {
            val s = client.openSession(config)
            session = s
            s.select(mailbox)
            while (running) {
                val changed = s.idle(IDLE_REFRESH_MS)
                if (!running) break
                if (changed) onChanged()
            }
        } catch (_: Throwable) {
            // Connection error or closed mid-IDLE; handled by onClosed below.
        } finally {
            runCatching { session?.close() }
            if (running) onClosed() // dropped unexpectedly — let the owner reconnect
        }
    }

    override fun close() {
        running = false
        runCatching { session?.close() } // closing the socket unblocks the IDLE read
    }

    private companion object {
        /** Refresh IDLE well within the RFC 2177 ~29-minute server idle limit. */
        const val IDLE_REFRESH_MS = 28 * 60 * 1000
    }
}
