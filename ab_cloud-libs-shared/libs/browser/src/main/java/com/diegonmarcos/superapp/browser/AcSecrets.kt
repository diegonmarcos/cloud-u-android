package com.diegonmarcos.superapp.browser

import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Where a saved secret's current value came from: this phone's own store, or Cloud Vault (which wins). */
enum class AcSource(val wire: String, val label: String) {
    LOCAL("local", "local"),
    VAULT("vault", "vault");
    companion object { fun of(s: String?) = entries.firstOrNull { it.wire == s } ?: LOCAL }
}

/** A saved sign-in. toString never prints the password. */
data class AcLogin(
    val id: String = AcIds.next(),
    /** The site it belongs to (see [AcSite.registrable]). */
    val site: String,
    val username: String,
    val password: String,
    val label: String = "",
    val source: AcSource = AcSource.LOCAL,
    val updatedAt: Long = 0,
) {
    override fun toString() = "AcLogin($id, $site, $REDACTED)"
}

/** A saved card. The number and the code are secrets; toString prints neither. */
data class AcCard(
    val id: String = AcIds.next(),
    val label: String = "",
    val holder: String = "",
    val number: String,
    val expMonth: String = "",
    val expYear: String = "",
    /** Optional: stored only when the user typed it. */
    val cvc: String = "",
    val source: AcSource = AcSource.LOCAL,
    val updatedAt: Long = 0,
) {
    val digits get() = number.filter { it.isDigit() }
    val last4 get() = digits.takeLast(4)
    fun brand(): String = when {
        digits.startsWith("4") -> "Visa"
        Regex("^(5[1-5]|2[2-7])").containsMatchIn(digits) -> "Mastercard"
        Regex("^3[47]").containsMatchIn(digits) -> "Amex"
        digits.startsWith("6") -> "Discover"
        else -> "Card"
    }
    fun title(): String = label.ifBlank { brand() } + " •••• " + last4
    override fun toString() = "AcCard($id, $REDACTED)"
}

/** Registrable-domain matching ("same site only"): a lookalike domain never matches. Pure. */
object AcSite {
    /** Public suffixes with two labels that this small list knows; the real list is Cloud Vault's. */
    private val TWO_LABEL = setOf("co.uk", "org.uk", "ac.uk", "gov.uk", "com.br", "com.au", "net.au", "org.au", "co.jp", "co.nz", "com.mx",
        "com.ar", "com.es", "com.tr", "co.za", "co.in", "com.cn", "com.hk", "com.sg", "co.kr", "com.pt", "com.co")

    /** eTLD+1 of a host or url. An IP address or a single label is returned as it is. */
    fun registrable(hostOrUrl: String): String {
        val h = AcSiteMemory.originKey(hostOrUrl)
        if (h.isEmpty() || Regex("^[0-9.]+$").matches(h) || h.contains(':') || !h.contains('.')) return h
        val parts = h.split('.')
        val two = parts.takeLast(2).joinToString(".")
        return if (two in TWO_LABEL && parts.size >= 3) parts.takeLast(3).joinToString(".") else two
    }

    fun same(a: String, b: String): Boolean = registrable(a).let { it.isNotEmpty() && it == registrable(b) }
}

/** What Cloud Vault handed over for a site or a card (through Android's autofill dataset the user picked). */
sealed class AcVaultItem {
    data class Login(val site: String, val username: String, val password: String) : AcVaultItem() {
        override fun toString() = "Login($site, $REDACTED)"
    }
    data class Card(val number: String, val holder: String = "", val expMonth: String = "", val expYear: String = "", val cvc: String = "") : AcVaultItem() {
        override fun toString() = "Card($REDACTED)"
    }
}

/** The outcome of one vault-wins merge. [changed] = the stored values differ from before. */
data class AcMerge<T>(val entry: T, val changed: Boolean, val created: Boolean)

