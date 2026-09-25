package com.diegonmarcos.superapp.profile

import org.json.JSONArray
import org.json.JSONObject

/**
 * #585 — what a file handed to Profile ▸ Import IS, decided BEFORE anything is
 * stored, so the import can never end in silence.
 *
 * WHY. The owner picked cloud-vault `configs/profile-secrets.json` — a
 * sops/age-ENCRYPTED document: every value is `ENC[AES256_GCM,…]`, only
 * `schema_version` and `_generated` are plaintext. It is valid JSON, so the old
 * route accepted it, wrote the whole 1.3 MB into the encrypted paste store
 * (overwriting the stored Authelia bearer on the way) and said "saved". No
 * consumer recognised a single key. Nothing anywhere changed. That is the
 * silence-guard defect (#233's shape) in the import's costume.
 *
 * THE DECISION (design doc, section "File route"). This app holds NO age
 * identity and ships none: the file's recipients are the fleet master key and
 * the c3-public-api server key, and the authenticated route that could deliver
 * a device key is the very route that already delivers the DECRYPTED bundle
 * (#569 fetch). So the file route accepts the decrypted export — what `sops -d`
 * prints, the same document the server's fetch returns — and lands it exactly
 * where the fetch lands ([VaultConnect.Imported], the Fleet cockpit). The
 * encrypted file is refused with what was read and what to feed instead.
 *
 * Pure: no Android, no I/O, no strings — the fragment turns a [Verdict] into a
 * sentence, and the JVM test asserts the verdict on the real encrypted bytes.
 */
object VaultFile {

    /** sops' at-rest value marker (sops v3 JSON store) and its metadata root —
     *  format constants of the tool, like the WireGuard key regex, not fleet data. */
    const val ENC_MARKER = "ENC["
    const val SOPS_ROOT = "sops"

    /** How much of a non-JSON file the report quotes, so the owner recognises it. */
    private const val HEAD = 60

    sealed class Verdict {
        /** Nothing came out of the box or the picker. */
        object Empty : Verdict()
        /** Not JSON at all: how much was read and how it starts. */
        data class NotJson(val chars: Int, val head: String, val reason: String) : Verdict()
        /** Still sops-encrypted. This app has no key; it can read none of the values. */
        data class Encrypted(val encValues: Int, val recipients: Int, val sections: List<String>) : Verdict()
        /** A vault export whose schema this build does not know (schema.json's rule). */
        data class UnknownSchema(val version: Int) : Verdict()
        /** The DECRYPTED vault export: root `schema_version` plus its sections. */
        data class Bundle(val bundle: JSONObject, val sections: List<String>) : Verdict()
        /** The paste shape (build.json::ui.import_schema): which sections it carries. */
        data class Blob(val root: JSONObject, val recognised: List<String>, val ignored: List<String>) : Verdict()
        /** Valid JSON that is neither: what it has, against what was expected. */
        data class Unrecognised(val keys: List<String>, val expected: List<String>) : Verdict()
    }

    /**
     * @param knownSchemaVersions [VaultConnect.knownSchemaVersions].
     * @param blobSections the paste shape's top-level sections, from [blobSections].
     *
     * The discriminator between the two accepted shapes is the root
     * `schema_version`: configs/schema.json makes every vault export carry it
     * (emit.py `check` refuses one without), and the paste shape never has one.
     */
    fun classify(text: String, knownSchemaVersions: Set<Int>, blobSections: Set<String>): Verdict {
        if (text.isBlank()) return Verdict.Empty
        val root = try {
            JSONObject(text)
        } catch (t: Throwable) {
            return Verdict.NotJson(text.length, text.take(HEAD).replace(Regex("\\s+"), " "), t.message ?: t.javaClass.simpleName)
        }
        val sops = root.optJSONObject(SOPS_ROOT)
        val enc = countEnc(root, skip = SOPS_ROOT)
        if (sops != null || enc > 0) {
            return Verdict.Encrypted(enc, sops?.optJSONArray("age")?.length() ?: 0, sectionsOf(root))
        }
        if (root.has("schema_version")) {
            val v = root.optInt("schema_version", 0)
            return if (v in knownSchemaVersions) Verdict.Bundle(root, sectionsOf(root)) else Verdict.UnknownSchema(v)
        }
        val keys = root.keys().asSequence().filterNot { it.startsWith("_") }.toList()
        val recognised = keys.filter { it in blobSections }
        return if (recognised.isEmpty()) Verdict.Unrecognised(keys, blobSections.toList())
        else Verdict.Blob(root, recognised, keys - recognised.toSet())
    }

    /** The paste shape's sections: the top-level keys of ui.import_schema that are
     *  not documentation (`_doc…`). Read from the baked schema, never restated. */
    fun blobSections(importSchemaJson: String): Set<String> = runCatching {
        JSONObject(importSchemaJson).keys().asSequence().filterNot { it.startsWith("_") }.toSet()
    }.getOrDefault(emptySet())

    /** Sections = top-level keys that are neither bookkeeping nor sops metadata —
     *  the same rule [VaultConnect.sections] applies to a bare bundle. */
    private fun sectionsOf(root: JSONObject): List<String> =
        root.keys().asSequence()
            .filter { !it.startsWith("_") && it != "schema_version" && it != SOPS_ROOT }
            .toList()

    /** How many leaves are still ciphertext, the sops block itself excluded. */
    private fun countEnc(v: Any?, skip: String = ""): Int = when (v) {
        is String -> if (v.startsWith(ENC_MARKER)) 1 else 0
        is JSONObject -> v.keys().asSequence().filter { it != skip }.sumOf { countEnc(v.opt(it)) }
        is JSONArray -> (0 until v.length()).sumOf { countEnc(v.opt(it)) }
        else -> 0
    }
}
