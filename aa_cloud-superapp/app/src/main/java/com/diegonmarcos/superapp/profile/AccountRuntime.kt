package com.diegonmarcos.superapp.profile

import android.content.Context
import android.os.SystemClock
import com.diegonmarcos.superapp.mail.JmapPrefs
import com.diegonmarcos.superapp.network.WgState
import com.diegonmarcos.superapp.settings.ConfigsPrefs
import com.diegonmarcos.superapp.texttools.TextToolsClient
import org.json.JSONArray
import org.json.JSONObject

/**
 * #778 Configs ▸ Account ▸ RUNTIME — what each fleet app is actually using right now, read live
 * from the app that holds it, per app the cockpit declaration names (build.json
 * ui.vault_connect.cockpit.sections: the vault sections it consumes, `apply`, `runtime`).
 *
 * Every value is reported at the vault path it is declared under, so the snapshot is R — the same
 * pattern as the server and local files, and [AccountDrift] compares the three field by field.
 *
 * HOW EACH APP IS REACHED: this app's own stores for what cloud-sa holds (mail's JMAP account, the
 * WireGuard tunnel, drive's credentials, the contact card, the installed apps); the ITextTools
 * binder for AI — binding starts the serving app if it is stopped, and the read waits for it under
 * a deadline; a package that exposes nothing (the keyboard's lists) is NOT_REPORTING, said plainly.
 * Reading never writes; [push] is the only writer, one declared value into one app.
 */
object AccountRuntime {

    enum class Status { REACHABLE, NOT_INSTALLED, NOT_REPORTING }

    /**
     * One app's reading. [values]: every path it observed → its value, null where it holds nothing.
     * [readOnly]: observed paths it cannot write back. [summary]: a reading that is not fields (apps).
     */
    data class AppRead(
        val id: String, val label: String, val status: Status, val detail: String,
        val values: Map<String, Any?>, val readOnly: Set<String> = emptySet(), val summary: String = "",
    )

    // ── R: the snapshot (pure) ───────────────────────────────────────────

    /** The R body — every observed value at its vault path — and its `apps` block (status, observed, read-only). */
    fun snapshot(reads: List<AppRead>): Pair<JSONObject, JSONObject> {
        val body = JSONObject()
        val apps = JSONObject()
        for (r in reads) {
            r.values.forEach { (p, v) -> if (v != null) AccountDrift.put(body, p, v) }
            apps.put(r.id, JSONObject()
                .put("label", r.label)
                .put("status", r.status.name.lowercase())
                .put("detail", r.detail)
                .put("summary", r.summary)
                .put("observed", JSONArray(r.values.keys.sorted()))
                .put("read_only", JSONArray(r.readOnly.sorted())))
        }
        return body to apps
    }

    /** Every path R's `apps` block says was observed. */
    fun observed(apps: JSONObject?): Set<String> = paths(apps, "observed")

    /** Every observed path whose app cannot take it back. */
    fun readOnly(apps: JSONObject?): Set<String> = paths(apps, "read_only")

    private fun paths(apps: JSONObject?, key: String): Set<String> {
        val out = sortedSetOf<String>()
        apps?.keys()?.forEach { id ->
            val a = apps.optJSONObject(id)?.optJSONArray(key) ?: return@forEach
            (0 until a.length()).forEach { out += a.optString(it) }
        }
        return out
    }

    // ── per-app path rules (pure) ────────────────────────────────────────

    /** `about`: the contact card, one path per declared field (device field → vault key). */
    fun aboutPaths(fields: Map<String, String>): Map<String, String> =
        fields.entries.associate { (field, key) -> "about${AccountDrift.SEP}profile${AccountDrift.SEP}$key" to field }

    /** `drive`: vault git item path → (ConfigsPrefs section, key). */
    fun drivePaths(): Map<String, Pair<String, String>> =
        VaultCockpit.DRIVE_SECRETS.mapKeys { (item, _) -> "git${AccountDrift.SEP}$item" }

    /** `ai`: vault token path → the device provider it feeds (cockpit ai_tokens). */
    fun aiPaths(tokens: Map<String, String>): Map<String, String> =
        tokens.mapKeys { (item, _) -> "ai${AccountDrift.SEP}tokens${AccountDrift.SEP}$item" }

    /** `mesh`: a profile name as [VaultCockpit.meshProfiles] gives it → its vault path (`peers` ones are `id/name`). */
    fun meshPath(name: String): String =
        if ('/' in name) "peers${AccountDrift.SEP}${name.substringBefore('/')}${AccountDrift.SEP}profiles${AccountDrift.SEP}${name.substringAfter('/')}"
        else "mesh${AccountDrift.SEP}profiles${AccountDrift.SEP}$name"

