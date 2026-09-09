package app.sterna.widget

import android.app.Application
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import app.sterna.MainActivity
import app.sterna.R
import app.sterna.container
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * ONE read, and everything the two ends of the cell spend it on: the rows, the palette they are drawn
 */
internal data class RecentWidgetDrawing(val snapshot: RecentWidgetSnapshot, val colours: WidgetColours?)

/** The ONE way the latest-messages widget is read and drawn. The FRAME is a `RemoteViews` this
 *  object builds; the ROWS are served by the launcher pulling on a `RemoteViewsFactory` on its own
 *  schedule. Both ends spend the SAME read, so they cannot come from two different moments. */
internal object RecentMailWidgetDraw {

    /** The cells the user has actually placed — checked before the container: a widget nobody
     *  placed must not open Room. */
    fun placedIds(context: Context): IntArray =
        AppWidgetManager.getInstance(context)
            .getAppWidgetIds(ComponentName(context, RecentMailWidgetProvider::class.java))

    /** The last read that ARRIVED, colours included. Null means no read has answered yet, and a
     *  caller then draws nothing rather than an empty list, which is a claim ("no mail"). */
    @Volatile
    private var held: RecentWidgetDrawing? = null

    /** What the factory serves between reads. */
    val drawing: RecentWidgetDrawing? get() = held

    /** Drop the held snapshot, because what it was computed FROM has changed: the factory falls back
     *  to [held] when its own read times out, and the moment the content setting is tightened that
     *  fallback would redraw the very senders the reader has just asked to hide. */
    fun forget() {
        held = null
    }

    /** Read the setting, the lock, the accounts, the mail and the theme, and project them — the only
     * place that does. The settings are read FIRST and waited for: drawing before these DataStore
     * flows answer would put a subject on the home screen of a reader who asked for NONE. Not
     *  bounded here — the caller bounds it, and the two callers hold different budgets. */
    suspend fun read(app: Application): RecentWidgetDrawing {
        val container = app.container
        val content = container.settingsRepository.notificationContent.first()
        // The sender-initials switch (#144), never defaulted here: a fourth copy of
        // LIST_MONOGRAM_DEFAULT would be free to disagree with the repository.
        val listMonogram = container.settingsRepository.listMonogram.first()
        val appLockEnabled = container.accountStore.appLockEnabled()
        // ONE read of the account list, not one per row, and two reads may answer differently.
        val accounts = container.accountStore.accounts()
        val inbox = container.mailRepository.recentUnifiedInbox(ROW_LIMIT)
        val snapshot = RecentMailWidgetContent.snapshot(
            inbox = inbox,
            content = content,
            appLockEnabled = appLockEnabled,
            listMonogram = listMonogram,
            unknownSender = app.getString(R.string.message_unknown_sender),
            noSubject = app.getString(R.string.message_no_subject),
            generic = app.getString(R.string.widget_latest_hidden),
            accountColors = RecentMailWidgetContent.accountColors(accounts),
        )
        // The cell's colours, from the app's OWN theme settings and no longer from the system's
        // night mode (#117), read here so frame and rows agree. Null is carried as-is. No `?:`.
        val colours = WidgetThemeReader.colours(app)
        val drawing = RecentWidgetDrawing(snapshot, colours)
        held = drawing
        return drawing
    }

