package app.sterna.widget

import android.app.Application
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.SizeF
import android.widget.RemoteViews
import app.sterna.MainActivity
import app.sterna.R
import app.sterna.container
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The ONE way the widget is drawn, and the one place that knows which cells exist: a second
 */
internal object UnreadWidgetDraw {

    /** The cells the user has actually placed. Empty for the large majority of installs, and every
     *  caller checks it BEFORE reaching for the container: a widget nobody placed must not open
     *  Room. */
    fun placedIds(context: Context): IntArray =
        AppWidgetManager.getInstance(context)
            .getAppWidgetIds(ComponentName(context, UnreadWidgetProvider::class.java))

    /**
     * Draw [unreadByAccount] into [ids]. Not free — `accounts()` decodes the whole blob and
     */
    suspend fun draw(app: Application, ids: IntArray, unreadByAccount: Map<String, Int>) {
        if (ids.isEmpty()) return
        // The cell's colours, from the app's own theme settings and not from the system's night
        // mode (#117) — three DataStore flows, which is why draw() suspends. Null travels to both
        // renders and is drawn by posting no colour at all. Never a `?:` default here.
        val palette = WidgetThemeReader.palette(app)
        // One read of the account list, spent three times: what the cell is CALLED, what a tap
        // asks for, and the enlarged layout's lines. Two reads is how the first two would disagree.
        val store = app.container.accountStore
        val accounts = store.accounts()
        val target = UnreadWidgetTap.of(accounts)
        // The small layout's figure is this breakdown TOTALLED, never a second count.
        val small = tapped(app, renderTotal(app, UnreadWidgetContent.of(accounts, unreadByAccount.values.sum()), palette), target)
        // A locked app draws the total at every size: an account's name can be its owner's address.
        val rows = UnreadWidgetContent.byAccount(accounts, unreadByAccount, store.appLockEnabled())
        val views =
            // API 31+ only: below it the size-map constructor does not exist and the cell keeps
            // the layout it has always had.
            if (rows.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // The ventilated entry's height is DERIVED from how many lines there are: a
                // constant is a claim about how many accounts the reader has, and at four it cost
                // them one, cut off in silence. The font scale is read here, at draw time,
                // because the rest of the size rule must stay runnable in a test.
                val fontScale = app.resources.configuration.fontScale
                val ventilated = SizeF(VENTILATED_WIDTH_DP, ventilatedHeightDp(rows.size, fontScale))
                RemoteViews(mapOf(SMALL to small, ventilated to tapped(app, renderAccounts(app, rows, palette), target)))
            } else {
                small
            }
        val manager = AppWidgetManager.getInstance(app)
        ids.forEach { manager.updateAppWidget(it, views) }
    }

    suspend fun redraw(context: Context, unreadByAccount: Map<String, Int>) {
        val app = context.applicationContext as? Application ?: return
        draw(app, placedIds(app), unreadByAccount)
    }

    /** Read the count and draw it, for a caller with no number in hand. [withTimeoutOrNull] and
     *  not `withTimeout`: the caller re-throws CancellationException, so a slow widget draw would
     *  cancel the pass delivering the mail. Returning null costs a stale cell instead. */
    suspend fun refresh(context: Context) {
        val app = context.applicationContext as? Application ?: return
        val ids = placedIds(app)
        if (ids.isEmpty()) return
        val unreadByAccount = withTimeoutOrNull(REFRESH_TIMEOUT_MS) {
            app.container.mailRepository.observeUnifiedInboxUnreadByAccount().first()
        } ?: return
        draw(app, ids, unreadByAccount)
    }

    /** The only place in this file that attaches a tap, and a source lint holds it to one line.
     *  The whole cell is the target, not one of the labels, and both sizes go through here so they
     *  cannot open different things. */
    private fun tapped(context: Context, views: RemoteViews, target: WidgetTapTarget): RemoteViews =
        views.apply {
            setOnClickPendingIntent(R.id.widget_unread_root, tapIntent(context, target))
        }

