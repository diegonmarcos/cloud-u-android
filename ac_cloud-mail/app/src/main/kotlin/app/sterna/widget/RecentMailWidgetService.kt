package app.sterna.widget

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import app.sterna.R
import app.sterna.ui.components.ToneRamp
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The rows of the latest-messages widget, served to the launcher. A list in a widget NEEDS this
 */
class RecentMailWidgetService : RemoteViewsService() {

    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        RecentMailWidgetFactory(applicationContext)
}

/** PLUMBING ONLY: every decision drawn here was taken in [RecentMailWidgetContent], where a JVM
 *  test can run it. A `RemoteViewsFactory` cannot be executed in that suite. */
internal class RecentMailWidgetFactory(private val context: Context) : RemoteViewsService.RemoteViewsFactory {

    /** What [getViewAt] serves, replaced whole by [onDataSetChanged] and never edited in place. */
    @Volatile
    private var rows: List<RecentWidgetItem> = emptyList()

    /** The colours [getViewAt] paints those rows in — from the SAME drawing as [rows], never fetched
     * here. Null is a real state, left uncoloured on purpose: a row drawn with no `setTextColor`
     * keeps the colour its layout froze in, stale rather than wrong. A hard-coded default palette
     *  here would be this file deciding a colour on the one path where nothing is known. */
    @Volatile
    private var palette: WidgetPalette? = null

    /** The tones a row's monogram badge is painted in. Null here means "the colour read did not
     *  answer": a badge is a BITMAP and there is no `values-night` behind one, so null is drawn as
     *  NO BADGE, and a hard-coded ramp would be the one thing the pane forbids. */
    @Volatile
    private var monogramRamps: List<ToneRamp>? = null

    override fun onCreate() = Unit

    /** The launcher's "refetch" — called on a binder thread and allowed to block, which is what makes
     * a database read legal here. [withTimeoutOrNull] and not `withTimeout`, whose
     * Nothing escapes: an exception from a binder callback takes the process with it. */
    override fun onDataSetChanged() {
        val app = context.applicationContext as? Application ?: return
        val read = try {
            runBlocking { withTimeoutOrNull(READ_TIMEOUT_MS) { RecentMailWidgetDraw.read(app) } }
        } catch (t: Throwable) {
            Log.w(TAG, "Latest-messages widget not refreshed; the cell keeps what it last showed", t)
            null
        }
        // Failing that, whatever the last read left behind; failing THAT, nothing is drawn.
        // Rows and colours come out of the SAME drawing, in one expression, or a read landing in
        // between would mix the colours of one moment with the rows of another.
        val drawing = read ?: RecentMailWidgetDraw.drawing ?: return
        rows = drawing.snapshot.rows
        palette = drawing.colours?.palette
        monogramRamps = drawing.colours?.monogramRamps
    }

    override fun onDestroy() {
        rows = emptyList()
        palette = null
        monogramRamps = null
    }

    override fun getCount(): Int = rows.size

    /** One row, already decided. The second line is GONE and not blank when there is nothing to
     *  write: an empty `TextView` still takes its line's height. */
    override fun getViewAt(position: Int): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_recent_row)
        // The launcher can ask for a position from a list we have since replaced, and an
        // out-of-range read would throw on a binder thread. The blank row is written blank rather
        // than returned untouched: a RemoteViews with no setTextViewText leaves the recycled view
        // showing the row it last drew.
        val item = rows.getOrNull(position)
        if (item == null) {
            views.setTextViewText(R.id.widget_recent_row_primary, "")
            views.setTextViewText(R.id.widget_recent_row_secondary, "")
            views.setTextViewText(R.id.widget_recent_row_date, "")
            // The badge is CLEARED, not merely hidden: a recycled ImageView holds the disc of
            // whatever row it last drew.
            views.setImageViewBitmap(R.id.widget_recent_row_monogram, null)
            views.setViewVisibility(R.id.widget_recent_row_monogram, View.INVISIBLE)
            // Nothing has to be said about the WEIGHT, a bare String replacing a SpannableString
            // whole. The account dot needs its own line for the recycling reason above.
            views.setViewVisibility(R.id.widget_recent_row_account, View.INVISIBLE)
            return views
        }
        val row = item.drawn
        // The app's own theme, from the same read as the rows (#112, #117). The sender takes
        // `rowPrimary` and the two quieter lines `label`. The account dot is NOT painted from
        // here — its colour says which mailbox the message came from.
        val colours = palette
        if (colours != null) {
            views.setTextColor(R.id.widget_recent_row_primary, colours.rowPrimary)
            views.setTextColor(R.id.widget_recent_row_secondary, colours.label)
            views.setTextColor(R.id.widget_recent_row_date, colours.label)
        }
        views.setTextViewText(R.id.widget_recent_row_primary, weighted(row.primary, row.unread))
        views.setTextViewText(R.id.widget_recent_row_secondary, weighted(row.secondary.orEmpty(), row.unread))
        views.setViewVisibility(
            R.id.widget_recent_row_secondary,
            if (row.secondary == null) View.GONE else View.VISIBLE,
        )
        views.setTextViewText(R.id.widget_recent_row_date, weighted(row.date, row.unread))
        // The badge, as a Bitmap because a RemoteViews has no other way to carry a coloured letter.
        // INVISIBLE and never GONE, like the dot below, or this row's sender would start at a
        // different x. The bitmap is posted on BOTH paths, a null clearing the recycled disc.
        val badge = monogramFor(row)
        views.setImageViewBitmap(R.id.widget_recent_row_monogram, badge)
        views.setViewVisibility(
            R.id.widget_recent_row_monogram,
            if (badge == null) View.INVISIBLE else View.VISIBLE,
        )
        // Which account this line came from, as a colour and never as a name. setColorFilter and
        // NOT setBackgroundColor, which replaces the drawable with a flat fill. No `?.let`, the view
        // being INVISIBLE whenever the colour is null.
        views.setInt(R.id.widget_recent_row_account, "setColorFilter", row.accountColor ?: 0)
        views.setViewVisibility(
            R.id.widget_recent_row_account,
            if (row.accountColor == null) View.INVISIBLE else View.VISIBLE,
        )
        // What a tap on THIS row asks for, as extras merged into the list's template. On the row's
        // ROOT: on a TextView the cell would only react on the sender's name.
        views.setOnClickFillInIntent(R.id.widget_recent_row_root, RecentMailWidgetDraw.rowFillIn(item.tap))
        return views
    }

    /**
     * The disc this row wears, or null — two ways of having none, and they differ. `row.monogram ==
     */
    private fun monogramFor(row: RecentWidgetRow): Bitmap? {
        val monogram = row.monogram ?: return null
        val ramps = monogramRamps ?: return null
        return WidgetMonogram.badge(context, monogram, ramps)
    }

    /**
     * [text] in bold when the row is unread — this cell's ONLY unread mark, and the message list's.
     */
    private fun weighted(text: String, unread: Boolean): CharSequence =
        if (!unread) text
        else SpannableString(text).apply {
            setSpan(StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount(): Int = 1

    override fun getItemId(position: Int): Long = position.toLong()

    override fun hasStableIds(): Boolean = false

    private companion object {
        const val TAG = "RecentMailWidget"

        /** How long a refetch may wait: shorter than the provider's budget, this one blocking a
         *  binder thread the launcher is holding. */
        const val READ_TIMEOUT_MS = 4_000L
    }
}
