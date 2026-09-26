package com.diegonmarcos.clouddrive.configs

import android.content.Context
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.diegonmarcos.clouddrive.R
import com.diegonmarcos.clouddrive.ui.DriveCard
import com.diegonmarcos.clouddrive.ui.DriveTags
import com.diegonmarcos.clouddrive.ui.Pill
import com.diegonmarcos.clouddrive.ui.StatusLight
import com.diegonmarcos.cloudlib.auth.SignIn
import com.diegonmarcos.cloudlib.auth.SignInHost
import com.diegonmarcos.cloudlib.auth.SignInResult
import com.diegonmarcos.cloudlib.auth.SignInWays
import com.diegonmarcos.cloudlib.auth.UserRegistry

/**
 * #587 Configs ▸ Sign in: THE fleet sign-in (libs:auth — the same surface
 * cloud-superapp's Profile ▸ Connect hosts), in this app's card and pill.
 *
 * A successful sign-in lands the consolidated artifact; [DriveAuthApply]
 * writes the DRIVE-relevant sections and the card shows the per-section
 * report. The light is a LOOK at the last outcome: on when something was
 * written, off when a sign-in yielded nothing this app could apply, unknown
 * before any. Nothing is stored here but what the engines' own stores take.
 */
@Composable
fun SignInCard(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var report by remember { mutableStateOf<DriveAuthApply.Report?>(null) }
    var who by remember { mutableStateOf("") }
    val host = remember { DriveSignInHost(ctx.applicationContext) { r, identity -> report = r; who = identity } }
    val light = when (val r = report) {
        null -> StatusLight.State.UNKNOWN
        else -> StatusLight.of(r.ok)
    }
    DriveCard(
        stringResource(R.string.configs_sign_in), modifier,
        light = light,
        summary = when {
            who.isNotBlank() -> stringResource(R.string.configs_sign_in_as, who)
            else -> stringResource(R.string.configs_sign_in_body)
        },
        tag = DriveTags.CONFIGS_CARD,
    ) {
        SignInWays(host = host, modifier = Modifier.padding(top = 8.dp), pill = { label, tag, onClick ->
            Pill(label, onClick, modifier = Modifier.padding(top = 6.dp).testTag(tag))
        })
        Text(stringResource(R.string.configs_sign_in_applies, DriveAuthApply.applies.keys.joinToString(" · ")), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        report?.let { Text(it.text(), Modifier.padding(top = 6.dp).testTag(DriveTags.CONFIGS_SIGN_IN_REPORT), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
    }
}

/**
 * The host side: the artifact goes to [DriveAuthApply], an identity-only
 * sign-in is reported as such. Nothing else — no bearer store, no journey:
 * what drive needs from a sign-in is what the artifact carries for it.
 */
class DriveSignInHost(private val app: Context, private val onOutcome: (DriveAuthApply.Report?, String) -> Unit) : SignInHost {
    override fun onSignedIn(result: SignInResult) {
        val artifact = result.artifact
        val identity = result.identity.ifBlank { artifact?.let { UserRegistry.parse(it)?.primaryIdentity?.email }.orEmpty() }
        if (artifact == null) {
            onOutcome(DriveAuthApply.Report(listOf(DriveAuthApply.Step("identity", false,
                app.getString(R.string.configs_sign_in_identity_only, result.provider.label)))), identity)
            return
        }
        SignIn.Current.session = SignIn.Session(result.provider.id, identity)
        onOutcome(DriveAuthApply.apply(app, artifact), identity)
    }
}
