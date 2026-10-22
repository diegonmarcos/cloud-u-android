package com.diegonmarcos.superapp.boards

import android.app.PendingIntent
import android.content.Context
import android.database.Cursor
import android.net.Uri
import com.diegonmarcos.superapp.devtools.FleetIpc
import com.diegonmarcos.superapp.launcher.AggregatorModel
// The PHONE feed (other apps' notifications captured by the listener), not core.NotificationStore — the in-app
// feed keeps exactly one reader (test-notification-centre-is-singular T4), and this file is not it.
import com.diegonmarcos.superapp.notificationcenter.PhoneNotificationStore as PhoneFeed
import org.json.JSONObject
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

/**
 * Boards (was Projects) — where each board's data comes from. Every board reads the app that OWNS the data; none of
 * them carries a number of its own. Each read answers [Read.Ok] or [Read.Unavailable] with the honest reason
 * (not installed, no permission, too old, failed), and the page prints that reason instead of an empty list.
 *
 *  - Inboxes: cloud-mail's agentmail provider (signature permission CONSTELLATION_DATA) for fleet mail, and the
 *    SuperApp's own notification listener (PhoneNotificationStore) for every other app — fleet chat / matrix / ntfy
 *    and third-party WhatsApp, Gmail, Telegram… — grouped by app.
 *  - My-PM: cloud-nav's tracker over the fleet IPC door (com.diegonmarcos.cloudnav.fleet, signature-guarded):
 *    `tracker/steps` and `tracker/places`.
 *  - Tasks: cloud-tasks over the same door: `projects/list`.
 *
 * Tests swap [Boards.source] for a fake; the parsers in [BoardsWire] are pure.
 */
interface BoardsSource {
    fun fleetMail(limit: Int = 30): Read<List<InboxItem>>
    fun phoneNotifications(): Read<List<InboxItem>>
    fun steps(days: Int = 7): Read<StepsBoard>
    /** My-Health: Health Connect read in this app (steps, distance, resting HR, sleep), 30 days ending today. */
    fun health(days: Int = 30): Read<HealthBoard> = Read.Unavailable(Why.FAILED, "Health Connect is not wired to this source")
    fun places(days: Int = 7): Read<List<PlacesDay>>
    fun taskProjects(): Read<List<TaskProject>>
}

sealed interface Read<out T> {
    data class Ok<T>(val value: T) : Read<T>
    data class Unavailable(val why: Why, val detail: String) : Read<Nothing>
}

enum class Why { NOT_INSTALLED, NO_PERMISSION, OUTDATED, FAILED }

/** One message. [target] is a routeTarget string (extapp:/app:); [threadKey] finds the notification's own
 *  contentIntent in [ThreadIntents] so a tap opens the thread, not only the app. */
data class InboxItem(
    val pkg: String,
    val appLabel: String,
    val sender: String,
    val preview: String,
    val ts: Long,
    val unread: Boolean,
    val target: String,
    val threadKey: String? = null,
    val fleet: Boolean = false,
)

data class InboxGroup(val pkg: String, val appLabel: String, val fleet: Boolean, val items: List<InboxItem>) {
    val newest: Long get() = items.maxOfOrNull { it.ts } ?: 0L
    val unread: Int get() = items.count { it.unread }
}

data class StepsDay(val date: LocalDate, val steps: Long)

/** [source] is HEALTH_CONNECT or DEVICE, [status] cloud-nav's SourceStatus (READY, NEEDS_PERMISSION, …). */
data class StepsBoard(
    val today: Long,
    val days: List<StepsDay>,
    val source: String,
    val status: String,
    val statusLine: String,
    val hint: String?,
) {
    val ready: Boolean get() = status == "READY" || status == "DEVICE_FALLBACK"
}

data class PlaceVisit(val part: String, val label: String, val lat: Double, val lon: Double, val predicted: Boolean)

data class PlacesDay(val date: LocalDate, val visits: List<PlaceVisit>, val steps: Long)

data class TaskProject(
    val id: String,
    val name: String,
    val open: Int,
    val done: Int,
    val nextDueName: String?,
    val nextDue: String?,
)

object Boards {
    const val MAIL_PKG = "com.diegonmarcos.comms.mail"
    const val NAV_PKG = "com.diegonmarcos.cloudnav"
    const val TASKS_PKG = "com.diegonmarcos.cloudagenda"

    /** Fleet comms apps: their notifications group under "Fleet" on the inbox, ahead of third-party apps. */
    val FLEET_COMMS = mapOf(
        MAIL_PKG to "extapp:cloud-mail",
        "com.diegonmarcos.comms.chat" to "extapp:cloud-chat",
        "com.diegonmarcos.comms.matrix" to "extapp:cloud-matrix",
        "io.element.android.x" to "extapp:cloud-matrix",
        "io.heckel.ntfy" to "app:io.heckel.ntfy",
    )

