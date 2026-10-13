/*
 * Task #460 — folder content index sheet. Shows the REAL walk progress
 * ("Indexed 412 of 1,908"), then the summary, with a specific message for
 * every dead end (empty folder, cancellation). The index persists in the
 * app's own Room database; this sheet is only the progress window.
 */

package com.diegonmarcos.mediacenter.feature_node.presentation.contentindex

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.diegonmarcos.mediacenter.R
import com.diegonmarcos.mediacenter.core.presentation.components.ModalSheet
import com.diegonmarcos.mediacenter.feature_node.domain.model.Media
import com.diegonmarcos.mediacenter.feature_node.domain.util.isImage
import com.diegonmarcos.mediacenter.feature_node.presentation.util.AppBottomSheetState

@Composable
fun FolderIndexSheet(
    state: AppBottomSheetState,
    albumId: Long,
    albumName: String,
    mediaList: List<Media.UriMedia>,
    onDismiss: () -> Unit = {},
) {
    val viewModel: FolderIndexViewModel = hiltViewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val images = remember(mediaList) { mediaList.filter { it.isImage } }

    LaunchedEffect(state.isVisible, albumId) {
        if (state.isVisible) {
            if (uiState is FolderIndexUiState.Idle) viewModel.startIndex(albumId, images)
        } else {
            viewModel.reset()
        }
    }

    ModalSheet(
        sheetState = state,
        content = {
            when (val current = uiState) {
                is FolderIndexUiState.Idle -> Unit
                is FolderIndexUiState.Running -> RunningContent(current, onCancel = { viewModel.cancel() })
                is FolderIndexUiState.Finished -> FinishedContent(current.summary)
                is FolderIndexUiState.Cancelled -> CancelledContent(current)
            }
        },
        onDismissRequest = { onDismiss() },
        title = stringResource(R.string.index_folder_content),
        subtitle = albumName,
    )
}

@Composable
private fun ColumnScope.RunningContent(state: FolderIndexUiState.Running, onCancel: () -> Unit) {
    Text(
        text = stringResource(R.string.index_progress, state.indexed, state.total),
        style = MaterialTheme.typography.titleMedium
    )
    LinearProgressIndicator(
        progress = { if (state.total > 0) state.indexed.toFloat() / state.total else 0f },
        modifier = Modifier.fillMaxWidth()
    )
    Text(
        text = stringResource(R.string.index_running_hint),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End
    ) {
        TextButton(onClick = onCancel) {
            Text(stringResource(R.string.index_cancel))
        }
    }
}

@Composable
private fun ColumnScope.FinishedContent(summary: com.diegonmarcos.mediacenter.feature_node.data.data_source.contentindex.IndexSummary) {
    if (summary.total == 0) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                Icons.Outlined.FolderOff,
                contentDescription = null,
                modifier = Modifier
                    .padding(end = 8.dp)
                    .size(20.dp)
            )
            Text(stringResource(R.string.index_empty_folder))
        }
        return
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Outlined.CheckCircle,
            contentDescription = null,
            modifier = Modifier
                .padding(end = 8.dp)
                .size(20.dp)
        )
        Text(
            text = stringResource(R.string.index_progress, summary.total, summary.total),
            style = MaterialTheme.typography.titleMedium
        )
    }
    Text(
        text = stringResource(
            R.string.index_result,
            summary.barcodes,
            summary.withText,
            summary.unchanged
        ),
        style = MaterialTheme.typography.bodyLarge
    )
    if (summary.failed > 0) {
        Text(
            text = stringResource(R.string.index_failed, summary.failed),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error
        )
    }
}

@Composable
private fun ColumnScope.CancelledContent(state: FolderIndexUiState.Cancelled) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(
            modifier = Modifier
                .padding(end = 12.dp)
                .size(20.dp),
            strokeWidth = 2.dp
        )
        Text(
            text = stringResource(R.string.index_cancelled, state.indexed, state.total),
            style = MaterialTheme.typography.bodyLarge
        )
    }
}