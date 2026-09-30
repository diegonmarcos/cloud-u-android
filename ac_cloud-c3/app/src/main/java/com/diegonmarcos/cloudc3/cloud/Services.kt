package com.diegonmarcos.cloudc3.cloud

import android.util.Base64
import com.diegonmarcos.cloudc3.BuildConfig
import org.json.JSONArray

/**
 * #648 the declared service estate, MOVED from the SuperApp's Sections.kt rather than
 * reauthored. Same two models, same field names, same `optString` defaults, same
 * sort-by-name, same cache — because this is a spin-off and the pages it feeds are the
 * ones that were already designed and working.
 *
 * Only the SOURCE of the two blobs changed owner: app/build.gradle bakes
 * data/services_public.json and data/services_private.json into this app's BuildConfig,
 * the way the SuperApp's own gradle baked them there. data/regen.sh in the SuperApp still
 * derives both files from cloud-data's consolidated export; this app carries the snapshot
 * it renders, as a copy rather than a symlink, so a stale copy is visible in a diff instead
 * of silently following someone else's regeneration.
 */
object Services {

    /** One container reachable from outside, through the caddy proxy. */
    data class PublicService(
        val name: String,
        val service: String,
        val vm: String,
        val publicUrl: String,
        val auth: String,
        val privateDns: String,
        val category: String,
    )

    /** One container with no public proxy: databases, queues, internal MCPs, side-cars. */
    data class PrivateService(
        val name: String,
        val service: String,
        val vm: String,
        val privateDns: String,
        val protocol: String,
        val category: String,
        val dbEngine: String,
    )

    private var cachedPub: List<PublicService>? = null
    private var cachedPriv: List<PrivateService>? = null

    /** data/services_public.json — containers with a caddy proxy.domain. */
    fun publicServices(): List<PublicService> {
        cachedPub?.let { return it }
        val json = decode(BuildConfig.SERVICES_PUBLIC_B64)
        val arr = JSONArray(json)
        val out = mutableListOf<PublicService>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(
                PublicService(
                    name = o.optString("name", ""),
                    service = o.optString("service", ""),
                    vm = o.optString("vm", ""),
                    publicUrl = o.optString("public_url", ""),
                    auth = o.optString("auth", ""),
                    privateDns = o.optString("private_dns", ""),
                    category = o.optString("category", ""),
                ),
            )
        }
        cachedPub = out.sortedBy { it.name }
        return cachedPub!!
    }

    /** data/services_private.json — no public proxy. */
    fun privateServices(): List<PrivateService> {
        cachedPriv?.let { return it }
        val json = decode(BuildConfig.SERVICES_PRIVATE_B64)
        val arr = JSONArray(json)
        val out = mutableListOf<PrivateService>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(
                PrivateService(
                    name = o.optString("name", ""),
                    service = o.optString("service", ""),
                    vm = o.optString("vm", ""),
                    privateDns = o.optString("private_dns", ""),
                    protocol = o.optString("protocol", "tcp"),
                    category = o.optString("category", ""),
                    dbEngine = o.optString("db_engine", ""),
                ),
            )
        }
        cachedPriv = out.sortedBy { it.name }
        return cachedPriv!!
    }

    /**
     * An EMPTY blob would render an empty table that looks exactly like a working one —
     * the #276 shape — so a blank bake yields an empty JSON array here and the page's own
     * count line then states zero instead of quietly drawing nothing.
     */
    private fun decode(b64: String): String =
        if (b64.isBlank()) "[]"
        else runCatching { String(Base64.decode(b64, Base64.NO_WRAP)) }.getOrDefault("[]")
}
