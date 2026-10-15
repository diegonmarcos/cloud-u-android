package app.sterna.ui.settings

import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.viewinterop.AndroidView
import app.sterna.R
import app.sterna.core.data.account.StoredSignature
import app.sterna.ui.theme.MailMetrics

/**
 * The named-signatures editor for one identity (#206): the list, which one is the default, and the
 * source of each.
 *
 * THE EDITING MODEL IS HTML SOURCE, with a toolbar that edits the source and a preview that renders it.
 *
 * The field's own label has always promised "texto sin formato o HTML" - plain text OR HTML - and a
 * source field is the only shape that keeps BOTH halves of that promise in one control. A rich-text
 * editor would need a second [app.sterna.core.data.text.RichBody] editing surface here, a way to say
 * "no, leave my text alone" for the plain half, and a storage shape that is neither of the two the app
 * already has. A mode toggle asks the owner to classify their own signature before typing it, which is
 * a question they should never be asked: [StoredSignature.of] can already tell markup from text.
 *
 * WHAT MAKES THE LABEL TRUE. The field shows [StoredSignature.source] - the HTML as written, never the
 * flattened text. Showing the flattened text was the defect: the owner typed `<b>Alex</b>`, the next
 * recomposition redrew the field as `Alex`, and their markup was gone.
 *
 * What is stored is unchanged: every edit still goes out through [StoredSignature.of], so sending reads
 * the same (text, html) pair it always has. The toolbar and the history are conveniences over the field.
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
    // The list as the owner will see it, with the pre-#206 signature already migrated in by
    // [StoredIdentity.resolvedSignatures] before it reaches here.
    signatures.forEachIndexed { index, signature ->
        fun update(transform: (StoredSignature) -> StoredSignature) {
            onChange(signatures.mapIndexed { i, s -> if (i == index) transform(s) else s })
        }
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
                    stringResource(R.string.settings_signature_default),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            OutlinedTextField(
                value = signature.name,
                onValueChange = { v -> update { it.copy(name = v) } },
                label = { Text(stringResource(R.string.settings_signature_name_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = MailMetrics.s4),
            )
            // Re-classified on every keystroke rather than remembered: typing "<" turns a plain
            // signature into an HTML one and deleting it turns it back, with no mode to be left in
            // by mistake and no way for the stored kind to disagree with the stored text.
            SignatureSourceEditor(
                signature = signature,
                onSource = { v -> update { StoredSignature.of(it.id, it.name, v) } },
            )
            SignatureSourcePreview(signature, delimiter)
            // Gated so an identity is never left with none: the composer would then send mail
            // with no sign-off and nothing on screen would have said why.
            if (signatures.size > 1) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = MailMetrics.s12),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
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
            HorizontalDivider(Modifier.padding(top = MailMetrics.s12))
        }
    }
    OutlinedButton(
        onClick = { onChange(signatures + StoredSignature(id = newId(), name = "")) },
        modifier = Modifier.padding(top = MailMetrics.s12),
    ) {
        Text(stringResource(R.string.settings_signature_add))
    }
}

/** Which dialog the toolbar has open; saved across rotation as its number. */
private const val DIALOG_NONE = 0
private const val DIALOG_LINK = 1
private const val DIALOG_IMAGE = 2
private const val DIALOG_COLOUR = 3

/** Swatches offered by the colour dialog; the field beside them takes any `#rrggbb`. */
private val COLOUR_SWATCHES = listOf("#000000", "#cc0000", "#e67e22", "#2e7d32", "#1565c0", "#6a1b9a", "#757575")

/**
 * The source field, its toolbar and its history, for one signature.
 *
 * Local state holds the field (text AND selection, which a toolbar button needs and a plain `String`
 * cannot carry); every change is handed up as the new source. Keyed by the signature id, so a row
 * that is removed or added never inherits another's history.
 */