/**
 * #900 VAULT WINS. The browser fills from its own store first, then Cloud Vault; when Cloud Vault has the
 * entry its value OVERWRITES the local one (source becomes vault), and a vault entry the local store never
 * had is kept as a local copy marked vault. A local entry the vault knows nothing about stays untouched.
 * Pure: JVM-tested.
 */
object AcVaultMerge {

    /** The local login the vault item is about: same site and same username; a lone local login of the site when the vault gave no username. */
    fun matchLogin(local: List<AcLogin>, v: AcVaultItem.Login): AcLogin? {
        val ofSite = local.filter { AcSite.same(it.site, v.site) }
        if (v.username.isBlank()) return ofSite.singleOrNull()
        return ofSite.firstOrNull { it.username.equals(v.username, ignoreCase = true) }
    }

    fun matchCard(local: List<AcCard>, v: AcVaultItem.Card): AcCard? = local.firstOrNull { it.digits == v.number.filter { c -> c.isDigit() } }

    fun login(local: List<AcLogin>, v: AcVaultItem.Login, now: Long): Pair<List<AcLogin>, AcMerge<AcLogin>> {
        val hit = matchLogin(local, v)
        if (hit == null) {
            val created = AcLogin(site = AcSite.registrable(v.site), username = v.username, password = v.password, source = AcSource.VAULT, updatedAt = now)
            return (local + created) to AcMerge(created, changed = true, created = true)
        }
        val same = hit.password == v.password && (v.username.isBlank() || hit.username == v.username)
        val merged = hit.copy(username = v.username.ifBlank { hit.username }, password = v.password, source = AcSource.VAULT,
            updatedAt = if (same && hit.source == AcSource.VAULT) hit.updatedAt else now)
        return local.map { if (it.id == hit.id) merged else it } to AcMerge(merged, changed = !same, created = false)
    }

    fun card(local: List<AcCard>, v: AcVaultItem.Card, now: Long): Pair<List<AcCard>, AcMerge<AcCard>> {
        val hit = matchCard(local, v)
        if (hit == null) {
            val created = AcCard(holder = v.holder, number = v.number, expMonth = v.expMonth, expYear = v.expYear, cvc = v.cvc, source = AcSource.VAULT, updatedAt = now)
            return (local + created) to AcMerge(created, changed = true, created = true)
        }
        val merged = hit.copy(holder = v.holder.ifBlank { hit.holder }, expMonth = v.expMonth.ifBlank { hit.expMonth },
            expYear = v.expYear.ifBlank { hit.expYear }, cvc = v.cvc.ifBlank { hit.cvc }, source = AcSource.VAULT, updatedAt = now)
        val changed = merged.holder != hit.holder || merged.expMonth != hit.expMonth || merged.expYear != hit.expYear || merged.cvc != hit.cvc
        return local.map { if (it.id == hit.id) merged else it } to AcMerge(merged, changed, created = false)
    }
}

/** Why Cloud Vault cannot be asked right now, or that it can. */
enum class AcVaultState(val usable: Boolean, val reason: String) {
    OFF(false, "Cloud Vault is switched off in Configs > Autocomplete > Use Cloud Vault"),
    NOT_INSTALLED(false, "Cloud Vault is not installed"),
    NOT_SERVICE(false, "Cloud Vault is not the autofill service: Configs > Autocomplete > Cloud Vault > Make it the autofill service"),
    NO_FRAMEWORK(false, "Android autofill is off on this phone"),
    READY(true, "Cloud Vault will be asked after the local store");

    companion object {
        /** Pure decision from the facts (see VaultAutofill.status): off wins, then installed, then framework, then service. */
        fun of(useVault: Boolean, installed: Boolean, frameworkEnabled: Boolean, isService: Boolean): AcVaultState = when {
            !useVault -> OFF
            !installed -> NOT_INSTALLED
            !frameworkEnabled -> NO_FRAMEWORK
            !isService -> NOT_SERVICE
            else -> READY
        }
    }
}

