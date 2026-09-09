package com.diegonmarcos.superapp.texttools

/**
 * Where the Text tools live, and what a caller is allowed to assume about them.
 *
 * [SERVICE_PKG] is a constellation ADDRESS, not configuration — the same kind of
 * constant as `FleetToken.AUTHORITY_PKG` and the translate client's companion
 * package. It is not read from a build.json on purpose: a library shared by
 * reference into several apps reads the CONSUMING app's build.json, so a field
 * baked that way would resolve to mail's own package when mail builds it, which is
 * the exact bug the keyboard's version_code once had. The keyboard is the one app
 * that holds the routing settings and the provider key, and that is a fact about
 * the fleet rather than about whoever is calling.
 */
object TextTools {

    /** The app that OWNS the engines, the settings and the credential. */
    const val SERVICE_PKG = "com.diegonmarcos.cloudkeyboard"

    /** The intent action [ITextTools] is published under. */
    const val ACTION = "com.diegonmarcos.superapp.texttools.ITextTools"

    /** Signature-level, so the platform refuses any caller not signed with the Cloud key. */
    const val PERMISSION = "com.diegonmarcos.cloud.permission.CONSTELLATION_DATA"

    /**
     * Shown when the keyboard is not there to answer. Names the fix, because the
     * fix is an install and not a retry — the translate bar learned that the hard
     * way and its wording is deliberately echoed here.
     */
    const val NOT_INSTALLED =
        "Text tools need the Cloud Keyboard app — it holds the AI routing settings and the provider key"

    /** Slot 0 of every [ITextTools] reply: the result. */
    const val SLOT_TEXT = 0

    /** Slot 1 of every [ITextTools] reply: why there is no result. */
    const val SLOT_REASON = 1

    /**
     * The empty [styleId] argument to `enhance`: use the style, tone, length and
     * output language the user pinned in Text Enhancements, rather than one named style.
     */
    const val STYLE_CONFIGURED = ""

    /** The empty target-tag argument to `translate`: use the configured default target. */
    const val TARGET_CONFIGURED = ""

    /** One call's outcome. [text] non-null = it worked; otherwise [error] says why, always readable. */
    class Result(val text: String?, val error: String?) {
        val ok: Boolean get() = text != null

        companion object {
            /** Read the wire's `{text, reason}` without trusting its shape — it crossed a process. */
            fun of(reply: Array<String>?): Result {
                val text = reply?.getOrNull(SLOT_TEXT)?.takeIf { it.isNotEmpty() }
                val reason = reply?.getOrNull(SLOT_REASON)?.takeIf { it.isNotEmpty() }
                return when {
                    text != null -> Result(text, null)
                    reason != null -> Result(null, reason)
                    // Neither slot filled: the far side answered, but said nothing. That is
                    // not success, and reporting it as an empty rewrite would wipe the
                    // user's text with the model's silence.
                    else -> Result(null, "Text tools returned an empty reply")
                }
            }

            fun failed(reason: String) = Result(null, reason)
        }
    }
}
