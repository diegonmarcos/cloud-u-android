package com.diegonmarcos.superapp.extinstall

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.PopupMenu
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.launcher.Sections
import java.lang.ref.WeakReference

/**
 * The install progress ring on an `extapp:` tile: a circular ring over the
 * tile's icon plus a small state label, drawn into the tile's ViewOverlay so
 * no tile layout changes. It reads [ExtAppInstalls] (the one store) every time
 * it draws, so it survives scrolling, leaving and returning to the page, and a
 * new process (the worker keeps publishing; the next bind reads the row).
 */
object ExtTileRing {

    /** `extapp:<id>[/fork][#target]` → the package its install would produce; null for any other tile. */
    fun packageFor(tileId: String): String? {
        if (!tileId.startsWith("extapp:")) return null
        val id = tileId.removePrefix("extapp:").substringBefore('#').substringBefore('/')
        return runCatching { Sections.externalApp(id)?.installPackage }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private val views = HashMap<String, WeakReference<View>>()

    /** Bind (or rebind, for a recycled tile) [tile] to [tileId]'s install row. */
    fun attach(tile: View, tileId: String) {
        val old = tile.getTag(R.id.ext_install_ring) as? Binding
        val pkg = packageFor(tileId)
        if (old != null && old.pkg == pkg) { old.refresh(); return }
        old?.unbind()
        if (pkg == null) { tile.setTag(R.id.ext_install_ring, null); return }
        val b = Binding(tile, pkg)
        tile.setTag(R.id.ext_install_ring, b)
        b.bind()
    }

    /** The current on-screen tile for [pkg], if any. */
    private fun viewFor(pkg: String): View? = views[pkg]?.get()?.takeIf { it.isAttachedToWindow }

    /** Install finished: launch it if the user is still on the screen with that tile. */
    fun onInstalled(ctx: Context, pkg: String) {
        val v = viewFor(pkg) ?: return
        if (v.isShown && v.hasWindowFocus()) ExtAppInstalls.launch(v.context, pkg)
    }

    /**
     * A tap on a tile whose install is running: Cancel / Restart / Details.
     * Returns false when there is no running job (the caller proceeds normally).
     */
    fun handleBusyTap(ctx: Context, pkg: String): Boolean {
        if (!ExtAppInstalls.isActive(pkg)) return false
        val anchor = viewFor(pkg)
        if (anchor != null) {
            PopupMenu(anchor.context, anchor).apply {
                menu.add(0, 1, 0, "Cancel")
                menu.add(0, 2, 1, "Restart")
                menu.add(0, 3, 2, "Details")
                setOnMenuItemClickListener { onMenu(anchor.context, pkg, it.itemId); true }
            }.show()
        } else {
            ComposeAsk.show(ctx, ExtAppInstalls.snapshot(pkg)?.label ?: pkg, "Install in progress", listOf(
                ComposeAsk.Choice("Cancel") { onMenu(ctx, pkg, 1) },
                ComposeAsk.Choice("Details") { onMenu(ctx, pkg, 3) },
                ComposeAsk.Choice("Restart") { onMenu(ctx, pkg, 2) },
            ))
        }
        return true
    }

    private fun onMenu(ctx: Context, pkg: String, id: Int) {
        when (id) {
            1 -> ExtAppInstalls.cancel(ctx, pkg)
            2 -> ExtAppInstalls.restart(ctx, pkg)
            3 -> showDetails(ctx, pkg)
        }
    }

    fun detailsText(s: ExtSnapshot): String = buildString {
        append("State: ").append(ExtInstallViews.tile(s)?.label ?: "Done").append('\n')
        append("Downloaded: ").append(ExtInstallFormat.bytes(s.bytes))
        if (s.total > 0) append(" / ").append(ExtInstallFormat.bytes(s.total))
        append('\n')
        if (s.bytesPerSec > 0) append("Speed: ").append(ExtInstallFormat.bytes(s.bytesPerSec)).append("/s\n")
        append("ETA: ").append(ExtInstallFormat.eta(s.etaSeconds)).append('\n')
        append("Source: ").append(s.sourceHost.ifEmpty { "—" })
        if (s.reason.isNotEmpty()) append("\nReason: ").append(s.reason)
    }

    private fun showDetails(ctx: Context, pkg: String) {
        val s = ExtAppInstalls.snapshot(pkg) ?: return
        ComposeAsk.show(ctx, s.label, detailsText(s), listOf(ComposeAsk.Choice("OK") {}))
    }

    private class Binding(val tile: View, val pkg: String) : View.OnAttachStateChangeListener {
        val ring = RingDrawable(tile.resources.displayMetrics.density,
            com.diegonmarcos.superapp.ui.StatusLight.colour(tile.context, com.diegonmarcos.superapp.ui.StatusLight.State.OFF))
        val listener: (String) -> Unit = { p -> if (p == pkg) refresh() }
        val layout = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> place() }

        fun bind() {
            tile.overlay.add(ring)
            tile.addOnAttachStateChangeListener(this)
            tile.addOnLayoutChangeListener(layout)
            if (tile.isAttachedToWindow) onViewAttachedToWindow(tile)
            place(); refresh()
        }

        fun unbind() {
            tile.overlay.remove(ring)
            tile.removeOnAttachStateChangeListener(this)
            tile.removeOnLayoutChangeListener(layout)
            ExtAppInstalls.removeListener(listener)
        }

        override fun onViewAttachedToWindow(v: View) {
            views[pkg] = WeakReference(tile)
            ExtAppInstalls.addListener(listener)
            ExtAppInstalls.refresh(tile.context)
            refresh()
        }

        override fun onViewDetachedFromWindow(v: View) { ExtAppInstalls.removeListener(listener) }

        fun place() {
            ring.setBounds(0, 0, tile.width, tile.height)
            val icon = byId(tile, R.id.tile_icon) ?: firstImage(tile)
            ring.iconRect = icon?.let { offsetIn(tile, it) }
            tile.invalidate()
        }

        fun refresh() {
            ring.progress = ExtInstallViews.tile(ExtAppInstalls.snapshot(pkg))
            tile.invalidate()
        }

        private fun byId(v: View, id: Int): View? {
            if (v.id == id) return v
            if (v is ViewGroup) for (i in 0 until v.childCount) byId(v.getChildAt(i), id)?.let { return it }
            return null
        }

        private fun firstImage(v: View): View? {
            if (v is ImageView) return v
            if (v is ViewGroup) for (i in 0 until v.childCount) firstImage(v.getChildAt(i))?.let { return it }
            return null
        }

        private fun offsetIn(root: View, child: View): Rect {
            val r = Rect(0, 0, child.width, child.height)
            (root as? ViewGroup)?.let { runCatching { it.offsetDescendantRectToMyCoords(child, r) } }
            return r
        }
    }