@Composable
private fun SignatureSourceEditor(signature: StoredSignature, onSource: (String) -> Unit) {
    val initial = remember(signature.id) { signature.source() }
    var field by remember(signature.id) { mutableStateOf(TextFieldValue(initial, TextRange(initial.length))) }
    val history = remember(signature.id) {
        SignatureHtmlTools.History(SignatureHtmlTools.Edit(initial, initial.length, initial.length))
    }
    var canUndo by remember(signature.id) { mutableStateOf(false) }
    var canRedo by remember(signature.id) { mutableStateOf(false) }
    var dialog by rememberSaveable(signature.id) { mutableStateOf(DIALOG_NONE) }
    var input by rememberSaveable(signature.id) { mutableStateOf("") }
    var rejected by rememberSaveable(signature.id) { mutableStateOf(false) }

    fun show(edit: SignatureHtmlTools.Edit) {
        field = TextFieldValue(edit.text, TextRange(edit.start, edit.end))
        onSource(edit.text)
        canUndo = history.canUndo
        canRedo = history.canRedo
    }

    /** A toolbar action is a step of its own; a null result (an unusable address) changes nothing. */
    fun commit(edit: SignatureHtmlTools.Edit?): Boolean {
        if (edit == null) return false
        history.record(edit)
        show(edit)
        return true
    }

    fun openDialog(which: Int) {
        input = ""
        rejected = false
        dialog = which
    }

    val scheme = MaterialTheme.colorScheme
    val transformation = remember(scheme) {
        HighlightTransformation(
            mapOf(
                SignatureHtmlTools.Kind.TAG to scheme.primary,
                SignatureHtmlTools.Kind.ATTRIBUTE to scheme.tertiary,
                SignatureHtmlTools.Kind.VALUE to scheme.secondary,
                SignatureHtmlTools.Kind.COMMENT to scheme.outline,
                SignatureHtmlTools.Kind.ENTITY to scheme.error,
            ),
        )
    }
    val boldLabel = stringResource(R.string.settings_signature_bold)
    val italicLabel = stringResource(R.string.settings_signature_italic)
    val underlineLabel = stringResource(R.string.settings_signature_underline)

    Row(
        modifier = Modifier.fillMaxWidth().padding(top = MailMetrics.s4).horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(enabled = canUndo, onClick = { history.undo()?.let { show(it) } }) {
            Text(stringResource(R.string.settings_signature_undo))
        }
        TextButton(enabled = canRedo, onClick = { history.redo()?.let { show(it) } }) {
            Text(stringResource(R.string.settings_signature_redo))
        }
        TextButton(onClick = { commit(SignatureHtmlTools.bold(field.text, field.selection.start, field.selection.end)) }) {
            Text("B", fontWeight = FontWeight.Bold, modifier = Modifier.semantics { contentDescription = boldLabel })
        }
        TextButton(onClick = { commit(SignatureHtmlTools.italic(field.text, field.selection.start, field.selection.end)) }) {
            Text("I", fontStyle = FontStyle.Italic, modifier = Modifier.semantics { contentDescription = italicLabel })
        }
        TextButton(onClick = { commit(SignatureHtmlTools.underline(field.text, field.selection.start, field.selection.end)) }) {
            Text(
                "U",
                textDecoration = TextDecoration.Underline,
                modifier = Modifier.semantics { contentDescription = underlineLabel },
            )
        }
        TextButton(onClick = { openDialog(DIALOG_LINK) }) { Text(stringResource(R.string.settings_signature_link)) }
        TextButton(onClick = { openDialog(DIALOG_IMAGE) }) { Text(stringResource(R.string.settings_signature_image)) }
        TextButton(onClick = { openDialog(DIALOG_COLOUR) }) { Text(stringResource(R.string.settings_signature_colour)) }
    }
    OutlinedTextField(
        // The SOURCE, so HTML the owner typed survives the next recomposition.
        value = field,
        onValueChange = { v ->
            if (v.text != field.text) {
                // A burst of typing is one undo step.
                history.record(SignatureHtmlTools.Edit(v.text, v.selection.min, v.selection.max), merge = true)
                onSource(v.text)
                canUndo = history.canUndo
                canRedo = history.canRedo
            }
            field = v
        },
        label = { Text(stringResource(R.string.settings_signature_label)) },
        minLines = 3,
        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        visualTransformation = transformation,
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
        modifier = Modifier.fillMaxWidth().padding(top = MailMetrics.s4),
    )

    if (dialog != DIALOG_NONE) {
        val title = when (dialog) {
            DIALOG_LINK -> R.string.compose_link_title
            DIALOG_IMAGE -> R.string.settings_signature_image_title
            else -> R.string.settings_signature_colour_title
        }
        val label = when (dialog) {
            DIALOG_LINK -> R.string.compose_link_url
            DIALOG_IMAGE -> R.string.settings_signature_image_url_label
            else -> R.string.settings_signature_colour_hex_label
        }
        AlertDialog(
            onDismissRequest = { dialog = DIALOG_NONE },
            title = { Text(stringResource(title)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it; rejected = false },
                        label = { Text(stringResource(label)) },
                        singleLine = true,
                        isError = rejected,
                        supportingText = if (rejected) {
                            { Text(stringResource(R.string.compose_link_bad_url)) }
                        } else {
                            null
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (dialog == DIALOG_COLOUR) {
                        Row(Modifier.fillMaxWidth().padding(top = MailMetrics.s8)) {
                            COLOUR_SWATCHES.forEach { hex ->
                                Box(
                                    Modifier
                                        .padding(end = MailMetrics.s8)
                                        .size(MailMetrics.s40)
                                        .clip(CircleShape)
                                        .background(Color(android.graphics.Color.parseColor(hex)))
                                        .clickable { input = hex; rejected = false },
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val at = field.selection
                    val edit = when (dialog) {
                        DIALOG_LINK -> SignatureHtmlTools.link(field.text, at.start, at.end, input)
                        DIALOG_IMAGE -> SignatureHtmlTools.image(field.text, at.start, at.end, input)
                        else -> SignatureHtmlTools.colour(field.text, at.start, at.end, input)
                    }
                    if (commit(edit)) dialog = DIALOG_NONE else rejected = true
                }) { Text(stringResource(R.string.compose_link_apply)) }
            },
            dismissButton = {
                TextButton(onClick = { dialog = DIALOG_NONE }) { Text(stringResource(R.string.compose_link_cancel)) }
            },
        )
    }
}

/** Paints [SignatureHtmlTools.highlight] over the text; the text itself, and so every offset, is untouched. */
private class HighlightTransformation(
    private val colours: Map<SignatureHtmlTools.Kind, Color>,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val painted = buildAnnotatedString {
            append(text.text)
            for (span in SignatureHtmlTools.highlight(text.text)) {
                colours[span.kind]?.let { addStyle(SpanStyle(color = it), span.start, span.end) }
            }
        }
        return TransformedText(painted, OffsetMapping.Identity)
    }
}

/**
 * What this signature actually puts in a message.
 *
 * For a PLAIN one that is [signaturePreview]'s text, unchanged from before #206 - the delimiter line
 * and the signature, exactly as the composer will insert them.
 *
 * For an HTML one it is the SANITISED markup - [StoredSignature.renderableHtml], the literal string that
 * goes on the wire - drawn by a WebView with JavaScript OFF, and under it the plain-text version derived
 * from the source, which is what a recipient without HTML receives. Rendering the sanitised string
 * rather than the typed one keeps what the preview always had: a dropped `<script>` or `onclick` is
 * visibly absent from the result, and the preview cannot become a place hostile markup executes.
 */
@Composable
private fun SignatureSourcePreview(signature: StoredSignature, delimiter: Boolean) {
    if (signature.isHtml) {
        val html = signature.renderableHtml()
        Column(Modifier.fillMaxWidth().padding(top = MailMetrics.s8)) {
            Text(
                stringResource(R.string.settings_signature_preview_title),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SignatureHtmlPreview(html, Modifier.padding(top = MailMetrics.s2))
            Text(
                stringResource(R.string.settings_signature_plain_title),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = MailMetrics.s8),
            )
            Text(
                SignatureHtmlTools.plainText(signature.source()),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = MailMetrics.s2),
            )
        }
        return
    }
    val preview = signaturePreview(signature.text, delimiter)?.let { AnnotatedString(it) } ?: return
    Column(Modifier.fillMaxWidth().padding(top = MailMetrics.s8)) {
        Text(
            stringResource(R.string.settings_signature_preview_title),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            preview,
            style = MaterialTheme.typography.bodySmall,
            // Monospace for the plain shape: its delimiter is two hyphens and a trailing space,
            // which a proportional face makes hard to tell from a decorative dash rule.
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = MailMetrics.s2),
        )
        if (signatureHasOwnDelimiter(signature.text, delimiter)) {
            Text(
                stringResource(R.string.settings_signature_duplicate_delimiter),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = MailMetrics.s4),
            )
        }
    }
}