    /**
     * `mail`: the declared account the device's mail is about — the one whose `name` is the local part
     * of [held] (the address JMAP holds), else of [owner] (who the device belongs to) — as its two
     * paths: the account's name and its password. Null when neither matches a declared account.
     */
    fun mailPaths(declared: JSONObject?, held: String, owner: String): Pair<String, String>? {
        val accounts = declared?.optJSONObject("mail")?.optJSONObject("accounts") ?: return null
        fun keyFor(email: String): String? {
            val local = email.substringBefore('@').trim()
            if (local.isBlank()) return null
            return accounts.keys().asSequence().firstOrNull { accounts.optJSONObject(it)?.optString("name") == local }
        }
        val key = keyFor(held) ?: keyFor(owner) ?: return null
        val passEnv = accounts.getJSONObject(key).optString("pass_env")
        val s = AccountDrift.SEP
        return "mail${s}accounts${s}$key${s}name" to "mail${s}passwords${s}$passEnv"
    }

    // ── reading (Android) ────────────────────────────────────────────────

    /** One binder client per process: constructing it binds, which is what wakes a stopped serving app. */
    @Volatile private var tools: TextToolsClient? = null
    private fun tools(ctx: Context) = tools ?: synchronized(this) { tools ?: TextToolsClient(ctx.applicationContext).also { tools = it } }

    /**
     * Every declared app's reading, in declared order. [declared] (L, else S) gives the shape a reading
     * is filed under — which mail account, which device's mesh profiles. BLOCKS (binder): call on IO.
     */
    fun read(ctx: Context, declared: JSONObject?, deadlineMs: Long): List<AppRead> =
        VaultCockpit.layout.sections.map { section ->
            runCatching { readOne(ctx, section, declared, deadlineMs) }.getOrElse {
                AppRead(section.id, section.label, Status.NOT_REPORTING, it.message ?: it.javaClass.simpleName, emptyMap())
            }
        }

    private fun installed(ctx: Context, pkg: String) = runCatching { ctx.packageManager.getPackageInfo(pkg, 0) }.isSuccess

    private fun device(ctx: Context, declared: JSONObject?): VaultCockpit.Device? =
        declared?.let { VaultCockpit.devices(it) }?.firstOrNull { it.id == VaultCockpit.selectedDevice(ctx) }

    private fun readOne(ctx: Context, section: VaultCockpit.Section, declared: JSONObject?, deadlineMs: Long): AppRead {
        val rt = section.runtime
        val base = AppRead(section.id, section.label, Status.REACHABLE, "", emptyMap())
        if (rt.servedBy != VaultCockpit.SELF && rt.servedBy != VaultCockpit.TEXT_TOOLS && !installed(ctx, rt.servedBy))
            return base.copy(status = Status.NOT_INSTALLED, detail = rt.servedBy)
        if (!rt.reports) return base.copy(status = Status.NOT_REPORTING, detail = rt.servedBy)
        return when (section.apply) {
            "about" -> {
                val p = ProfilePrefs(ctx)
                base.copy(values = aboutPaths(section.fields).mapValues { (_, f) -> profileField(p, f)?.trim()?.ifBlank { null } })
            }
            "drive" -> {
                val prefs = ConfigsPrefs(ctx)
                base.copy(values = drivePaths().mapValues { (_, at) -> prefs.secret(at.first, at.second).ifBlank { null } })
            }
            "mail" -> {
                val jmap = JmapPrefs(ctx)
                val owner = ConfigsPrefs(ctx).autheliaEmail.ifBlank { ProfilePrefs(ctx).email.trim() }
                    .ifBlank { declared?.let { VaultCockpit.ownerEmail(it, VaultCockpit.layout) }.orEmpty() }
                val (namePath, pwPath) = mailPaths(declared, jmap.email, owner)
                    ?: return base.copy(detail = jmap.email.ifBlank { "no account held" })
                base.copy(detail = jmap.email.ifBlank { "no account held" }, values = mapOf(
                    namePath to jmap.email.substringBefore('@').trim().ifBlank { null },
                    pwPath to jmap.password.ifBlank { null },
                ))
            }
            "mesh" -> {
                val d = device(ctx, declared) ?: return base.copy(detail = "no device picked on Connect")
                val wg = WgState.prefs(ctx)
                val tunnel = VaultCockpit.tunnelState(wg)
                val rows = VaultCockpit.meshRows(declared!!, d, tunnel).associateBy { it.label }
                val values = VaultCockpit.meshProfiles(declared, d).entries.associate { (name, conf) ->
                    // The tunnel IS the declared profile when address and peers agree; otherwise what it runs.
                    meshPath(name) to (if (rows[name]?.state == VaultCockpit.State.MATCH) conf else rows[name]?.device)
                }
                base.copy(detail = "${d.label} · ${tunnel.name.ifBlank { "no tunnel" }}", values = values,
                    readOnly = if (rt.writable) emptySet() else values.keys)
            }
            "ai" -> {
                val client = tools(ctx)
                if (!client.isServingAppInstalled()) return base.copy(status = Status.NOT_INSTALLED, detail = "text tools")
                val until = SystemClock.elapsedRealtime() + deadlineMs
                while (!client.isConnected() && SystemClock.elapsedRealtime() < until) Thread.sleep(100)
                if (!client.isConnected()) return base.copy(status = Status.NOT_REPORTING, detail = "text tools did not bind in ${deadlineMs} ms")
                base.copy(values = aiPaths(VaultCockpit.layout.aiTokens).mapValues { (_, provider) ->
                    client.revealAiKey(provider).text?.trim()?.ifBlank { null }
                })
            }
            "apps" -> {
                val d = device(ctx, declared) ?: return base.copy(detail = "no device picked on Connect")
                val fleet = com.diegonmarcos.superapp.appstore.AppInventory.fleetPackages()
                val want = VaultCockpit.appsDeclared(declared!!, d, fleet)
                val have = want.count { installed(ctx, it.pkg) }
                base.copy(detail = d.label, summary = "$have / ${want.size}")
            }
            else -> base.copy(status = Status.NOT_REPORTING, detail = "no reader for '${section.apply}'")
        }
    }

