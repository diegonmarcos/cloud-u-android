package app.sterna.widget

import app.sterna.widget.DeclarationSource.file
import app.sterna.widget.DeclarationSource.functionBody
import app.sterna.widget.DeclarationSource.kotlinLines
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — the same instrument and the same disclaimer as
 */
class RecentMailWidgetPaletteWiringLintTest {

    /**
     * `setBackgroundResource`, NEVER `setBackgroundColor`.
     */
    @Test fun `the drawn frame is painted from the palette, and keeps its rounded corner`() {
        assertEquals(
            "RecentMailWidgetDraw.draw must paint each cell from the palette it was handed: the " +
                "background as a pre-built DRAWABLE, then the notice's colour. ⛔ " +
                "setBackgroundColor here throws the shape away and the cell sits square among " +
                "rounded neighbours; a missing setTextColor leaves the notice in the layout's " +
                "compile-time colour on a surface it was never meant for — dark on dark. " +
                "⛔ AND BOTH SIT UNDER `if (palette != null)`: a null palette is the reader saying " +
                "the app does not diverge from the system, and posting either line then freezes " +
                "the cell against a system that flips at dusk.",
            listOf(
                "fun draw(app: Application, ids: IntArray, drawing: RecentWidgetDrawing) {",
                "if (ids.isEmpty()) return",
                "val snapshot = drawing.snapshot",
                "val palette = drawing.colours?.palette",
                "val manager = AppWidgetManager.getInstance(app)",
                "val notice = RecentMailWidgetContent.notice(snapshot.cell)?.let { app.getString(it) } ?: app.getString(R.string.app_name)",
                "ids.forEach { id ->",
                "val views = RemoteViews(app.packageName, R.layout.widget_recent)",
                "if (palette != null) {",
                BACKGROUND,
                "views.setTextColor(R.id.widget_recent_notice, palette.label)",
                "}",
                "views.setTextViewText(R.id.widget_recent_notice, notice)",
                "@Suppress(\"DEPRECATION\")",
                "views.setRemoteAdapter(R.id.widget_recent_list, adapterIntent(app, id))",
                "views.setPendingIntentTemplate(R.id.widget_recent_list, rowTapTemplate(app))",
                "views.setEmptyView(R.id.widget_recent_list, R.id.widget_recent_notice)",
                "manager.updateAppWidget(id, views)",
                "manager.notifyAppWidgetViewDataChanged(id, R.id.widget_recent_list)",
                "}",
            ),
            functionBody(DRAW, "fun draw(app: Application, ids: IntArray, drawing: RecentWidgetDrawing)"),
        )
        assertEquals(
            "nothing that draws this widget may call setBackgroundColor. It is one word away " +
                "from the line above and it costs the cell its rounded corner — the shape drawable " +
                "is thrown away and the home screen shows a bare rectangle of colour.",
            emptyList<String>(),
            (kotlinLines(DRAW) + kotlinLines(SERVICE)).filter { "setBackgroundColor" in it },
        )
    }

