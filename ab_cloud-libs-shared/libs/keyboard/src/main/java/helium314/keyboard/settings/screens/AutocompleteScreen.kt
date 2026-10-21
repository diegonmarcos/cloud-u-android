// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.superapp.autofill.AutofillSchemas
import com.diegonmarcos.superapp.autofill.SchemaTable
import helium314.keyboard.latin.R
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.preferences.PreferenceCategory

/**
 * #1077: Configs > Autocomplete. One table per store the fleet deploys, every field with its key and whether it is
 * sensitive. The tables come from [AutofillSchemas] (Cloud Account's from its provider declaration, AutofillSot), never
 * from a list kept here.
 */
@Composable
fun AutocompleteScreen(onClickBack: () -> Unit) {
    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.settings_screen_autocomplete),
        settings = emptyList(),
    ) {
        Scaffold(contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)) { innerPadding ->
            Column(Modifier.verticalScroll(rememberScrollState()).padding(innerPadding)) {
                AutofillSchemas.all().forEach { SchemaTableView(it) }
            }
        }
    }
}

@Composable
private fun SchemaTableView(table: SchemaTable) {
    PreferenceCategory("${table.owner} · ${table.title}")
    Text(table.store, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 16.dp).alpha(0.6f), color = MaterialTheme.colorScheme.onSurface)
    table.fields.forEach { f ->
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)) {
            Text(f.label, fontSize = 13.sp, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
            Text(f.key, fontSize = 11.sp, modifier = Modifier.weight(1f).alpha(0.6f), color = MaterialTheme.colorScheme.onSurface)
            Text(if (f.sensitive) "sensitive" else "", fontSize = 11.sp, modifier = Modifier.padding(start = 8.dp), color = MaterialTheme.colorScheme.error)
        }
    }
}