/**
 * The sanitised signature in a WebView that cannot run anything: JavaScript off, no file, content or
 * storage access, links swallowed, and every request refused but the pictures the owner pointed it at.
 * The document also carries its own `default-src 'none'` policy ([SignatureHtmlTools.previewDocument]).
 *
 * Software layer, always: this is a few lines of static markup, and the reader's hardware-layer latch
 * ([app.sterna.ui.message] WebViewLayerGuard) exists for a crash this view has no reason to risk.
 */
@Composable
private fun SignatureHtmlPreview(sanitisedHtml: String, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val foreground = cssColour(scheme.onSurface)
    val background = cssColour(scheme.surface)
    val document = remember(sanitisedHtml, foreground, background) {
        SignatureHtmlTools.previewDocument(sanitisedHtml, foreground, background)
    }
    AndroidView(
        modifier = modifier.fillMaxWidth().height(MailMetrics.s256),
        factory = { ctx ->
            WebView(ctx).apply {
                setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                settings.javaScriptEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                @Suppress("DEPRECATION")
                settings.allowFileAccessFromFileURLs = false
                @Suppress("DEPRECATION")
                settings.allowUniversalAccessFromFileURLs = false
                settings.domStorageEnabled = false
                settings.setGeolocationEnabled(false)
                settings.setSupportZoom(false)
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                webViewClient = PreviewClient
            }
        },
        update = { view ->
            if (view.tag != document) {
                view.tag = document
                view.loadDataWithBaseURL(null, document, "text/html", "utf-8", null)
            }
        },
    )
}

private fun cssColour(c: Color): String = "#%06x".format(c.toArgb() and 0xFFFFFF)

/** Default-deny: only pictures over http(s) and inline data leave the page; a tapped link goes nowhere. */
private object PreviewClient : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = true

    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
        val scheme = request?.url?.scheme?.lowercase()
        return if (scheme == "https" || scheme == "http" || scheme == "data" || scheme == "about") {
            null
        } else {
            WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))
        }
    }
}
