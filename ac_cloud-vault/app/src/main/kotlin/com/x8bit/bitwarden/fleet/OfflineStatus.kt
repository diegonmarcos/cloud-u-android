package com.x8bit.bitwarden.fleet

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Whether the last vault sync attempt failed. Cleared by a successful sync. */
object SyncHealth {
    private val mutable = MutableStateFlow(false)
    val lastSyncFailed: StateFlow<Boolean> = mutable
    fun setLastSyncFailed(value: Boolean) {
        mutable.value = value
    }
}

/** The vault header line shown whenever the last sync failed or the device is offline. */
object OfflineStatus {
    private val format = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    /** Null while online and the last sync succeeded, else "Offline — last synced <time>". */
    fun label(
        connected: Boolean,
        lastSyncFailed: Boolean,
        lastSync: Instant?,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String? = when {
        connected && !lastSyncFailed -> null
        lastSync == null -> "Offline — not synced yet"
        else -> "Offline — last synced ${format.withZone(zone).format(lastSync)}"
    }
}
