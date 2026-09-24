package com.diegonmarcos.cloudlib.rclone

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource

/**
 * The one entry point libs:rclone exports. Hosted by the app that links this
 * module (cloud-drive, push 5 of #567); this library never names that app.
 *
 * Push 1 skeleton: the signature is the contract the host wires against, and
 * it does not change when the engine lands.
 *
 * @param target what the host asked the engine to open — a path or an id the
 *   engine understands, or null for the engine's own home screen.
 * @param onOpenFile the host's file hand-off: the engine hands a LOCAL path
 *   back and the host decides what opens it (its viewer, another engine).
 * @param onClose the host's way out of this screen.
 */
@Composable
fun RcloneScreen(
    target: String?,
    onOpenFile: (String) -> Unit,
    onClose: () -> Unit,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(R.string.cloudlib_rclone_title),
            style = MaterialTheme.typography.titleLarge,
        )
    }
}
