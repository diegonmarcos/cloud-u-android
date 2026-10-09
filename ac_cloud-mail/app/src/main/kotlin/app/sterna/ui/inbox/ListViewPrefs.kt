package app.sterna.ui.inbox

import android.content.Context
import app.sterna.core.data.mail.AuthFilter
import app.sterna.core.data.mail.ListShape
import app.sterna.core.data.settings.SortOrder

/** How rows are grouped: threads ("by subject"), by sender address, by sender domain, or not at all. */
internal enum class GroupMode { SUBJECT, SENDER, DOMAIN, NONE }

/** How rows are ranked. Newest first is the default. */
internal enum class RankMode(val order: SortOrder) {
    NEWEST(SortOrder.DATE_DESC),
    SENDER(SortOrder.SENDER),
    SUBJECT(SortOrder.SUBJECT),
}

/** The combinable filters. UNREAD is the old funnel, now one filter among the others. */
internal enum class ListFilter {
    STARRED, UNREAD, ATTACHMENTS,

    /** Rule "G0 _ Auth": Ga Code, Gb Link to auth, or either. Exclusive among themselves. */
    AUTH_CODES, AUTH_LINKS, AUTH_ANY;

    val isAuth get() = this == AUTH_CODES || this == AUTH_LINKS || this == AUTH_ANY
}

/**
 * One folder's view choices. A null [group] or [rank] means "not chosen here": the global settings
 * (conversation view, sort order) apply, so a folder nobody touched looks exactly as it always did.
 */
internal data class ListView(
    val group: GroupMode? = null,
    val rank: RankMode? = null,
    val filters: Set<ListFilter> = emptySet(),
) {
    fun effectiveGroup(globalConversation: Boolean): GroupMode =
        group ?: if (globalConversation) GroupMode.SUBJECT else GroupMode.NONE

    fun effectiveSort(globalSort: SortOrder): SortOrder = rank?.order ?: globalSort

    fun shape(): ListShape = ListShape(
        starred = ListFilter.STARRED in filters,
        attachments = ListFilter.ATTACHMENTS in filters,
        auth = when {
            ListFilter.AUTH_ANY in filters -> AuthFilter.ANY
            ListFilter.AUTH_CODES in filters -> AuthFilter.CODES
            ListFilter.AUTH_LINKS in filters -> AuthFilter.LINKS
            else -> AuthFilter.OFF
        },
        bySender = group == GroupMode.SENDER,
        byDomain = group == GroupMode.DOMAIN,
    )

    /** Whether the three icon groups should read as active: any non-default choice is set. */
    fun viewModeActive(globalConversation: Boolean): Boolean =
        effectiveGroup(globalConversation) != (if (globalConversation) GroupMode.SUBJECT else GroupMode.NONE)

    fun rankActive(globalSort: SortOrder): Boolean = effectiveSort(globalSort) != SortOrder.DATE_DESC

    fun filterActive(): Boolean = filters.isNotEmpty()

    /** Toggle [filter]; choosing one auth class lifts the others (they are exclusive). */
    fun toggled(filter: ListFilter): ListView = copy(
        filters = when {
            filter in filters -> filters - filter
            filter.isAuth -> filters.filterNot { it.isAuth }.toSet() + filter
            else -> filters + filter
        },
    )

    /** `group;rank;FILTER,FILTER` — empty fields are "not chosen". */
    fun encode(): String =
        listOf(group?.name.orEmpty(), rank?.name.orEmpty(), filters.sorted().joinToString(",")).joinToString(";")

    companion object {
        fun decode(raw: String?): ListView {
            val parts = raw.orEmpty().split(';')
            fun <T> pick(i: Int, parse: (String) -> T): T? =
                parts.getOrNull(i)?.takeIf { it.isNotEmpty() }?.let { runCatching { parse(it) }.getOrNull() }
            return ListView(
                group = pick(0) { GroupMode.valueOf(it) },
                rank = pick(1) { RankMode.valueOf(it) },
                filters = parts.getOrNull(2).orEmpty().split(',')
                    .mapNotNull { f -> runCatching { ListFilter.valueOf(f) }.getOrNull() }.toSet(),
            )
        }
    }
}

/**
 * Persistence of [ListView] per account and folder, in this app's own preferences file `list_view`
 * (declared in fleet-config.json). The key names the view, never a bare folder id: servers number
 * folders per account.
 */
internal object ListViewPrefs {
    private const val FILE = "list_view"

    fun keyFor(accountId: String?, folderId: String?) = "${accountId.orEmpty()}|${folderId.orEmpty()}"

    fun load(context: Context, key: String): ListView =
        ListView.decode(prefs(context).getString(key, null))

    fun save(context: Context, key: String, view: ListView) {
        prefs(context).edit().apply {
            if (view == ListView()) remove(key) else putString(key, view.encode())
        }.apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
