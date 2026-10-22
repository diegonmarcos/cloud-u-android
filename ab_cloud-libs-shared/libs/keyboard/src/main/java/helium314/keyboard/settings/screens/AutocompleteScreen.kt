// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import helium314.keyboard.latin.R
import helium314.keyboard.latin.autocomplete.AutocompleteEngine
import helium314.keyboard.latin.autocomplete.AutocompletePage
import helium314.keyboard.latin.autocomplete.AutocompleteStore
import helium314.keyboard.latin.autocomplete.AutocompleteSync
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.preferences.PreferenceCategory

/**
 * #1103 Configs > Autocomplete. The keyboard's own Autocomplete store, page by page: how many rows each holds, whether
 * the page is masked, when Cloud Account last filled it. The rows themselves are never listed here; "Sync now" asks
 * Cloud Account again over the local provider and "Clear" empties the store on this phone.
 */
@Composable
fun AutocompleteScreen(onClickBack: () -> Unit) {
    val ctx = LocalContext.current
    val store = remember { AutocompleteStore.open(ctx) }
    var tick by remember { mutableIntStateOf(0) }
    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.settings_screen_autocomplete),
        settings = emptyList(),
    ) {
        Scaffold(contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)) { innerPadding ->
            Column(Modifier.verticalScroll(rememberScrollState()).padding(innerPadding)) {
                tick // read: a sync or a clear redraws the counts
                val all = store.all()
                PreferenceCategory(stringResource(R.string.autocomplete_pages))
                AutocompletePage.entries.forEach { p ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                        Text(p.title, fontSize = 14.sp, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
                        Text(AutocompleteEngine.rows(all, p).size.toString(), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                        if (p.masked) Text(stringResource(R.string.autocomplete_masked), fontSize = 11.sp, modifier = Modifier.padding(start = 8.dp),
                            color = MaterialTheme.colorScheme.error)
                    }
                }
                Text(stringResource(R.string.autocomplete_source_note), fontSize = 12.sp, modifier = Modifier.padding(16.dp).alpha(0.7f),
                    color = MaterialTheme.colorScheme.onSurface)
                PreferenceCategory(stringResource(R.string.autocomplete_actions))
                Text(stringResource(R.string.autocomplete_sync_now), fontSize = 14.sp, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth().clickable {
                        Thread { AutocompleteSync.pull(ctx.applicationContext, store); tick++ }.start()
                    }.padding(16.dp))
                Text(stringResource(R.string.autocomplete_clear), fontSize = 14.sp, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth().clickable { store.clear(); tick++ }.padding(16.dp))
            }
        }
    }
}