    /**
     * Draw the frame of every cell in [ids] and ask the launcher to pull the rows again. One
     */
    fun draw(app: Application, ids: IntArray, drawing: RecentWidgetDrawing) {
        if (ids.isEmpty()) return
        val snapshot = drawing.snapshot
        val palette = drawing.colours?.palette
        val manager = AppWidgetManager.getInstance(app)
        val notice = RecentMailWidgetContent.notice(snapshot.cell)?.let { app.getString(it) } ?: app.getString(R.string.app_name)
        ids.forEach { id ->
            val views = RemoteViews(app.packageName, R.layout.widget_recent)
            if (palette != null) {
                views.setInt(R.id.widget_recent_root, "setBackgroundResource", WidgetThemeReader.background(palette.surface))
                views.setTextColor(R.id.widget_recent_notice, palette.label)
            }
            views.setTextViewText(R.id.widget_recent_notice, notice)
            @Suppress("DEPRECATION") // RemoteCollectionItems is API 31+; minSdk here is 26.
            views.setRemoteAdapter(R.id.widget_recent_list, adapterIntent(app, id))
            // ONE template for every row — the platform's only way of making the rows of a
            // collection clickable. Without it the rows draw and swallow taps.
            views.setPendingIntentTemplate(R.id.widget_recent_list, rowTapTemplate(app))
            // The notice takes the cell whenever the list is empty; the launcher swaps the two.
            views.setEmptyView(R.id.widget_recent_list, R.id.widget_recent_notice)
            manager.updateAppWidget(id, views)
            // AFTER updateAppWidget, which is what binds the adapter.
            manager.notifyAppWidgetViewDataChanged(id, R.id.widget_recent_list)
        }
    }

    /** Read and draw every placed cell. `updatePeriodMillis="0"` means the system asks at placement,
     *  at boot and after an update and at no other time, so without this a message that arrived an
     *  mail. [placedIds] is asked first, so nothing here opens Room for the majority. */
    suspend fun refresh(context: Context) {
        val app = context.applicationContext as? Application ?: return
        val ids = placedIds(app)
        if (ids.isEmpty()) return
        val drawing = withTimeoutOrNull(REFRESH_TIMEOUT_MS) { read(app) } ?: return
        draw(app, ids, drawing)
    }

    /** The intent the launcher binds the row factory with. The data URI is not decoration:
     *  `RemoteViews` compares adapter intents by `filterEquals`, which ignores extras, so two cells
     *  would otherwise share one factory and show the same rows. */
    private fun adapterIntent(context: Context, widgetId: Int): Intent =
        Intent(context, RecentMailWidgetService::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
        }

    /**
     * The ONE `PendingIntent` behind every row of a cell — a TEMPLATE, the only shape the platform
     */
    private fun rowTapTemplate(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(ACTION_ROW_TAP)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            ROW_TAP_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
    }

    /** What ONE row adds to the template when it is touched: the identity of its message, and
     *  strictly nothing else — the three extras a tapped notification already carries, so a row opens
     *  app starts. */
    fun rowFillIn(tap: RecentRowTap): Intent {
        val intent = Intent()
        intent.putExtra(MainActivity.EXTRA_OPEN_EMAIL_ID, tap.emailId)
        tap.accountId?.let { intent.putExtra(MainActivity.EXTRA_OPEN_ACCOUNT_ID, it) }
        tap.mailboxId?.let { intent.putExtra(MainActivity.EXTRA_OPEN_MAILBOX_ID, it) }
        return intent
    }

    /** This widget's own intent action, and NOT the counter's: it is what keeps the row template
     *  from being filterEquals-equal to a notification's intent and to the counter's. */
    private const val ACTION_ROW_TAP = "app.sterna.widget.OPEN_RECENT"

    /** A belt rather than the mechanism, the action above being the real defence. It must not be
     *  the counter's `TAP_REQUEST_CODE`, or the two templates would differ by their action alone.
     * Do not "make it unique" against the notifications either — no number is. */
    private const val ROW_TAP_REQUEST_CODE = 1

    /** How many messages the cell may list, lower than the read's own cap: nobody scrolls a
     *  home-screen cell far, and every row costs a `RemoteViews` sent over binder at each refresh. */
    const val ROW_LIMIT = 25

    /** How long [refresh] may wait before leaving the cell as it is: this runs at the end of a
     *  delivery pass holding a service awake. Nothing retries — the next arrival draws again. */
    private const val REFRESH_TIMEOUT_MS = 5_000L
}
