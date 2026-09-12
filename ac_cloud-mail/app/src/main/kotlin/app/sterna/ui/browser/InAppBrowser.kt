package app.sterna.ui.browser

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.util.Locale

/**
 * The one place that decides where a tapped link goes.
 *
 * It used to be one answer — hand the address to whatever app claims it and leave. That is the
 * wrong default for a mail client: reading the message you are half way through is the task, and
 * every link took the user out of it, into a browser with the whole rest of their browsing session
 * in it, with no way back but the system Back stack. [openLink] keeps a web address INSIDE this app
 * (see [MiniBrowserActivity]) and leaves only when leaving is the only thing that makes sense.
 *
 * "Only thing that makes sense" is not a preference, it is what the scheme can do. A WebView cannot
 * dial a number, compose a message or drop a pin on a map, so `tel:`, `sms:`, `mailto:` and `geo:`
 * still go to the system — and `mailto:` in particular comes straight back to this app, which is
 * registered for it. Keeping the decision here rather than at each call site is what stops the two
 * halves drifting: the reader used to own its own copy of the opener.
 */
object InAppBrowser {

    /** Cloud Browser, the fleet's own browser — one of the two hand-off targets in the menu. */
    const val CLOUD_BROWSER_PACKAGE = "com.diegonmarcos.cloudbrowser"

    /** Brave, the other one. Not installed on every device, which is why [openExternally] reports. */
    const val BRAVE_PACKAGE = "com.brave.browser"

    /**
     * Schemes the built-in browser can actually render. Everything else is a capability a WebView
     * does not have, and pretending otherwise would show a blank page where a dialler should open.
     */
    private val IN_APP_SCHEMES = setOf("http", "https")

    /**
     * Open [uri] the way the app's own browser would, falling back to the system for the schemes
     * that are not web pages. Reports whether anything took it, for the same reason
     * [openExternally] does: a caller that cannot tell a hand-off from a dud latches a dead link.
     */
    fun openLink(context: Context, uri: Uri): Boolean =
        if (uri.scheme?.lowercase(Locale.ROOT) in IN_APP_SCHEMES) {
            MiniBrowserActivity.openMiniBrowser(context, uri)
        } else {
            openExternally(context, uri)
        }

    /**
     * Hand [uri] to another app, and say whether anything took it — a device with no browser at all
     * throws, and so does an explicit [packageName] that is not installed. Swallowing that and
     * returning nothing reads the same on screen but not to the caller, which is how a dead link
     * ends up latched or a missing Brave ends up looking like a broken menu entry.
     *
     * [packageName] names ONE app instead of asking the system: that is what "open in Brave" means,
     * as opposed to "open in a browser". Android 11+ needs the package declared in `<queries>` for
     * this to resolve at all — see the manifest.
     */
    fun openExternally(context: Context, uri: Uri, packageName: String? = null): Boolean = try {
        val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (packageName != null) intent.setPackage(packageName)
        context.startActivity(intent)
        true
    } catch (e: Exception) {
        // No app can handle the URL — report it rather than crash.
        false
    }

    /**
     * [url] as seen through Google's translating proxy, into [target] (a language code).
     *
     * The `translate.google.com/translate?u=` form and not the `<host>.translate.goog` one it
     * redirects to: the proxy host is the SAME address with its dots turned into dashes and its
     * dashes doubled, so building it here would put a URL-mangling rule in this app that only
     * Google can keep correct. The redirect costs one hop and cannot go stale.
     *
     * `sl=auto` because the page says what language it is in and the reader does not have to.
     */
    fun translatedUrl(url: String, target: String): String =
        "https://translate.google.com/translate?sl=auto&tl=$target&u=" + Uri.encode(url)
}
