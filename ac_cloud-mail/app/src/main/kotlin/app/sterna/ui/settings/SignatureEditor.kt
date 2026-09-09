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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.sterna.R
import app.sterna.core.data.account.StoredSignature

/**
 * The named-signatures editor for one identity (#206): the list, which one is the default, and the
 * source of each.
 *
 * THE EDITING MODEL IS HTML SOURCE PLUS A PREVIEW, not rich text, and not a mode toggle.
 *
 * The field's own label has always promised "texto sin formato o HTML" — plain text OR HTML — and a
 * source field is the only shape that keeps BOTH halves of that promise in one control. A rich-text
 * editor would need a second [app.sterna.core.data.text.RichBody] editing surface here, a way to say
 * "no, leave my text alone" for the plain half, and a storage shape that is neither of the two the app
 * already has. A mode toggle asks the owner to classify their own signature before typing it, which is
 * a question they should never be asked: [StoredSignature.of] can already tell markup from text.
 *
 * WHAT MAKES THE LABEL TRUE. The field shows [StoredSignature.source] — the HTML as written, never the
 * flattened text. Showing the flattened text was the defect: the owner typed `<b>Alex</b>`, the next
 * recomposition redrew the field as `Alex`, and their markup was gone.
 */
@Composable
internal fun SignatureListEditor(
    signatures: List<StoredSignature>,
    defaultSignatureId: String?,
    delimiter: Boolean,
    onChange: (List<StoredSignature>) -> Unit,
    onDefaultChange: (String?) -> Unit,
    onImportHtml: ((String) -> Unit) -> Unit,
    newId: () -> String,
) {
    // The list as the owner will see it, with the pre-#206 signature already migrated in by
    // [StoredIdentity.resolvedSignatures] before it reaches here.
    signatures.forEachIndexed { index, signature ->
        fun update(transform: (StoredSignature) -> StoredSignature) {
            onChange(signatures.mapIndexed { i, s -> if (i == index) transform(s) else s })
        }
        Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
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
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.settings_signature_default),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            OutlinedTextField(
                value = signature.name,
                onValueChange = { v -> update { it.copy(name = v) } },
                label = { Text(stringResource(R.string.settings_signature_name_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
            OutlinedTextField(
                // The SOURCE, so HTML the owner typed survives the next recomposition.
                value = signature.source(),
                // Re-classified on every keystroke rather than remembered: typing "<" turns a plain
                // signature into an HTML one and deleting it turns it back, with no mode to be left in
                // by mistake and no way for the stored kind to disagree with the stored text.
                onValueChange = { v -> update { StoredSignature.of(it.id, it.name, v) } },
                label = { Text(stringResource(R.string.settings_signature_label)) },
                minLines = 2,
                supportingText = {
                    Text(
                        stringResource(
                            if (signature.isHtml) {
                                R.string.settings_signature_supporting_html
                            } else {
                                R.string.settings_signature_supporting
                            },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
            SignatureSourcePreview(signature, delimiter)
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = {
                    onImportHtml { html -> update { StoredSignature.of(it.id, it.name, html) } }
                }) {
                    Text(stringResource(R.string.settings_import_html))
                }
                // Gated so an identity is never left with none: the composer would then send mail
                // with no sign-off and nothing on screen would have said why.
                if (signatures.size > 1) {
                    Spacer(Modifier.weight(1f))
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
            HorizontalDivider(Modifier.padding(top = 12.dp))
        }
    }
    OutlinedButton(
        onClick = { onChange(signatures + StoredSignature(id = newId(), name = "")) },
        modifier = Modifier.padding(top = 12.dp),
    ) {
        Text(stringResource(R.string.settings_signature_add))
    }
}

/**
 * What this signature actually puts in a message.
 *
 * For a PLAIN one that is [signaturePreview]'s text, unchanged from before #206 — the delimiter line
 * and the signature, exactly as the composer will insert them.
 *
 * For an HTML one it is the SANITISED markup, which is the literal string that goes on the wire. Two
 * things follow that a rendered preview would not give: the owner sees that their `<script>` or
 * `onclick` was dropped rather than silently shipping a signature that means something different from
 * what they wrote, and the preview cannot itself become a place hostile markup executes.
 *
 * ponytail: source, not a rendered WYSIWYG preview. Upgrade path is the reader's own WebView, which
 * already renders this exact sanitised-fragment shape behind JavaScript-off and a default-deny
 * request filter; it is a screen's worth of work and needs a device to judge, so it is not guessed at
 * here.
 */
@Composable
private fun SignatureSourcePreview(signature: StoredSignature, delimiter: Boolean) {
    val preview = if (signature.isHtml) {
        signature.renderableHtml().takeIf { it.isNotBlank() }
    } else {
        signaturePreview(signature.text, delimiter)
    } ?: return
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(
            stringResource(R.string.settings_signature_preview_title),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            preview,
            style = MaterialTheme.typography.bodySmall,
            // Monospace: for the plain shape the delimiter is two hyphens and a trailing space, which
            // a proportional face makes hard to tell from a decorative dash rule; for the HTML shape
            // this is source, and source is read in a monospace face.
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
        if (!signature.isHtml && signatureHasOwnDelimiter(signature.text, delimiter)) {
            Text(
                stringResource(R.string.settings_signature_duplicate_delimiter),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
