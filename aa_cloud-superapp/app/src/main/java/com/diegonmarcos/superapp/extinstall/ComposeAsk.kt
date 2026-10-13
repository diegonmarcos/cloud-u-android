package com.diegonmarcos.superapp.extinstall

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.ViewGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.ComposeView

/**
 * A small Compose dialog hosted on the activity's window — the first-run
 * permission steps and the install Details / menu use it. New UI is Compose
 * (the compose ratchet), and none of these needs a Fragment of its own.
 */
object ComposeAsk {

    data class Choice(val label: String, val run: () -> Unit)

    fun activityOf(ctx: Context): Activity? {
        var c: Context? = ctx
        while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
        return null
    }

    /**
     * Show [title]/[text] with [choices] as buttons (last one is the confirm side).
     * [onDismiss] runs once whichever way it closes. Returns false when no activity can host it.
     */
    fun show(ctx: Context, title: String, text: String, choices: List<Choice>,
             dismissable: Boolean = true, onDismiss: () -> Unit = {}): Boolean {
        val act = activityOf(ctx) ?: return false
        if (act.isFinishing) return false
        val host = ComposeView(act)
        var closed = false
        fun close() {
            if (closed) return
            closed = true
            (host.parent as? ViewGroup)?.removeView(host)
            onDismiss()
        }
        host.setContent {
            val open = remember { mutableStateOf(true) }
            if (open.value) AlertDialog(
                onDismissRequest = { if (dismissable) { open.value = false; close() } },
                title = { Text(title) },
                text = { Text(text) },
                confirmButton = {
                    choices.lastOrNull()?.let { c ->
                        TextButton(onClick = { open.value = false; close(); c.run() }) { Text(c.label) }
                    }
                },
                dismissButton = {
                    for (c in choices.dropLast(1))
                        TextButton(onClick = { open.value = false; close(); c.run() }) { Text(c.label) }
                },
            )
        }
        return runCatching {
            act.addContentView(host, ViewGroup.LayoutParams(0, 0)); true
        }.getOrElse { false }
    }
}
