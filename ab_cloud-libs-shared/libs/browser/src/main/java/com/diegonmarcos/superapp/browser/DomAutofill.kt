package com.diegonmarcos.superapp.browser

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.compose.runtime.mutableStateOf
import com.diegonmarcos.superapp.autofill.AddressType
import com.diegonmarcos.superapp.autofill.AutofillAddress
import com.diegonmarcos.superapp.autofill.AutofillCandidates
import com.diegonmarcos.superapp.autofill.AutofillContact
import com.diegonmarcos.superapp.autofill.AutofillProfile
import com.diegonmarcos.superapp.autofill.AutofillSotClient
import com.diegonmarcos.superapp.autofill.ContactKind
import com.diegonmarcos.superapp.autofill.Fields
import com.diegonmarcos.superapp.autofill.FillTarget
import com.diegonmarcos.superapp.autofill.SiteRule
import com.diegonmarcos.superapp.autofill.Snippet
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/** One fillable field of a form, as the page engine reported it: never a value. */
data class PlanField(val id: Int, val key: String, val literal: String = "")

/** What the bottom chip shows while a classified contact/address (or snippet) field has focus. */
data class AutofillChip(
    val text: String,
    /** The profile × address combinations the picker offers, the site's default first. */
    val targets: List<FillTarget>,
    val fields: List<PlanField>,
    val incognito: Boolean,
    /** A free-text box: [snippets] are offered instead of profile data. */
    val snippets: List<Snippet> = emptyList(),
)

/** The user-confirmed "save this address" offer after an address form was submitted. */
data class SaveOffer(val text: String, val profileId: Long?, val profile: AutofillProfile, val address: AutofillAddress)

/**
 * Tier 2 of the fleet autofill, pure half (a0_docs/eng-specs/autofill-3-tier.md): what the
 * chip says, which value each field gets, what a submitted form proposes to save.
 */
object DomAutofillPlan {
    /** The chip's words. Incognito names no profile (no prompt — the user taps to choose); null = no chip. */
    fun chipText(kind: String, targets: List<FillTarget>, incognito: Boolean, snippets: Int = 0): String? {
        if (kind == "snippet") return if (snippets == 0) null else "Insert snippet ▾"
        if (targets.isEmpty()) return null
        if (incognito) return "Autofill…"
        val what = if (kind == "address") "address" else "contact"
        val more = if (targets.size > 1) " ▾" else ""
        return "Fill $what: ${targets.first().title}$more"
    }

    /** field id → value from [t] (or the rule's literal). A secret key is never given a value, whatever asked. */
    fun values(fields: List<PlanField>, t: FillTarget): Map<Int, String> {
        val coSeparate = fields.any { it.key == "x-co" }
        return fields.mapNotNull { f ->
            if (Fields.isSecretToken(f.key) || f.key == Fields.SNIPPET) return@mapNotNull null
            val v = f.literal.ifBlank { if (f.key in Fields.ALL) t.value(f.key, coSeparate) else "" }
            v.takeIf { it.isNotEmpty() }?.let { f.id to it }
        }.toMap()
    }

    /** A snippet into the one free-text field the user asked for. */
    fun snippetValues(fields: List<PlanField>, s: Snippet): Map<Int, String> =
        fields.filter { it.key == Fields.SNIPPET }.take(1).associate { it.id to s.text }

    fun fields(a: JSONArray?): List<PlanField> = if (a == null) emptyList() else (0 until a.length()).mapNotNull { i ->
        a.optJSONObject(i)?.let { PlanField(it.optInt("id", -1), it.optString("key"), it.optString("literal")) }
    }.filter { it.id >= 0 && it.key.isNotBlank() }

    /** Picker order on [host]: the rule's / country's / default profile first, then each profile's addresses. */
    fun targets(host: String, keys: List<String>, rules: List<SiteRule>, profiles: List<AutofillProfile>): List<FillTarget> =
        AutofillCandidates.targets(keys, AutofillCandidates.ordered(host, rules, profiles))

