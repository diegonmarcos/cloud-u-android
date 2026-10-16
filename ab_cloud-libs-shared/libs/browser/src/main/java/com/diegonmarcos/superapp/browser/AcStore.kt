package com.diegonmarcos.superapp.browser

import com.diegonmarcos.superapp.autofill.AutofillProfile
import com.diegonmarcos.superapp.autofill.ContactKind
import org.json.JSONArray
import org.json.JSONObject

/** A tiny key-value surface: EncryptedSharedPreferences on the phone ([AcAndroid]), a map in the JVM tests. */
interface AcKv {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun remove(key: String)
    fun keys(): Set<String>
}

/** In memory; for tests and as a never-persisting fallback. */
class AcMemoryKv : AcKv {
    private val m = LinkedHashMap<String, String>()
    override fun getString(key: String) = m[key]
    override fun putString(key: String, value: String) { m[key] = value }
    override fun remove(key: String) { m.remove(key) }
    override fun keys(): Set<String> = m.keys.toSet()
}

/** One schema step: [from] -> [from]+1, run on a store holding [from]. Steps ADD or REWRITE; they never delete user data. */
class AcMigration(val from: Int, val name: String, val run: (AcKv) -> Unit)

/**
 * #900 THE AUTOCOMPLETE STORE. Two key-value stores (both encrypted at rest on the phone):
 *   [profileKv]  profiles + per-site memory + the "never save here" list   (declared browser_ac_profiles)
 *   [secretKv]   saved sign-ins and cards, every secret field sealed with [box] (declared browser_ac_secrets)
 * Each carries a `schema` number and [migrate] walks it forward. NOTHING here deletes data on its own: unreadable
 * JSON is copied to a `bak_` key before anything is written over it, a secret that cannot be opened is skipped
 * and counted (never dropped), and the old browser profile / Cloud Account profiles are IMPORTED once, never moved.
 */
