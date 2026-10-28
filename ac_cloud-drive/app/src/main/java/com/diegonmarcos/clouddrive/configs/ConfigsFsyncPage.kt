package com.diegonmarcos.clouddrive.configs

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.diegonmarcos.clouddrive.Declarations
import com.diegonmarcos.clouddrive.DriveActions
import com.diegonmarcos.clouddrive.R
import com.diegonmarcos.clouddrive.storage.StorageCoordinator
import com.diegonmarcos.clouddrive.sync.AccountLine
import com.diegonmarcos.clouddrive.sync.ConnectionRow
import com.diegonmarcos.clouddrive.ui.DriveMetrics
import com.diegonmarcos.clouddrive.ui.EmptyState
import com.diegonmarcos.clouddrive.ui.IconCatalog
import com.diegonmarcos.clouddrive.ui.SectionHeader

/**
 * #1216 CONFIGS ▸ FSYNC: the servers, APIs and SFTP hosts the folder sync rules can use. The list is the entries of
 * data/drive-connections.json whose kind is one of ui.sync.config_fsync.connection_kinds ([Declarations.fsyncConnectionKinds]),
 * drawn with the row the Cloud volumes use; the account line says whether Cloud Account (which owns the keys and hosts) answers.
 * Nothing is invented here: a kind with no entry shows the empty state.
 */
@Composable
fun ConfigsFsyncPage(storage: StorageCoordinator, actions: DriveActions, modifier: Modifier = Modifier) {
    val kinds = Declarations.fsyncConnectionKinds
    val rows = Declarations.connections.filter { it.kind in kinds }
    LazyColumn(modifier.fillMaxSize()) {
        item { AccountLine(storage, actions) }
        item { SectionHeader(stringResource(R.string.configs_fsync_servers), count = rows.size) }
        item { Text(stringResource(R.string.configs_fsync_body), Modifier.padding(horizontal = DriveMetrics.sectionInset), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (rows.isEmpty()) item { EmptyState(IconCatalog.vectorOrDefault(Declarations.iconDefault), stringResource(R.string.configs_fsync_empty), "") }
        items(rows, key = { "fsync-conn-" + it.name }) { c -> ConnectionRow(c) }
    }
}
