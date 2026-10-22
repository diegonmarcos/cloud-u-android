package com.diegonmarcos.superapp.crash

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * #1122 the generic crash screen: the newest report in files/crashes with stack trace, Copy, Share, Retry in safe
 * mode and Close. Own process (`:crash`, see the manifest); uses no theme or prefs of the app.
 */
class CrashActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val a = this
        val report = CrashActions.load(a)
        val label = CrashActions.label(a)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Column(
                    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(start = 16.dp, top = 48.dp, end = 16.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(stringResource(R.string.crash_report_title, label), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                    Text(stringResource(R.string.crash_report_body), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SelectionContainer(Modifier.weight(1f).fillMaxWidth()) {
                        Text(report, Modifier.verticalScroll(rememberScrollState()), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onBackground)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { CrashActions.copy(a, report) }, Modifier.weight(1f)) { Text(stringResource(R.string.crash_report_copy)) }
                        OutlinedButton(onClick = { CrashActions.share(a, report) }, Modifier.weight(1f)) { Text(stringResource(R.string.crash_report_share)) }
                    }
                    Button(onClick = { CrashActions.retryInSafeMode(a) }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.crash_report_safe_mode)) }
                    OutlinedButton(onClick = { a.finish() }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.crash_report_close)) }
                }
            }
        }
    }
}
