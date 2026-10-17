package com.diegonmarcos.superapp.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.diegonmarcos.superapp.uikit.LocalKitPalette

/**
 * Brave-style address focus: a tap on the address bar opens this over the bar and the page.
 * Box 1 (top): an EMPTY search field, focused, keyboard up; typing shows the address-bar suggestions; Go searches
 * or navigates. Box 2 (under it): the CURRENT address (title + full URL) with Copy (to the clipboard) and Edit
 * (puts the URL into box 1, all selected). A tap outside, back, or a pick closes it.
 */
@Composable
fun BrowserAddressOverlay(
    title: String,
    url: String,
    hint: String,
    state: SuggestState,
    onQuery: (String) -> Unit,
    onSubmit: (String) -> Unit,
    onPick: (BrowserSuggest.Suggestion) -> Unit,
    onCopied: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val p = LocalKitPalette.current
    val clipboard = LocalClipboardManager.current
    var field by remember { mutableStateOf(TextFieldValue("")) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val rows = remember(state.sections, state.engineLabel) { SuggestRow.build(state.sections, state.engineLabel) }
    val shape = RoundedCornerShape(8.dp)
    Box(Modifier.fillMaxSize().background(Color(0x99000000)).clickable(onClick = onDismiss).testTag("browser:address:scrim")) {
        Column(
            Modifier.fillMaxWidth().background(p.surface)
                .clickable(remember { MutableInteractionSource() }, null) {}   // the panel itself does not dismiss
                .padding(8.dp).testTag("browser:address:overlay"),
        ) {
            // Box 1: search
            OutlinedTextField(
                value = field,
                onValueChange = { v -> val changed = v.text != field.text; field = v; if (changed) onQuery(v.text) },
                modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("browser:address:search"),
                singleLine = true,
                placeholder = { Text(hint, maxLines = 1) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { if (field.text.isNotBlank()) onSubmit(field.text) }),
            )
            Spacer(Modifier.height(6.dp))
            // Box 2: the current address
            if (url.isNotBlank()) {
                Row(
                    Modifier.fillMaxWidth().border(1.dp, p.hairline, shape).padding(start = 10.dp, top = 4.dp, bottom = 4.dp, end = 2.dp)
                        .testTag("browser:address:current"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        if (title.isNotBlank() && title != url)
                            Text(title, color = p.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("browser:address:title"))
                        Text(url, color = p.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("browser:address:url"))
                    }
                    TextButton(onClick = { clipboard.setText(AnnotatedString(url)); onCopied(url) },
                        modifier = Modifier.testTag("browser:address:copy")) { Text("Copy") }
                    TextButton(onClick = {
                        field = TextFieldValue(url, TextRange(0, url.length))
                        onQuery(url)
                        runCatching { focus.requestFocus() }
                    }, modifier = Modifier.testTag("browser:address:edit")) { Text("Edit") }
                }
            }
            if (state.showing) SuggestRows(rows, onPick, Modifier.fillMaxWidth().weight(1f, fill = false).testTag("browser:address:suggest"))
        }
    }
}
