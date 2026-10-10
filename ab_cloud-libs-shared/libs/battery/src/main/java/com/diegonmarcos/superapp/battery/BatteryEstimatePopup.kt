package com.diegonmarcos.superapp.battery

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView

/**
 * The home-screen TOP-RIGHT battery bubble (tap the battery icon). It draws
 * the battery SoT's report ([BatteryRepository.report]) through
 * [BatteryRows.popup] — the same values the Battery badge and Configs ›
 * About › Battery print, computed once — in the network popup's shape: bold
 * section headers, a full-width 1 dp divider between sections, scrolling,
 * capped at ~85% of the screen.
 *
 *   Battery                                   Battery stats ›
 *   NOW               level, state + source, current, power, rate (%/h · W),
 *                     to empty / to full, voltage, temperature, health
 *   ───────────────
 *   SINCE LAST CHARGE unplugged, used, average (%/h · W), to empty at avg,
 *                     screen on / off   (SINCE PLUGGED IN while on power)
 *
 * While open it re-reads every [REFRESH_MS], so the smoothed "now" settles in
 * front of the user; the refresh stops when the bubble closes.
 */
object BatteryEstimatePopup {

    const val REFRESH_MS = 2_000L

    /** [openStats] opens Configs › About › Battery (the host app owns navigation); null hides the link. */
    fun show(ctx: Context, anchor: View, openStats: (() -> Unit)? = null) {
        val d = ctx.resources.displayMetrics.density
        val pad = (12 * d).toInt()
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            minimumWidth = (ctx.resources.displayMetrics.widthPixels * 0.5f).toInt()
            background = GradientDrawable().apply {
                cornerRadius = 12f * d
                setColor(0xEE111111.toInt())
                setStroke(maxOf(1, (1 * d).toInt()), 0x44FFFFFF.toInt())
            }
        }
        var popup: PopupWindow? = null
        fun fill() {
            container.removeAllViews()
            val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            head.addView(title(ctx, "Battery"), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            if (openStats != null) head.addView(link(ctx, "Battery stats ›") { popup?.dismiss(); openStats() })
            container.addView(head)
            val report = runCatching { BatteryRepository.report(ctx) }.getOrNull()
            if (report == null) { container.addView(value(ctx, "Battery state unavailable")); return }
            BatteryRows.popup(report).forEachIndexed { i, sec ->
                if (i > 0) container.addView(divider(ctx))
                container.addView(header(ctx, sec.title))
                for (row in sec.rows) container.addView(value(ctx, "${row.label}: ${row.value}"))
            }
        }
        fill()

        val dm = ctx.resources.displayMetrics
        val scroll = ScrollView(ctx).apply { isVerticalScrollBarEnabled = false; addView(container) }
        container.measure(
            View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val maxH = (dm.heightPixels * 0.85f).toInt()
        val pw = PopupWindow(
            scroll,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            if (container.measuredHeight > maxH) maxH else LinearLayout.LayoutParams.WRAP_CONTENT,
            true,
        ).apply {
            isOutsideTouchable = true
            isFocusable = true
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = 8 * d
        }
        popup = pw
        val main = Handler(Looper.getMainLooper())
        val tick = object : Runnable {
            override fun run() { if (!pw.isShowing) return; fill(); main.postDelayed(this, REFRESH_MS) }
        }
        pw.setOnDismissListener { main.removeCallbacks(tick) }
        // Gravity.END: the bubble's right edge on the icon's, so it extends leftward and stays on screen.
        pw.showAsDropDown(anchor, 0, (6 * d).toInt(), Gravity.END)
        main.postDelayed(tick, REFRESH_MS)
    }

    private fun title(ctx: Context, t: String) = TextView(ctx).apply {
        text = t
        setTextColor(0xFFFFFFFFL.toInt())
        textSize = 16f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    /** A section header: the label's look, bold (the network popup's). */
    private fun header(ctx: Context, t: String) = TextView(ctx).apply {
        text = t
        setTextColor(0xAAFFFFFFL.toInt())
        textSize = 11f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        val d = ctx.resources.displayMetrics.density
        setPadding(0, (6 * d).toInt(), 0, (2 * d).toInt())
    }

    private fun value(ctx: Context, t: String) = TextView(ctx).apply {
        text = t
        setTextColor(0xFFFFFFFFL.toInt())
        textSize = 12f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
    }

    private fun link(ctx: Context, t: String, onTap: () -> Unit) = TextView(ctx).apply {
        text = t
        setTextColor(0xFF7FB8FF.toInt())
        textSize = 12f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        val d = ctx.resources.displayMetrics.density
        setPadding((10 * d).toInt(), (4 * d).toInt(), 0, (4 * d).toInt())
        isClickable = true
        setOnClickListener { onTap() }
    }

    /** The full-width 1 dp line between two sections, with 8 dp above the next header. */
    private fun divider(ctx: Context) = View(ctx).apply {
        val d = ctx.resources.displayMetrics.density
        setBackgroundColor(0x40FFFFFF)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, maxOf(1, (1 * d).toInt())).apply {
            topMargin = (6 * d).toInt(); bottomMargin = (8 * d).toInt()
        }
    }
}