/**
 * Record-bound authenticated encryption for a secret value: AES-256-GCM, a fresh 12-byte IV per seal, and the
 * record's identity ([aad]: kind + id + field) authenticated, so a sealed blob copied onto another record fails to
 * open instead of silently decrypting there. Envelope: `ac1.<base64 iv>.<base64 ciphertext+tag>`.
 * The key comes from an [AcKeyProvider]: the Android Keystore on the phone, fixed bytes in the JVM tests.
 */
class AcSealedBox(private val keys: AcKeyProvider) {
    private val rng = SecureRandom()

    fun seal(plain: String, aad: String): String {
        if (plain.isEmpty()) return ""
        val iv = ByteArray(IV_BYTES).also { rng.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, keys.key(), GCMParameterSpec(TAG_BITS, iv))
        c.updateAAD(aad.toByteArray(Charsets.UTF_8))
        val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        val b64 = Base64.getEncoder().withoutPadding()
        return "$PREFIX${b64.encodeToString(iv)}.${b64.encodeToString(ct)}"
    }

    /** The plaintext, or null when the envelope is damaged, the key is wrong or [aad] is not the one it was sealed with. */
    fun open(sealed: String, aad: String): String? {
        if (sealed.isEmpty()) return ""
        if (!sealed.startsWith(PREFIX)) return null
        return runCatching {
            val parts = sealed.removePrefix(PREFIX).split('.')
            val dec = Base64.getDecoder()
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, keys.key(), GCMParameterSpec(TAG_BITS, dec.decode(parts[0])))
            c.updateAAD(aad.toByteArray(Charsets.UTF_8))
            String(c.doFinal(dec.decode(parts[1])), Charsets.UTF_8)
        }.getOrNull()
    }

    companion object {
        const val PREFIX = "ac1."
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
        fun isSealed(s: String) = s.startsWith(PREFIX)
    }
}

/** Where the AES key comes from. */
fun interface AcKeyProvider {
    fun key(): javax.crypto.SecretKey

    companion object {
        /** A fixed 32-byte key: for tests only. */
        fun fixed(bytes: ByteArray): AcKeyProvider = AcKeyProvider { SecretKeySpec(bytes, "AES") }
    }
}

/**
 * The secrets at rest: [AcLogin]s and [AcCard]s as JSON where every secret field (password, card number, code)
 * is an [AcSealedBox] envelope bound to its record, never plaintext. Opening is all-or-nothing per record: a
 * record that cannot be opened is skipped and counted, never deleted (the key may be back after a restore).
 */
object AcSecretsJson {
    fun aad(kind: String, id: String, field: String) = "cb-ac|$kind|$id|$field"

    fun loginsToJson(list: List<AcLogin>, box: AcSealedBox): JSONArray = JSONArray().also { a -> list.forEach {
        a.put(JSONObject().put("id", it.id).put("site", it.site).put("user", it.username).put("label", it.label).put("source", it.source.wire)
            .put("updated", it.updatedAt).put("pw", box.seal(it.password, aad("login", it.id, "pw")))) } }

