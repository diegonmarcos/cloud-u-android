/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.diegonmarcos.mediacenter.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.diegonmarcos.mediacenter.cloud.core.ProviderRegistry
import com.diegonmarcos.mediacenter.cloud.core.SyncState
import com.diegonmarcos.mediacenter.cloud.data.dao.CloudMediaDao
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** The cloud backup, as it is in the app's own sync table: nothing is assumed. */
data class SyncSummary(
    val accounts: Int,
    val synced: Int,
    val pending: Int,
    val remoteOnly: Int,
    val conflicts: Int,
) {
    val connected: Boolean get() = accounts > 0

    /** Everything this phone holds is on the server and nothing is waiting or in conflict. */
    val upToDate: Boolean get() = connected && pending == 0 && conflicts == 0
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    cloudMediaDao: CloudMediaDao,
    private val registry: ProviderRegistry,
) : ViewModel() {

    /** Null until the first read of the sync table. */
    val sync: StateFlow<SyncSummary?> = combine(
        cloudMediaDao.countBySyncState(SyncState.SYNCED),
        cloudMediaDao.countBySyncState(SyncState.UPLOAD_PENDING),
        cloudMediaDao.countBySyncState(SyncState.REMOTE_ONLY),
        cloudMediaDao.countBySyncState(SyncState.CONFLICT),
    ) { synced, pending, remoteOnly, conflicts ->
        SyncSummary(
            accounts = registry.getRemoteProviders().count { it.isAvailable },
            synced = synced,
            pending = pending,
            remoteOnly = remoteOnly,
            conflicts = conflicts,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}
