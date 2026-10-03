package com.diegonmarcos.superapp.browser

import android.os.Handler
import android.os.Looper

/**
 * #802 how a write that did not come from the screen (a debug route, the fleet
 * import) reaches the live browser. The stores are the truth and the fragment
 * re-reads them; this only tells it WHEN. One listener — there is one browser screen.
 * ponytail: a single listener, not a flow; add fan-out if a second screen needs it.
 */
object BrowserBus {
    const val SETTINGS = "settings"
    const val TABS = "tabs"
    const val OPEN = "open:"

    @Volatile var listener: ((String) -> Unit)? = null

    fun post(change: String) {
        val l = listener ?: return
        Handler(Looper.getMainLooper()).post { l(change) }
    }
}
