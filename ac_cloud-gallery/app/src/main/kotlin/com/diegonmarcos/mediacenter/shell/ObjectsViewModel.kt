/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.diegonmarcos.mediacenter.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkQuery
import com.diegonmarcos.mediacenter.cloud.core.PersonInfo
import com.diegonmarcos.mediacenter.cloud.data.repository.CloudRepository
import com.diegonmarcos.mediacenter.core.Resource
import com.diegonmarcos.mediacenter.core.ml.ModelGroup
import com.diegonmarcos.mediacenter.core.ml.ModelManager
import com.diegonmarcos.mediacenter.core.ml.ModelStatus
import com.diegonmarcos.mediacenter.core.workers.CategoryWorker
import com.diegonmarcos.mediacenter.core.workers.FaceIndexerWorker
import com.diegonmarcos.mediacenter.core.workers.forceFaceIndex
import com.diegonmarcos.mediacenter.core.workers.startCategoryClassification
import com.diegonmarcos.mediacenter.core.workers.stopCategoryClassification
import com.diegonmarcos.mediacenter.feature_node.data.data_source.CategoryWithMediaCount
import com.diegonmarcos.mediacenter.feature_node.data.data_source.InternalDatabase
import com.diegonmarcos.mediacenter.feature_node.data.data_source.contentindex.MediaContentIndexEntity
import com.diegonmarcos.mediacenter.feature_node.domain.model.Media
import com.diegonmarcos.mediacenter.feature_node.domain.repository.MediaRepository
import com.diegonmarcos.mediacenter.feature_node.domain.util.isImage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The Objects tab's state: what the on-device indexes found (faces, scenes, text, documents) and
 * the real status of each pass. Faces and scenes are the app's existing WorkManager jobs
 * (ArcFace grouping, CLIP categories); text and documents come from the OCR content index
 * ([TextIndexRunner]). Nothing here leaves the phone: face groups are stored locally (or on the
 * private Immich server the user configured), and OCR runs in the on-device image engine.
 */
@HiltViewModel
class ObjectsViewModel @Inject constructor(
    database: InternalDatabase,
    private val workManager: WorkManager,
    private val modelManager: ModelManager,
    private val repository: MediaRepository,
    cloud: CloudRepository,
    private val runner: TextIndexRunner,
) : ViewModel() {

    private val index = database.getMediaContentIndexDao()
    private val whileObserved = SharingStarted.WhileSubscribed(5_000)

    val people: StateFlow<List<PersonInfo>> = cloud.getAllPeople()
        .map { (it as? Resource.Success)?.data.orEmpty() }
        .stateIn(viewModelScope, whileObserved, emptyList())

    val categories: StateFlow<List<CategoryWithMediaCount>> = repository.getCategoriesWithMediaCount()
        .stateIn(viewModelScope, whileObserved, emptyList())

    /** Every image with recognised text, newest first. */
    val textRows: StateFlow<List<MediaContentIndexEntity>> = index.withTextFlow()
        .stateIn(viewModelScope, whileObserved, emptyList())

    /** The text rows that are pages of text (documents). */
    val documentRows: StateFlow<List<MediaContentIndexEntity>> = textRows
        .map { rows -> rows.filter { ContentKinds.isDocument(it.ocrText) } }
        .stateIn(viewModelScope, whileObserved, emptyList())

    private var library: List<Media.UriMedia> = emptyList()
    private val libraryImages = MutableStateFlow(0)

    /** Set by the screen with the current library; the text pass reads these. */
    fun setLibrary(media: List<Media.UriMedia>) {
        library = media
        libraryImages.value = media.count { it.isImage }
    }

    val libraryImageCount: StateFlow<Int> = libraryImages

    private data class Work(val running: Boolean, val progress: Float)

    private fun Flow<List<WorkInfo>>.asWork(progressKey: String): Flow<Work> = map { infos ->
        val active = infos.firstOrNull { it.state == WorkInfo.State.RUNNING }
        Work(
            running = infos.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED },
            progress = active?.progress?.getFloat(progressKey, -1f) ?: -1f,
        )
    }

    private val faceWork = workManager.getWorkInfosFlow(WorkQuery.fromUniqueWorkNames(FaceIndexerWorker.WORK_NAME))
        .asWork(FaceIndexerWorker.KEY_PROGRESS)
    private val categoryWork = workManager.getWorkInfosByTagFlow(CategoryWorker.TAG)
        .asWork(CategoryWorker.KEY_PROGRESS)

    private val faceModelsReady = combine(
        modelManager.status(ModelGroup.FACE_DETECT),
        modelManager.status(ModelGroup.FACE_RECOGNITION),
    ) { detect, recognise -> detect == ModelStatus.READY && recognise == ModelStatus.READY }

    private val facesStatus = combine(people, faceWork, faceModelsReady) { people, work, ready ->
        IndexReducer.fromWork(
            blocked = if (ready) null else IndexBlock.MODEL_MISSING,
            running = work.running,
            progress = work.progress,
            produced = people.size,
        )
    }

    private val scenesStatus = combine(
        categories, categoryWork, modelManager.status(ModelGroup.SEARCH),
    ) { categories, work, search ->
        IndexReducer.fromWork(
            blocked = if (search == ModelStatus.READY) null else IndexBlock.MODEL_MISSING,
            running = work.running,
            progress = work.progress,
            produced = categories.sumOf { it.mediaCount },
        )
    }

    private val textStatus = combine(
        index.countFlow(), runner.progress, runner.engineProblem, libraryImages,
    ) { indexed, pass, problem, total ->
        IndexReducer.fromCounts(
            blocked = if (problem != null) IndexBlock.ENGINE_MISSING else null,
            running = pass.running,
            indexed = indexed.coerceAtMost(total),
            total = total,
        )
    }

    private val documentsStatus = combine(textStatus, documentRows) { text, documents ->
        if (text is IndexStatus.Done) IndexStatus.Done(documents.size) else text
    }

    val overview: StateFlow<IndexOverview> = combine(
        facesStatus, scenesStatus, textStatus, documentsStatus,
    ) { faces, scenes, text, documents ->
        IndexOverview(
            mapOf(
                IndexKind.FACES to faces,
                IndexKind.SCENES to scenes,
                IndexKind.TEXT to text,
                IndexKind.DOCUMENTS to documents,
            )
        )
    }.stateIn(viewModelScope, whileObserved, IndexOverview.EMPTY)

    /** The sentence the image engine gave for why OCR cannot run, or null. */
    val engineProblem: StateFlow<String?> = runner.engineProblem

    /** Asks the image engine whether it can serve (binds it, so only the Objects tab calls this). */
    fun probe() {
        runner.probe()
    }

    fun startFaces() {
        workManager.forceFaceIndex()
    }

    fun startScenes() {
        viewModelScope.launch {
            repository.initializeDefaultCategories()
            workManager.startCategoryClassification()
        }
    }

    fun stopScenes() {
        workManager.stopCategoryClassification()
    }

    fun startText() {
        runner.start(library)
    }

    fun stopText() {
        runner.stop()
    }
}