    // ── pushing (Android) ────────────────────────────────────────────────

    /**
     * SERVER → RUNTIME for one field: [value] (the server file's) written into the app that owns
     * [path]. [server] is the whole S body, for the fields an app takes as a unit (a mail account,
     * a mesh profile). Returns the report line, ✓ or ✗. BLOCKS (binder): call on IO.
     */
    fun push(ctx: Context, path: String, value: Any, server: JSONObject): String {
        val section = VaultCockpit.layout.sections.firstOrNull { path.substringBefore(AccountDrift.SEP) in it.vault }
            ?: return "✗ $path: no app consumes it"
        return when (section.apply) {
            "about" -> {
                val field = aboutPaths(section.fields)[path] ?: return "✗ $path: not a contact-card field"
                if (setProfileField(ProfilePrefs(ctx), field, value.toString())) "✓ $field" else "✗ $field"
            }
            "drive" -> {
                val at = drivePaths()[path] ?: return "✗ $path: not a drive credential"
                ConfigsPrefs(ctx).putSecret(at.first, at.second, value.toString().let { if (path.endsWith("github_token")) it.trim() else it })
                "✓ ${path.substringAfterLast(AccountDrift.SEP)}"
            }
            "ai" -> {
                val provider = aiPaths(VaultCockpit.layout.aiTokens)[path] ?: return "✗ $path: mapped to no provider"
                val r = tools(ctx).setAiRouting(provider, apiKey = value.toString().trim())
                if (r.ok) "✓ $provider" else "✗ $provider: ${r.error}"
            }
            "mail" -> {
                val owner = ConfigsPrefs(ctx).autheliaEmail.ifBlank { ProfilePrefs(ctx).email.trim() }
                    .ifBlank { VaultCockpit.ownerEmail(server, VaultCockpit.layout) }
                val accounts = VaultCockpit.mailAccounts(server, owner.substringAfter('@', ""))
                val key = path.split(AccountDrift.SEP).getOrNull(2)
                val decl = server.optJSONObject("mail")?.optJSONObject("accounts")
                val account = accounts.firstOrNull { it.account == key }
                    ?: accounts.firstOrNull { decl?.optJSONObject(it.account)?.optString("pass_env") == key }
                    ?: return "✗ $path: no declared account at the owner's domain"
                VaultCockpit.applyMail(JmapPrefs(ctx), account)
            }
            "mesh" -> {
                val name = path.split(AccountDrift.SEP).let { if (it[0] == "peers") "${it[1]}/${it.last()}" else it.last() }
                VaultCockpit.applyMesh(WgState.prefs(ctx), name, value.toString())
            }
            else -> "✗ ${section.label}: this app takes nothing pushed"
        }
    }

    /** The contact card's field [field] — the ProfilePrefs vocabulary; null for a field it has not. */
    fun profileField(p: ProfilePrefs, field: String): String? = when (field) {
        "name" -> p.name
        "email" -> p.email
        "company" -> p.company
        "location" -> p.location
        "website" -> p.website
        "titles" -> p.titles
        else -> null
    }

    fun setProfileField(p: ProfilePrefs, field: String, value: String): Boolean = when (field) {
        "name" -> { p.name = value; true }
        "email" -> { p.email = value; true }
        "company" -> { p.company = value; true }
        "location" -> { p.location = value; true }
        "website" -> { p.website = value; true }
        "titles" -> { p.titles = value; true }
        else -> false
    }
}