    /** The ring + label. Draws nothing when there is no job. */
    private class RingDrawable(private val density: Float, failColour: Int) : Drawable() {
        var progress: TileProgress? = null
        var iconRect: Rect? = null
        private var sweepStart = 0f
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3 * density; color = 0x44FFFFFF }
        private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3 * density; strokeCap = Paint.Cap.ROUND; color = 0xFFA78BFA.toInt() }
        private val fail = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3 * density; color = failColour }
        private val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC1E1B2E.toInt() }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); textSize = 9 * density; textAlign = Paint.Align.CENTER }

        override fun draw(canvas: Canvas) {
            val p = progress ?: return
            val b = bounds
            val ir = iconRect ?: Rect(b.centerX() - (16 * density).toInt(), b.top + (4 * density).toInt(),
                b.centerX() + (16 * density).toInt(), b.top + (36 * density).toInt())
            val pad = 4 * density
            val r = maxOf(ir.width(), ir.height()) / 2f + pad
            val oval = RectF(ir.exactCenterX() - r, ir.exactCenterY() - r, ir.exactCenterX() + r, ir.exactCenterY() + r)
            canvas.drawOval(oval, track)
            when {
                p.phase == ExtPhase.FAILED || p.phase == ExtPhase.CANCELLED -> canvas.drawOval(oval, fail)
                p.fraction >= 0f -> canvas.drawArc(oval, -90f, 360f * p.fraction, false, arc)
                else -> { // indeterminate: a turning 90° arc
                    sweepStart = (sweepStart + 12f) % 360f
                    canvas.drawArc(oval, sweepStart, 90f, false, arc)
                    invalidateSelf()
                }
            }
            val tw = text.measureText(p.label) + 8 * density
            val ty = minOf(b.bottom - 3 * density, oval.bottom + 12 * density)
            canvas.drawRoundRect(RectF(b.exactCenterX() - tw / 2, ty - 10 * density, b.exactCenterX() + tw / 2, ty + 3 * density),
                6 * density, 6 * density, pill)
            canvas.drawText(p.label, b.exactCenterX(), ty, text)
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
