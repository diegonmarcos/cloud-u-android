package app.sterna.pgp

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * The openpgp-api user-interaction round-trip: a provider call that returns
 */
@Composable
fun rememberPgpInteractionLauncher(onResult: (Intent?) -> Unit): (PendingIntent) -> Unit {
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        onResult(if (result.resultCode == Activity.RESULT_OK) result.data ?: Intent() else null)
    }
    return remember(launcher) {
        { pendingIntent ->
            launcher.launch(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
        }
    }
}
