package app.affine.pro.plugin

import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin

// #543: THE OTHER HALF OF THE DE-CLOUDING.
//
// 045953dcb ("de-cloud the Android shell") deleted the native plugins that
// talked to the AFFiNE cloud — AuthPlugin, HashCashPlugin, AIButtonPlugin — and
// left the WEB side that calls them exactly as upstream shipped it:
// packages/frontend/apps/android/src/plugins/{auth,hashcash,ai-button} still
// call registerPlugin('Auth'|'HashCash'|'AIButton'). A Capacitor bridge call
// with no registered implementation does not degrade; it THROWS
// `"Auth" plugin is not implemented on android`.
//
// For Auth that throw was fatal on the one flow this build exists for. proxy.ts
// hands the Auth plugin to installAuthRequestProxy(), which replaces
// globalThis.fetch — and authEndpointForUrl() treats the app's OWN origin
// (https://localhost) as an auth endpoint, so EVERY same-origin fetch asked the
// missing plugin for a token first. Creating a purely LOCAL workspace therefore
// died inside getValidAccessToken, which is the trace Diego sent.
//
// So the plugins are registered again, as what this build actually is: a
// local-first note editor with NO server. getValidAccessToken answers the truth
// — there is no session, token = null — and the fetch proxy then sends the
// request with no Authorization header, which is exactly right for localhost
// and works in airplane mode. Nothing here opens a socket, so registering them
// cannot re-introduce the cloud dependency the de-clouding removed.
//
// The invariant, enforced by test/test-capacitor-plugin-surface.sh: every
// Capacitor plugin name the web bundle registers must have a native class in
// MainActivity's registerPlugins list. Deleting a plugin's native side without
// deleting its caller is what produced this ticket.

/** There is no AFFiNE server in this build, so there is never a session. */
@CapacitorPlugin(name = "Auth")
class LocalAuthPlugin : Plugin() {

    @PluginMethod
    fun getValidAccessToken(call: PluginCall) {
        // JSObject.put(String, null) is ambiguous in Kotlin; the web side reads
        // `token?: string | null` and an absent key is already `undefined`.
        call.resolve(JSObject())
    }

    @PluginMethod
    fun refreshAccessToken(call: PluginCall) = noServer(call)

    @PluginMethod
    fun signInMagicLink(call: PluginCall) = noServer(call)

    @PluginMethod
    fun signInOauth(call: PluginCall) = noServer(call)

    @PluginMethod
    fun signInPassword(call: PluginCall) = noServer(call)

    @PluginMethod
    fun signInOpenApp(call: PluginCall) = noServer(call)

    /** Nothing is stored, so clearing always succeeds. */
    @PluginMethod
    fun signOut(call: PluginCall) = call.resolve(JSObject().put("ok", true))

    @PluginMethod
    fun clearEndpointSession(call: PluginCall) = call.resolve(JSObject().put("ok", true))

    // AUTH_SESSION_EMPTY is the code upstream's web layer already understands
    // as "no credential here", so the editor reports it instead of an
    // "internal error" with a stack trace in it.
    private fun noServer(call: PluginCall) =
        call.reject("Cloud Notes is local-only and has no sign-in server", "AUTH_SESSION_EMPTY")
}

/** The captcha proof-of-work only ever guarded cloud sign-in. */
@CapacitorPlugin(name = "HashCash")
class LocalHashCashPlugin : Plugin() {

    @PluginMethod
    fun hash(call: PluginCall) =
        call.reject("Cloud Notes is local-only and issues no captcha challenge", "AUTH_SESSION_EMPTY")
}

/** The AI button drove cloud copilot; there is no copilot to present. */
@CapacitorPlugin(name = "AIButton")
class LocalAIButtonPlugin : Plugin() {

    @PluginMethod
    fun present(call: PluginCall) = call.resolve(JSObject())

    @PluginMethod
    fun dismiss(call: PluginCall) = call.resolve(JSObject())
}
