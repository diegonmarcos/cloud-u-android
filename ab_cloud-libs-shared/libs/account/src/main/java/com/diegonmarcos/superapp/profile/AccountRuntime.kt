package com.diegonmarcos.superapp.profile

import android.content.Context
import android.os.SystemClock
import com.diegonmarcos.superapp.mail.JmapPrefs
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
 * binder for AI and (#781) the keyboard's autocomplete lists — binding starts the serving app if it
 * is stopped, and the read waits for it under a deadline; a package that exposes nothing (cloud-drive's
 * own token) is NOT_REPORTING with its declared reason, said plainly.
 * Reading never writes; [push] is the only writer, one declared value into one app.
 */
object AccountRuntime {

    enum class Status { REACHABLE, NOT_INSTALLED, NOT_REPORTING }

    /**
     * One app's reading. [values]: every path it observed → its value, null where it holds nothing.
     * [readOnly]: observed paths it cannot write back. [summary]: a reading that is not fields (apps).
     * #781 [fields]: the Profiles fields this app HOLDS (cockpit `vault_fields`) — what it declares;
     * [missing]: what it should hold and does not (observed empty, or the app could not be read);
     * [unread]: a declared field no observed path falls under this time (out of this phone's scope,
     * e.g. another peer's profiles) — named, never judged.
     */
    data class AppRead(
        val id: String, val label: String, val status: Status, val detail: String,
        val values: Map<String, Any?>, val readOnly: Set<String> = emptySet(), val summary: String = "",
        val fields: List<String> = emptyList(), val missing: List<String> = emptyList(), val unread: List<String> = emptyList(),
        /** #810 declared fields not read this time WITH a reason (the field map's `unread_why`, or an
         *  app store that is empty on this phone) — named, counted, and not "unread". */
        val justified: Map<String, String> = emptyMap(),
        /** #782 rows that are not vault fields: the fleet roster (apps), the live tunnel (mesh). */
        val roster: List<JSONObject> = emptyList(),
    ) {
        val reported: Int get() = values.count { it.value != null }
    }

    /** True when [path] is the Profiles field [field] or lies under it. */
    fun under(path: String, field: String): Boolean = path == field || path.startsWith(field + AccountDrift.SEP)

    /**
     * #781 [r] with what it declares, misses and did not read, from the field map [vf]. An app that
     * could not be read misses every field it holds; a read one misses what it observed empty.
     */
    fun coverage(r: AppRead, vf: Map<String, VaultCockpit.VaultField>): AppRead {
        val fields = vf.filter { (_, f) -> f.held && r.id in f.apps }.keys.toList()
        if (r.status != Status.REACHABLE) return r.copy(fields = fields, missing = fields, unread = emptyList())
        val missing = r.values.filterValues { it == null }.keys.sorted()
        val (why, unread) = fields.filter { f -> r.values.keys.none { under(it, f) } }
            .partition { vf[it]?.unreadWhy?.isNotBlank() == true }
        return r.copy(fields = fields, missing = missing, unread = unread,
            justified = r.justified + why.associateWith { vf.getValue(it).unreadWhy })
    }

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
                .put("read_only", JSONArray(r.readOnly.sorted()))
                .put("fields", JSONArray(r.fields))
                .put("missing", JSONArray(r.missing))
                .put("unread", JSONArray(r.unread))
                .put("justified", JSONObject(r.justified.toSortedMap() as Map<*, *>))
                .put("roster", JSONArray(r.roster))
                .put("counts", JSONObject().put("declared", r.fields.size).put("observed", r.values.size)
                    .put("reported", r.reported).put("missing", r.missing.size).put("unread", r.unread.size)
                    .put("justified", r.justified.size).put("roster", r.roster.size)))
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

    /** `mail`: the host of the JMAP server URL the device holds (the vault declares `endpoints › domain`). */
    fun hostOf(server: String): String? = runCatching { java.net.URI(server.trim()).host }.getOrNull()?.ifBlank { null }

    /**
     * #781 `keyboard`: the keyboard's clipboard export ([export], ITextTools.clipboardLists) at the
     * vault's autocomplete paths — the manifest's version and tabs (its exportedAt is the moment of an
     * export, not config), and each list by its declared tab file ([lists]: vault key → file).
     */
    fun keyboardValues(export: JSONObject, lists: Map<String, String>): Map<String, Any?> {
        val s = AccountDrift.SEP
        val files = export.optJSONObject("files") ?: JSONObject()
        val out = LinkedHashMap<String, Any?>()
        out["autocomplete${s}manifest${s}version"] = export.opt("version")?.takeIf { it != JSONObject.NULL }
        out["autocomplete${s}manifest${s}tabs"] = export.optJSONArray("tabs")?.takeIf { it.length() > 0 }
        lists.forEach { (key, file) -> out["autocomplete$s$key"] = files.optJSONArray(file)?.takeIf { it.length() > 0 } }
        return out
    }

    /**
     * #781 The device a reading is filed under: the one picked on Connect, else the declared device
     * whose mesh address the live tunnel carries ([liveAddress], the interface's Address line) — so a
     * phone with no pick still reads its own profiles and apps. Second: true when inferred.
     */
    fun deviceFor(devices: List<VaultCockpit.Device>, picked: String, liveAddress: String): Pair<VaultCockpit.Device, Boolean>? {
        devices.firstOrNull { it.id == picked }?.let { return it to false }
        val live = liveAddress.split(',').map { it.trim().substringBefore('/') }.filter { it.isNotBlank() }.toSet()
        return devices.firstOrNull { it.wgIp in live || (it.wgIpv6.isNotBlank() && it.wgIpv6 in live) }?.let { it to true }
    }

    /**
     * #782 One fleet app's row on Runtime ▸ apps — what the SuperApp itself knows, with no device
     * pick: installed or not, the installed versionName/versionCode, the Store stage, whether the
     * unattended pass may update it, and the version the fleet roster declares.
     */
    fun appRow(id: String, label: String, pkg: String, versionName: String?, versionCode: Long?,
               stage: String, stageText: String, autoUpdate: Boolean, declaredVersion: String?): JSONObject =
        JSONObject().put("id", id).put("label", label).put("pkg", pkg)
            .put("installed", versionCode != null)
            .put("version_name", versionName ?: JSONObject.NULL)
            .put("version_code", versionCode ?: JSONObject.NULL)
            .put("stage", stage).put("stage_text", stageText)
            .put("auto_update", autoUpdate)
            .put("declared_version", declaredVersion ?: JSONObject.NULL)

    /** #782 The live tunnel as Runtime ▸ mesh shows it with no device pick: name, address, peer count — never a key. */
    fun tunnelRow(t: VaultCockpit.TunnelState): JSONObject =
        JSONObject().put("tunnel", t.name.ifBlank { "none" }).put("address", t.address.ifBlank { "none" }).put("peers", t.peerKeys.size)

    // ── reading (Android) ────────────────────────────────────────────────

    /** One binder client per process: constructing it binds, which is what wakes a stopped serving app. */
    @Volatile private var tools: TextToolsClient? = null
    private fun tools(ctx: Context) = tools ?: synchronized(this) { tools ?: TextToolsClient(ctx.applicationContext).also { tools = it } }

    /**
     * Every declared app's reading, in declared order. [declared] (L, else S) gives the shape a reading
     * is filed under — which mail account, which device's mesh profiles. BLOCKS (binder): call on IO.
     */
    fun read(ctx: Context, declared: JSONObject?, deadlineMs: Long): List<AppRead> =
        AccountFleet.merge(VaultCockpit.layout.sections.map { section ->
            coverage(runCatching { readOne(ctx, section, declared, deadlineMs) }.getOrElse {
                AppRead(section.id, section.label, Status.NOT_REPORTING, it.message ?: it.javaClass.simpleName, emptyMap())
            }, VaultCockpit.layout.vaultFields)
        }, AccountFleet.reads(ctx))  // #783 every fleet app's own configuration, through its contract

    private fun installed(ctx: Context, pkg: String) = runCatching { ctx.packageManager.getPackageInfo(pkg, 0) }.isSuccess

    /** #782 Every constellation-fleet.json member as [appRow]: PackageManager for the version, StoreStages for the stage. */
    private fun appsRoster(ctx: Context): List<JSONObject> {
        val auto = com.diegonmarcos.superapp.updater.AutoUpdatePrefs.enabled(ctx)
        return AccountFleet.fleetApps().map { app ->
            val pi = listOfNotNull(app.pkg, app.altId).firstNotNullOfOrNull { p ->
                runCatching { ctx.packageManager.getPackageInfo(p, 0) }.getOrNull()
            }
            @Suppress("DEPRECATION")
            val code = pi?.let { if (android.os.Build.VERSION.SDK_INT >= 28) it.longVersionCode else it.versionCode.toLong() }
            val st = runCatching { com.diegonmarcos.superapp.appstore.StoreStages.stage(ctx, app) }.getOrNull()
            appRow(app.id, app.label, app.pkg, pi?.versionName, code, st?.id ?: "unknown", st?.text.orEmpty(),
                auto && !app.blocked, app.declaredVersionName)
        }
    }

    private fun device(ctx: Context, declared: JSONObject?): Pair<VaultCockpit.Device, Boolean>? =
        declared?.let { deviceFor(VaultCockpit.devices(it), VaultCockpit.selectedDevice(ctx), AccountHost.mesh?.interfaceAddress(ctx).orEmpty()) }

    private fun deviceLabel(d: Pair<VaultCockpit.Device, Boolean>) = d.first.label + if (d.second) " (from the live tunnel)" else ""

    private fun readOne(ctx: Context, section: VaultCockpit.Section, declared: JSONObject?, deadlineMs: Long): AppRead {
        val rt = section.runtime
        val base = AppRead(section.id, section.label, Status.REACHABLE, "", emptyMap())
        if (rt.servedBy != VaultCockpit.SELF && rt.servedBy != VaultCockpit.TEXT_TOOLS && !installed(ctx, rt.servedBy))
            return base.copy(status = Status.NOT_INSTALLED, detail = rt.servedBy)
        if (!rt.reports) return base.copy(status = Status.NOT_REPORTING, detail = rt.why.ifBlank { rt.servedBy })
        // The ITextTools serving app (the keyboard): binding starts it when stopped, so wait for it under the deadline.
        val client = if (rt.servedBy == VaultCockpit.TEXT_TOOLS) tools(ctx) else null
        if (client != null) {
            if (!client.isServingAppInstalled()) return base.copy(status = Status.NOT_INSTALLED, detail = "text tools")
            val until = SystemClock.elapsedRealtime() + deadlineMs
            while (!client.isConnected() && SystemClock.elapsedRealtime() < until) Thread.sleep(100)
            if (!client.isConnected()) return base.copy(status = Status.NOT_REPORTING, detail = "text tools did not bind in ${deadlineMs} ms")
        }
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
                // #781 the JMAP host is read either way; it is not written back (the mail apply leaves the server alone).
                val domainPath = "mail${AccountDrift.SEP}endpoints${AccountDrift.SEP}domain"
                val domain = mapOf(domainPath to hostOf(jmap.server))
                val held = jmap.email.ifBlank { "no account held" }
                val (namePath, pwPath) = mailPaths(declared, jmap.email, owner)
                    ?: return base.copy(detail = "$held · no declared account matches", values = domain, readOnly = domain.keys)
                base.copy(detail = held, values = domain + mapOf(
                    namePath to jmap.email.substringBefore('@').trim().ifBlank { null },
                    pwPath to jmap.password.ifBlank { null },
                ), readOnly = domain.keys)
            }
            "mesh" -> {
                val tunnel = AccountHost.mesh?.state(ctx) ?: VaultCockpit.TunnelState("", "", emptySet())
                // #782 the live tunnel is shown whether or not a device is picked.
                val live = listOf(tunnelRow(tunnel))
                val picked = device(ctx, declared)
                    ?: return base.copy(detail = "no device picked on Connect · live tunnel ${tunnel.name.ifBlank { "none" }}", roster = live)
                val d = picked.first
                val rows = VaultCockpit.meshRows(declared!!, d, tunnel).associateBy { it.label }
                val values = VaultCockpit.meshProfiles(declared, d).entries.associate { (name, conf) ->
                    // The tunnel IS the declared profile when address and peers agree; otherwise what it runs.
                    meshPath(name) to (if (rows[name]?.state == VaultCockpit.State.MATCH) conf else rows[name]?.device)
                }
                base.copy(detail = "${deviceLabel(picked)} · ${tunnel.name.ifBlank { "no tunnel" }}", values = values, roster = live,
                    readOnly = if (rt.writable) emptySet() else values.keys)
            }
            "ai" -> base.copy(values = aiPaths(VaultCockpit.layout.aiTokens).mapValues { (_, provider) ->
                client!!.revealAiKey(provider).text?.trim()?.ifBlank { null }
            })
            // #781 the keyboard's autocomplete lists, live, over ITextTools.clipboardLists.
            "keyboard" -> {
                val export = client!!.clipboardLists()?.let { runCatching { JSONObject(it) }.getOrNull() }
                    ?: return base.copy(status = Status.NOT_REPORTING, detail = "the keyboard answered no lists — older than this build, or its clipboard store is locked")
                val values = keyboardValues(export, rt.lists)
                base.copy(detail = "${export.optJSONArray("tabs")?.length() ?: 0} lists", values = values)
            }
            "apps" -> {
                // #782 the fleet roster is the SuperApp's own knowledge: listed with or without a pick.
                val roster = appsRoster(ctx)
                val auto = com.diegonmarcos.superapp.updater.AutoUpdatePrefs.enabled(ctx)
                val inst = roster.count { it.optBoolean("installed") }
                val autoLine = "auto-update " + if (auto) "on" else "off"
                val fleet = com.diegonmarcos.superapp.appstore.AppInventory.fleetPackages()
                val picked = device(ctx, declared)
                    ?: return base.copy(detail = "no device picked on Connect · whole fleet · $autoLine",
                        summary = "$inst / ${roster.size}", roster = roster)
                val want = VaultCockpit.appsDeclared(declared!!, picked.first, fleet)
                val have = want.count { installed(ctx, it.pkg) }
                base.copy(detail = "${deviceLabel(picked)} · $autoLine", summary = "$have / ${want.size} declared · $inst / ${roster.size} fleet", roster = roster)
            }
            // #789 cloud-drive's own copy of the token, through its #783 FleetConfig export
            // (libs:core's provider, CONSTELLATION_DATA-guarded) — no channel of its own.
            "cloud-drive" -> {
                val export = when (val r = com.diegonmarcos.superapp.core.FleetConfig.export(ctx, rt.servedBy)) {
                    is com.diegonmarcos.superapp.core.FleetConfig.Reply.Ok -> r.json
                    is com.diegonmarcos.superapp.core.FleetConfig.Reply.Unreachable -> return base.copy(status = Status.NOT_REPORTING, detail = r.why)
                    is com.diegonmarcos.superapp.core.FleetConfig.Reply.Refused -> return base.copy(status = Status.NOT_REPORTING, detail = r.why)
                }
                val (detail, value) = heldSecret(export, rt.store)
                val held = VaultCockpit.layout.vaultFields.filter { (_, f) -> f.held && section.id in f.apps }.keys
                base.copy(detail = detail, values = held.associateWith { value })
            }
            else -> base.copy(status = Status.NOT_REPORTING, detail = "no reader for '${section.apply}'")
        }
    }

    /**
     * #789 A secret store from a FleetConfig export as Runtime may say it: how many entries hold a
     * value and the sha256 fingerprint of each distinct one — the detail line never carries the
     * value. The value itself comes back only when every entry holds the same one, so Drift can
     * compare it with the declared copy; the screen masks it like every other secret path.
     */
    fun heldSecret(export: JSONObject, store: String): Pair<String, String?> {
        val none = "no token held" to null
        val o = export.optJSONObject("stores")?.optJSONObject(store) ?: return none
        val vals = o.keys().asSequence().filter { it != com.diegonmarcos.superapp.core.FleetConfig.TYPES }
            .mapNotNull { o.opt(it) as? String }.filter { it.isNotBlank() }.toList()
        if (vals.isEmpty()) return none
        val fps = vals.distinct().map { fingerprint(it) }
        return "token held by ${vals.size} repo(s) · sha256 ${fps.joinToString(", ")}" to vals.distinct().singleOrNull()
    }

    /** The first 12 hex of a value's sha256: enough to tell two copies apart, nothing to recover it from. */
    fun fingerprint(v: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(v.toByteArray()).joinToString("") { "%02x".format(it) }.take(12)

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
                if (path.split(AccountDrift.SEP).getOrNull(1) == "endpoints") return "✗ $path: the JMAP server is not written from the vault"
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
                AccountHost.mesh?.apply(ctx, name, value.toString()) ?: "✗ $name: the mesh tunnel is not available in this app"
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
