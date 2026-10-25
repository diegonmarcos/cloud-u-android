package com.diegonmarcos.superapp.fleetendpoints

/**
 * The per-service settings Cloud Account's Setup page shows, derived ONLY from the fleet declaration (build.json
 * fleet_endpoints, parsed by [FleetEndpointsContract.parseDecl]); no URL is typed anywhere else. Pure and secret-free: a
 * row holds URLs, a mode and the PATH of the credential in the account vault, never a value.
 *
 * Mode: a service with no public URL, or `mesh_only`, is PRIVATE (the WireGuard mesh name); `public_only` is the public
 * URL with the service's own [FleetEndpointsContract.Service.auth] (bearer, mTLS, SSO); `mesh_first` is PRIVATE with
 * that public URL as the fallback.
 */
object FleetServiceSettings {
    enum class Mode(val label: String) {
        PRIVATE("Private (mesh)"), PUBLIC_BEARER("Public + bearer"), PUBLIC_MTLS("Public + mTLS"), PUBLIC_SSO("Public + SSO")
    }

    data class Row(
        val id: String, val kind: String, val mode: Mode,
        /** The URL requests go to first. */
        val url: String,
        /** The public URL used when the mesh is down ("" when none or when [mode] is already public). */
        val fallbackUrl: String,
        val aliases: List<String>,
        val auth: String,
        /** Where the credential lives in the account vault (`section.key`), or null when Cloud Vault holds it (mTLS certificate). */
        val credentialPath: String?,
        val health: String,
    ) {
        /** The credential word for the page: what the credential is, never its value. */
        val credentialLabel: String get() = when (auth) {
            FleetEndpointsContract.AUTH_MTLS -> "client certificate (held by Cloud Vault)"
            FleetEndpointsContract.AUTH_SSO -> "Authelia SSO token"
            else -> "fleet bearer"
        }
        val needsCredential: Boolean get() = mode != Mode.PRIVATE || fallbackUrl.isNotEmpty()
    }

    const val BEARER_PATH = FleetEndpointsContract.GRANT_BEARER
    const val SSO_PATH = "auth.authelia_token"

    fun publicMode(auth: String): Mode = when (auth) {
        FleetEndpointsContract.AUTH_MTLS -> Mode.PUBLIC_MTLS
        FleetEndpointsContract.AUTH_SSO -> Mode.PUBLIC_SSO
        else -> Mode.PUBLIC_BEARER
    }

    fun credentialPath(auth: String): String? = when (auth) {
        FleetEndpointsContract.AUTH_MTLS -> null
        FleetEndpointsContract.AUTH_SSO -> SSO_PATH
        else -> BEARER_PATH
    }

    fun row(s: FleetEndpointsContract.Service): Row {
        val publicFirst = s.hasPublic && s.policy == FleetEndpointsContract.POLICY_PUBLIC_ONLY
        val mode = if (publicFirst) publicMode(s.auth) else Mode.PRIVATE
        return Row(s.id, s.kind, mode, if (publicFirst) s.publicUrl() else s.meshUrl(),
            if (!publicFirst && s.hasPublic && s.policy == FleetEndpointsContract.POLICY_MESH_FIRST) s.publicUrl() else "",
            s.aliases.map { "https://$it" }, s.auth, credentialPath(s.auth), s.health)
    }

    fun rows(services: List<FleetEndpointsContract.Service>): List<Row> = services.map(::row)

    /** One line for the result of a Test: words and a status code only. */
    fun testLine(r: Row, meshUp: Boolean?, publicStatus: Int?): String {
        val mesh = when (meshUp) { true -> "mesh up"; false -> "mesh down"; null -> "" }
        val pub = when {
            r.mode == Mode.PRIVATE && r.fallbackUrl.isEmpty() -> ""
            r.auth == FleetEndpointsContract.AUTH_MTLS -> "public needs the client certificate (checked by Cloud Vault)"
            publicStatus != null -> "public HTTP $publicStatus"
            else -> "public unreachable"
        }
        return listOf(mesh, pub).filter { it.isNotEmpty() }.joinToString(" · ").ifEmpty { "not checked" }
    }

    /** Passed when the first route answers, or a fallback exists and answers. mTLS public is never probed without the certificate. */
    fun testOk(r: Row, meshUp: Boolean?, publicStatus: Int?): Boolean {
        val publicOk = publicStatus != null && r.auth != FleetEndpointsContract.AUTH_MTLS
        return when (r.mode) {
            Mode.PRIVATE -> meshUp == true || (r.fallbackUrl.isNotEmpty() && publicOk)
            else -> publicOk
        }
    }
}
