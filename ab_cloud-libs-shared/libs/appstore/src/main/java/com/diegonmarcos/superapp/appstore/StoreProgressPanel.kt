package com.diegonmarcos.superapp.appstore

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.superapp.updater.UpdateProgress
import com.diegonmarcos.superapp.updater.Updater

/**
 * The live progress row under a Store page's buttons: the app, its stage, bytes and %, one overall line
 * while several keyed jobs run, and Cancel while something is cancellable. Same sources and rules as the
 * Cloud page's row ([StoreStages.progress] — the line `/api/store/progress` returns — and the [StoreJobs]
 * board), so every page and the API say the same thing. Compose, hosted in the View page by [view].
 * // ponytail: the Cloud page still draws its own View copy of this row; switch it to this class next.
 */
class StoreProgressPanel {
    private class Ui(val text: String, val failed: Boolean, val percent: Int, val indeterminate: Boolean, val cancellable: Boolean)

    private var ui by mutableStateOf<Ui?>(null)
    private val model = ProgressBarModel()
    private val main = Handler(Looper.getMainLooper())

    private val progressObserver: (UpdateProgress.State) -> Unit = { state ->
        val p = StoreStages.progress(state)
        main.post { ui = if (StoreJobs.board.overall().active > 0) board() else single(state, p) }
    }
    private val boardObserver: () -> Unit = {
        main.post { ui = if (StoreJobs.board.overall().active > 0) board() else single(UpdateProgress.state, StoreStages.progress()) }
    }

    /** The row (empty until there is work), listening from now on; [detach] stops. */
    fun view(ctx: Context): View {
        detach()
        UpdateProgress.addObserver(progressObserver)
        StoreJobs.install()
        StoreJobs.addObserver(boardObserver)
        return ComposeView(ctx).apply { setContent { MaterialTheme(colorScheme = darkColorScheme()) { Panel(ctx) } } }
    }

    fun detach() {
        UpdateProgress.removeObserver(progressObserver)
        StoreJobs.removeObserver(boardObserver)
    }

    private fun board(): Ui {
        val o = StoreJobs.board.overall()
        val only = StoreJobs.board.rows().filter { !it.finished }.singleOrNull()
        val d = model.step(if (o.multi) "overall" else only?.key.orEmpty(),
            if (o.percent > 0) 1L else 0L, if (o.multi) o.percent else only?.percent ?: -1, false)
        return Ui(if (o.multi || only == null) o.text() else "${only.label} · ${only.text()}", false, d.percent, d.indeterminate, true)
    }

    private fun single(state: UpdateProgress.State, p: StoreStages.Progress?): Ui? {
        if (state is UpdateProgress.State.Cancelled || p == null) { model.reset(); return null }
        val d = model.step(p.appId.ifEmpty { p.pkg }, p.bytes, p.percent, p.failed)
        val cancellable = state is UpdateProgress.State.Downloading || state is UpdateProgress.State.CheckingManifest ||
            state is UpdateProgress.State.Installing
        return Ui(if (p.failed) StoreRowError.banner(p.app) else p.text, p.failed, d.percent, d.indeterminate, cancellable)
    }

    @Composable
    private fun Panel(ctx: Context) {
        val u = ui ?: return
        Column(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(u.text, color = if (u.failed) Color(0xFFF56565) else Color(0xFFF6AD55), fontSize = StoreDensity.T_META.sp,
                    modifier = Modifier.weight(1f))
                if (u.cancellable) Text("Cancel", color = Color.White, fontSize = StoreDensity.T_META.sp,
                    modifier = Modifier.padding(start = 8.dp).background(Color(0xFF4A4A55))
                        .clickableNoRipple { Updater.cancelNow(ctx.applicationContext) }.padding(horizontal = 12.dp, vertical = 6.dp))
            }
            if (u.indeterminate) LinearProgressIndicator(Modifier.fillMaxWidth())
            else LinearProgressIndicator(progress = { u.percent / 100f }, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** A plain tap target, without the ripple the dense store rows never use. */
internal fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier =
    this.then(Modifier.clickable(interactionSource = null, indication = null, onClick = onClick))