class AcStore(
    private val profileKv: AcKv,
    private val secretKv: AcKv,
    private val box: AcSealedBox?,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    // ── schema ──────────────────────────────────────────────────────────────────────────────

    /** Bring both stores to [SCHEMA]. Idempotent. */
    fun migrate() {
        for (kv in listOf(profileKv, secretKv)) {
            var v = kv.getString(K_SCHEMA)?.toIntOrNull() ?: 0
            while (v < SCHEMA) {
                MIGRATIONS.firstOrNull { it.from == v }?.run?.invoke(kv)
                v += 1
                kv.putString(K_SCHEMA, v.toString())
            }
        }
    }

    fun schemaOf(kv: AcKv = profileKv): Int = kv.getString(K_SCHEMA)?.toIntOrNull() ?: 0

    // ── profiles ────────────────────────────────────────────────────────────────────────────

    fun profiles(): List<AcProfile> = AcProfiles.normalize(AcProfiles.fromJson(readArray(profileKv, K_PROFILES)))

    fun saveProfiles(list: List<AcProfile>) = writeArray(profileKv, K_PROFILES, AcProfiles.toJson(AcProfiles.normalize(list)))

    /** Add or replace by id. The first profile ever is the default. */
    fun upsertProfile(p: AcProfile) {
        val cur = profiles()
        saveProfiles(if (cur.any { it.id == p.id }) cur.map { if (it.id == p.id) p else it } else cur + p)
    }

    fun deleteProfile(id: String) = saveProfiles(profiles().filterNot { it.id == id })

    fun setDefaultProfile(id: String) = saveProfiles(profiles().map { it.copy(isDefault = it.id == id) })

    /** The profile last used on [site] (to preselect it there). */
    fun lastProfileFor(site: String): String? = readObject(profileKv, K_LAST_PROFILE).optString(site).takeIf { it.isNotBlank() }

    fun setLastProfileFor(site: String, profileId: String) {
        if (site.isBlank()) return
        profileKv.putString(K_LAST_PROFILE, readObject(profileKv, K_LAST_PROFILE).put(site, profileId).toString())
    }

    // ── per-site memory ─────────────────────────────────────────────────────────────────────

    fun memory(): List<AcMemoryEntry> = AcSiteMemory.fromJson(readArray(profileKv, K_MEMORY))

    fun remember(e: AcMemoryEntry) = writeArray(profileKv, K_MEMORY, AcSiteMemory.toJson(AcSiteMemory.upsert(memory(), e, clock())))

    /** The entry was offered and used: count it. */
    fun used(id: String) = writeArray(profileKv, K_MEMORY, AcSiteMemory.toJson(memory().map { if (it.id == id) it.copy(uses = it.uses + 1, lastUsed = clock()) else it }))

    fun forgetMemory(id: String) = writeArray(profileKv, K_MEMORY, AcSiteMemory.toJson(memory().filterNot { it.id == id }))

    fun forgetSite(origin: String) = writeArray(profileKv, K_MEMORY, AcSiteMemory.toJson(memory().filterNot { it.origin == origin }))

    fun clearMemory() = writeArray(profileKv, K_MEMORY, JSONArray())

    // ── "never offer to save here" ──────────────────────────────────────────────────────────

    fun neverSaveOn(kind: String): Set<String> = readArray(profileKv, "never_$kind").let { a -> (0 until a.length()).map { a.optString(it) }.toSet() }

    fun setNeverSave(kind: String, site: String, never: Boolean) {
        val cur = neverSaveOn(kind)
        writeArray(profileKv, "never_$kind", JSONArray((if (never) cur + site else cur - site).sorted()))
    }

    // ── logins and cards (sealed) ───────────────────────────────────────────────────────────

    /** (logins, records that could not be opened: kept on disk, not shown). */
    fun logins(): Pair<List<AcLogin>, Int> =
        if (box == null) emptyList<AcLogin>() to 0 else AcSecretsJson.loginsFromJson(readArray(secretKv, K_LOGINS), box)

    /** False when the Android Keystore gave no key: nothing secret is shown or saved (nothing is deleted either). */
    val secretsAvailable: Boolean get() = box != null

    fun saveLogin(l: AcLogin) {
        val (cur, _) = logins()
        val withTime = l.copy(updatedAt = clock())
        val next = if (cur.any { it.id == l.id }) cur.map { if (it.id == l.id) withTime else it } else cur + withTime
        writeLogins(next)
    }

    fun deleteLogin(id: String) {
        // Records that could not be opened stay on disk: only the opened ones are rewritten, the rest are carried over raw.
        writeLogins(logins().first.filterNot { it.id == id })
    }

    fun cards(): Pair<List<AcCard>, Int> =
        if (box == null) emptyList<AcCard>() to 0 else AcSecretsJson.cardsFromJson(readArray(secretKv, K_CARDS), box)

    fun saveCard(c: AcCard) {
        val (cur, _) = cards()
        val withTime = c.copy(updatedAt = clock())
        writeCards(if (cur.any { it.id == c.id }) cur.map { if (it.id == c.id) withTime else it } else cur + withTime)
    }

    fun deleteCard(id: String) = writeCards(cards().first.filterNot { it.id == id })

    /** Cloud Vault gave [v] (the user picked its autofill dataset): vault wins over the local entry. */
    fun mergeVaultLogin(v: AcVaultItem.Login): AcMerge<AcLogin> {
        val (next, m) = AcVaultMerge.login(logins().first, v, clock())
        if (m.changed || m.created) writeLogins(next)
        return m
    }

    fun mergeVaultCard(v: AcVaultItem.Card): AcMerge<AcCard> {
        val (next, m) = AcVaultMerge.card(cards().first, v, clock())
        if (m.changed || m.created) writeCards(next)
        return m
    }

    /** Logins offered on [hostOrUrl]: the same registrable domain only, newest first. */
    fun loginsFor(hostOrUrl: String): List<AcLogin> =
        logins().first.filter { AcSite.same(it.site, hostOrUrl) }.sortedByDescending { it.updatedAt }

    private fun writeLogins(l: List<AcLogin>) = secretKv.putString(K_LOGINS, carryUnopened(K_LOGINS, AcSecretsJson.loginsToJson(l, requireBox()), l.map { it.id }.toSet()).toString())
    private fun writeCards(c: List<AcCard>) = secretKv.putString(K_CARDS, carryUnopened(K_CARDS, AcSecretsJson.cardsToJson(c, requireBox()), c.map { it.id }.toSet()).toString())

    /** Records of the stored array whose secret did not open (and so are not in [openedIds]) are kept verbatim. */
    private fun carryUnopened(key: String, fresh: JSONArray, openedIds: Set<String>): JSONArray {
        val old = readArray(secretKv, key)
        val keep = (0 until old.length()).mapNotNull { old.optJSONObject(it) }
        val b = requireBox()
        val unopened = keep.filter { o ->
            val id = o.optString("id")
            id.isNotBlank() && id !in openedIds && when (key) {
                K_LOGINS -> b.open(o.optString("pw"), AcSecretsJson.aad("login", id, "pw")) == null
                else -> b.open(o.optString("num"), AcSecretsJson.aad("card", id, "num")) == null
            }
        }
        // an id deleted on purpose opened fine, so it is not carried; only the unreadable ones are
        return fresh.also { f -> unopened.forEach { f.put(it) } }
    }

    private fun requireBox(): AcSealedBox = box ?: error("the secret store has no key: Android Keystore is unavailable")

    // ── imports (once, never destructive) ───────────────────────────────────────────────────

    /** Import the old browser profile once (flagged); returns the profile added, or null. */
    fun importLegacyOnce(legacy: BrowserProfile): AcProfile? {
        if (profileKv.getString(K_LEGACY_DONE) != null) return null
        val p = AcImport.fromLegacy(legacy) ?: return null
        profileKv.putString(K_LEGACY_DONE, clock().toString())
        saveProfiles(profiles() + p.copy(isDefault = false))
        return p
    }

    /** Import Cloud Account profiles by explicit user action: those not already imported (by label + source id) are added. */
    fun importAccount(list: List<AutofillProfile>): Int {
        val cur = profiles()
        val have = cur.filter { it.source == "account" }.map { it.label }.toSet()
        val add = list.filterNot { it.isEmpty }.map { AcImport.fromAccount(it) }.filter { it.label !in have }
        if (add.isNotEmpty()) saveProfiles(cur + add.map { it.copy(isDefault = false) })
        return add.size
    }

    // ── json plumbing: never overwrite what cannot be read ──────────────────────────────────

    private fun readArray(kv: AcKv, key: String): JSONArray {
        val raw = kv.getString(key) ?: return JSONArray()
        return runCatching { JSONArray(raw) }.getOrElse { backup(kv, key, raw); JSONArray() }
    }

    private fun readObject(kv: AcKv, key: String): JSONObject {
        val raw = kv.getString(key) ?: return JSONObject()
        return runCatching { JSONObject(raw) }.getOrElse { backup(kv, key, raw); JSONObject() }
    }

    private fun writeArray(kv: AcKv, key: String, a: JSONArray) = kv.putString(key, a.toString())

    private fun backup(kv: AcKv, key: String, raw: String) {
        val k = "bak_$key"
        if (kv.getString(k) == null) kv.putString(k, raw)
    }

    companion object {
        const val SCHEMA = 1
        const val K_SCHEMA = "schema"
        const val K_PROFILES = "profiles"
        const val K_MEMORY = "memory"
        const val K_LAST_PROFILE = "last_profile"
        const val K_LOGINS = "logins"
        const val K_CARDS = "cards"
        const val K_LEGACY_DONE = "legacy_imported"

        /**
         * v0 -> v1: the stores are born. A v0 store may already hold profiles / memory written before the schema key
         * existed; step 0 only STAMPS them (every reader tolerates missing fields), it deletes nothing.
         */
        val MIGRATIONS = listOf(AcMigration(0, "stamp the first schema") { _ -> })
    }
}

