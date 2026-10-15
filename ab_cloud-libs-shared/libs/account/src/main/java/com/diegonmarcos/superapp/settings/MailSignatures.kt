package com.diegonmarcos.superapp.settings

import android.content.Context
import com.diegonmarcos.superapp.fleetconfig.SetupContract
import com.diegonmarcos.superapp.profile.AccountFleet
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Cloud Mail's signatures, as Cloud Account carries them: captured into the encrypted bundle on export,
 * handed back on import, and exchanged on the Connections page as a small file.
 *
 * Mail serves them through the ordinary fleet setup contract, as the declared store `mail_signatures`
 * (fleet-config.json): one key per sending ADDRESS whose value is that identity's signatures as JSON.
 * Nothing here knows Mail's storage; it moves the contract's strings and no more. The key `*` addresses
 * every identity, which is how a bare .html file, naming no mailbox, arrives.
 *
 * The same document is the file the Connections page writes and reads:
 *
 *     {"format":"cloud-mail-signatures","v":1,"identities":{"<address>":{"default":"<id>","signatures":[{"id","name","text","html"}]}}}
 *
 * Signatures are configuration, not secrets, so the contract hands them to any fleet caller; the bundle
 * is encrypted as a whole regardless.
 */
object MailSignatures {
    const val STORE = "mail_signatures"
    const val FORMAT = "cloud-mail-signatures"
    const val VERSION = 1
    const val ALL_IDENTITIES = "*"
    private const val APP_ID = "mail"
    private const val TYPES = "_types"

    /** Mail's package from the fleet manifest, never a literal: a fork or a debug build is the same app. */
    @Volatile var packageOf: (Context) -> String? = { c -> runCatching { AccountFleet.manifest(c).apps[APP_ID]?.pkg }.getOrNull() }

    /** One provider call; the JVM suite hands in a fake. BLOCKS: call off the main thread. */
    @Volatile var transport: (Context, String, String, String?, JSONObject?) -> SetupContract.Reply =
        { c, pkg, method, store, body -> SetupContract.call(c, pkg, method, store, body) }

    data class Handed(val ok: Boolean, val count: Int, val why: String = "")

    // ── Mail <-> contract ────────────────────────────────────────────────

    /** `{address: entry JSON}` as Mail exports it, or null when Mail is not installed, too old, or has none. */
    fun capture(ctx: Context): Map<String, String>? {
        val pkg = packageOf(ctx) ?: return null
        val reply = transport(ctx, pkg, SetupContract.METHOD_EXPORT, STORE, null) as? SetupContract.Reply.Ok ?: return null
        val file = reply.json.optJSONObject("stores")?.optJSONObject(STORE)?.optJSONObject(STORE) ?: return null
        val out = LinkedHashMap<String, String>()
        for (k in file.keys()) if (k != TYPES) out[k] = file.optString(k)
        return out.ifEmpty { null }
    }

    /** Applies [values] to Mail through the contract; [Handed.ok] only when Mail wrote them. */
    fun handBack(ctx: Context, values: Map<String, String>): Handed {
        if (values.isEmpty()) return Handed(false, 0, "nothing to hand over")
        val pkg = packageOf(ctx) ?: return Handed(false, 0, "Mail is not in the fleet manifest")
        val body = JSONObject().put("file", STORE).put("values", JSONObject().also { o -> values.forEach { (k, v) -> o.put(k, v) } })
        return when (val r = transport(ctx, pkg, SetupContract.METHOD_APPLY, STORE, body)) {
            is SetupContract.Reply.Ok ->
                if (r.json.optBoolean("ok")) Handed(true, values.size) else Handed(false, 0, r.json.optString("why").ifBlank { "Mail refused" })
            is SetupContract.Reply.Refused -> Handed(false, 0, r.why)
            is SetupContract.Reply.Unreachable -> Handed(false, 0, r.why)
        }
    }

    // ── the document ─────────────────────────────────────────────────────

    /** The file / bundle form of [values]; an entry that is not JSON is dropped rather than carried. */
    fun document(values: Map<String, String>): JSONObject {
        val ids = JSONObject()
        for ((k, v) in values) runCatching { JSONObject(v) }.getOrNull()?.let { ids.put(k, it) }
        return JSONObject().put("format", FORMAT).put("v", VERSION).put("identities", ids)
    }

    /** The contract's values from a document, or null when it is not one of ours or comes from a newer format. */
    fun valuesOf(doc: JSONObject): Map<String, String>? {
        if (doc.optString("format") != FORMAT || doc.optInt("v", 0) !in 1..VERSION) return null
        val ids = doc.optJSONObject("identities") ?: return null
        val out = LinkedHashMap<String, String>()
        for (k in ids.keys()) ids.optJSONObject(k)?.let { out[k] = it.toString() }
        return out.ifEmpty { null }
    }

    /**
     * What a picked file means: a signature document, or a BARE .html file, which becomes one signature
     * for every identity named after [fileName]. Null for anything else (a bundle, plain text, damage).
     */
    fun parseImport(text: String, fileName: String = ""): Map<String, String>? {
        val t = text.trim()
        if (t.startsWith("{")) return runCatching { JSONObject(t) }.getOrNull()?.let(::valuesOf)
        if (!HTML.containsMatchIn(t)) return null
        return fromHtml(t, fileName)
    }

    /** One signature for [ALL_IDENTITIES] from raw HTML. Its plain-text half is left for Mail to derive. */
    fun fromHtml(html: String, name: String): Map<String, String> {
        val title = name.substringBeforeLast('.').trim().ifEmpty { TITLE.find(html)?.groupValues?.get(1)?.trim().orEmpty() }
        val id = "html-" + MessageDigest.getInstance("SHA-256").digest(html.toByteArray()).joinToString("") { "%02x".format(it) }.take(12)
        val sig = JSONObject().put("id", id).put("name", title.ifEmpty { "Imported" }).put("text", "").put("html", html)
        return mapOf(ALL_IDENTITIES to JSONObject().put("signatures", org.json.JSONArray().put(sig)).toString())
    }

    private val HTML = Regex("<[a-zA-Z/!]")
    private val TITLE = Regex("(?is)<title[^>]*>(.*?)</title>")
}