    /**
     * The three texts of a row take the palette; the account dot does NOT.
     */
    @Test fun `a served row takes its text colours from the palette, and its dot from the account`() {
        assertEquals(
            "RecentMailWidgetFactory.getViewAt must colour the sender with palette.rowPrimary and " +
                "the subject and date with palette.label, from the palette held beside the rows. " +
                "Dropped, the row keeps the colour res/values-night froze into the layout and a " +
                "reader whose app is light reads white on white. ⛔ And the account dot must keep " +
                "its setColorFilter on row.accountColor: a theme colour there and the dot no " +
                "longer says which mailbox the message came from. ⭐ The badge is the third " +
                "colour on this row and the only one that is a BITMAP: it comes from " +
                "monogramFor(row), i.e. from the ramps read beside the rows, and it is null both " +
                "when the ramps are unknown and when the row carries no monogram at all — under " +
                "the app lock and at the NONE setting, the two states in which a row names " +
                "nobody. ⛔ Its out-of-range branch must clear the bitmap AND hide the view: a " +
                "recycled row would otherwise wear the face of a message that is gone, and there " +
                "is no res/values-night behind a bitmap to make that merely stale.",
            listOf(
                "override fun getViewAt(position: Int): RemoteViews {",
                "val views = RemoteViews(context.packageName, R.layout.widget_recent_row)",
                "val item = rows.getOrNull(position)",
                "if (item == null) {",
                "views.setTextViewText(R.id.widget_recent_row_primary, \"\")",
                "views.setTextViewText(R.id.widget_recent_row_secondary, \"\")",
                "views.setTextViewText(R.id.widget_recent_row_date, \"\")",
                "views.setImageViewBitmap(R.id.widget_recent_row_monogram, null)",
                "views.setViewVisibility(R.id.widget_recent_row_monogram, View.INVISIBLE)",
                "views.setViewVisibility(R.id.widget_recent_row_account, View.INVISIBLE)",
                "return views",
                "}",
                "val row = item.drawn",
                "val colours = palette",
                "if (colours != null) {",
                "views.setTextColor(R.id.widget_recent_row_primary, colours.rowPrimary)",
                "views.setTextColor(R.id.widget_recent_row_secondary, colours.label)",
                "views.setTextColor(R.id.widget_recent_row_date, colours.label)",
                "}",
                "views.setTextViewText(R.id.widget_recent_row_primary, weighted(row.primary, row.unread))",
                "views.setTextViewText(R.id.widget_recent_row_secondary, weighted(row.secondary.orEmpty(), row.unread))",
                "views.setViewVisibility(",
                "R.id.widget_recent_row_secondary,",
                "if (row.secondary == null) View.GONE else View.VISIBLE,",
                ")",
                "views.setTextViewText(R.id.widget_recent_row_date, weighted(row.date, row.unread))",
                "val badge = monogramFor(row)",
                "views.setImageViewBitmap(R.id.widget_recent_row_monogram, badge)",
                "views.setViewVisibility(",
                "R.id.widget_recent_row_monogram,",
                "if (badge == null) View.INVISIBLE else View.VISIBLE,",
                ")",
                ACCOUNT_DOT,
                "views.setViewVisibility(",
                "R.id.widget_recent_row_account,",
                "if (row.accountColor == null) View.INVISIBLE else View.VISIBLE,",
                ")",
                "views.setOnClickFillInIntent(R.id.widget_recent_row_root, RecentMailWidgetDraw.rowFillIn(item.tap))",
                "return views",
            ),
            functionBody(SERVICE, "override fun getViewAt(position: Int): RemoteViews"),
        )
        assertEquals(
            "and the badge's own two lines are pinned with it: the tones come from the field " +
                "filled by the SAME read as the rows, and null there means no badge — never a " +
                "ramp chosen here. ⛔ A default palette on this path is the one thing the whole " +
                "pane forbids, and unlike a text colour it cannot be left to res/values-night: " +
                "the badge is a bitmap and there is no night twin behind it.",
            listOf(
                "private fun monogramFor(row: RecentWidgetRow): Bitmap? {",
                "val monogram = row.monogram ?: return null",
                "val ramps = monogramRamps ?: return null",
                "return WidgetMonogram.badge(context, monogram, ramps)",
            ),
            functionBody(SERVICE, "private fun monogramFor("),
        )
    }

    /**
     * THE CARRIED PALETTE IS NULLABLE, and it is the same `null` [RecentMailWidgetFactory]
     */
    @Test fun `the palette carried beside the rows is optional, and null means the resources answer`() {
        assertEquals(
            "RecentWidgetDrawing.palette must be nullable. Null is not an absence to be defaulted " +
                "away: it is the reader's answer that nothing needs posting, either because the " +
                "read timed out or because the app follows the system with no pure black and no " +
                "Material You. Non-null again and every draw bakes a literal colour into the " +
                "RemoteViews with no -night resource behind it and no trigger to redraw on a " +
                "configuration change.",
            listOf(
                "internal data class RecentWidgetDrawing(val snapshot: RecentWidgetSnapshot, " +
                    "val colours: WidgetColours?)",
            ),
            kotlinLines(DRAW).filter { it.startsWith("internal data class RecentWidgetDrawing") },
        )
        assertEquals(
            "and the factory's own field must stay nullable with it — it is the one site that " +
                "already drew null correctly, and its KDoc is the rule the other two now follow.",
            listOf("private var palette: WidgetPalette? = null"),
            kotlinLines(SERVICE).filter { it.startsWith("private var palette") },
        )
        assertEquals(
            "⭐ and the BADGE TONES ride beside it, nullable HERE for a different reason than the " +
                "palette is: null means the colour read did not answer at all, so no badge is " +
                "drawn — it never means \"the default palette\". WidgetColours.monogramRamps " +
                "itself is not nullable, because a bitmap has no res/values-night to fall back on.",
            listOf("private var monogramRamps: List<ToneRamp>? = null"),
            kotlinLines(SERVICE).filter { it.startsWith("private var monogramRamps") },
        )
    }

    /**
     * THE ROWS AND THEIR COLOURS COME OUT OF ONE READ, and the whole body is pinned because the
     */
    @Test fun `the rows and the colours they are drawn in come from the same read`() {
        assertEquals(
            "RecentMailWidgetFactory.onDataSetChanged must take the rows AND the palette off the " +
                "same RecentWidgetDrawing. Read separately, the frame and the rows answer from two " +
                "different moments and the home screen shows a light box over dark lines until " +
                "something makes the launcher refetch — which nothing does on a schedule.",
            listOf(
                "override fun onDataSetChanged() {",
                "val app = context.applicationContext as? Application ?: return",
                "val read = try {",
                "runBlocking { withTimeoutOrNull(READ_TIMEOUT_MS) { RecentMailWidgetDraw.read(app) } }",
                "} catch (t: Throwable) {",
                "Log.w(TAG, \"Latest-messages widget not refreshed; the cell keeps what it last showed\", t)",
                "null",
                "}",
                "val drawing = read ?: RecentMailWidgetDraw.drawing ?: return",
                "rows = drawing.snapshot.rows",
                "palette = drawing.colours?.palette",
                "monogramRamps = drawing.colours?.monogramRamps",
            ),
            functionBody(SERVICE, "override fun onDataSetChanged()"),
        )
        assertEquals(
            "and what onDataSetChanged filled must be dropped by onDestroy, all three of them: a " +
                "palette or a set of badge tones left behind on a dead factory is a colour " +
                "outliving the read it came from.",
            listOf(
                "override fun onDestroy() {",
                "rows = emptyList()",
                "palette = null",
                "monogramRamps = null",
            ),
            functionBody(SERVICE, "override fun onDestroy()"),
        )
    }

