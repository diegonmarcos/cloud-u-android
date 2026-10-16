package app.sterna.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import app.sterna.R
import app.sterna.core.data.account.StoredSignature
import app.sterna.ui.theme.MailMetrics

/**
 * The named-signatures list for one identity (#206): which one is the default, and each one's summary.
 * Editing a signature opens [SignatureEditorDialog], the full-screen HTML editor with a live preview.
 *
 * THE EDITING MODEL IS HTML SOURCE (see [SignatureEditorState]). The label has always promised "texto
 * sin formato o HTML" - plain text OR HTML - and a source field is the only shape that keeps both
 * halves of that promise in one control; [StoredSignature.of] tells markup from text, so the owner is
 * never asked to classify their own signature before typing it.
 *
 * What is stored is unchanged: every save goes out through [StoredSignature.of], so sending reads the
 * same (text, html) pair it always has, and the vault / Cloud Account path ([SignatureTransfer]) sees
 * no new shape. Several signatures per identity and the default among them were already the model.
 */
@Composable
internal fun SignatureListEditor(
    signatures: List<StoredSignature>,
    defaultSignatureId: String?,
    delimiter: Boolean,
    onChange: (List<StoredSignature>) -> Unit,
    onDefaultChange: (String?) -> Unit,
    newId: () -> String,
) {
    // The signature open in the editor, by id; a NEW one exists only in the editor until it is saved,
    // so cancelling "Add signature" leaves no empty row behind.
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }

    // The list as the owner will see it, with the pre-#206 signature already migrated in by
    // [StoredIdentity.resolvedSignatures] before it reaches here.
    signatures.forEachIndexed { index, signature ->
        Column(Modifier.fillMaxWidth().padding(top = MailMetrics.s8)) {
            // Which one composing pre-selects. The SAME control as "Remitente predeterminado" one
            // level up, deliberately: that screen already means "several things, one preselected", and
            // a differently-shaped answer to the same question is a thing to learn twice.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = signature.id == defaultSignatureId,
                        role = Role.RadioButton,
                        onClick = { onDefaultChange(signature.id) },
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = signature.id == defaultSignatureId, onClick = null)
                Spacer(Modifier.width(MailMetrics.s8))
                Text(
                    signature.name.ifBlank { stringResource(R.string.settings_signature_name_label) },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
            }
            SignatureSummary(signature, delimiter)
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = MailMetrics.s4),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = { adding = false; editing = signature.id }, modifier = Modifier.testTag("sig-edit-$index")) {
                    Text(stringResource(R.string.sigedit_edit))
                }
                Spacer(Modifier.weight(1f))
                // Gated so an identity is never left with none: the composer would then send mail
                // with no sign-off and nothing on screen would have said why.
                if (signatures.size > 1) {
                    TextButton(onClick = {
                        // Removing the default clears the stored choice, so [defaultSignature]
                        // degrades to the first rather than naming a row that is gone.
                        if (signature.id == defaultSignatureId) onDefaultChange(null)
                        onChange(signatures.filterIndexed { i, _ -> i != index })
                    }) {
                        Text(
                            stringResource(R.string.settings_remove),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            HorizontalDivider(Modifier.padding(top = MailMetrics.s12))
        }
    }
    OutlinedButton(
        onClick = { adding = true; editing = newId() },
        modifier = Modifier.padding(top = MailMetrics.s12),
    ) {
        Text(stringResource(R.string.settings_signature_add))
    }

    editing?.let { id ->
        val existing = signatures.firstOrNull { it.id == id }
        if (existing != null || adding) {
            SignatureEditorDialog(
                signature = existing ?: StoredSignature(id = id),
                isDefault = if (existing == null) signatures.isEmpty() else id == defaultSignatureId,
                delimiter = delimiter,
                onSave = { name, source, makeDefault ->
                    val saved = StoredSignature.of(id, name, source)
                    onChange(if (existing == null) signatures + saved else signatures.map { if (it.id == id) saved else it })
                    if (makeDefault) onDefaultChange(id)
                },
                onClose = { editing = null; adding = false },
            )
        }
    }
}

/**
 * A signature at a glance: its first lines as a recipient without HTML would read them. The full
 * rendered preview lives in the editor; a list of WebViews would be the heaviest way to say it.
 */
@Composable
private fun SignatureSummary(signature: StoredSignature, delimiter: Boolean) {
    val text = signature.text.trim()
    if (text.isNotEmpty()) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(top = MailMetrics.s4),
        )
    }
    if (!signature.isHtml && signatureHasOwnDelimiter(signature.text, delimiter)) {
        Text(
            stringResource(R.string.settings_signature_duplicate_delimiter),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = MailMetrics.s4),
        )
    }
}
