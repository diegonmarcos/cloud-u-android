package app.sterna.core.data.mail

/** What an [AccountGoneException] may claim in a log line: `AccountStore.accounts()` answers an
 *  unreadable blob with an empty list, just as it answers "no account is configured", so only the
 *  caller, which holds the store, can tell a storage failure from a sign-out.
 *  @param passKind the caller's own word for what was running ("pass", "walk"). */
fun accountGoneCause(accountId: String, accountsUnreadable: Boolean, passKind: String): String =
    if (accountsUnreadable) {
        "the stored account list could not be read, so $accountId was not found in it (not a sign-out)"
    } else {
        "$accountId signed out mid-$passKind"
    }