    /**
     * THE CACHE IS KEYED ON THE COLOUR IT ACTUALLY PAINTED — the one decision of this pane that
     */
    @Test fun `the badge is cached under the colour it was painted, and the cache is bounded in bytes`() {
        assertEquals(
            "WidgetMonogram.badge must resolve the colour first and put THAT colour in the key. " +
                "Keyed on the slot instead, the entry survives a theme change it should have " +
                "missed, and the home screen keeps the old palette's discs until the process dies.",
            listOf(
                "fun badge(context: Context, monogram: RecentRowMonogram, ramps: List<ToneRamp>): Bitmap {",
                "val side = context.resources.getDimensionPixelSize(R.dimen.widget_recent_monogram)",
                "val argb = widgetMonogramArgb(monogram, ramps)",
                "val key = widgetMonogramKey(monogram.initial, argb, side)",
                "cache.get(key)?.let { return it }",
                "return draw(monogram.initial, argb, side).also { cache.put(key, it) }",
            ),
            functionBody(MONOGRAM, "fun badge(context: Context"),
        )
        assertEquals(
            "and the cache must stay bounded in BYTES: sizeOf in kilobytes against a kilobyte " +
                "cap. A cap in entries holds sixteen times the memory on an xxxhdpi screen for the " +
                "same number, and nothing on any screen would report it.",
            listOf(
                "private const val CACHE_KB = 512",
                "private val cache = object : LruCache<String, Bitmap>(CACHE_KB) {",
                "override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024",
            ),
            kotlinLines(MONOGRAM).filter {
                it.startsWith("private const val CACHE_KB") ||
                    it.startsWith("private val cache =") ||
                    it.startsWith("override fun sizeOf(")
            },
        )
    }

    /**
     * AND THE DISC ITSELF — the one place in this pane where a one-character mutation is
     */
    @Test fun `the badge's letter is coloured against the disc it sits on, and centred on the font's metrics`() {
        assertEquals(
            "WidgetMonogram.draw must pass the DISC'S OWN colour to widgetMonogramLetterArgb — " +
                "never a constant, and never `argb`'s replacement by one. A literal there keeps " +
                "the contrast rule called and correct while every initial in the widget comes " +
                "out the same colour: white on a light scheme's pastel tones is 1.40:1, drawn " +
                "and unreadable, on the one surface nobody can debug. The baseline must come " +
                "from the paint's fontMetrics (centred on side / 2 alone, every letter floats " +
                "high by about a third of its height) and the size from LETTER_SCALE, the ratio " +
                "the in-app badge draws at.",
            listOf(
                "private fun draw(initial: String, argb: Int, side: Int): Bitmap {",
                "val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)",
                "val canvas = Canvas(bitmap)",
                "val centre = side / 2f",
                "val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = argb }",
                "canvas.drawCircle(centre, centre, centre, disc)",
                "val letter = Paint(Paint.ANTI_ALIAS_FLAG).apply {",
                "color = widgetMonogramLetterArgb(argb)",
                "textSize = side * LETTER_SCALE",
                "textAlign = Paint.Align.CENTER",
                "}",
                "val metrics = letter.fontMetrics",
                "canvas.drawText(initial, centre, centre - (metrics.ascent + metrics.descent) / 2f, letter)",
                "return bitmap",
            ),
            functionBody(MONOGRAM, "private fun draw(initial: String, argb: Int, side: Int)"),
        )
    }

    private companion object {
        val DRAW = file("app/src/main/kotlin/app/sterna/widget/RecentMailWidgetDraw.kt")
        val MONOGRAM = file("app/src/main/kotlin/app/sterna/widget/WidgetMonogram.kt")
        val SERVICE = file("app/src/main/kotlin/app/sterna/widget/RecentMailWidgetService.kt")

        const val BACKGROUND =
            "views.setInt(R.id.widget_recent_root, \"setBackgroundResource\", " +
                "WidgetThemeReader.background(palette.surface))"

        const val ACCOUNT_DOT =
            "views.setInt(R.id.widget_recent_row_account, \"setColorFilter\", " +
                "row.accountColor ?: 0)"
    }
}