    /** Swapped by tests. */
    @Volatile var source: (Context) -> BoardsSource = { FleetBoardsSource(it.applicationContext) }

    /** Fleet mail first (when the provider answered, its own notifications are dropped as duplicates), then every
     *  other app; inside a group newest first; groups ordered fleet-first, then by newest message. */
    fun group(mail: List<InboxItem>, phone: List<InboxItem>): List<InboxGroup> {
        val dropMailNotifs = mail.isNotEmpty()
        val all = mail + phone.filter { !(dropMailNotifs && it.pkg == MAIL_PKG) }
        return all.groupBy { it.pkg }.map { (pkg, items) ->
            val sorted = items.sortedWith(compareByDescending<InboxItem> { it.unread }.thenByDescending { it.ts })
            InboxGroup(pkg, sorted.first().appLabel, sorted.any { it.fleet }, sorted)
        }.sortedWith(compareByDescending<InboxGroup> { it.fleet }.thenByDescending { it.newest })
    }
}

/** sbn.key -> the notification's contentIntent, filled by the listener; a tap on an inbox row sends it. In memory:
 *  a PendingIntent cannot be persisted, and after a restart the row falls back to opening the app. */
object ThreadIntents {
    private val map = ConcurrentHashMap<String, PendingIntent>()
    private const val MAX = 200
    fun put(key: String, pi: PendingIntent?) {
        if (pi == null) return
        if (map.size >= MAX) map.keys.firstOrNull()?.let { map.remove(it) }
        map[key] = pi
    }
    fun get(key: String?): PendingIntent? = key?.let { map[it] }
}

/** The JSON each fleet route answers, parsed. Pure, so a JVM test holds the wire. */
object BoardsWire {
    fun steps(body: String): StepsBoard {
        val o = JSONObject(body)
        val arr = o.optJSONArray("days")
        val days = (0 until (arr?.length() ?: 0)).map { i ->
            val d = arr!!.getJSONObject(i)
            StepsDay(LocalDate.parse(d.getString("date")), d.optLong("steps"))
        }.sortedBy { it.date }
        return StepsBoard(
            today = o.optLong("today"),
            days = days,
            source = o.optString("source"),
            status = o.optString("status"),
            statusLine = o.optString("status_line"),
            hint = o.optString("hint").takeIf { it.isNotBlank() },
        )
    }

    fun places(body: String): List<PlacesDay> {
        val arr = JSONObject(body).optJSONArray("days") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val d = arr.getJSONObject(i)
            val v = d.optJSONArray("visits")
            PlacesDay(
                date = LocalDate.parse(d.getString("date")),
                steps = d.optLong("steps"),
                visits = (0 until (v?.length() ?: 0)).map { j ->
                    val p = v!!.getJSONObject(j)
                    PlaceVisit(p.optString("part"), p.optString("label"), p.optDouble("lat"), p.optDouble("lon"),
                        p.optBoolean("predicted"))
                },
            )
        }.sortedByDescending { it.date }
    }

    fun projects(body: String): List<TaskProject> {
        val arr = JSONObject(body).optJSONArray("projects") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val p = arr.getJSONObject(i)
            TaskProject(
                id = p.getString("id"),
                name = p.optString("name"),
                open = p.optInt("open"),
                done = p.optInt("done"),
                nextDueName = p.optString("next_due_name").takeIf { it.isNotBlank() },
                nextDue = p.optString("next_due").takeIf { it.isNotBlank() },
            )
        }
    }
}

/**
 * The ONE Health Connect route: [HealthBoardReader] then the pure [HealthAgg]. Boards > MyHealth and the Notify MyHealth
 * card both come through here, so they can never disagree. Cancellable, so a caller can put a timeout on it.
 */
suspend fun readHealthBoard(ctx: Context, days: Int = 30, today: LocalDate = LocalDate.now()): Read<HealthBoard> = try {
    val raw = com.diegonmarcos.superapp.health.HealthBoardReader.read(ctx, days, today)
    when (raw.status) {
        com.diegonmarcos.superapp.health.HealthBoardReader.Status.UPDATE_REQUIRED ->
            Read.Unavailable(Why.OUTDATED, "Health Connect needs an update before it can be read")
        com.diegonmarcos.superapp.health.HealthBoardReader.Status.UNAVAILABLE ->
            Read.Unavailable(Why.NOT_INSTALLED, "Health Connect is not available on this phone")
        else -> Read.Ok(HealthAgg.board(raw, today))
    }
} catch (e: kotlinx.coroutines.CancellationException) {
    throw e
} catch (e: Exception) {
    Read.Unavailable(Why.FAILED, "Health Connect: ${e.message ?: e.javaClass.simpleName}")
}

/** The real reads. Blocking: call off the main thread. */
class FleetBoardsSource(private val ctx: Context) : BoardsSource {