    /**
     * What a submitted address form proposes, or null when it is not an address or a profile already holds
     * that place. The address goes under [into] (the site's default profile) when there is one; with no
     * profile at all it becomes a new profile carrying the form's name, email and phone.
     */
    fun proposal(submitted: Map<String, String>, existing: List<AutofillProfile>, into: AutofillProfile?): SaveOffer? {
        val f = HashMap<String, String>()
        submitted.forEach { (k, v) -> if (!Fields.isSecretToken(k) && v.isNotBlank()) f[k] = v.trim() }
        val street = f["x-street"] ?: f["address-line1"] ?: f["street-address"]?.lines()?.firstOrNull()?.trim()
        val line2 = f["address-line2"] ?: f["street-address"]?.lines()?.drop(1)?.joinToString(", ")?.ifBlank { null }
        val a = AutofillAddress(type = AddressType.HOME, fields = mapOf(
            "street" to street.orEmpty(), "house_number" to f["x-house-number"].orEmpty(), "floor_door" to f["x-floor-door"].orEmpty(),
            "complement" to (f["x-complement"] ?: line2).orEmpty(), "co_line" to f["x-co"].orEmpty().removePrefix("c/o ").trim(),
            "neighborhood" to f["address-level3"].orEmpty(), "postal_code" to f["postal-code"].orEmpty(), "city" to f["address-level2"].orEmpty(),
            "state" to f["address-level1"].orEmpty(), "country" to f["country"].orEmpty(),
        ).filterValues { it.isNotBlank() })
        if (a["street"].isEmpty() && a["postal_code"].isEmpty()) return null
        if (existing.any { p -> p.addresses.any { it.samePlace(a) } }) return null
        if (into != null) return SaveOffer("Save this address to ${into.title} in Cloud Account?", into.id, into, a)
        val name = f["name"].orEmpty()
        val given = f["given-name"] ?: name.substringBefore(' ').ifBlank { null }
        val family = f["family-name"] ?: f["x-family-name-1"] ?: name.substringAfter(' ', "").ifBlank { null }
        val p = AutofillProfile(label = a["city"].ifBlank { "Saved" }, isDefault = existing.isEmpty(), fields = mapOf(
            "given_name" to given.orEmpty(), "family_name" to family.orEmpty(), "family_name2" to f["x-family-name-2"].orEmpty(),
            "organization" to f["organization"].orEmpty()).filterValues { it.isNotBlank() },
            contacts = listOfNotNull(f["email"]?.let { AutofillContact(kind = ContactKind.EMAIL, value = it, isDefault = true) },
                f["tel"]?.let { AutofillContact(kind = ContactKind.TEL, value = it, isDefault = true) }))
        return SaveOffer("Save this address to Cloud Account?", null, p, a.copy(isDefault = true))
    }

    /** The legacy browser profile (imports from before the SOT) as SOT profiles, so it still fills. */
    fun legacy(b: BrowserProfile): List<AutofillProfile> {
        if (b.isEmpty) return emptyList()
        val i = b.identity
        fun c(kind: String, v: String) = if (v.isBlank()) null else AutofillContact(kind = kind, value = v, isDefault = true)
        return listOf(AutofillProfile(label = "Browser profile", fields = mapOf("given_name" to i.first, "additional_name" to i.middle,
            "family_name" to i.last, "display_name" to i.full, "organization" to i.company).filterValues { it.isNotBlank() },
            contacts = listOfNotNull(c(ContactKind.EMAIL, i.email.ifBlank { b.addresses.firstOrNull()?.email.orEmpty() }),
                c(ContactKind.TEL, i.phone.ifBlank { b.addresses.firstOrNull()?.phone.orEmpty() }), c(ContactKind.URL, i.website)),
            addresses = b.addresses.mapIndexed { n, a -> AutofillAddress(label = a.label, isDefault = n == 0, fields = mapOf(
                "street" to a.street, "house_number" to a.number, "complement" to a.apartment, "city" to a.city, "state" to a.state,
                "postal_code" to a.zip, "country" to a.country).filterValues { it.isNotBlank() }) }))
    }

    /** The engine's config for one host: incognito and the matching rules only (no other site's rules reach a page). */
    fun config(host: String, rules: List<SiteRule>, incognito: Boolean): JSONObject = JSONObject()
        .put("incognito", incognito)
        .put("rules", JSONArray(AutofillCandidates.rulesFor(host, rules).filterNot { it.isProfileRule }.map {
            JSONObject().put("form", it.formSelector).put("field", it.fieldSelector).put("key", it.fieldKey).put("value", it.literalValue)
        }))
}

/**
 * Tier 2, the host half: injects assets/browser/autofill_engine.js into every page, turns the
 * engine's focus reports into [chip], fills on the user's tap, and turns an address form's submit
 * into a [save] offer the user confirms. Reads the Cloud Account SOT (AUTOFILL_PROFILE_READ) off the
 * main thread; writes only a confirmed new address (AUTOFILL_PROFILE_WRITE). Never logs a value.
 *
 * Cloud Vault is never second-guessed: the engine refuses secrets and login identities, and the
 * WebView keeps exposing its fields to the Android Autofill framework (importantForAutofill).
 */
class DomAutofill(context: Context, private val enabled: () -> Boolean, private val offerSave: () -> Boolean) {
    private val ctx = context.applicationContext
    val chip = mutableStateOf<AutofillChip?>(null)
    val save = mutableStateOf<SaveOffer?>(null)
    // Category 3 (autofill data) lives in the Cloud Account SOT; here it is only a short in-memory copy,
    // never in WebView storage, so no cookie / site-data clear can touch it (and none of those touch this).
    @Volatile private var profiles: List<AutofillProfile> = emptyList()
    @Volatile private var rules: List<SiteRule> = emptyList()
    @Volatile private var snippets: List<Snippet> = emptyList()
    @Volatile private var loadedAt = 0L
    @Volatile private var incognito = false
    @Volatile private var host = ""
    private var page: WebView? = null
    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    /** Registers the page bridge on [wv] (before it loads anything). */
    fun attach(wv: WebView) {
        wv.addJavascriptInterface(Bridge(wv), JS_BRIDGE)
    }

