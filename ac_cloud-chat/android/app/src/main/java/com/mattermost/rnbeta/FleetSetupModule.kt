package com.mattermost.rnbeta

import android.content.Context
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.module.annotations.ReactModule

/**
 * Cloud fleet: the server link Cloud Account's Fleet Setup pushed into this app (fleet-config.json store
 * `chat_setup`, keys `server_url` / `server_name`, written through the `<package>.fleetsetup` provider libs:core
 * serves). The JS side (app/utils/fleet_setup.ts) reads it once and the server screen uses it the way it
 * uses a managed (EMM) config's serverUrl / serverName. Read-only: nothing here writes, and no token lives
 * here (sign-in stays interactive, the session token stays in the Keychain).
 */
@ReactModule(name = FleetSetupModule.NAME)
class FleetSetupModule(ctx: ReactApplicationContext) : ReactContextBaseJavaModule(ctx) {
    override fun getName(): String = NAME

    @ReactMethod
    fun getConfig(promise: Promise) {
        try {
            val p = reactApplicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE)
            val map = Arguments.createMap()
            p.getString(KEY_URL, null)?.trim()?.takeIf { it.isNotEmpty() }?.let { map.putString("serverUrl", it) }
            p.getString(KEY_NAME, null)?.trim()?.takeIf { it.isNotEmpty() }?.let { map.putString("serverName", it) }
            promise.resolve(map)
        } catch (e: Exception) {
            promise.resolve(Arguments.createMap())
        }
    }

    companion object {
        const val NAME = "CloudFleetSetup"
        /** fleet-config.json stores.chat_setup: the one prefs file Fleet Setup writes for this app. */
        const val STORE = "chat_setup"
        const val KEY_URL = "server_url"
        const val KEY_NAME = "server_name"
    }
}
