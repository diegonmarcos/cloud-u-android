package app.sterna.ui.search

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import app.sterna.container
import app.sterna.R
import app.sterna.core.data.account.StoredAccount
import app.sterna.core.jmap.model.Email
import app.sterna.core.jmap.model.SearchQuery
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface SearchState {
    data object Idle : SearchState
    data object Searching : SearchState
    /**
     * [query] is the query these results came from — NOT the one being edited, so the folded panel
     */
    data class Results(
        val query: SearchQuery,
        val emails: List<Email>,
        val complete: Boolean = true,
    ) : SearchState
    data class Error(val message: String) : SearchState
}

/**
 * Lazy-list key for a result row. Account + id, never the id alone: search spans every account and
 */
internal fun searchResultKey(email: Email): String = "${email.accountId}|${email.id}"

/**
 * The same ceiling as the inbox's own search bar: the "advanced" screen returning fewer messages
 * than the plain field for the same words made no sense to anyone.
 */
private const val SEARCH_LIMIT = 200

/**
 * One criterion as the screen opens: what was [saved] wins over the [argument] the caller navigated
 */
internal fun initialCriterion(saved: String?, argument: String?): String = saved ?: argument.orEmpty()

/** Nav argument: the words already typed in the inbox's search bar, so they aren't retyped here. */
const val SEARCH_QUERY_ARG = "q"

/**
 * Nav argument: the address the screen opens on, so "search this sender" lands on a search that is
 */
const val SEARCH_FROM_ARG = "from"

/**
 * Nav argument: open with the "flagged" criterion already on AND the search already run. This is
 * what the drawer's Starred entry navigates to, and it is the whole of that feature.
 *
 * ⛔ STARRED IS NOT A MAILBOX AND THIS ARGUMENT IS WHY IT NEVER BECOMES ONE. `$flagged` is a
 * KEYWORD on the message (RFC 8621 §4.1.1), not a container, so "starred mail" is a QUERY:
 * `Email/query { hasKeyword: "$flagged" }` on JMAP, `SEARCH FLAGGED` on IMAP — both of which
 * `repo.search` already issues for [SearchQuery.flagged]. Routing the drawer entry through here
 * means no Mailbox is created on the server, no message changes folders, and there is exactly ONE
 * implementation of "show me my starred mail" rather than a second one that would drift from this.
 *
 * It also answers `SearchScreen`'s own objection to a drawer entry — that one "could only list what
 * the cache holds". This one does not: it asks the server, like every other search on that screen.
 */
const val SEARCH_FLAGGED_ARG = "flagged"

private const val KEY_TEXT = "form.text"
private const val KEY_FROM = "form.from"
private const val KEY_RECIPIENT = "form.recipient"
private const val KEY_SUBJECT = "form.subject"
private const val KEY_ATTACHMENT = "form.attachment"
private const val KEY_FLAGGED = "form.flagged"
private const val KEY_AFTER = "form.after"
private const val KEY_BEFORE = "form.before"
private const val KEY_EXPANDED = "form.expanded"
private const val KEY_SUBMITTED = "form.submitted"

