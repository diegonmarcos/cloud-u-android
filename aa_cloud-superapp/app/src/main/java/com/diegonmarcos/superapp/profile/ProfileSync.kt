package com.diegonmarcos.superapp.profile

import android.content.Context
import android.util.Log
import com.diegonmarcos.superapp.BuildConfig
import com.diegonmarcos.superapp.core.ProfileSyncClient
import org.json.JSONObject

/**
 * Binds [ProfilePrefs] to [ProfileSyncClient]: builds the wire document,
 * decides when it is worth sending, and owns the ordering of an erasure.
 *
 * WHY THE PROFILE SYNCS AT ALL: when the constellation update chain broke,
 * the fleet had thousands of installs and no way to tell any of them. Email is
 * the channel that still works when the app cannot update itself, so this is
 * recovery infrastructure — which is why the mandatory fields are the two you
 * need to contact a human, and why delivery is queued rather than best-effort.
 *
 * Lives in app/ (not libs/core) because it knows the profile SCHEMA, which is
 * this app's business. The transport, retry and queue underneath it are
 * schema-agnostic and stay in the R-free library.
 */
object ProfileSync {

    private const val TAG = "ProfileSync"

    /** Wire schema version, so the server can tell an old client's document
     *  from a new one without guessing from which keys happen to be present. */
    private const val SCHEMA = 1

    /** Configured endpoint, or blank when this build has no profile sync
     *  wired — in which case every entry point below is a no-op rather than
     *  an error. A build without an endpoint is a valid build. */
    private val endpoint: String get() = BuildConfig.UI_PROFILE_SYNC_URL

    /**
     * Mirror the current profile to the server.
     *
     * GATED ON COMPLETENESS, on purpose. An incomplete profile is not synced:
     * a record with no name and no email cannot serve the one purpose the
     * server-side copy has, and uploading blanks would overwrite a good record
     * that an earlier install of the same person already published. The user
     * is never blocked by this — the app stays fully usable and the profile
     * screen says plainly that sync is waiting on those two fields.
     */
    fun push(context: Context, prefs: ProfilePrefs = ProfilePrefs(context)) {
        if (endpoint.isBlank()) return
        if (!prefs.isComplete) {
            // Field NAMES only — never the values.
            Log.i(TAG, "not syncing: profile incomplete (name/email required)")
            return
        }
        ProfileSyncClient.enqueue(
            context = context,
            endpoint = endpoint,
            installId = prefs.installId,
            secret = prefs.installSecret,
            payload = document(prefs),
        )
    }

    /**
     * Retry a queued document. Safe and cheap to call on every app start and
     * whenever the profile screen resumes — those are the two moments when
     * connectivity has plausibly come back since the last failure.
     */
    fun flush(context: Context) {
        if (endpoint.isBlank()) return
        val prefs = ProfilePrefs(context)
        ProfileSyncClient.flush(context, endpoint, prefs.installId, prefs.installSecret)
    }

    /** True when an edit is still waiting to reach the server. The profile
     *  screen surfaces this so a stuck sync is visible instead of silent. */
    fun isPending(context: Context): Boolean = ProfileSyncClient.isPending(context)

    /**
     * Restore from the server's copy. Blocking — call from a background
     * dispatcher. Returns true when something was actually applied.
     *
     * Only fills fields that are EMPTY locally: a restore must never clobber
     * something the person just typed on this device, and after a reinstall
     * everything is empty anyway, which is the case this exists for.
     */
    fun restore(context: Context): Boolean {
        if (endpoint.isBlank()) return false
        val prefs = ProfilePrefs(context)
        val doc = ProfileSyncClient.fetch(endpoint, prefs.installId, prefs.installSecret) ?: return false
        val profile = doc.optJSONObject("profile") ?: return false
        var applied = false
        fun fill(key: String, current: String, set: (String) -> Unit) {
            val incoming = profile.optString(key).trim()
            if (current.isBlank() && incoming.isNotBlank()) { set(incoming); applied = true }
        }
        fill("name", prefs.name) { prefs.name = it }
        fill("email", prefs.email) { prefs.email = it }
        fill("phone", prefs.phone) { prefs.phone = it }
        fill("birth", prefs.birth) { prefs.birth = it }
        fill("location", prefs.location) { prefs.location = it }
        fill("company", prefs.company) { prefs.company = it }
        fill("website", prefs.website) { prefs.website = it }
        fill("titles", prefs.titles) { prefs.titles = it }
        // `city_from` / `social_media_links` may still arrive from a server
        // record written by an older client. They no longer exist on this
        // device, so they are ignored rather than resurrected into a store the
        // user has no way to view or clear.
        Log.i(TAG, "restore applied=$applied")
        return applied
    }

