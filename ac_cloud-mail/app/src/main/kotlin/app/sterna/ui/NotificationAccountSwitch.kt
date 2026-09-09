package app.sterna.ui

/**
 * Which account should become the current one when a new-mail notification is opened.
 */
object NotificationAccountSwitch {

    /**
     * The account to make current, or null to leave the current account alone.
     */
    fun resolve(
        notificationAccountId: String?,
        currentAccountId: String,
        knownAccountIds: Collection<String>,
        unifiedView: Boolean,
    ): String? {
        val target = notificationAccountId?.takeIf { it.isNotBlank() } ?: return null
        if (target == currentAccountId) return null
        if (target !in knownAccountIds) return null
        if (unifiedView) return null
        return target
    }
}
