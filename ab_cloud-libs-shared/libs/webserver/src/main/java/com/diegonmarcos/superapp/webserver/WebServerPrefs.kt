package com.diegonmarcos.superapp.webserver

import android.content.Context
import java.io.File

/**
 * Persisted configuration for the static web server.
 *
 * Plain SharedPreferences, not the encrypted flavour: nothing here is a
 * secret. The document root is a path the user chose and the port is a
 * number anyone can read off the status screen — encrypting them would only
 * add a failure mode.
 *
 * The default root is the app's own external files directory, which needs no
 * storage permission and survives an uninstall no worse than the app itself.
 */
class WebServerPrefs(context: Context) {
    private val app = context.applicationContext
    private val sp = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Master switch. Default OFF — a listening socket is never something an
     *  app should open on the user's behalf without being asked. */
    var enabled: Boolean
        get() = sp.getBoolean(K_ENABLED, false)
        set(value) { sp.edit().putBoolean(K_ENABLED, value).apply() }

    var port: Int
        get() = sp.getInt(K_PORT, DEFAULT_PORT)
        set(value) { sp.edit().putInt(K_PORT, value.coerceIn(MIN_PORT, MAX_PORT)).apply() }

    /** False publishes the server on every interface, which on this device
     *  means the Wi-Fi LAN and the WireGuard mesh. Default true. */
    var loopbackOnly: Boolean
        get() = sp.getBoolean(K_LOOPBACK, true)
        set(value) { sp.edit().putBoolean(K_LOOPBACK, value).apply() }

    var directoryListing: Boolean
        get() = sp.getBoolean(K_LISTING, true)
        set(value) { sp.edit().putBoolean(K_LISTING, value).apply() }

    var docRootPath: String
        get() = sp.getString(K_DOC_ROOT, null)?.takeIf { it.isNotBlank() } ?: defaultDocRoot().absolutePath
        set(value) { sp.edit().putString(K_DOC_ROOT, value).apply() }

    fun defaultDocRoot(): File =
        File(app.getExternalFilesDir(null) ?: app.filesDir, "www")

    fun toConfig(): WebServer.Config = WebServer.Config(
        port = port,
        docRoot = File(docRootPath),
        loopbackOnly = loopbackOnly,
        directoryListing = directoryListing,
    )

    companion object {
        const val DEFAULT_PORT = 8088
        private const val MIN_PORT = 1024
        private const val MAX_PORT = 65535
        private const val PREFS = "cloud_webserver"
        private const val K_ENABLED = "enabled"
        private const val K_PORT = "port"
        private const val K_LOOPBACK = "loopback_only"
        private const val K_LISTING = "directory_listing"
        private const val K_DOC_ROOT = "doc_root"
    }
}
