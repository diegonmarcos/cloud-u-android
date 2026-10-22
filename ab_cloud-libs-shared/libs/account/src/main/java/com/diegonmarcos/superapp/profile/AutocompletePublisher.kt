package com.diegonmarcos.superapp.profile

import android.content.Context
import android.os.Bundle
import com.diegonmarcos.superapp.fleetendpoints.FleetEndpointsContract
import com.diegonmarcos.superapp.fleetendpoints.FleetHttp
import com.diegonmarcos.superapp.settings.AccountVault
import org.json.JSONArray
import org.json.JSONObject

/**
 * #1103 Cloud Account is the holder of the owner's private data (the cloud-me-* private repos, served by cloud-me-api)
 * and hands the Autocomplete rows (IDs, Addresses, URLs, Secrets) to Cloud Keyboard's own store over this app's
 * `accountdata` provider: CONSTELLATION_DATA to get in, and the per-package grant [GRANT] (Secrets section) to be given
 * the rows. The keyboard never reaches the network for them and never asks Cloud Vault.
 *
 * The names below are the wire contract Cloud Keyboard reads (libs:autofill AutocompleteContract); libs:account does not
 * compile that library, so ac_cloud-account's AutocompleteSyncTest holds the two equal.
 */
object AutocompletePublisher {
    const val METHOD = "autocomplete"
    const val GRANT = "autocomplete.read"
    const val KEY_OK = "ok"
    const val KEY_JSON = "autocomplete_json"
    val PAGES = listOf("ids", "addresses", "urls", "secrets")
    const val MAX_PER_PAGE = 500

    /** What the provider answers a granted caller: the stored document, `{}` when Account holds nothing yet. */
    fun publish(vault: AccountVault): Bundle = Bundle().apply {
        putBoolean(KEY_OK, true)
        putString(KEY_JSON, vault.autocompleteJson().ifBlank { "{}" })
    }

    /** cloud-me-api's answer cut down to the four pages and `label` / `value` strings. Null when it is not a document. */
    fun normalize(body: String): Pair<String, Int>? {
        val src = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val out = JSONObject().put("v", 1)
        var count = 0
        for (page in PAGES) {
            val a = src.optJSONArray(page) ?: JSONArray()
            val kept = JSONArray()
            for (i in 0 until minOf(a.length(), MAX_PER_PAGE)) {
                val o = a.optJSONObject(i) ?: continue
                val value = o.optString("value")
                if (value.isBlank()) continue
                kept.put(JSONObject().put("label", o.optString("label").trim()).put("value", value)); count++
            }
            out.put(page, kept)
        }
        return out.toString() to count
    }
}

/** #1103 Pulls the Autocomplete document from cloud-me-api (mesh first, over the fleet transport) into Account's own encrypted store. */
object MeAutocompleteSync {
    const val SERVICE = "cloud-me-api"
    const val PATH = "/me/autocomplete"

    sealed interface Outcome {
        data class Synced(val rows: Int) : Outcome
        data object NoService : Outcome
        data class Failed(val why: String) : Outcome
    }

    /** One GET (url, headers), status and body; a test hands in its own. */
    @Volatile var http: (String, Map<String, String>) -> Pair<Int, String> =
        { url, headers -> FleetHttp.call(FleetHttp.shared, "GET", url, headers, null) }

    /**
     * cloud-me-api verifies an Authelia bearer itself, mesh or public (the mesh is not a credential for it), so the request
     * carries the Authelia token Account holds, else the fleet bearer. Null when Account holds neither.
     */
    fun bearer(vault: AccountVault): String? =
        listOf("auth.authelia_token", "fleet.bearer").firstNotNullOfOrNull { vault.connection(it)?.toString()?.takeIf { t -> t.isNotBlank() } }

    fun url(decl: String): String? =
        FleetEndpointsContract.parseDecl(decl).firstOrNull { it.id == SERVICE }?.let { it.meshUrl() + PATH }

    fun sync(ctx: Context, vault: AccountVault = AccountVault(ctx), decl: String = AccountHost.fleetEndpointsDecl()): Outcome {
        val url = url(decl) ?: return Outcome.NoService
        val token = bearer(vault) ?: return Outcome.Failed("no bearer")
        val (code, body) = runCatching { http(url, mapOf("Authorization" to "Bearer $token", "Accept" to "application/json")) }
            .getOrElse { return Outcome.Failed(it.javaClass.simpleName) }
        if (code !in 200..299) return Outcome.Failed("HTTP $code")
        val (doc, count) = AutocompletePublisher.normalize(body) ?: return Outcome.Failed("not a document")
        vault.putAutocomplete(doc)
        return Outcome.Synced(count)
    }
}
