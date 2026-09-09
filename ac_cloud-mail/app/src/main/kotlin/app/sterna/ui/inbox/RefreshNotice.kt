package app.sterna.ui.inbox

/** What the mail list has to tell the reader about a refresh that did not land. */
internal enum class RefreshNotice {
    /** Nothing to say. */
    NONE,

    /** The DEVICE has no usable network: the calm offline banner, and the offline empty state
     *  that promises a resync (#65 — this is the reported case, with a reporter behind it). */
    OFFLINE,

    /** The refresh failed while the device is online: say so, and show WHICH failure. */
    ERROR,
}

/** Which of the three a refresh landed on. Not `offline || error != null`: being offline is a
 *  claim about the device, and a rejected password or a missing folder is no network outage — the
 *  banner would say "You're offline" about a phone plainly online. [offline] wins on both (#65). */
internal fun refreshNotice(offline: Boolean, error: String?): RefreshNotice = when {
    offline -> RefreshNotice.OFFLINE
    error != null -> RefreshNotice.ERROR
    else -> RefreshNotice.NONE
}
