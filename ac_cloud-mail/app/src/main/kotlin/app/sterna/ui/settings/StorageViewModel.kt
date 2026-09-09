package app.sterna.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import app.sterna.R
import androidx.lifecycle.viewModelScope
import app.sterna.container
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** On-device storage usage shown in Settings → Storage. */
data class StorageUiState(
    val loading: Boolean = true,
    val databaseBytes: Long = 0L,
    val attachmentBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val perAccount: List<AccountStorageUi> = emptyList(),
    val quotas: List<QuotaUi> = emptyList(),
)

data class AccountStorageUi(val label: String, val messageCount: Int)

/** One server-side resource quota for display: bytes ("octets") or message count. */
data class QuotaUi(val resourceType: String, val used: Long, val limit: Long?)

class StorageViewModel(application: Application) : AndroidViewModel(application) {
    private val storage = application.container.storageRepository
    private val mail = application.container.mailRepository
    private val store = application.container.accountStore

    private val _state = MutableStateFlow(StorageUiState())
    val state = _state.asStateFlow()

    private val _clearing = MutableStateFlow(false)
    val clearing = _clearing.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            val usage = storage.usage()
            val accounts = store.accounts()
            val perAccount = usage.perAccount
                .map { row ->
                    val account = accounts.firstOrNull { it.id == row.accountId }
                    val label = account?.let { it.accountName.ifBlank { it.username } }
                        ?: getApplication<Application>().getString(R.string.status_removed_account)
                    AccountStorageUi(label, row.messageCount)
                }
                .sortedByDescending { it.messageCount }
            _state.value = StorageUiState(
                loading = false,
                databaseBytes = usage.databaseBytes,
                attachmentBytes = usage.attachmentBytes,
                totalBytes = usage.totalBytes,
                perAccount = perAccount,
                quotas = _state.value.quotas,
            )
            // Server-side quota is a network call; fetched after the instant on-device figures.
            val quotas = runCatching { store.load()?.let { mail.loadQuotas(it) }.orEmpty() }
                .getOrDefault(emptyList())
                .filter { it.scope == "account" && (it.resourceType == "octets" || it.resourceType == "count") }
                .map { QuotaUi(it.resourceType, it.used, it.limit) }
            _state.value = _state.value.copy(quotas = quotas)
        }
    }

    fun clearCache() {
        viewModelScope.launch {
            _clearing.value = true
            storage.clearAllCache()
            mail.resetSyncState()
            // Re-sync the current account so the folder list and inbox repopulate instead of
            // leaving the app in an empty, broken-looking state.
            runCatching { store.load()?.let { mail.refresh(it) } }
            _clearing.value = false
            load()
        }
    }
}