    /**
     * GDPR erasure. Order matters and is the whole reason this is one method:
     * the remote DELETE must go out WHILE the install credential is still on
     * disk, because that credential is the only proof we may erase that
     * record. Wiping locally first would leave an orphaned server-side record
     * that nobody — including the user — can ever delete again.
     */
    fun forgetMe(context: Context, onDone: (String) -> Unit) {
        val prefs = ProfilePrefs(context)
        if (endpoint.isBlank()) {
            prefs.clearPersonalData()
            onDone("Profile erased from this device. This build has no server copy.")
            return
        }
        ProfileSyncClient.delete(
            context = context,
            endpoint = endpoint,
            installId = prefs.installId,
            secret = prefs.installSecret,
        ) { result ->
            // Local wipe happens either way — the user asked to be forgotten on
            // this device and a server that is merely unreachable must not
            // block that. A failed remote delete is REPORTED, never swallowed,
            // so it can be chased rather than silently assumed done.
            prefs.clearPersonalData()
            onDone(
                when (result) {
                    is ProfileSyncClient.Result.Delivered ->
                        "Profile erased from this device and from the server."
                    is ProfileSyncClient.Result.Rejected ->
                        "Erased on this device. The server refused the delete (HTTP ${result.code}) — report this."
                    is ProfileSyncClient.Result.Retry ->
                        "Erased on this device. The server could not be reached (${result.reason}); " +
                            "the server copy may still exist — report this."
                }
            )
        }
    }

    /**
     * The wire document.
     *
     * Everything personal sits under `profile`; identity and provenance sit at
     * the top level. The install id is in the BODY here (it is also the
     * `X-Install-Id` header the transport authenticates with) so a stored
     * record is self-describing without its request context.
     *
     * Note what is NOT here: no picture/banner paths (local file paths that
     * mean nothing off-device), no device fingerprint, no location fix, no
     * account tokens. The document is a contact card, and keeping it to that
     * is what makes "what do you store about me" answerable in one screen.
     *
     * CREDENTIALS CAN NEVER GET IN, and that holds by construction twice over.
     * First, the `profile` object is built by NAMING each key — there is no
     * sweep of [ProfilePrefs] or of the SharedPreferences map, so adding a
     * field to any store does not add it here. Second, [enforceAllowlist]
     * re-checks the finished object against [ALLOWED_PROFILE_KEYS] and drops
     * anything else, so if a later refactor DOES replace the enumeration with
     * a sweep, the sweep's extra keys are removed instead of uploaded. The
     * Authelia bearer (settings.ConfigsPrefs) and the WireGuard private key
     * (network.WireGuardPrefs) live in other stores this file never reads, and
     * their key names are not in the allowlist.
     */
    private fun document(prefs: ProfilePrefs): JSONObject = JSONObject().apply {
        put("schema", SCHEMA)
        put("install_id", prefs.installId)
        put("app_version_code", BuildConfig.VERSION_CODE)
        put("app_version_name", BuildConfig.VERSION_NAME)
        put("updated_at", java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            .format(java.util.Date()))
        put("profile", enforceAllowlist(JSONObject().apply {
            put("name", prefs.name.trim())
            put("email", prefs.email.trim())
            put("phone", prefs.phone.trim())
            put("birth", prefs.birth.trim())
            put("location", prefs.location.trim())
            put("company", prefs.company.trim())
            put("website", prefs.website.trim())
            put("titles", prefs.titles.trim())
        }))
    }

    /**
     * Strip anything not in [ALLOWED_PROFILE_KEYS] from the outgoing `profile`
     * object.
     *
     * A no-op today — the builder above already emits exactly this set. It is
     * here for the edit that has not happened yet: the day someone "simplifies"
     * [document] into a loop over the preference map, this is what stops a
     * bearer token or a private key riding along. It drops rather than throws,
     * because refusing to sync a contact card is not a proportionate response
     * to an unexpected key, and it logs the key NAME only, never the value.
     */
    private fun enforceAllowlist(profile: JSONObject): JSONObject {
        val extra = profile.keys().asSequence().filterNot { it in ALLOWED_PROFILE_KEYS }.toList()
        if (extra.isNotEmpty()) {
            Log.e(TAG, "dropping non-allowlisted profile keys before upload: ${extra.joinToString(", ")}")
            extra.forEach { profile.remove(it) }
        }
        return profile
    }

    /**
     * Every key the profile document may carry. Adding one here is a
     * deliberate act with a matching change to
     * `docs/profile-sync-contract.md`; anything absent is dropped by
     * [enforceAllowlist] before the POST.
     *
     * NEVER add `authelia_token`, `bearer`, `private_key`, `if_privkey`, or any
     * other credential name to this set. This document is stored server-side as
     * a plain JSON file; a mesh private key or a live session bearer placed in
     * it is on the wire and at rest on a host, which is the precise thing
     * holding those credentials on-device exists to avoid.
     */
    private val ALLOWED_PROFILE_KEYS = setOf(
        "name", "email", "phone", "birth",
        "location", "company", "website", "titles",
    )
}