    /**
     * The cell as it has always been: one figure, one label under it. It reads nothing — [palette]
     */
    private fun renderTotal(
        context: Context,
        state: UnreadWidgetState,
        palette: WidgetPalette?,
    ): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_unread).apply {
            if (palette != null) {
                setInt(R.id.widget_unread_root, "setBackgroundResource", WidgetThemeReader.background(palette.surface))
                setTextColor(R.id.widget_unread_count, palette.accent)
                setTextColor(R.id.widget_unread_label, palette.label)
            }
            setTextViewText(R.id.widget_unread_count, state.count.toString())
            setTextViewText(R.id.widget_unread_label, text(context, state.label))
        }

    /**
     * The enlarged cell: [rows] and nothing else. `removeAllViews` first, not because this object
     */
    private fun renderAccounts(
        context: Context,
        rows: List<UnreadAccountRow>,
        palette: WidgetPalette?,
    ): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_unread_accounts).apply {
            if (palette != null) {
                setInt(R.id.widget_unread_root, "setBackgroundResource", WidgetThemeReader.background(palette.surface))
            }
            removeAllViews(R.id.widget_unread_accounts)
            rows.forEach { row ->
                val line = RemoteViews(context.packageName, R.layout.widget_unread_account_row)
                line.setTextViewText(R.id.widget_unread_account_name, row.name)
                line.setTextViewText(R.id.widget_unread_account_count, row.count.toString())
                if (palette != null) {
                    line.setTextColor(R.id.widget_unread_account_name, palette.rowPrimary)
                    line.setTextColor(R.id.widget_unread_account_count, palette.accent)
                }
                addView(R.id.widget_unread_accounts, line)
            }
        }

    /**
     * What a tap opens (#112): the unified inbox when the app has one, the app plainly otherwise.
     */
    private fun tapIntent(context: Context, target: WidgetTapTarget): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(ACTION_WIDGET_TAP)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (target == WidgetTapTarget.UnifiedInbox) {
            intent.putExtra(MainActivity.EXTRA_OPEN_UNIFIED, true)
        }
        return PendingIntent.getActivity(
            context,
            TAP_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun text(context: Context, label: WidgetLabel): CharSequence = when (label) {
        is WidgetLabel.Translated -> context.getString(label.id, *label.args.toTypedArray())
        is WidgetLabel.Verbatim -> label.text
    }

    /** How long [refresh] may wait for the count before leaving the cell as it is: this runs at the
     *  end of a delivery pass holding a service awake. Nothing retries. */
    private const val REFRESH_TIMEOUT_MS = 5_000L

    /** The smaller of the two sizes offered to the launcher; it is the declared minimum of
     * `appwidget_info_unread.xml`, so a cell can never end up with nothing to draw. The other
     *  entry is not a constant beside it: it is the room this many lines need at this font scale,
     *  a fixed height having cut the fourth account off. */
    private val SMALL = SizeF(110f, 40f)

    /** The widget's own intent action — what keeps its `PendingIntent` from being a notification's
     *  (see [tapIntent]). Never declared in an `intent-filter`: the action's only job is to differ. */
    private const val ACTION_WIDGET_TAP = "app.sterna.widget.TAP"

    /** A belt rather than the mechanism, the action above being the disjunction: this number only
     * separates the widget's intent from itself. Do not "make it unique". */
    private const val TAP_REQUEST_CODE = 0
}

/**
 * Is at least one widget placed? — the condition [UnreadWidgetPush] gates its collection on, held
 */
internal object UnreadWidgetPresence {

    private val state = MutableStateFlow(false)

    @Volatile private var seeded = false

    fun placed(context: Context): StateFlow<Boolean> {
        if (!seeded) {
            seeded = true
            set(anyWidgetPlaced(UnreadWidgetDraw.placedIds(context)))
        }
        return state
    }

    /** The same flow WITHOUT the seed — what [set] writes into, and the handle a JVM test can
     *  hold: seeding needs a [Context] and the widget manager. */
    val live: StateFlow<Boolean> get() = state

    fun set(placed: Boolean) {
        state.value = placed
    }
}

/**
 * Does this set of placed cell ids mean the app has a widget? — the seed's decision, pulled out
 */
internal fun anyWidgetPlaced(ids: IntArray): Boolean = ids.isNotEmpty()
