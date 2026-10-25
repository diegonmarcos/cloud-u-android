/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.diegonmarcos.mediacenter.shell

import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.diegonmarcos.mediacenter.R
import com.diegonmarcos.mediacenter.cloud.core.PersonInfo
import com.diegonmarcos.mediacenter.core.LocalEventHandler
import com.diegonmarcos.mediacenter.core.navigate
import com.diegonmarcos.mediacenter.feature_node.data.data_source.CategoryWithMediaCount
import com.diegonmarcos.mediacenter.feature_node.data.data_source.contentindex.MediaContentIndexEntity
import com.diegonmarcos.mediacenter.feature_node.domain.model.Media
import com.diegonmarcos.mediacenter.feature_node.domain.model.MediaMetadataState
import com.diegonmarcos.mediacenter.feature_node.domain.model.MediaState
import com.diegonmarcos.mediacenter.feature_node.presentation.util.Screen

/**
 * Objects: what the phone recognised in the library, in four pages (second row): Faces, Scenes
 * (objects), Text and Documents. Everything is indexed on this phone and incrementally: the status
 * card at the top of each page is the real state of the index behind it. Face groups can be named
 * (their page); the names and the faces stay on the phone.
 */
@Composable
fun ObjectsTabScreen(
    mediaState: State<MediaState<Media.UriMedia>>,
    @Suppress("UNUSED_PARAMETER") metadataState: State<MediaMetadataState>,
) {
    val vm = hiltViewModel<ObjectsViewModel>()
    val overview by vm.overview.collectAsStateWithLifecycle()
    val engineProblem by vm.engineProblem.collectAsStateWithLifecycle()
    val media = mediaState.value.media
    LaunchedEffect(Unit) { vm.probe() }
    LaunchedEffect(media) { vm.setLibrary(media) }
    // The text pass is cheap to start and incremental, so it starts by itself once the library is known and the engine answers.
    val text = overview.status[IndexKind.TEXT]
    val libraryReady = !mediaState.value.isLoading && media.isNotEmpty()
    LaunchedEffect(libraryReady, engineProblem) {
        if (libraryReady && engineProblem == null && (text is IndexStatus.Idle || text is IndexStatus.Partial)) vm.startText()
    }
    val page = LocalGalleryPages.current.pageOf(GalleryTab.OBJECTS.id, GalleryPages.OBJECTS_FACES)
    when (page) {
        GalleryPages.OBJECTS_SCENES -> ScenesPage(vm, overview, engineProblem)
        GalleryPages.OBJECTS_TEXT -> TextPage(vm, overview, engineProblem, documentsOnly = false)
        GalleryPages.OBJECTS_DOCUMENTS -> TextPage(vm, overview, engineProblem, documentsOnly = true)
        else -> FacesPage(vm, overview, engineProblem)
    }
}

@Composable
internal fun IndexStatus.label(): String = when (this) {
    is IndexStatus.Blocked -> stringResource(
        if (why == IndexBlock.MODEL_MISSING) R.string.shell_index_blocked_model else R.string.shell_index_blocked_engine
    )
    IndexStatus.Idle -> stringResource(R.string.shell_index_idle)
    is IndexStatus.Running ->
        if (percent != null) stringResource(R.string.shell_index_running, percent) else stringResource(R.string.shell_index_running_unknown)
    is IndexStatus.Partial -> stringResource(R.string.shell_index_partial, indexed, total)
    is IndexStatus.Done -> stringResource(R.string.shell_index_done, indexed)
}

@Composable
internal fun IndexKind.label(): String = stringResource(
    when (this) {
        IndexKind.FACES -> R.string.shell_index_faces
        IndexKind.SCENES -> R.string.shell_index_scenes
        IndexKind.TEXT -> R.string.shell_index_text
        IndexKind.DOCUMENTS -> R.string.shell_index_documents
    }
)

