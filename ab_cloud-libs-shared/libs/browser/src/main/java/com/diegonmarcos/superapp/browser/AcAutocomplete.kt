package com.diegonmarcos.superapp.browser

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.compose.runtime.mutableStateOf
import androidx.fragment.app.FragmentActivity
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/** One suggestion on the fill bar. [badge] says where the value comes from ("local", "vault", "this site"). */
data class AcChip(val id: String, val text: String, val badge: String? = null)

/** The fill bar above the keyboard: chips, and a note (why Cloud Vault was not asked, say). */
data class AcBar(val chips: List<AcChip>, val note: String? = null)

/** The chooser: which profile, and which value per kind when a profile has several. */
class AcChooserState(val title: String, val profiles: List<AcProfile>, val plan: AcChoicePlan, val fields: List<AcField>, val initial: AcChoice)

/** The save prompt after a submit: what could be remembered, and the profiles it could go into. */
class AcSaveState(val site: String, val proposals: List<AcProposal>, val profiles: List<AcProfile>, val vaultNote: String?)

/**
 * #900 AUTOCOMPLETE, the host half. Installs assets/browser/ac_engine.js in every page, turns its reports into the
 * fill bar, the profile/value chooser and the save prompt, fills on the user's tap, and keeps the stores ([AcStore]).
 *
 *  - Profiles (many-valued) fill name, address, phone, email, birth date, documents; a chooser asks which profile
 *    and which value per kind when several.
 *  - Passwords and cards come from this phone's encrypted store behind fingerprint / screen lock ([AcUnlock]).
 *    With "Use Cloud Vault" on, Cloud Vault is asked through Android's autofill AFTER the local store, and what it
 *    fills wins over the local copy ([AcVaultMerge]); not installed / not the autofill service / locked degrades to
 *    local-only with the reason on the bar. Cloud Vault exposes no secret over any API (its contract), so the vault's
 *    value reaches the browser only as the autofill dataset the user picked, read back from the filled fields.
 *  - Values typed on a site that are not generic personal data are remembered for that site and form only.
 *
 * Nothing here logs a value. Private tabs offer fills only on an explicit pick and never offer to save.
 */
