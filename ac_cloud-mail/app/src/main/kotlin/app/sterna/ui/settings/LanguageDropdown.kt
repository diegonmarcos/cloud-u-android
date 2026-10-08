package app.sterna.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import app.sterna.ui.components.Icon
import app.sterna.ui.text.MailLanguages
import app.sterna.ui.theme.MailMetrics

/** Test tags of the dropdown: the field, and a row by language tag. */
internal const val LANGUAGE_FIELD_TAG = "language-dropdown-field"
internal fun languageRowTag(tag: String) = "language-dropdown-row-$tag"

/**
 * A language PICKER: the current language in a read-only field, a menu of [MailLanguages] under it.
 * It replaces the free-text tag field - a typed tag that matched nothing was a setting that failed
 * only when a message was translated.
 *
 * [selected] is a BCP-47 tag (blank means the default, English); [onSelect] gets the chosen tag. The
 * whole field is the tap target, not just the arrow.
 */
@Composable
internal fun LanguageDropdown(
    label: String,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    val current = MailLanguages.normalise(selected)
    Box(modifier.fillMaxWidth().padding(horizontal = MailMetrics.s16)) {
        OutlinedTextField(
            value = MailLanguages.nameOf(current),
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(label) },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
            modifier = Modifier.fillMaxWidth().testTag(LANGUAGE_FIELD_TAG),
        )
        // The field swallows taps for its own text editing; a transparent layer on top is the target.
        Box(Modifier.matchParentSize().clickable { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            MailLanguages.options(current).forEach { (tag, name) ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = { open = false; onSelect(tag) },
                    modifier = Modifier.testTag(languageRowTag(tag)),
                )
            }
        }
    }
}
