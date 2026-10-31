package com.diegonmarcos.cloudlib.web

import android.webkit.WebSettings

/**
 * The declared usages of an embedded web view (#1371). Every web view of the fleet is built for exactly one of these
 * by [CloudWeb.create]; 1_cicd/src/data/webview-guard.json maps each app's usage to its profile and the guard fails on
 * a usage without one. One entry per line, `NAME(` at four spaces: the guard reads the names from this file.
 *
 * - UNTRUSTED_HTML: mail bodies and other foreign markup. No JS, no storage, no file or content access, remote
 *   images blocked until the host lifts the gate ([CloudWeb.remoteImages]), never mixed content, pinch zoom. Our own
 *   editor or preview over foreign markup may run JS sandboxed ([CloudWeb.sandboxedJs]): still no storage or files.
 * - APP_PAGE: a trusted fleet page (bundled asset or our own origin) with JS and storage, mixed content only in
 *   compatibility mode, no third-party cookies. A bundled file:///android_asset page may read its sibling assets
 *   (file access from file URLs); universal access is never granted. New pages prefer CloudWeb.assetUrl (https).
 * - WEB_WORLD: full 3D/GPU content (three.js, WebGL2, WebGPU): media plays without a gesture, the page owns its
 *   gestures, transparent background, renderer priority kept even when hidden.
 * - BROWSER: full browsing: zoom, third-party cookies, multiple windows (sign-in popups), geolocation. File access
 *   only per site for a local archive ([CloudWeb.site]).
 */
enum class WebProfile(
    val javaScript: Boolean,
    val storage: Boolean,
    val remoteImages: Boolean,
    val mixedContent: Int,
    val autoplay: Boolean,
    val zoom: Boolean,
    val thirdPartyCookies: Boolean,
    val fileAccess: Boolean,
) {
    UNTRUSTED_HTML(false, false, false, WebSettings.MIXED_CONTENT_NEVER_ALLOW, false, true, false, false),
    APP_PAGE(true, true, true, WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE, false, false, false, true),
    WEB_WORLD(true, true, true, WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE, true, false, true, false),
    BROWSER(true, true, true, WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE, false, true, true, false),
}