class AcAutocomplete(
    context: Context,
    private val flag: (String) -> Boolean?,
    private val activity: () -> FragmentActivity?,
    private val vaultPackage: () -> String?,
) {
    private val ctx = context.applicationContext
    val bar = mutableStateOf<AcBar?>(null)
    val chooser = mutableStateOf<AcChooserState?>(null)
    val save = mutableStateOf<AcSaveState?>(null)

    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    @Volatile private var holder: AcAndroid.Holder? = null
    @Volatile private var anyProfile = false
    private var page: WebView? = null
    private var incognito = false
    private var host = ""
    private var form: Form? = null
    private var actions: Map<String, () -> Unit> = emptyMap()
    private var vaultAskedFor: String? = null

    private class FormField(val id: Int, val sig: AcFieldSignals, val cls: AcClass, val fw: Boolean)
    private class Form(val key: String, val host: String, val fields: List<FormField>) {
        fun sigKey(): String = AcSiteMemory.formSignature(key, fields.map { AcFieldSig(it.sig.name, it.sig.id, it.sig.autocomplete, it.sig.label).key() })
    }

    /** True once the user has at least one local profile: the old Cloud Account chip steps aside for it. */
    fun hasLocalProfiles(): Boolean = anyProfile

    val store: AcStore? get() = holder?.store
    val persistent: Boolean get() = holder?.persistent ?: true

    /** Open the stores off the main thread (the first open creates the Keystore keys). */
    fun warm() {
        if (holder != null) return
        io.execute { runCatching { AcAndroid.open(ctx) }.getOrNull()?.let { holder = it; anyProfile = it.store.profiles().isNotEmpty() } }
    }

    fun attach(wv: WebView) { wv.addJavascriptInterface(Bridge(wv), JS_BRIDGE) }

    /** onPageFinished: install the engine for this document. */
    fun inject(wv: WebView, url: String?, privateTab: Boolean) {
        dismiss(); save.value = null; chooser.value = null
        page = wv; incognito = privateTab; form = null; vaultAskedFor = null
        warm()
        if (flag("autofill_enabled") == false || url == null || !(url.startsWith("https://") || url.startsWith("http://"))) return
        host = BrowserSitePolicy.hostOf(url)
        val cfg = JSONObject().put("incognito", privateTab).toString()
        runCatching { wv.evaluateJavascript(BrowserPageActions.script(ctx, "ac_engine").replace("__CFG__", cfg), null) }
    }

    fun dismiss() { bar.value = null; actions = emptyMap() }

    /** A tap on a chip of the bar. */
    fun pick(id: String) { actions[id]?.invoke() }

    // ── focus: build the bar ────────────────────────────────────────────────────────────────

    private fun parseForm(o: JSONObject): Form? {
        val arr = o.optJSONArray("fields") ?: return null
        val raw = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        val sigs = raw.map { AcProposals.signals(it) }
        val classes = AcProposals.loginReclass(sigs.map { AcFieldClassifier.classify(it) }, sigs)
        return Form(o.optString("formKey"), host, raw.mapIndexed { i, r -> FormField(r.optInt("id", -1), sigs[i], classes[i], r.optBoolean("fw")) }.filter { it.id >= 0 })
    }

    private fun onFocus(o: JSONObject) {
        val h = holder ?: return
        if (flag("autofill_enabled") == false) return
        val f = parseForm(o) ?: return
        form = f
        val cur = f.fields.firstOrNull { it.id == o.optInt("field", -1) } ?: return dismiss()
        val built = when (cur.cls.kind) {
            AcKind.LOGIN_USER, AcKind.PASSWORD, AcKind.NEW_PASSWORD -> loginBar(h, f, cur)
            AcKind.CARD -> cardBar(h, f, cur)
            AcKind.PROFILE -> profileBar(h, f)
            AcKind.SITE -> memoryBar(h, f, cur)
            else -> null
        }
        if (built == null) dismiss() else bar.value = built
    }

    private fun vaultState(): AcVaultState {
        val pkg = vaultPackage()
        val st = runCatching { VaultAutofill.status(ctx, pkg) }.getOrNull() ?: JSONObject()
        val isService = !pkg.isNullOrBlank() && VaultAutofill.isVault(st.optString("autofill_service"), pkg)
        return AcVaultState.of(flag("autofill_use_vault") == true, st.optBoolean("installed"), st.optBoolean("framework_enabled"), isService)
    }

    private fun source(s: AcSource) = s.label

    private fun loginBar(h: AcAndroid.Holder, f: Form, cur: FormField): AcBar? {
        val acts = LinkedHashMap<String, () -> Unit>()
        val chips = ArrayList<AcChip>()
        if (cur.cls.kind == AcKind.NEW_PASSWORD) {
            chips += AcChip("gen", "Use a strong password")
            acts["gen"] = { fillNewPassword(f) }
        } else if (h.store.secretsAvailable) {
            h.store.loginsFor(host).take(4).forEach { l ->
                val id = "login:${l.id}"
                chips += AcChip(id, l.username.ifBlank { l.site }, source(l.source))
                acts[id] = { unlocked("Fill saved password") { fillLogin(f, l) } }
            }
        }
        val vs = vaultState()
        var note: String? = null
        if (vs.usable) {
            chips += AcChip("vault", "Cloud Vault", "vault")
            acts["vault"] = { askVault(cur.id) }
            if (chips.size == 1 && vaultAskedFor != f.key.ifBlank { "-" }) { vaultAskedFor = f.key.ifBlank { "-" }; ui.post { askVault(cur.id) } }
        } else if (flag("autofill_use_vault") == true) note = "Cloud Vault: ${vs.reason}"
        if (chips.isEmpty() && note == null) return null
        actions = acts
        return AcBar(chips, note)
    }

    private fun cardBar(h: AcAndroid.Holder, f: Form, cur: FormField): AcBar? {
        val acts = LinkedHashMap<String, () -> Unit>()
        val chips = ArrayList<AcChip>()
        if (h.store.secretsAvailable) h.store.cards().first.take(4).forEach { c ->
            val id = "card:${c.id}"
            chips += AcChip(id, c.title(), source(c.source))
            acts[id] = { unlocked("Fill saved card") { fillCard(f, c) } }
        }
        val vs = vaultState()
        var note: String? = null
        if (vs.usable) {
            chips += AcChip("vault", "Cloud Vault", "vault")
            acts["vault"] = { askVault(cur.id) }
        } else if (flag("autofill_use_vault") == true) note = "Cloud Vault: ${vs.reason}"
        if (chips.isEmpty() && note == null) return null
        actions = acts
        return AcBar(chips, note)
    }

    private fun profileBar(h: AcAndroid.Holder, f: Form): AcBar? {
        val fields = f.fields.filter { it.cls.kind == AcKind.PROFILE && it.cls.token != null }.map { AcField(it.id, it.cls.token!!) }
        if (fields.isEmpty()) return null
        val profiles = h.store.profiles().filterNot { it.isEmpty }
        val tokens = fields.map { it.token }
        val plan = AcFill.plan(profiles, tokens)
        if (plan.profiles.isEmpty()) return null
        val choice = AcFill.defaultChoice(profiles, tokens, h.store.lastProfileFor(AcSite.registrable(host))) ?: return null
        val chosen = profiles.first { it.id == choice.profileId }
        val acts = LinkedHashMap<String, () -> Unit>()
        val chips = ArrayList<AcChip>()
        val needsChooser = !plan.isTrivial()
        if (incognito) {
            chips += AcChip("choose", "Autofill…"); acts["choose"] = { openChooser(profiles, plan, fields, choice) }
        } else {
            chips += AcChip("fill", "Fill: " + chosen.title() + if (needsChooser) " ▾" else "")
            acts["fill"] = { if (needsChooser) openChooser(profiles, plan, fields, choice) else fillProfile(profiles, fields, choice) }
            if (needsChooser) { chips += AcChip("choose", "Choose…"); acts["choose"] = { openChooser(profiles, plan, fields, choice) } }
        }
        actions = acts
        return AcBar(chips)
    }

    private fun memoryBar(h: AcAndroid.Holder, f: Form, cur: FormField): AcBar? {
        if (flag("site_memory") == false) return null
        val key = AcFieldSig(cur.sig.name, cur.sig.id, cur.sig.autocomplete, cur.sig.label).key()
        val hits = AcSiteMemory.suggestions(h.store.memory(), AcSiteMemory.originKey(host), f.sigKey(), key).take(4)
        if (hits.isEmpty()) return null
        val acts = LinkedHashMap<String, () -> Unit>()
        val chips = hits.map { e ->
            val id = "mem:${e.id}"
            acts[id] = { fillOne(cur.id, e.value); h.store.used(e.id); dismiss() }
            AcChip(id, e.value, "this site")
        }
        actions = acts
        return AcBar(chips)
    }

    // ── fills ───────────────────────────────────────────────────────────────────────────────

    private fun js(code: String) { page?.let { wv -> runCatching { wv.evaluateJavascript(code, null) } } }

    private fun fillJs(values: Map<Int, String>, force: Boolean) {
        if (values.isEmpty()) return
        val o = JSONObject().also { j -> values.forEach { (k, v) -> j.put(k.toString(), v) } }
        js("(function(){var a=window.__ac;return a?a.fill($o,$force):'{}';})()")
    }

    private fun fillOne(id: Int, value: String) = fillJs(mapOf(id to value), false)

    private fun unlocked(title: String, then: () -> Unit) {
        val a = activity() ?: return
        AcUnlock.require(a, flag("secrets_require_unlock") != false, title, onOk = then, onDenied = { why -> bar.value = AcBar(emptyList(), why) })
    }

    private fun fillLogin(f: Form, l: AcLogin) {
        val v = HashMap<Int, String>()
        f.fields.forEach { x ->
            when (x.cls.kind) { AcKind.LOGIN_USER -> v[x.id] = l.username; AcKind.PASSWORD -> v[x.id] = l.password; else -> Unit }
        }
        fillJs(v, true)
        dismiss()
    }

    private fun fillNewPassword(f: Form) {
        val pw = AcProposals.generatePassword()
        fillJs(f.fields.filter { it.cls.kind == AcKind.NEW_PASSWORD }.associate { it.id to pw }, true)
        dismiss()
    }

    private fun fillCard(f: Form, c: AcCard) {
        val v = HashMap<Int, String>()
        f.fields.forEach { x ->
            AcCardFormat.value(c, x.cls.token, x.sig)?.let { v[x.id] = it }
        }
        fillJs(v, true)
        dismiss()
    }

    private fun fillProfile(profiles: List<AcProfile>, fields: List<AcField>, choice: AcChoice) {
        fillJs(AcFill.values(profiles, fields, choice), false)
        store?.setLastProfileFor(AcSite.registrable(host), choice.profileId)
        dismiss(); chooser.value = null
    }

    private fun openChooser(profiles: List<AcProfile>, plan: AcChoicePlan, fields: List<AcField>, initial: AcChoice) {
        chooser.value = AcChooserState("Fill from a profile", profiles, plan, fields, initial)
    }

    /** "Autofill this page" (page dropdown): the page's main form, always through the chooser. [done] gets the refusal in words, or null. */
    fun fillPage(done: (String?) -> Unit) {
        val wv = page ?: return done("no page is open")
        wv.evaluateJavascript("(function(){var a=window.__ac;return a?a.pageForm():'{}';})()") { raw ->
            val h = holder
            val o = BrowserPageActions.decode(raw)
            val f = if (o == null || h == null) null else parseForm(o)
            if (f == null || h == null) return@evaluateJavascript done("autofill is not ready on this page")
            form = f
            val fields = f.fields.filter { it.cls.kind == AcKind.PROFILE && it.cls.token != null }.map { AcField(it.id, it.cls.token!!) }
            val profiles = h.store.profiles().filterNot { it.isEmpty }
            val tokens = fields.map { it.token }
            val plan = AcFill.plan(profiles, tokens)
            val choice = AcFill.defaultChoice(profiles, tokens, h.store.lastProfileFor(AcSite.registrable(host)))
            if (fields.isEmpty() || plan.profiles.isEmpty() || choice == null) return@evaluateJavascript done("no profile fits this page's fields")
            openChooser(profiles, plan, fields, choice)
            done(null)
        }
    }

    /** The chooser's Fill: the user's profile + value per kind. */
    fun chooserFill(choice: AcChoice) {
        val st = chooser.value ?: return
        fillProfile(st.profiles, st.fields, choice)
    }

    fun chooserDismiss() { chooser.value = null }

    // ── Cloud Vault ─────────────────────────────────────────────────────────────────────────

    /** Focus the field, then ask Android's autofill framework (Cloud Vault's service) to fill it. */
    private fun askVault(fieldId: Int) {
        val wv = page ?: return
        js("(function(){var a=window.__ac;return a?a.focusField($fieldId):'';})()")
        ui.postDelayed({
            val am = wv.context.getSystemService(android.view.autofill.AutofillManager::class.java)
            if (am != null && am.isEnabled) { wv.requestFocus(); am.requestAutofill(wv) }
        }, 120)
    }

    /** The framework filled fields (Cloud Vault's dataset): read them back and let the vault win over the local copy. */
    private fun onFramework(o: JSONObject) {
        val h = holder ?: return
        if (flag("autofill_use_vault") != true || incognito) return
        val f = form ?: return
        val ids = o.optJSONArray("ids")?.let { a -> (0 until a.length()).map { a.optInt(it) } }.orEmpty()
        if (ids.isEmpty()) return
        val wv = page ?: return
        wv.evaluateJavascript("(function(){var a=window.__ac;return a?a.read(${JSONArray(ids)}):'{}';})()") { raw ->
            val read = BrowserPageActions.decode(raw) ?: return@evaluateJavascript
            val values = HashMap<Int, String>().also { m -> ids.forEach { id -> read.optString(id.toString()).takeIf { it.isNotEmpty() }?.let { m[id] = it } } }
            mergeFromVault(h, f, values)
        }
    }

    private fun mergeFromVault(h: AcAndroid.Holder, f: Form, values: Map<Int, String>) {
        fun of(kind: AcKind) = f.fields.firstOrNull { it.cls.kind == kind && values.containsKey(it.id) }?.let { values[it.id] }
        fun tok(t: String) = f.fields.firstOrNull { it.cls.token == t && values.containsKey(it.id) }?.let { values[it.id] }
        val pw = of(AcKind.PASSWORD)
        if (pw != null) h.store.mergeVaultLogin(AcVaultItem.Login(host, of(AcKind.LOGIN_USER).orEmpty(), pw))
        val num = tok(AcTokens.CC_NUMBER)
        if (num != null) {
            val exp = tok(AcTokens.CC_EXP)?.let { AcProposals.parseExpiry(it) }
            h.store.mergeVaultCard(AcVaultItem.Card(num, tok(AcTokens.CC_NAME).orEmpty(), exp?.first ?: tok(AcTokens.CC_EXP_MONTH).orEmpty(),
                exp?.second ?: tok(AcTokens.CC_EXP_YEAR).orEmpty(), tok(AcTokens.CC_CSC).orEmpty()))
        }
        ui.post { form?.let { dismiss() } }
    }

    // ── submit: offer to save ───────────────────────────────────────────────────────────────

    private fun onSubmitted(o: JSONObject) {
        val h = holder ?: return
        if (incognito || flag("autofill_enabled") == false) return
        val fields = AcProposals.parse(o.optJSONArray("fields"))
        if (fields.isEmpty()) return
        val formSig = AcSiteMemory.formSignature(o.optString("formKey"), fields.map { it.sig.key() })
        val offer = AcOffer(
            password = flag("offer_save_password") != false && h.store.secretsAvailable,
            card = flag("offer_save_card") != false && h.store.secretsAvailable,
            profileData = flag("offer_save_profile_data") != false,
            siteMemory = flag("site_memory") != false,
        )
        // Cloud Vault filled these: it wins; the local copy follows. No save prompt for what the vault already owns.
        if (flag("autofill_use_vault") == true) {
            val vf = fields.filter { it.fw }
            if (vf.isNotEmpty()) {
                val values = vf.associate { it.id to it.value }
                form?.let { mergeFromVault(h, it, values) }
            }
        }
        val proposals = AcProposals.fromSubmission(fields, host, formSig, h.store.profiles(), h.store.logins().first, h.store.cards().first,
            h.store.memory(), offer, h.store.neverSaveOn("password"))
        if (proposals.isEmpty()) return
        val vs = vaultState()
        save.value = AcSaveState(AcSite.registrable(host), proposals, h.store.profiles(),
            if (vs.usable) "Cloud Vault will also be asked to save it" else if (flag("autofill_use_vault") == true) "Cloud Vault: ${vs.reason}" else null)
    }

    /** The save prompt answered: [picked] indexes into the proposals; [profileId] null = a new profile. [never] = never offer a password here. */
    fun confirmSave(picked: Set<Int>, profileId: String?, never: Boolean) {
        val st = save.value ?: return
        save.value = null
        val h = holder ?: return
        var targetProfile: AcProfile? = if (profileId == null) null else h.store.profiles().firstOrNull { it.id == profileId }
        var anyPassword = false
        st.proposals.forEachIndexed { i, p ->
            if (never && p is AcProposal.Password) { h.store.setNeverSave("password", st.site, true); return@forEachIndexed }
            if (i !in picked) return@forEachIndexed
            when (p) {
                is AcProposal.Password -> {
                    anyPassword = true
                    val old = p.existingId?.let { id -> h.store.logins().first.firstOrNull { it.id == id } }
                    h.store.saveLogin(old?.let { o2 -> o2.copy(password = p.password, username = p.username.ifBlank { o2.username }) }
                        ?: AcLogin(site = p.site, username = p.username, password = p.password))
                }
                is AcProposal.CardSave -> h.store.saveCard(p.card)
                is AcProposal.SiteValue -> h.store.remember(p.entry)
                is AcProposal.ProfileRecord -> {
                    val base = targetProfile ?: AcProfile(label = "Profile", isDefault = h.store.profiles().isEmpty())
                    val next = AcProposals.addTo(base, p)
                    h.store.upsertProfile(next)
                    targetProfile = next
                    anyProfile = true
                }
            }
        }
        // "also sends it to the vault": Android's own save flow, which opens Cloud Vault's add-item screen for the user to confirm.
        if ((anyPassword || st.proposals.any { it is AcProposal.CardSave && picked.contains(st.proposals.indexOf(it)) }) && vaultState().usable) page?.let { VaultAutofill.commitSave(it) }
    }

    fun dismissSave() { save.value = null }

    /** The stores changed from a Configs screen. */
    fun refresh() { anyProfile = holder?.store?.profiles()?.isNotEmpty() == true }

    private inner class Bridge(private val wv: WebView) {
        @JavascriptInterface fun focus(json: String?) { post(json) { onFocus(it) } }
        @JavascriptInterface fun none(json: String?) { ui.post { if (page === wv) dismiss() } }
        @JavascriptInterface fun submitted(json: String?) { post(json) { onSubmitted(it) } }
        @JavascriptInterface fun framework(json: String?) { post(json) { onFramework(it) } }

        private fun post(json: String?, f: (JSONObject) -> Unit) {
            val o = runCatching { JSONObject(json ?: return) }.getOrNull() ?: return
            ui.post { if (page === wv) f(o) }
        }
    }

    companion object { const val JS_BRIDGE = "CloudAc" }
}
