package app.sterna.core.data.text

/**
 * GENERATED from `core/data/auth-patterns.json`, the vendored copy of the fleet's declared pattern set for
 * rule "G0 _ Auth" (source of truth: cloud-u-containers `_shared/mail-auth-patterns.json`, which the mail
 * server's G0 _ AUTH rules are derived from too). Do not edit by hand: change the phrase in the source,
 * re-vendor the JSON, and run `python3 ac_cloud-mail/test/gen-auth-patterns.py` (the tester
 * test-mail-auth-patterns.sh and AuthPatternsParityTest fail when this file is stale).
 */
internal object AuthPatterns {
    val LINK_PHRASES: List<String> = listOf(
        "verify your email",
        "verify your account",
        "confirm your email",
        "confirm your account",
        "reset your password",
        "password reset",
        "forgot your password",
        "magic link",
        "sign-in link",
        "sign in to",
        "log in to",
        "activate your account",
        "finish signing up",
    )

    val URL_TOKENS: List<String> = listOf(
        "verif",
        "confirm",
        "magic",
        "signin",
        "sign-in",
        "login",
        "log-in",
        "reset",
        "activat",
        "passwordless",
        "one-time",
        "otp",
        "auth/",
        "/auth",
        "recover",
    )

    val CODE_SUBJECT_PHRASES: List<String> = listOf(
        "verification code",
        "security code",
        "one-time code",
        "one-time password",
        "login code",
        "sign-in code",
        "authentication code",
        "confirmation code",
        "your code",
        "passcode",
    )
}
