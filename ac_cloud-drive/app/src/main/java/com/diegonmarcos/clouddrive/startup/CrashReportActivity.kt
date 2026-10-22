package com.diegonmarcos.clouddrive.startup

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.clouddrive.MainActivity
import com.diegonmarcos.clouddrive.R
import com.diegonmarcos.clouddrive.ui.denseTypography

/**
 * #1106 "Drive hit a startup error": the newest report in files/crashes with Copy, Share and
 * "Retry in safe mode". Deliberately NOTHING the app's own startup could have broken: it does not
 * use DriveTheme, DrivePrefs, strings.xml or any initialiser, and it runs in its own process
 * (:crash, AndroidManifest) so it survives the crash it reports.
 */
class CrashReportActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dir = StartupGuard.dir(this)
        val report = StartupGuard.shareText(dir)
        runCatching { StartupGuard.markAllSeen(dir) }
        setContent { MaterialTheme(colorScheme = darkColorScheme(), typography = denseTypography()) { Screen(report) } }
    }

    @Composable
    private fun Screen(report: String) {
        Column(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.crash_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            Text(
                stringResource(R.string.crash_body),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SelectionContainer(Modifier.weight(1f).fillMaxWidth()) {
                Text(
                    report,
                    Modifier.verticalScroll(rememberScrollState()),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { copy(report) }, Modifier.weight(1f)) { Text(stringResource(R.string.crash_copy)) }
                OutlinedButton(onClick = { share(report) }, Modifier.weight(1f)) { Text(stringResource(R.string.crash_share)) }
            }
            Button(onClick = { retryInSafeMode() }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.crash_safe_mode)) }
            OutlinedButton(onClick = { finish() }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.crash_close)) }
        }
    }

    private fun copy(report: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(getString(R.string.crash_title), report))
        Toast.makeText(this, getString(R.string.crash_copied), Toast.LENGTH_SHORT).show()
    }

    private fun share(report: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, getString(R.string.crash_title)).putExtra(Intent.EXTRA_TEXT, report)
        startActivity(Intent.createChooser(send, getString(R.string.crash_share)))
    }

    private fun retryInSafeMode() {
        startActivity(Intent(this, MainActivity::class.java).putExtra(StartupGuard.EXTRA_SAFE_MODE, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        finish()
    }
}