    /** (logins, how many records could not be opened). */
    fun loginsFromJson(a: JSONArray?, box: AcSealedBox): Pair<List<AcLogin>, Int> {
        var bad = 0
        val out = if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it) }.mapNotNull { o ->
            val id = o.optString("id").ifBlank { return@mapNotNull null }
            val pw = box.open(o.optString("pw"), aad("login", id, "pw")) ?: run { bad++; return@mapNotNull null }
            AcLogin(id, o.optString("site"), o.optString("user"), pw, o.optString("label"), AcSource.of(o.optString("source")), o.optLong("updated"))
        }
        return out to bad
    }

    fun cardsToJson(list: List<AcCard>, box: AcSealedBox): JSONArray = JSONArray().also { a -> list.forEach {
        a.put(JSONObject().put("id", it.id).put("label", it.label).put("holder", it.holder).put("last4", it.last4).put("exp_m", it.expMonth).put("exp_y", it.expYear)
            .put("source", it.source.wire).put("updated", it.updatedAt)
            .put("num", box.seal(it.number, aad("card", it.id, "num"))).put("cvc", box.seal(it.cvc, aad("card", it.id, "cvc")))) } }

    fun cardsFromJson(a: JSONArray?, box: AcSealedBox): Pair<List<AcCard>, Int> {
        var bad = 0
        val out = if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it) }.mapNotNull { o ->
            val id = o.optString("id").ifBlank { return@mapNotNull null }
            val num = box.open(o.optString("num"), aad("card", id, "num")) ?: run { bad++; return@mapNotNull null }
            val cvc = box.open(o.optString("cvc"), aad("card", id, "cvc")) ?: run { bad++; return@mapNotNull null }
            AcCard(id, o.optString("label"), o.optString("holder"), num, o.optString("exp_m"), o.optString("exp_y"), cvc,
                AcSource.of(o.optString("source")), o.optLong("updated"))
        }
        return out to bad
    }
}

/** How a saved card's parts are written into a checkout's boxes. Pure: JVM-tested. */
object AcCardFormat {
    /** The text for field [token] (null = nothing to put there). [sig] is the field's own signals (its placeholder hints the format). */
    fun value(c: AcCard, token: String?, sig: AcFieldSignals = AcFieldSignals()): String? = when (token) {
        AcTokens.CC_NUMBER -> c.digits.chunked(4).joinToString(" ").takeIf { c.digits.isNotEmpty() }
        AcTokens.CC_NAME -> c.holder.takeIf { it.isNotBlank() }
        AcTokens.CC_EXP_MONTH -> c.expMonth.takeIf { it.isNotBlank() }
        AcTokens.CC_EXP_YEAR -> c.expYear.takeIf { it.isNotBlank() }?.let { if (sig.maxLength == 2 || Regex("(?i)^y{2}$").matches(sig.placeholder.trim())) it.takeLast(2) else it }
        AcTokens.CC_EXP -> if (c.expMonth.isBlank() || c.expYear.isBlank()) null else {
            val long = sig.placeholder.lowercase().contains("yyyy") || sig.maxLength in 7..9
            val sep = if (sig.placeholder.contains(" / ")) " / " else "/"
            c.expMonth.padStart(2, '0') + sep + (if (long) c.expYear else c.expYear.takeLast(2))
        }
        AcTokens.CC_CSC -> c.cvc.takeIf { it.isNotBlank() }
        AcTokens.CC_TYPE -> c.brand()
        else -> null
    }
}

/** When a saved secret may be shown or filled without asking again. Pure: JVM-tested. */
class AcUnlockWindow(private val graceMs: Long = 60_000L) {
    private var at = Long.MIN_VALUE
    fun open(nowMs: Long) { at = nowMs }
    fun isOpen(nowMs: Long): Boolean = at != Long.MIN_VALUE && nowMs - at in 0 until graceMs
    fun lock() { at = Long.MIN_VALUE }
}

/** One step of a login or card fill. */
enum class AcFillStep { LOCAL, VAULT }

/**
 * "Try fill, then let Cloud Vault overwrite it." Pure plan of what a fill does, in order:
 * the local store's entry (when the user picked one) first, then, when Cloud Vault is usable, its
 * autofill request, whose dataset overwrites what the local step put in the fields. Private tabs
 * never ask the vault. Nothing here sees a secret value.
 */
object AcFillOrder {
    fun steps(localPicked: Boolean, vault: AcVaultState, incognito: Boolean = false): List<AcFillStep> = buildList {
        if (localPicked) add(AcFillStep.LOCAL)
        if (vault.usable && !incognito) add(AcFillStep.VAULT)
    }

    /** The value a field ends with: the vault's when it gave one, else the local one. */
    fun finalValue(local: String?, vault: String?): String? = vault?.takeIf { it.isNotEmpty() } ?: local
}
