package app.sterna.push

/**
 * A transport Sterna serves with a connection of its own, held open by [PushService].
 */
internal val Transport.isDirect: Boolean
    get() = this == Transport.EVENT_SOURCE || this == Transport.IMAP_IDLE

/** One watched account reduced to what the restart decision needs (issue #98). */
internal data class AccountPushState(
    val notificationsEnabled: Boolean,
    val transport: Transport,
)

/**
 * The decision behind [BootReceiver], kept apart from Android so it can be tested:
 */
internal object BootRestart {

    /**
     * True only when at least one watched account still wants notifications *and* is
     */
    fun needsPushService(accounts: List<AccountPushState>): Boolean =
        accounts.any { it.notificationsEnabled && it.transport.isDirect }
}