    override fun fleetMail(limit: Int): Read<List<InboxItem>> {
        val authority = "$MAIL_AUTH"
        if (ctx.packageManager.resolveContentProvider(authority, 0) == null)
            return Read.Unavailable(Why.NOT_INSTALLED, "Cloud Mail is not installed, or too old to share its inbox")
        val since = System.currentTimeMillis() - 14L * 86_400_000L
        val uri = Uri.parse("content://$authority/messages?limit=$limit&since=$since")
        return try {
            val rows = ctx.contentResolver.query(uri, null, null, null, null)?.use { c -> mailRows(c) }
                ?: return Read.Unavailable(Why.FAILED, "Cloud Mail did not answer")
            Read.Ok(rows)
        } catch (e: SecurityException) {
            Read.Unavailable(Why.NO_PERMISSION, "Cloud Mail refused: this build is not signed with the fleet key")
        } catch (e: Exception) {
            Read.Unavailable(Why.FAILED, "Cloud Mail: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun mailRows(c: Cursor): List<InboxItem> {
        val out = ArrayList<InboxItem>()
        val iName = c.getColumnIndex("from_name"); val iMail = c.getColumnIndex("from_email")
        val iSubj = c.getColumnIndex("subject"); val iSort = c.getColumnIndex("sort_key")
        val iRecv = c.getColumnIndex("received_at"); val iSeen = c.getColumnIndex("seen")
        while (c.moveToNext()) {
            val name = c.getString(iName).orEmpty(); val mail = c.getString(iMail).orEmpty()
            val ts = when {
                iSort >= 0 && c.getType(iSort) == Cursor.FIELD_TYPE_INTEGER -> c.getLong(iSort)
                iRecv >= 0 && c.getType(iRecv) == Cursor.FIELD_TYPE_INTEGER -> c.getLong(iRecv)
                else -> 0L
            }
            out += InboxItem(MAIL_PKG, "Cloud Mail", name.ifBlank { mail }, c.getString(iSubj).orEmpty(), ts,
                unread = c.getInt(iSeen) == 0, target = "extapp:cloud-mail", fleet = true)
        }
        return out
    }

    override fun phoneNotifications(): Read<List<InboxItem>> {
        if (!AggregatorModel.isNotificationAccessGranted(ctx))
            return Read.Unavailable(Why.NO_PERMISSION, "Notification Access is off, so other apps' messages cannot be read")
        return Read.Ok(PhoneFeed.all(ctx).map { e ->
            val fleetTarget = Boards.FLEET_COMMS[e.packageName]
            InboxItem(e.packageName, e.appLabel, e.title, e.text, e.ts, unread = true,
                target = fleetTarget ?: "app:${e.packageName}", threadKey = e.key, fleet = fleetTarget != null)
        })
    }

    override fun steps(days: Int): Read<StepsBoard> =
        ask(Boards.NAV_PKG, "Cloud Nav", "tracker/steps", mapOf("days" to days.toString())) { BoardsWire.steps(it) }

    override fun health(days: Int): Read<HealthBoard> =
        try { kotlinx.coroutines.runBlocking { readHealthBoard(ctx, days) } }
        catch (e: Exception) { Read.Unavailable(Why.FAILED, "Health Connect: ${e.message ?: e.javaClass.simpleName}") }

    override fun places(days: Int): Read<List<PlacesDay>> =
        ask(Boards.NAV_PKG, "Cloud Nav", "tracker/places", mapOf("days" to days.toString())) { BoardsWire.places(it) }

    override fun taskProjects(): Read<List<TaskProject>> =
        ask(Boards.TASKS_PKG, "Cloud Tasks", "projects/list", emptyMap()) { BoardsWire.projects(it) }

    private fun <T> ask(pkg: String, app: String, op: String, q: Map<String, String>, parse: (String) -> T): Read<T> {
        if (!FleetIpc.hasDoor(ctx, pkg)) return Read.Unavailable(Why.NOT_INSTALLED, "$app is not installed")
        val r = FleetIpc.call(ctx, pkg, op, q, httpFallback = false)
        return when {
            r.ok -> runCatching { Read.Ok(parse(r.body)) }
                .getOrElse { Read.Unavailable(Why.FAILED, "$app answered something unreadable") }
            r.status == 403 -> Read.Unavailable(Why.NO_PERMISSION, "$app refused: not signed with the fleet key")
            r.status == 404 -> Read.Unavailable(Why.OUTDATED, "$app is too old to share this (no $op) — update it")
            else -> Read.Unavailable(Why.FAILED, "$app did not answer ($op: ${r.status} ${r.note})".trim())
        }
    }

    private companion object {
        const val MAIL_AUTH = "${Boards.MAIL_PKG}.agentmail"
        const val MAIL_PKG = Boards.MAIL_PKG
    }
}
