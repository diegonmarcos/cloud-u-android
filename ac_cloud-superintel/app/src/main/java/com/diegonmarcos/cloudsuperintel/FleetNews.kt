package com.diegonmarcos.cloudsuperintel

import android.content.Context
import android.net.Uri
import com.diegonmarcos.superapp.ai.AiHttp
import java.net.URLEncoder

/**
 * The News tab's route to the fleet `news` service, as ac_cloud-account/build.json::fleet_endpoints declares it: the MESH base
 * first (no credential), then the PUBLIC base with the fleet bearer. The bearer is read on use from Cloud Account / SuperApp
 * and never stored, logged or sent to a mesh name; with no sign-in the public route is skipped.
 */
object FleetNews {
    private const val ACCOUNT_AUTHORITY = "com.diegonmarcos.cloudaccount.accountdata"
    private const val SUPERAPP_AUTHORITY = "com.diegonmarcos.superapp.fleetbearer"

    /** Tests replace the provider reads. */
    @Volatile var reader: (Context) -> String = { read(it) }

    /** The (base, headers) routes to try in order. */
    fun routes(mesh: String, public: String, bearer: String?): List<Pair<String, Map<String, String>>> =
        listOfNotNull(
            mesh.takeIf { it.isNotBlank() }?.let { it to emptyMap<String, String>() },
            public.takeIf { it.isNotBlank() && !bearer.isNullOrBlank() }?.let { it to mapOf("Authorization" to "Bearer $bearer") },
        )

    /** The first route that answers 2xx; otherwise the last answer, or the throw of the last route when none answered. */
    fun get(ctx: Context, cfg: NewsConfig, query: String, call: (Pair<String, Map<String, String>>) -> AiHttp.Response): AiHttp.Response {
        val routes = routes(BuildConfig.NEWS_MESH_URL, BuildConfig.NEWS_PUBLIC_URL, reader(ctx).takeIf { it.isNotBlank() })
        return first(routes) { base, headers -> call(News.url(base, cfg, query) { URLEncoder.encode(it, "UTF-8") } to headers) }
    }

    fun first(routes: List<Pair<String, Map<String, String>>>, call: (String, Map<String, String>) -> AiHttp.Response): AiHttp.Response {
        var last: AiHttp.Response? = null
        var err: Throwable? = null
        for ((base, headers) in routes) {
            try {
                val r = call(base, headers)
                if (r.code in 200..299) return r
                last = r
            } catch (e: Exception) { err = e }
        }
        return last ?: throw (err ?: IllegalStateException("news has no reachable route (no mesh address, and the public route needs the fleet sign-in of Cloud Account)"))
    }

    private fun read(ctx: Context): String {
        val fromAccount = runCatching {
            ctx.contentResolver.call(Uri.parse("content://$ACCOUNT_AUTHORITY"), "secret", "fleet.bearer", null)
        }.getOrNull()?.takeIf { it.getBoolean("ok") }?.getString("value").orEmpty().trim()
        if (fromAccount.isNotEmpty()) return fromAccount
        return runCatching {
            ctx.contentResolver.call(Uri.parse("content://$SUPERAPP_AUTHORITY"), "bearer", null, null)
        }.getOrNull()?.takeIf { it.getBoolean("ok") }?.getString("token").orEmpty().trim()
    }
}