class SearchViewModel(
    application: Application,
    private val handle: SavedStateHandle,
) : AndroidViewModel(application) {
    private val store = application.container.accountStore
    private val repo = application.container.mailRepository

    /**
     * The query lives HERE, next to the results, not in the screen's `remember`s: split between the
     * two, a process death restored a folded panel with no criteria and no results.
     */
    private val _form = MutableStateFlow(restoreForm())
    val form: StateFlow<SearchForm> = _form.asStateFlow()

    private val _state = MutableStateFlow<SearchState>(SearchState.Idle)
    val state = _state.asStateFlow()

    /** Accounts, for the result rows' account pill (shown only when there is more than one). */
    val accounts: StateFlow<List<StoredAccount>> = store.accountsFlow

    init {
        // The screen was showing results when the process died: run the restored criteria again
        // rather than come back to an empty list under a summary that promises hits.
        if (handle.get<Boolean>(KEY_SUBMITTED) == true && !_form.value.query.isEmpty()) {
            run(_form.value.query)
        } else if (arrivedPreRun()) {
            // The drawer's Starred entry hands the criterion over and expects a FOLDER: a list of
            // starred mail, not a form somebody else filled in. [search] and not [run], so the
            // panel folds exactly as the Search button folds it — one path, one behaviour.
            search()
        }
    }

    /**
     * Whether this screen was opened by [SEARCH_FLAGGED_ARG] and has not been used yet, so the
     * search should run itself.
     *
     * The `== null` is load-bearing and is NOT `!= true`: [KEY_SUBMITTED] is written on every
     * [run], so "absent" is the only value that means "nobody has searched on this screen yet".
     * Read it as `!= true` and clearing the criteria would re-run the starred search on the next
     * recreation, putting results back under a form the reader had just emptied on purpose.
     */
    private fun arrivedPreRun(): Boolean =
        handle.get<Boolean>(SEARCH_FLAGGED_ARG) == true && handle.get<Boolean>(KEY_SUBMITTED) == null

    fun updateQuery(query: SearchQuery) {
        _form.value = _form.value.copy(query = query)
        persist()
    }

    /** The toolbar toggle and the summary row: show the criteria again to change them. */
    fun togglePanel() {
        _form.value = _form.value.toggled()
        persist()
    }

    /**
     * The drag handle's end-state: the swipe decides open or closed and sets it outright rather than
     * flipping, keeping [SearchForm.expanded] the single source of truth.
     */
    fun setExpanded(expanded: Boolean) {
        if (_form.value.expanded == expanded) return
        _form.value = _form.value.copy(expanded = expanded)
        persist()
    }

    /** Run the current criteria; the panel folds away so the results get the screen. */
    fun search() {
        _form.value = _form.value.afterSearch()
        persist()
        run(_form.value.query)
    }

    private fun run(query: SearchQuery) {
        if (query.isEmpty()) {
            _state.value = SearchState.Idle
            handle[KEY_SUBMITTED] = false
            return
        }
        handle[KEY_SUBMITTED] = true
        _state.value = SearchState.Searching
        viewModelScope.launch {
            try {
                // Global search screen: span every account, not just the active one.
                val credentials = store.allCredentials()
                if (credentials.isEmpty()) {
                    error(getApplication<Application>().getString(R.string.status_no_saved_account))
                }
                val result = repo.search(credentials, query, SEARCH_LIMIT)
                _state.value = SearchState.Results(query, result.emails, result.complete)
            } catch (t: Throwable) {
                _state.value = SearchState.Error(t.message ?: t.javaClass.simpleName)
            }
        }
    }

    private fun restoreForm(): SearchForm = SearchForm(
        query = SearchQuery(
            // The inbox's search bar hands its words over as a nav argument; a value saved here
            // wins over it after a process death. Both criteria go through the SAME decision.
            text = initialCriterion(handle[KEY_TEXT], handle[SEARCH_QUERY_ARG]),
            from = initialCriterion(handle[KEY_FROM], handle[SEARCH_FROM_ARG]),
            recipient = handle[KEY_RECIPIENT] ?: "",
            subject = handle[KEY_SUBJECT] ?: "",
            hasAttachment = handle[KEY_ATTACHMENT] ?: false,
            // Same precedence as the two criteria above: a value saved on this screen wins over
            // the nav argument, so turning the switch OFF in a Starred search stays off.
            flagged = handle[KEY_FLAGGED] ?: handle[SEARCH_FLAGGED_ARG] ?: false,
            afterMillis = handle[KEY_AFTER],
            beforeMillis = handle[KEY_BEFORE],
        ),
        expanded = handle[KEY_EXPANDED] ?: true,
    )

    private fun persist() {
        val form = _form.value
        handle[KEY_TEXT] = form.query.text
        handle[KEY_FROM] = form.query.from
        handle[KEY_RECIPIENT] = form.query.recipient
        handle[KEY_SUBJECT] = form.query.subject
        handle[KEY_ATTACHMENT] = form.query.hasAttachment
        handle[KEY_FLAGGED] = form.query.flagged
        handle[KEY_AFTER] = form.query.afterMillis
        handle[KEY_BEFORE] = form.query.beforeMillis
        handle[KEY_EXPANDED] = form.expanded
    }
}
