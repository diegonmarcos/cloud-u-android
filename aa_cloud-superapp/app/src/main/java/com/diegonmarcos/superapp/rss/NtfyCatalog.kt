package com.diegonmarcos.superapp.rss

import android.util.Base64
import com.diegonmarcos.superapp.BuildConfig
import org.json.JSONObject

/**
 * Human names for ntfy topics, and the address of the advisory channel.
 *
 * ## Why a label table exists at all
 * A snake_case topic is an ADDRESS, not a name. `sec_yara` says nothing about
 * what arrives there, and a screen listing twenty-six of them is an inventory
 * nobody reads — which is the same as not publishing it. The fleet's own
 * registry (`cloud-infra/a_solutions/infra-obs_ntfy/src/build.json::topics`)
 * has carried a human `title` per topic all along; this mirrors it so the app
 * can show the sentence and keep the address as the subtitle.
 *
 * ## Missing labels degrade, they do not hide
 * [labelOf] returns the raw topic when nothing is declared. Same rule as
 * [NtfyScopes]'s last-scope fallback and for the same reason: an unlabelled
 * channel is merely less readable, an omitted one has stopped being watched
 * and nobody finds out. Adding a channel stays a data edit in
 * `build.json::ui.ntfy` (FIRE RULE #6), never a code change.
 */
object NtfyCatalog {

    @Volatile private var cachedLabels: Map<String, String>? = null

    private fun config(): JSONObject = runCatching {
        JSONObject(String(Base64.decode(BuildConfig.UI_NTFY_B64, Base64.NO_WRAP)))
    }.getOrDefault(JSONObject())

    private fun labels(): Map<String, String> {
        cachedLabels?.let { return it }
        val o = config().optJSONObject("labels") ?: JSONObject()
        val out = HashMap<String, String>(o.length())
        for (k in o.keys()) out[k] = o.optString(k)
        cachedLabels = out
        return out
    }

    /** Human title for [topic]; the topic itself when none is declared. */
    fun labelOf(topic: String): String = labels()[topic]?.takeIf { it.isNotBlank() } ?: topic

    @Volatile private var cachedTaxon: Map<String, String>? = null

    private fun taxons(): Map<String, String> {
        cachedTaxon?.let { return it }
        val o = config().optJSONObject("taxon") ?: JSONObject()
        val out = HashMap<String, String>(o.length())
        for (k in o.keys()) out[k] = o.optString(k)
        cachedTaxon = out
        return out
    }

    /**
     * The phone-taxonomy SECTION PREFIX this cloud publisher belongs to —
     * the same one-character vocabulary `ui.phone_sections` declares, so the
     * Notify tabs can narrow a cloud stream by the categories they were
     * built from ("_" Sys, "@" Inboxes & AI, "." Data Apps, …).
     *
     * Cloud messages carry no package and therefore no classifier can be run
     * on them: a topic or an in-app producer name is all there is. Without
     * this map every cloud publisher passed unfiltered and the SuperApp
     * updater showed up on all six tabs — an obviously system-level stream
     * appearing under Inboxes and under Data Apps.
     *
     * Blank when the key is not declared, and a blank prefix is treated as
     * "All tab only": a publisher nobody has categorised should not silently
     * claim a category.
     */
    fun taxonOf(key: String): String = taxons()[key].orEmpty()

    /**
     * The origin every programmatic ntfy READ must use: the service itself on
     * the WireGuard mesh, ahead of the public edge's authorization gate.
     *
     * ## Being on the mesh is not being authorized
     * `rss.diegonmarcos.com` and this address are the SAME ntfy container
     * (`10.0.0.6:8090`), reached two different ways. The public hostname is
     * `wg_only` — so the mesh is what makes it REACHABLE — and behind that it
     * is still gated by Caddy's three-tier rule
     * (`cloud-u-containers/infra-sec_caddy/src/caddyfile.nix::mkNtfyBlock`):
     * an `Authorization: Bearer eyJ…` JWT, an `Authorization: Bearer tk_…`
     * ntfy token, or an Authelia session cookie. A poll carrying none of the
     * three falls to the cookie tier and Authelia refuses it. MEASURED from a
     * mesh peer, credentials stripped:
     *
     *     GET https://rss.diegonmarcos.com/fleet_advisory/json?poll=1  -> 401
     *     GET http://10.0.0.6:8090/fleet_advisory/json?poll=1          -> 200
     *
     * Reachability and authorization are different questions and the mesh only
     * answers the first one. The 401 was the gate working, not the network
     * failing.
     *
     * ## Why the ungated path rather than a token
     * The gate is CORRECT and stays. ntfy's own access control already grants
     * what a read needs — `auth-default-access: read-write` in
     * `cloud-infra/a_solutions/infra-obs_ntfy/src/templates/server.yml.tpl` —
     * and the fleet already treats wg0 as the auth boundary for reading this
     * service: the `/feed*` RSS routes on the same host are declared
     * `feed_auth: "none"` for exactly that reason. So a read has an authorized
     * path already; it was knocking on the wrong door. Minting a bearer for it
     * would add a credential that can expire, on a screen whose whole job is
     * to be trustworthy when other things are broken.
     *
     * Browser links stay on the public hostname — a WebView carries the
     * Authelia session cookie and satisfies the third tier, which is the tier
     * that exists for humans.
     *
     * Declared in `build.json::ui.ntfy.base_url` when present so the origin can
     * move without a code change (FIRE RULE #6); the fallback is the address
     * [com.diegonmarcos.superapp.recovery.AdvisoryFeed] measured and has been
     * polling successfully all along. That sibling deliberately keeps its own
     * copy of this constant rather than calling here — it is the escape hatch
     * for a device too stale to update, and a lifeline with one dependency is
     * a lifeline with one fewer way to fail.
     *
     * Cleartext to `10.0.0.6` is permitted by `res/xml/network_security_config
     * .xml`, which enumerates the mesh peers.
     */
    fun readBaseUrl(): String =
        config().optString("base_url").ifBlank { "http://10.0.0.6:8090" }.trimEnd('/')

    /**
     * The topic carrying out-of-band install/repair advisories.
     *
     * Declared rather than hardcoded because it is the one channel a stranded
     * device is told to look at, and the app that would have to be updated to
     * learn a new address is precisely the app that cannot update. Keeping it
     * in `ui.ntfy` at least means the NEXT build can be pointed elsewhere
     * without touching Kotlin.
     */
    fun advisoryTopic(): String =
        config().optString("advisory_topic").ifBlank { "cloud-sa-notifications" }
}