/** Importers into the profile model. Pure: JVM-tested. */
object AcImport {
    /** The old browser profile (identity + addresses) as one profile; null when it holds nothing. Cards (metadata only, no number) are not migrated. */
    fun fromLegacy(b: BrowserProfile): AcProfile? {
        if (b.identity.present.not() && b.addresses.isEmpty()) return null
        val i = b.identity
        val names = buildList {
            if (i.displayName.isNotBlank()) add(AcName(given = i.first, middle = i.middle, family = i.last, full = i.full, isDefault = true))
            b.addresses.filter { it.first.isNotBlank() || it.last.isNotBlank() || it.full.isNotBlank() }.forEach { a ->
                val n = AcName(given = a.first, family = a.last, full = a.full)
                if (none { x -> x.fullName().equals(n.fullName(), ignoreCase = true) }) add(n)
            }
        }
        val phones = (listOf(i.phone) + b.addresses.map { it.phone }).filter { it.isNotBlank() }.distinct().map { AcPhone(value = it) }
        val emails = (listOf(i.email) + b.addresses.map { it.email }).filter { it.isNotBlank() }.distinct().map { AcEmail(value = it) }
        val addrs = b.addresses.filter { it.street.isNotBlank() || it.city.isNotBlank() || it.zip.isNotBlank() }.map {
            AcAddress(label = it.label.ifBlank { "home" }, street = it.street, number = it.number, line2 = it.apartment, city = it.city,
                postal = it.zip, region = it.state, country = it.country)
        }
        return AcProfile(label = "Imported", names = names, addresses = addrs, phones = phones, emails = emails,
            organisations = listOf(i.company).filter { it.isNotBlank() }, source = "legacy").normalized()
    }

    /** A Cloud Account (autofill SOT) profile: its two family names join into one, its typed addresses keep their type as label. */
    fun fromAccount(p: AutofillProfile): AcProfile {
        val name = AcName(given = p.get("given_name"), middle = p.get("additional_name"), family = p.familyName, full = p.get("display_name"), isDefault = true)
        val addrs = p.addresses.filterNot { it.isEmpty }.map { a ->
            fun f(k: String) = a.fields[k].orEmpty()
            AcAddress(label = a.title, street = f("street"), number = f("house_number"),
                line2 = listOf(f("floor_door"), f("complement")).filter { it.isNotBlank() }.joinToString(", "),
                city = f("city"), postal = f("postal_code"), region = f("state"), country = f("country"), isDefault = a.isDefault)
        }
        val dates = p.get("bday").takeIf { it.isNotBlank() }?.let { listOf(AcDate(kind = "birth", value = it)) }.orEmpty()
        return AcProfile(label = p.title, isDefault = p.isDefault,
            names = listOf(name).filterNot { it.isEmpty },
            addresses = addrs,
            phones = p.contacts(ContactKind.TEL).filter { it.value.isNotBlank() }.map { AcPhone(label = it.type.ifBlank { "mobile" }, value = it.value, isDefault = it.isDefault) },
            emails = p.contacts(ContactKind.EMAIL).filter { it.value.isNotBlank() }.map { AcEmail(label = it.type.ifBlank { "personal" }, value = it.value, isDefault = it.isDefault) },
            dates = dates, organisations = listOf(p.get("organization")).filter { it.isNotBlank() }, source = "account").normalized()
    }
}
