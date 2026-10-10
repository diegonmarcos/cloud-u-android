package com.diegonmarcos.superapp.appstore

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.diegonmarcos.superapp.updater.UpdateProgress
import com.diegonmarcos.superapp.updater.Updater

/**
 * The live progress row under a Store page's buttons: the app, its stage, bytes and %, one overall line
 * while several keyed jobs run, and Cancel while something is cancellable. Same sources and rules as the
 * Cloud page's row ([StoreStages.progress] — the line `/api/store/progress` returns — and the [StoreJobs]
 * board), so every page and the API say the same thing.
 * // ponytail: the Cloud page still draws its own copy of this row; switch it to this class next.
 */
class StoreProgressPanel {
    private var row: LinearLayout? = null
    private var icon: ImageView? = null
    private var label: TextView? = null
    private var bar: ProgressBar? = null
    private var cancel: TextView? = null
    private val model = ProgressBarModel()
    private val cWork = 0xFFF6AD55.toInt()
    private val cFail = 0xFFF56565.toInt()

    private val progressObserver: (UpdateProgress.State) -> Unit = { state ->
        val p = StoreStages.progress(state)
        row?.post { if (StoreJobs.board.overall().active > 0) renderBoard() else renderProgress(state, p) }
    }
    private val boardObserver: () -> Unit = {
        row?.post { if (StoreJobs.board.overall().active > 0) renderBoard() else renderProgress(UpdateProgress.state, StoreStages.progress()) }
    }

    /** Build the row (hidden until there is work) and start listening; [detach] stops. */
    fun view(ctx: Context): View {
        detach()
        val d = { v: Int -> StoreDensity.dp(ctx, v) }
        val r = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, d(StoreDensity.S6), 0, d(StoreDensity.S4))
            visibility = View.GONE
            tag = StoreBar.PROGRESS_TAG
        }
        val ic = ImageView(ctx).apply { visibility = View.GONE }
        val lb = TextView(ctx).apply { textSize = StoreDensity.T_META; setTextColor(cWork) }
        val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(ic, LinearLayout.LayoutParams(d(StoreDensity.GLYPH), d(StoreDensity.GLYPH)).apply { marginEnd = d(StoreDensity.S6) })
        head.addView(lb, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val pb = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; isIndeterminate = true }
        val cx = TextView(ctx).apply {
            text = "Cancel"; textSize = StoreDensity.T_META; setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF4A4A55.toInt()); setPadding(d(StoreDensity.S12), d(StoreDensity.S6), d(StoreDensity.S12), d(StoreDensity.S6))
            visibility = View.GONE
            setOnClickListener { Updater.cancelNow(ctx.applicationContext) }
        }
        r.addView(head)
        r.addView(pb, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, d(StoreDensity.S6)).apply { topMargin = d(StoreDensity.S4) })
        r.addView(cx, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = d(StoreDensity.S6); gravity = Gravity.END
        })
        row = r; icon = ic; label = lb; bar = pb; cancel = cx
        UpdateProgress.addObserver(progressObserver)
        StoreJobs.install()
        StoreJobs.addObserver(boardObserver)
        boardObserver()
        return r
    }

    fun detach() {
        UpdateProgress.removeObserver(progressObserver)
        StoreJobs.removeObserver(boardObserver)
        row = null; icon = null; label = null; bar = null; cancel = null
    }

    private fun draw(pb: ProgressBar, dr: ProgressBarModel.Draw) {
        if (pb.isIndeterminate != dr.indeterminate) pb.isIndeterminate = dr.indeterminate
        if (!dr.indeterminate && pb.progress != dr.percent) pb.progress = dr.percent
    }

    private fun renderBoard() {
        val r = row ?: return; val lb = label ?: return; val pb = bar ?: return
        val o = StoreJobs.board.overall()
        val only = StoreJobs.board.rows().filter { !it.finished }.singleOrNull()
        lb.text = if (o.multi || only == null) o.text() else "${only.label} · ${only.text()}"
        lb.setTextColor(cWork)
        draw(pb, model.step(if (o.multi) "overall" else only?.key.orEmpty(),
            if (o.percent > 0) 1L else 0L, if (o.multi) o.percent else only?.percent ?: -1, false))
        icon?.visibility = View.GONE
        cancel?.visibility = View.VISIBLE
        r.visibility = View.VISIBLE
    }

    private fun renderProgress(state: UpdateProgress.State, p: StoreStages.Progress?) {
        val r = row ?: return; val lb = label ?: return; val pb = bar ?: return
        if (state is UpdateProgress.State.Cancelled || p == null) {
            model.reset(); cancel?.visibility = View.GONE; r.visibility = View.GONE; return
        }
        lb.text = if (p.failed) StoreRowError.banner(p.app) else p.text
        lb.setTextColor(if (p.failed) cFail else cWork)
        draw(pb, model.step(p.appId.ifEmpty { p.pkg }, p.bytes, p.percent, p.failed))
        val ic = p.pkg.takeIf { it.isNotEmpty() }?.let { runCatching { r.context.packageManager.getApplicationIcon(it) }.getOrNull() }
        icon?.apply { setImageDrawable(ic); visibility = if (ic != null) View.VISIBLE else View.GONE }
        cancel?.visibility = when (state) {
            is UpdateProgress.State.Downloading, is UpdateProgress.State.CheckingManifest,
            is UpdateProgress.State.Installing -> View.VISIBLE
            else -> View.GONE
        }
        r.visibility = View.VISIBLE
    }
}
