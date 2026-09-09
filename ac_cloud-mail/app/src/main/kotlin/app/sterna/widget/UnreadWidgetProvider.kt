package app.sterna.widget

import android.app.Application
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.util.Log
import app.sterna.container
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * The home-screen unread counter: one number and the name of what it counts — or, on a large enough
 */
class UnreadWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        val app = context.applicationContext as? Application ?: return
        // onUpdate runs on the main thread and is over the moment it returns, while reading the
        // count opens the account store and Room. goAsync() holds the process at receiver
        // importance until finish(), which EVERY path below reaches: never finishing leaks the
        // broadcast and the system kills the process after ~10 s, finishing twice throws.
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val container = app.container
                // One emission, then done — a draw, not a subscription. BOUNDED: on a cold process
                // first() waits for Room to open and any migration to run, and if it never emits
                // the PendingResult is never finished and the system kills the process.
                val unreadByAccount = withTimeout(DRAW_TIMEOUT_MS) {
                    container.mailRepository.observeUnifiedInboxUnreadByAccount().first()
                }
                UnreadWidgetDraw.draw(app, appWidgetIds, unreadByAccount)
            } catch (t: Throwable) {
                // An exception escaping a coroutine takes the process down, and a widget that could
                // not be drawn must cost no more than a stale cell. NOTHING is drawn on this
                Log.w(TAG, "Unread widget not redrawn; the cell keeps what it last showed", t)
            } finally {
                pending.finish()
            }
        }
    }

    /**
     * The FIRST cell was placed — from here on the app has a reason to watch the unread total.
     */
    override fun onEnabled(context: Context) {
        UnreadWidgetPresence.set(true)
    }

    /**
     * The LAST cell went. The collection in `AppContainer` stops on this, and the app goes back to
     * costing a user who has no widget exactly nothing.
     */
    override fun onDisabled(context: Context) {
        UnreadWidgetPresence.set(false)
    }

    private companion object {
        const val TAG = "UnreadWidget"

        /**
         * How long the draw may wait for the count before giving the broadcast back undrawn. 5 s,
         */
        const val DRAW_TIMEOUT_MS = 5_000L
    }
}