    /** onPageFinished: refresh the SOT (at most every [TTL_MS]) and install the engine for [url]'s host. */
    fun inject(wv: WebView, url: String?, privateTab: Boolean) {
        chip.value = null; save.value = null
        page = wv; incognito = privateTab
        if (!enabled() || url == null || !(url.startsWith("https://") || url.startsWith("http://"))) return
        val h = BrowserSitePolicy.hostOf(url)
        host = h
        io.execute {
            if (System.currentTimeMillis() - loadedAt > TTL_MS) reload()
            val cfg = DomAutofillPlan.config(h, rules, privateTab).toString()
            ui.post {
                if (page !== wv) return@post
                runCatching { wv.evaluateJavascript(BrowserPageActions.script(ctx, "autofill_engine").replace("__CFG__", cfg), null) }
            }
        }
    }

    private fun reload() {
        val sot = runCatching { AutofillSotClient.profiles(ctx) }.getOrDefault(emptyList()).filterNot { it.isEmpty }
        profiles = sot.ifEmpty { runCatching { DomAutofillPlan.legacy(BrowserProfileStore(ctx).load()) }.getOrDefault(emptyList()) }
        rules = runCatching { AutofillSotClient.rules(ctx) }.getOrDefault(emptyList())
        snippets = runCatching { AutofillSotClient.snippets(ctx) }.getOrDefault(emptyList())
        loadedAt = System.currentTimeMillis()
    }

    /** Drops the in-memory copy (Configs ▸ Autofill data ▸ forget): the next page reads the SOT again. */
    fun forgetCache() { profiles = emptyList(); rules = emptyList(); snippets = emptyList(); loadedAt = 0L; chip.value = null; save.value = null }

    /** The user tapped the chip (or picked [target] from its list): fill the block. */
    fun fill(target: FillTarget) = send(chip.value?.let { DomAutofillPlan.values(it.fields, target) })

    /** The user picked [s] for the free-text field that has focus. */
    fun insert(s: Snippet) = send(chip.value?.let { DomAutofillPlan.snippetValues(it.fields, s) })

    private fun send(plan: Map<Int, String>?) {
        val wv = page ?: return
        chip.value = null
        if (plan.isNullOrEmpty()) return
        val values = JSONObject().apply { plan.forEach { (id, v) -> put(id.toString(), v) } }
        // frameworkFilled() first: a field Vault filled since the focus report is deferred, never overwritten.
        wv.evaluateJavascript("(function(){var a=window.__cloudAutofill;if(!a)return'{}';a.frameworkFilled();return JSON.stringify(a.fill($values));})()", null)
    }

    fun dismiss() { chip.value = null }

    fun confirmSave(yes: Boolean) {
        val offer = save.value ?: return
        save.value = null
        if (!yes || incognito) return
        io.execute {
            AutofillSotClient.addAddress(ctx, offer.profileId, offer.profile, offer.address)
            loadedAt = 0L   // next page reloads the SOT
        }
    }

    private inner class Bridge(private val wv: WebView) {
        @JavascriptInterface
        fun focus(json: String?) {
            val o = runCatching { JSONObject(json ?: return) }.getOrNull() ?: return
            ui.post {
                if (page !== wv || !enabled()) return@post
                val fields = DomAutofillPlan.fields(o.optJSONArray("fields"))
                val kind = o.optString("kind")
                val targets = if (kind == "snippet") emptyList() else DomAutofillPlan.targets(host, fields.map { it.key }, rules, profiles)
                val snips = if (kind == "snippet") snippets.filter { it.text.isNotBlank() } else emptyList()
                val text = DomAutofillPlan.chipText(kind, targets, incognito, snips.size)
                chip.value = if (text == null || fields.isEmpty()) null else AutofillChip(text, targets, fields, incognito, snips)
            }
        }

        @JavascriptInterface
        fun none(json: String?) { ui.post { if (page === wv) chip.value = null } }

        @JavascriptInterface
        fun submitted(json: String?) {
            val o = runCatching { JSONObject(json ?: return).optJSONObject("fields") }.getOrNull() ?: return
            val m = o.keys().asSequence().associateWith { o.optString(it) }
            ui.post {
                if (page !== wv || incognito || !enabled() || !offerSave() || !AutofillSotClient.installed(ctx)) return@post
                val into = AutofillCandidates.defaultProfileFor(host, rules, profiles.filter { it.id > 0 })
                save.value = DomAutofillPlan.proposal(m, profiles, into) ?: return@post
            }
        }
    }

    companion object {
        const val JS_BRIDGE = "CloudAutofill"
        private const val TTL_MS = 15_000L
    }
}
