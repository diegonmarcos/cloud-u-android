package app.sterna.core.data.mail

/** Whether an account's departure is proven — the only state in which a row that is the sole copy
 *  of a message may be destroyed. `accounts()` answers an unreadable blob with an empty list, so
 *  [accountStillListed] alone would read a storage failure as "every account left at once". */
fun accountDepartureIsProven(accountsUnreadable: Boolean, accountStillListed: Boolean): Boolean =
    !accountsUnreadable && !accountStillListed
