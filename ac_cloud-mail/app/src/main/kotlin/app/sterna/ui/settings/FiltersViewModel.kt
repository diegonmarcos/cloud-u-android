package app.sterna.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.sterna.container
import app.sterna.core.data.filter.FilterRule
import app.sterna.core.data.filter.ForeignScript
import app.sterna.core.data.mail.FilterRulesState
import app.sterna.ui.inbox.mailboxFilePath
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class FiltersError { LOAD, SAVE }

data class FiltersUiState(
    val loading: Boolean = true,
    val noAccount: Boolean = false,
    val supported: Boolean = true,
    val foreignActive: Boolean = false,
    /** The rules below are on the server but not running: the script carrying them is not the active
     *  one. Distinct from [foreignActive], which is false in the very state this flag exists for —
     *  a vacation responder switched on and off again leaves no script active at all. */
    val rulesNotRunning: Boolean = false,
    /** The script named `vacation` is the active one: the auto-reply is running, and Save would
     * switch it off by activating Sterna's script. Activity, not existence — an inactive
     *  `vacation` script beside a third active one must not claim the auto-reply is at stake. */
    val vacationScriptActive: Boolean = false,
    /** This account's `sterna` script exists and could not be parsed, so Save would replace content
     *  nobody read. Carried separately from [foreignActive], which the rules read folds it into. */
    val scriptUnreadable: Boolean = false,
    /** The active script this app did not write, WITH its text — what the account is being filtered
     *  by right now. Held beside [rules] rather than merged into it: [rules] is what this app can
     *  model and push, this is what it cannot, and a save must never quietly carry the second. */
    val foreignScript: ForeignScript? = null,
    val accountLabel: String = "",
    val rules: List<FilterRule> = emptyList(),
    /** Folder paths offered in the "move to folder" picker, and stored as the rule's target.
     * A path, not a name: Sieve names a subfolder by its whole path, so "Done" alone reaches the
     *  wrong folder the moment two folders share that last segment. */
    val folders: List<String> = emptyList(),
    val saving: Boolean = false,
    val errorKind: FiltersError? = null,
    val errorDetail: String = "",
    /** Bumped after each successful save; the screen shows a confirmation while > 0. */
    val savedTick: Int = 0,
    /** Whether [rules] still differ from what the server holds. Gates Save, so the button is offered
     *  only when it has something to push (#34), and goes out again if the edits are undone. */
    val dirty: Boolean = false,
)

/** Drives the server-side filter rules (JMAP Sieve): edited locally and pushed in one Save
 *  (compile → validate → activate). Every read and write is a network round-trip. */
class FiltersViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = application.container.mailRepository
    private val store = application.container.accountStore

    private val _state = MutableStateFlow(FiltersUiState())
    val state = _state.asStateFlow()

    /** The rules as the server last confirmed them; edits are compared to these (#34). */
    private var serverRules: List<FilterRule> = emptyList()

    init { load() }

    fun load() {
        val credentials = store.load()
        if (credentials == null) {
            _state.value = FiltersUiState(loading = false, noAccount = true)
            return
        }
        _state.update { it.copy(loading = true, errorKind = null) }
        viewModelScope.launch {
            try {
                // Whole paths, resolved against the account's folder list: the server knows
                // "INBOX.ProjectA.Done", not "Done". mapNotNull, so a folder the app cannot name
                // with certainty is not offered at all.
                val folders = runCatching {
                    repo.observeMailboxes(credentials.id).first()
                        .let { all -> all.mapNotNull { mb -> mailboxFilePath(mb, all) } }
                }.getOrDefault(emptyList())
                when (val result = repo.loadFilterRules(credentials)) {
                    FilterRulesState.Unsupported -> {
                        serverRules = emptyList()
                        _state.value = FiltersUiState(
                            loading = false, supported = false, accountLabel = store.accountLabel(),
                        )
                    }
                    is FilterRulesState.Loaded -> {
                        serverRules = result.rules
                        // Before the list is published: these flags decide whether Save is
                        // offered and what the red line says, so a list drawn without them claims
                        // the rules are running and greys out the gesture that restores them.
                        _state.value = filtersStateWithStatus(
                            state = FiltersUiState(
                                loading = false,
                                supported = true,
                                accountLabel = store.accountLabel(),
                                rules = result.rules,
                                folders = folders,
                            ),
                            status = repo.loadFilterScriptStatus(credentials),
                            foreignFromRulesRead = result.foreignActiveScript,
                            unreadableFromRulesRead = result.scriptUnreadable,
                            foreignScriptFromRulesRead = result.foreignScript,
                        )
                    }
                }
            } catch (t: Throwable) {
                _state.update {
                    it.copy(
                        loading = false,
                        errorKind = FiltersError.LOAD,
                        errorDetail = t.message ?: t.javaClass.simpleName,
                    )
                }
            }
        }
    }

    fun addRule() = edit { it.copy(rules = it.rules + FilterRule()) }

    /** Commits an edited rule; an untouched one is dropped instead of left as a ghost row. */
    fun updateRule(index: Int, rule: FilterRule) = edit {
        it.copy(
            rules = it.rules.toMutableList().also { list ->
                if (rule.isEmpty) list.removeAt(index) else list[index] = rule
            },
        )
    }

    fun removeRule(index: Int) = edit {
        it.copy(rules = it.rules.toMutableList().also { list -> list.removeAt(index) })
    }

    fun setRuleEnabled(index: Int, enabled: Boolean) = edit {
        it.copy(rules = it.rules.toMutableList().also { list -> list[index] = list[index].copy(enabled = enabled) })
    }

    private fun edit(transform: (FiltersUiState) -> FiltersUiState) =
        _state.update {
            val next = transform(it).copy(errorKind = null, savedTick = 0)
            next.copy(dirty = next.rules != serverRules)
        }

    fun save() {
        val credentials = store.load() ?: return
        // Empty rules never reach the script: they would come back as ghost rows.
        val rules = _state.value.rules.filterNot { it.isEmpty }
        _state.update { it.copy(rules = rules, saving = true, errorKind = null) }
        viewModelScope.launch {
            var written = false
            try {
                repo.saveFilterRules(credentials, rules)
                serverRules = rules
                _state.update { it.copy(saving = false, savedTick = it.savedTick + 1, dirty = false) }
                written = true
            } catch (t: Throwable) {
                _state.update {
                    it.copy(
                        saving = false,
                        errorKind = FiltersError.SAVE,
                        errorDetail = t.message ?: t.javaClass.simpleName,
                    )
                }
            }
            // Outside the try, and after the state that lets a "save then leave" go: that exit
            // cancels this read, and inside the catch above a cancellation would be painted as a
            // failed save over rules the server has already taken. All three flags come from this
            // read, so they cannot describe two moments; a failed read moves none.
            if (written) {
                val status = repo.loadFilterScriptStatus(credentials)
                _state.update { filtersStateWithStatus(state = it, status = status) }
            }
        }
    }
}
