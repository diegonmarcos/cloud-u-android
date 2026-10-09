package com.diegonmarcos.superapp.browser

import android.webkit.CookieManager
import android.webkit.WebView

/**
 * The android half of [PrivateProfile]: binding a WebView to a named androidx.webkit profile, and the
 * cookie manager of that profile. A profile name comes from [PrivateProfile.nameFor] (null = default).
 * Anything tab-scoped (downloads, offline saves, third-party-cookie switches) goes through here, never
 * through the process-wide default manager, which a private tab's cookies are not in.
 */
object TabProfile {
    /** MUST run before [wv] loads anything. A null [name] leaves the default profile. */
    fun bind(wv: WebView, name: String?) {
        name ?: return
        runCatching {
            androidx.webkit.ProfileStore.getInstance().getOrCreateProfile(name)
            androidx.webkit.WebViewCompat.setProfile(wv, name)
        }
    }

    /** The cookie manager a request for profile [name] must read and write. */
    fun cookies(name: String?): CookieManager =
        PrivateProfile.jarFor(name,
            { n -> androidx.webkit.ProfileStore.getInstance().getOrCreateProfile(n).cookieManager },
            { CookieManager.getInstance() })
}
