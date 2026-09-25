package com.diegonmarcos.superapp.settings
import com.diegonmarcos.superapp.BuildConfig
import com.diegonmarcos.superapp.R

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import com.diegonmarcos.superapp.profile.VaultConnect
import com.diegonmarcos.superapp.profile.VaultFile
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText

/**
 * Configs ▸ Profile ▸ "Import from a file instead" (the last line of journey
 * step 4, #573) and the paste box behind it.
 *
 * TWO SHAPES, ONE DOOR, NO SILENCE (#585). What comes in is classified by
 * [VaultFile.classify] before anything is written:
 *   • the DECRYPTED vault export (root `schema_version` + sections) lands in
 *     [VaultConnect.Imported] — the Fleet cockpit — exactly where the server
 *     fetch lands, and is applied there per section, never here;
 *   • the paste shape (build.json::ui.import_schema, baked into
 *     BuildConfig.UI_IMPORT_SCHEMA_B64) is merged SECTION BY SECTION into
 *     [ConfigsPrefs] through its own `putSecret`, so a blob without an `auth`
 *     section no longer erases the stored Authelia bearer;
 *   • everything else — the sops-ENCRYPTED file, zero bytes, not JSON, an
 *     unknown schema, sections nothing here knows — is refused with what was
 *     read and what to feed instead.
 * Every exit of [loadFromUri] and [describe] goes through [report]; the
 * silence guard (1_cicd/src/data/silence-guard.json) fails the build on any
 * `return` that does not.
 */
class ImportConfigsFragment : Fragment(R.layout.fragment_import_configs) {

    private lateinit var input: TextInputEditText
    private lateinit var status: TextView

    /**
     * The encrypted store, behind three seams. [ConfigsPrefs] needs the Android
     * Keystore for its MasterKey, which a JVM test does not have; the test
     * swaps these for a map and drives the whole route on the real bytes.
     * ponytail: three lambdas, not an interface with one implementation.
     */
    internal var readBlob: (Context) -> String = { ConfigsPrefs(it).json }
    internal var putSecret: (Context, String, String, String) -> Unit =
        { c, section, key, value -> ConfigsPrefs(c).putSecret(section, key, value) }
    internal var clearBlob: (Context) -> Unit = { ConfigsPrefs(it).clear() }

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        // A dismissed picker is the owner's choice, not a failure: nothing to report.
        if (uri != null) loadFromUri(uri)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val schemaTv     = view.findViewById<TextView>(R.id.import_schema)
        input            = view.findViewById(R.id.import_input)
        val save         = view.findViewById<MaterialButton>(R.id.import_save)
        val clear        = view.findViewById<MaterialButton>(R.id.import_clear)
        val openFile     = view.findViewById<MaterialButton>(R.id.import_from_file)
        status           = view.findViewById(R.id.import_status)

        schemaTv.text = importSchemaJson()
        input.setText(readBlob(requireContext()))

        openFile.setOnClickListener {
            // Accept anything; ACTION_OPEN_DOCUMENT respects the device file
            // picker (Files / Storage / 3rd-party). JSON or plain-text fine.
            pickFile.launch(arrayOf("application/json", "text/*", "*/*"))
        }
        save.setOnClickListener { describe(classify(input.text?.toString().orEmpty()), store = true) }
        clear.setOnClickListener {
            clearBlob(requireContext())
            input.setText("")
            report(R.string.import_cleared)
        }
    }

    /**
     * Read the picked file and SAY what it is, before Save. A file that reads
     * as zero bytes (a cloud folder that has not synced, a provider that
     * streams lazily) is reported as such, not as an empty import.
     */
    internal fun loadFromUri(uri: Uri) {
        val name = uri.lastPathSegment ?: uri.toString()
        val text = try {
            requireContext().contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        } catch (t: Throwable) { report(R.string.import_file_error, "${t.javaClass.simpleName}: ${t.message}"); return }
        if (text == null) { report(R.string.import_file_no_stream, name); return }
        if (text.isEmpty()) { report(R.string.import_file_empty, name); return }
        input.setText(text)
        describe(classify(text), store = false)
    }

    /**
     * One sentence per verdict; on Save, the write that verdict allows. The
     * encrypted file, an unknown schema and an unrecognised shape write
     * NOTHING and say so — with the counts, the sections and the way out.
     */
    internal fun describe(v: VaultFile.Verdict, store: Boolean) {
        val ctx = requireContext()
        when (v) {
            VaultFile.Verdict.Empty -> report(R.string.import_nothing)
            is VaultFile.Verdict.NotJson -> report(R.string.import_invalid, v.chars, v.head, v.reason)
            is VaultFile.Verdict.Encrypted -> report(R.string.import_encrypted, v.encValues, v.recipients, v.sections.joinToString(", "))
            is VaultFile.Verdict.UnknownSchema -> report(R.string.vault_connect_schema_unknown, v.version, VaultConnect.knownSchemaVersions.sorted().joinToString(", "))
            is VaultFile.Verdict.Unrecognised -> report(R.string.import_unrecognised, v.keys.joinToString(", "), v.expected.joinToString(", "))
            is VaultFile.Verdict.Bundle -> {
                val sections = VaultConnect.sections(v.bundle)
                val values = sections.sumOf { it.rows.size }
                if (!store) { report(R.string.import_bundle_read, values, sections.size); return }
                VaultConnect.Imported.last = sections
                VaultConnect.Imported.bundle = v.bundle
                report(R.string.import_bundle_landed, values, sections.size)
            }
            is VaultFile.Verdict.Blob -> {
                val ignored = v.ignored.joinToString(", ").ifEmpty { "—" }
                if (!store) { report(R.string.import_blob_read, v.recognised.joinToString(", "), ignored); return }
                var written = 0
                for (section in v.recognised) {
                    val o = v.root.optJSONObject(section) ?: continue
                    for (key in o.keys()) {
                        if (key.startsWith("_")) continue
                        putSecret(ctx, section, key, o.opt(key).toString())
                        written++
                    }
                }
                if (written == 0) { report(R.string.import_blob_no_keys, v.recognised.joinToString(", ")); return }
                report(R.string.import_blob_stored, written, v.recognised.joinToString(", "), ignored)
            }
        }
    }

    private fun classify(text: String): VaultFile.Verdict =
        VaultFile.classify(text, VaultConnect.knownSchemaVersions, VaultFile.blobSections(importSchemaJson()))

    private fun importSchemaJson(): String =
        String(Base64.decode(BuildConfig.UI_IMPORT_SCHEMA_B64, Base64.NO_WRAP))

    /** THE reporter: the sentence goes on screen and to the screen reader. */
    private fun report(@StringRes res: Int, vararg args: Any) {
        val text = getString(res, *args)
        status.text = text
        status.announceForAccessibility(text)
    }

    companion object {
        fun newInstance() = ImportConfigsFragment()
    }
}
