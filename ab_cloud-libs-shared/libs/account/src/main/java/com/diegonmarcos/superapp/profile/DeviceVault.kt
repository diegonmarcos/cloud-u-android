package com.diegonmarcos.superapp.profile

import android.content.Context
import com.diegonmarcos.superapp.settings.AccountVault
import com.diegonmarcos.superapp.settings.ConfigsPrefs
import org.json.JSONArray
import org.json.JSONObject

/**
 * Cloud Account redesign (spec 2, 5.2): the per-device files in the vault, each operation one
 * commit through [ForgeClient.put].
 *
 *  - [backup]     capture this phone -> `devices/<id>.json` (no commit when only captured_at would change)
 *  - [load]       fetch `devices/<id|DEFAULT>.json` -> the `working` slot (replaces L)
 *  - [setDefault] copy a device file over `devices/DEFAULT.json`
 *
 * Phone-side keys (AccountVault Connections, encrypted): `forge.primary`, `forge.<id>.token`,
 * `backup.last`, `restore.last`. The token is read here and handed to the client; it is never
 * returned, logged or put in a message.
 */
class DeviceVault(private val ctx: Context, val decl: ForgeClient.Decl = ForgeClient.Decl.fromBuildConfig(
    com.diegonmarcos.superapp.account.BuildConfig.UI_ACCOUNT_B64)) {

    private val vault = AccountVault(ctx)

    /** The primary forge: Connections `forge.primary` if declared and usable, else the first with a repo. */
    fun primary(): ForgeClient.Forge? =
        decl.forge(vault.connection("forge.primary") as? String)?.takeIf { it.repo != null } ?: decl.firstUsable()

    /** Connections `forge.<id>.token`; for GitHub, the gh engine's token when none is filed. */
    private fun token(f: ForgeClient.Forge): String? =
        (vault.connection("forge.${f.id}.token") as? String)?.ifBlank { null }
            ?: if (f.id == "github") AccountModel.ghHost()?.let { runCatching { GhEngine(ctx).token(it) }.getOrNull() } else null

    fun client(forgeId: String? = null): ForgeClient? {
        val f = (if (forgeId.isNullOrBlank()) primary() else decl.forge(forgeId)) ?: return null
        return ForgeClient(f, token(f).orEmpty())
    }

    /** Which forges hold a token, by id (true/false) — never the token. */
    fun credentials(): JSONObject = JSONObject().also { o -> decl.forges.forEach { o.put(it.id, token(it) != null) } }

    private fun noForge() = JSONObject().put("ok", false).put("result", "✗ no usable forge declared (ui.account.forges with a repo)")

    private fun fail(r: ForgeClient.Result.Failed) = JSONObject().put("ok", false).put("status", r.status).put("result", "✗ ${r.reason}")

    /** devices/: name, id, sha and the device's captured_at (one GET per file). */
    fun devices(): JSONObject {
        val c = client() ?: return noForge()
        val entries = when (val l = c.list(decl.devicesDir, decl.branch)) {
            is ForgeClient.Result.Ok -> l.value
            is ForgeClient.Result.Failed -> return fail(l)
        }
        val out = JSONArray()
        entries.filter { it.name.endsWith(".json") }.forEach { e ->
            val row = JSONObject().put("id", e.name.removeSuffix(".json")).put("path", e.path).put("sha", e.sha)
            when (val g = c.get(e.path, decl.branch)) {
                is ForgeClient.Result.Ok -> runCatching { JSONObject(g.value.text).optJSONObject("device") }.getOrNull()
                    ?.let { row.put("captured_at", it.opt("captured_at")).put("model", it.opt("model")) }
                is ForgeClient.Result.Failed -> row.put("error", g.reason)
            }
            out.put(row)
        }
        // AccountDevice derives this phone's id from these rows' model (main-thread safe: cached, no GET).
        ConfigsPrefs(ctx).putText(AccountDevice.K_LISTING, out.toString())
        return JSONObject().put("ok", true).put("forge", c.forge.id).put("dir", decl.devicesDir).put("devices", out)
    }

    /**
     * Capture this phone as [deviceId]. [dry]: key NAMES and counts only, nothing committed. [capture] first reads
     * every installed fleet app's `export` into the Configs section ([AccountMigrate.capture]), so the file carries
     * what the apps hold now; secret-class keys are still written as `@vault:` references ([DeviceProfile.mask]).
     */
    fun backup(deviceId: String, dry: Boolean, capture: Boolean = true): JSONObject {
        if (deviceId.isBlank() || deviceId == DeviceProfile.DEFAULT_ID || !deviceId.matches(Regex("[A-Za-z0-9._-]+")))
            return JSONObject().put("ok", false).put("result", "✗ device= must be a declared device id (not DEFAULT)")
        val captured = if (!capture) emptyList() else runCatching {
            AccountMigrate.capture(vault, AccountFleet.manifest(ctx), { FleetSetup.installed(ctx, it) }, FleetSetup.transport(ctx))
        }.getOrElse { return JSONObject().put("ok", false).put("result", "✗ capture: ${it.javaClass.simpleName}") }
        val profile = runCatching { DeviceProfile.capture(ctx, deviceId) }
            .getOrElse { return JSONObject().put("ok", false).put("result", "✗ ${it.message}") }
        val (apps, keys) = DeviceProfile.counts(profile)
        val path = decl.devicePath(deviceId)
        val message = "account($deviceId): backup — $apps apps, $keys settings"
        if (dry) return JSONObject().put("ok", true).put("dry", true).put("path", path).put("message", message)
            .put("apps", apps).put("settings", keys).put("settings_keys", keyNames(profile.optJSONObject("settings") ?: JSONObject()))
            .put("forge", primary()?.id ?: JSONObject.NULL).put("captured", JSONArray(captured))
        val c = client() ?: return noForge()
        val current = when (val g = c.get(path, decl.branch)) {
            is ForgeClient.Result.Ok -> g.value
            is ForgeClient.Result.Failed -> if (g.status == 404) null else return fail(g)
        }
        // Equal but for captured_at = nothing to record: no commit (a second Backup is a no-op).
        if (current != null) {
            val prev = runCatching { JSONObject(current.text) }.getOrNull()
            val at = prev?.optJSONObject("device")?.opt("captured_at")
            if (at != null) {
                val same = JSONObject(profile.toString()).also { it.getJSONObject("device").put("captured_at", at) }
                if (DeviceProfile.text(same) == current.text) return JSONObject().put("ok", true).put("noop", true)
                    .put("sha", current.sha).put("result", "= unchanged since ${at} — nothing committed")
            }
        }
        return when (val p = c.put(path, DeviceProfile.text(profile), current?.sha, message, decl.branch)) {
            is ForgeClient.Result.Ok -> {
                vault.putConnection("backup.last", JSONObject().put("device", deviceId).put("sha", p.value)
                    .put("at", profile.getJSONObject("device").optString("captured_at")).toString())
                // The file just committed IS this phone's current file: the pages read it from the working slot.
                runCatching { setWorking(deviceId, p.value, JSONObject(DeviceProfile.text(profile))) }
                JSONObject().put("ok", true).put("sha", p.value).put("path", path).put("forge", c.forge.id).put("result", "✓ $message")
            }
            is ForgeClient.Result.Failed -> fail(p)
        }
    }

    /** Fetch `devices/<id>.json` (or DEFAULT) into the working slot. Gated by [DeviceProfile.parse]. */
    fun load(deviceId: String): JSONObject {
        val id = deviceId.ifBlank { DeviceProfile.DEFAULT_ID }
        val c = client() ?: return noForge()
        val file = when (val g = c.get(decl.devicePath(id), decl.branch)) {
            is ForgeClient.Result.Ok -> g.value
            is ForgeClient.Result.Failed -> return fail(g)
        }
        return when (val p = DeviceProfile.parse(file.text, DeviceProfile.manifestClass(ctx))) {
            is DeviceProfile.Parsed.Refused -> JSONObject().put("ok", false).put("result", "✗ ${p.reason}")
            is DeviceProfile.Parsed.Ok -> {
                ConfigsPrefs(ctx).putText(K_WORKING, JSONObject().put("device", id).put("sha", file.sha).put("profile", p.json).toString())
                vault.putConnection("restore.last", JSONObject().put("device", id).put("sha", file.sha).toString())
                val (apps, keys) = DeviceProfile.counts(p.json)
                JSONObject().put("ok", true).put("device", id).put("sha", file.sha).put("apps", apps).put("settings", keys)
                    .put("result", "✓ loaded $id into working ($apps apps, $keys settings)")
            }
        }
    }

    private fun setWorking(device: String, sha: String, profile: JSONObject) =
        ConfigsPrefs(ctx).putText(K_WORKING, JSONObject().put("device", device).put("sha", sha).put("profile", profile).toString())

    /**
     * This phone's device file as the Account pages read it (profile tiles, perms, drift): the working
     * slot when it already holds [deviceId] at the last backup's sha; else STALE (nothing loaded, another
     * device's file, or a newer commit than the slot) and fetched from the forge into the slot, without
     * touching `restore.last` (a read, not a restore). A failed fetch keeps the slot. Blocks: off main.
     */
    fun current(deviceId: String): JSONObject? {
        val w = working()
        if (deviceId.isBlank() || deviceId == DeviceProfile.DEFAULT_ID) return w
        val last = (vault.connection("backup.last") as? String)?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?.takeIf { it.optString("device") == deviceId }
        val fresh = w != null && w.optString("device") == deviceId && (last == null || last.optString("sha") == w.optString("sha"))
        if (fresh) return w
        val c = client() ?: return w
        val file = (c.get(decl.devicePath(deviceId), decl.branch) as? ForgeClient.Result.Ok)?.value ?: return w
        val p = DeviceProfile.parse(file.text, DeviceProfile.manifestClass(ctx)) as? DeviceProfile.Parsed.Ok ?: return w
        setWorking(deviceId, file.sha, p.json)
        return working()
    }

    /** The working slot: the loaded profile, its device and file sha; null when nothing is loaded. */
    fun working(): JSONObject? = ConfigsPrefs(ctx).text(K_WORKING).takeIf { it.isNotBlank() }?.let { runCatching { JSONObject(it) }.getOrNull() }

    /** Copy `devices/<id>.json` over `devices/DEFAULT.json` (its device.id becomes DEFAULT). */
    fun setDefault(deviceId: String): JSONObject {
        if (deviceId.isBlank() || deviceId == DeviceProfile.DEFAULT_ID) return JSONObject().put("ok", false).put("result", "✗ device= names the file to copy")
        val c = client() ?: return noForge()
        val src = when (val g = c.get(decl.devicePath(deviceId), decl.branch)) {
            is ForgeClient.Result.Ok -> g.value
            is ForgeClient.Result.Failed -> return fail(g)
        }
        val profile = when (val parsed = DeviceProfile.parse(src.text, DeviceProfile.manifestClass(ctx))) {
            is DeviceProfile.Parsed.Ok -> parsed.json
            is DeviceProfile.Parsed.Refused -> return JSONObject().put("ok", false).put("result", "✗ ${parsed.reason}")
        }
        profile.optJSONObject("device")?.put("id", DeviceProfile.DEFAULT_ID)?.put("from", deviceId)
        val target = decl.devicePath(DeviceProfile.DEFAULT_ID)
        val current = when (val g = c.get(target, decl.branch)) {
            is ForgeClient.Result.Ok -> g.value
            is ForgeClient.Result.Failed -> if (g.status == 404) null else return fail(g)
        }
        val text = DeviceProfile.text(profile)
        if (current != null && current.text == text) return JSONObject().put("ok", true).put("noop", true).put("sha", current.sha).put("result", "= DEFAULT already equals $deviceId")
        val (apps, keys) = DeviceProfile.counts(profile)
        return when (val p = c.put(target, text, current?.sha, "account(DEFAULT): set from $deviceId — $apps apps, $keys settings", decl.branch)) {
            is ForgeClient.Result.Ok -> JSONObject().put("ok", true).put("sha", p.value).put("path", target).put("result", "✓ DEFAULT = $deviceId")
            is ForgeClient.Result.Failed -> fail(p)
        }
    }

    /**
     * Delete `devices/<id>.json`. Never DEFAULT (that is a pointer other devices rely on) and
     * never silent — the caller gates this behind a confirm (Profiles ▸ devices, spec 4.3).
     */
    fun delete(deviceId: String): JSONObject {
        if (deviceId.isBlank() || deviceId == DeviceProfile.DEFAULT_ID) return JSONObject().put("ok", false).put("result", "✗ device= must name a device file (not DEFAULT)")
        val c = client() ?: return noForge()
        val path = decl.devicePath(deviceId)
        val current = when (val g = c.get(path, decl.branch)) {
            is ForgeClient.Result.Ok -> g.value
            is ForgeClient.Result.Failed -> return fail(g)
        }
        return when (val d = c.delete(path, current.sha, "account($deviceId): delete", decl.branch)) {
            is ForgeClient.Result.Ok -> JSONObject().put("ok", true).put("path", path).put("result", "✓ deleted $deviceId")
            is ForgeClient.Result.Failed -> fail(d)
        }
    }

    companion object {
        const val K_WORKING = "account_working_json"

        /** `app.store.file.key` for every settings key — names only, never a value. */
        fun keyNames(settings: JSONObject): JSONArray {
            val out = JSONArray()
            settings.keys().asSequence().filter { !it.startsWith("_") }.forEach { a ->
                val stores = settings.optJSONObject(a) ?: return@forEach
                stores.keys().asSequence().filter { !it.startsWith("_") }.forEach { s ->
                    val files = stores.optJSONObject(s) ?: return@forEach
                    files.keys().asSequence().filter { !it.startsWith("_") }.forEach { f ->
                        val keys = files.optJSONObject(f) ?: return@forEach
                        keys.keys().asSequence().filter { !it.startsWith("_") }.forEach { k -> out.put("$a.$s.$f.$k") }
                    }
                }
            }
            return out
        }
    }
}