/** The index status: one row per kind with its real state and the one action that moves it on. */
@Composable
private fun IndexHeader(vm: ObjectsViewModel, overview: IndexOverview, engineProblem: String?, modifier: Modifier = Modifier) {
    val eventHandler = LocalEventHandler.current
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.shell_index_title), style = MaterialTheme.typography.titleSmall)
            for (kind in IndexKind.entries) {
                val status = overview.status[kind] ?: IndexStatus.Idle
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(kind.label(), style = MaterialTheme.typography.bodyMedium)
                        Text(status.label(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (status is IndexStatus.Running) {
                            val percent = status.percent
                            if (percent != null) LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                            else LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                        }
                    }
                    val action: IndexAction? = when (status) {
                        is IndexStatus.Blocked ->
                            if (status.why == IndexBlock.MODEL_MISSING) IndexAction(R.string.shell_index_models) { eventHandler.navigate(Screen.AIModelsManagerScreen()) } else null
                        IndexStatus.Idle -> IndexAction(R.string.shell_index_start) { startOf(vm, kind) }
                        is IndexStatus.Partial -> IndexAction(R.string.shell_index_resume) { startOf(vm, kind) }
                        is IndexStatus.Running -> stopOf(vm, kind)?.let { stop -> IndexAction(R.string.shell_index_stop, stop) }
                        is IndexStatus.Done -> null
                    }
                    if (action != null) TextButton(onClick = action.run) { Text(stringResource(action.label)) }
                }
            }
            if (engineProblem != null) {
                Text(engineProblem, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

private class IndexAction(val label: Int, val run: () -> Unit)

private fun startOf(vm: ObjectsViewModel, kind: IndexKind) {
    when (kind) {
        IndexKind.FACES -> vm.startFaces()
        IndexKind.SCENES -> vm.startScenes()
        IndexKind.TEXT, IndexKind.DOCUMENTS -> vm.startText()
    }
}

/** The pass that can be stopped from here: scenes and text; the face pass has no cancel. */
private fun stopOf(vm: ObjectsViewModel, kind: IndexKind): (() -> Unit)? = when (kind) {
    IndexKind.FACES -> null
    IndexKind.SCENES -> ({ vm.stopScenes() })
    IndexKind.TEXT, IndexKind.DOCUMENTS -> ({ vm.stopText() })
}

private val PagePadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 16.dp)

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun FacesPage(vm: ObjectsViewModel, overview: IndexOverview, engineProblem: String?) {
    val people by vm.people.collectAsStateWithLifecycle()
    val eventHandler = LocalEventHandler.current
    LazyVerticalGrid(
        columns = GridCells.Adaptive(104.dp),
        contentPadding = PagePadding,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) { IndexHeader(vm, overview, engineProblem) }
        if (people.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                ShellEmptyState(
                    icon = Icons.Outlined.Person,
                    title = stringResource(R.string.shell_faces_empty_title),
                    body = stringResource(R.string.shell_faces_empty_body),
                    modifier = Modifier.heightIn(min = 240.dp),
                )
            }
        } else {
            items(people, key = { it.id }) { person ->
                PersonCell(person) { eventHandler.navigate(Screen.PersonDetailScreen.personId(person.id)) }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    stringResource(R.string.shell_faces_privacy),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun PersonCell(person: PersonInfo, onClick: () -> Unit) {
    Column(
        Modifier.clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val shape = CircleShape
        if (person.thumbnailUrl != null) {
            GlideImage(
                model = person.thumbnailUrl.toUri(),
                contentDescription = person.name,
                modifier = Modifier.size(92.dp).clip(shape),
                contentScale = ContentScale.Crop,
            )
        } else {
            androidx.compose.foundation.layout.Box(
                Modifier.size(92.dp).clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) { androidx.compose.material3.Icon(Icons.Outlined.Person, contentDescription = null) }
        }
        Text(
            person.name.ifBlank { stringResource(R.string.shell_faces_unnamed) },
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            stringResource(R.string.shell_count_photos, person.assetCount),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun ScenesPage(vm: ObjectsViewModel, overview: IndexOverview, engineProblem: String?) {
    val categories by vm.categories.collectAsStateWithLifecycle()
    val shown = remember(categories) { categories.filter { it.mediaCount > 0 }.sortedByDescending { it.mediaCount } }
    val eventHandler = LocalEventHandler.current
    LazyVerticalGrid(
        columns = GridCells.Adaptive(128.dp),
        contentPadding = PagePadding,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) { IndexHeader(vm, overview, engineProblem) }
        if (shown.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                ShellEmptyState(
                    icon = Icons.Outlined.Visibility,
                    title = stringResource(R.string.shell_scenes_empty_title),
                    body = stringResource(R.string.shell_scenes_empty_body),
                    modifier = Modifier.heightIn(min = 240.dp),
                )
            }
        } else {
            items(shown, key = { it.id }) { category -> SceneCell(category) { eventHandler.navigate(Screen.CategoryViewScreen.categoryId(category.id)) } }
        }
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun SceneCell(category: CategoryWithMediaCount, onClick: () -> Unit) {
    Card(Modifier.clickable(onClick = onClick)) {
        Column {
            val thumb = category.thumbnailMediaId
            if (thumb != null) {
                GlideImage(
                    model = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, thumb),
                    contentDescription = category.name,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp, max = 96.dp),
                    contentScale = ContentScale.Crop,
                )
            }
            Column(Modifier.padding(8.dp)) {
                Text(category.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    stringResource(R.string.shell_count_photos, category.mediaCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun TextPage(vm: ObjectsViewModel, overview: IndexOverview, engineProblem: String?, documentsOnly: Boolean) {
    val textRows by vm.textRows.collectAsStateWithLifecycle()
    val documentRows by vm.documentRows.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    val source = if (documentsOnly) documentRows else textRows
    val rows = remember(source, query) { source.filter { ContentKinds.matches(it.ocrText, query) } }
    val eventHandler = LocalEventHandler.current
    LazyColumn(
        contentPadding = PagePadding,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { IndexHeader(vm, overview, engineProblem) }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(stringResource(R.string.shell_text_search_hint)) },
                keyboardOptions = KeyboardOptions.Default,
            )
        }
        if (rows.isEmpty()) {
            item {
                ShellEmptyState(
                    icon = Icons.Outlined.Description,
                    title = stringResource(
                        when {
                            source.isNotEmpty() -> R.string.shell_text_no_match
                            documentsOnly -> R.string.shell_documents_empty_title
                            else -> R.string.shell_text_empty_title
                        }
                    ),
                    body = stringResource(if (documentsOnly) R.string.shell_documents_empty_body else R.string.shell_text_empty_body),
                    modifier = Modifier.heightIn(min = 220.dp),
                )
            }
        } else {
            items(rows, key = { it.mediaId }) { row -> TextRow(row) { eventHandler.navigate(Screen.MediaViewScreen.idAndAlbum(row.mediaId, -1L)) } }
        }
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun TextRow(row: MediaContentIndexEntity, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlideImage(
            model = Uri.parse(row.uri),
            contentDescription = null,
            modifier = Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)),
            contentScale = ContentScale.Crop,
        )
        Column(Modifier.weight(1f)) {
            Text(row.ocrText.orEmpty().replace('\n', ' '), maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            row.language?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}
