/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.diegonmarcos.mediacenter.shell

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LocationOff
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.diegonmarcos.mediacenter.R
import com.diegonmarcos.mediacenter.core.LocalEventHandler
import com.diegonmarcos.mediacenter.core.navigate
import com.diegonmarcos.mediacenter.feature_node.domain.model.LocationFilter
import com.diegonmarcos.mediacenter.feature_node.domain.model.Media
import com.diegonmarcos.mediacenter.feature_node.domain.model.MediaMetadataState
import com.diegonmarcos.mediacenter.feature_node.domain.model.MediaState
import com.diegonmarcos.mediacenter.feature_node.domain.model.TimelineFilter
import com.diegonmarcos.mediacenter.feature_node.presentation.classifier.CategoriesViewModel
import com.diegonmarcos.mediacenter.feature_node.presentation.location.LocationsScreen
import com.diegonmarcos.mediacenter.feature_node.presentation.timeline.TimelineMediaContent
import com.diegonmarcos.mediacenter.feature_node.presentation.timeline.filteredBy
import com.diegonmarcos.mediacenter.feature_node.presentation.timeline.geoTaggedIds
import com.diegonmarcos.mediacenter.feature_node.presentation.util.Screen

/**
 * Maps: the photos that carry a GPS position, clustered on a MapLibre map (the gallery's cluster
 * engine, [com.diegonmarcos.mediacenter.feature_node.presentation.location.MapPhotoClusterer]); the
 * second page lists the photos that carry none. Positions are read from each photo's own metadata,
 * on the phone; no photo or position is sent anywhere.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun MapsTabScreen(
    mediaState: State<MediaState<Media.UriMedia>>,
    metadataState: State<MediaMetadataState>,
    isScrolling: MutableState<Boolean>,
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: AnimatedContentScope,
) {
    val page = LocalGalleryPages.current.pageOf(GalleryTab.MAPS.id, GalleryPages.MAPS_MAP)
    if (page == GalleryPages.MAPS_NO_LOCATION) {
        NoLocationPage(mediaState, metadataState, isScrolling, sharedTransitionScope, animatedContentScope)
    } else {
        MapPage(metadataState)
    }
}

@Composable
private fun MapPage(metadataState: State<MediaMetadataState>) {
    val vm = hiltViewModel<CategoriesViewModel>()
    val geoMedia by vm.geoMedia.collectAsStateWithLifecycle()
    val locations by vm.locations.collectAsStateWithLifecycle()
    if (geoMedia.isEmpty()) {
        if (metadataState.value.isLoading) ReadingLocations() else {
            ShellEmptyState(
                icon = Icons.Outlined.Map,
                title = stringResource(R.string.shell_maps_empty_title),
                body = stringResource(R.string.shell_maps_empty_body),
            )
        }
    } else {
        LocationsScreen(metadataState = metadataState, locations = locations, geoMedia = geoMedia, showBack = false)
    }
}

@Composable
private fun ReadingLocations() {
    ShellEmptyState(
        icon = Icons.Outlined.Map,
        title = stringResource(R.string.shell_maps_loading),
        body = stringResource(R.string.shell_maps_loading_body),
    ) { CircularProgressIndicator() }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun NoLocationPage(
    mediaState: State<MediaState<Media.UriMedia>>,
    metadataState: State<MediaMetadataState>,
    isScrolling: MutableState<Boolean>,
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: AnimatedContentScope,
) {
    val eventHandler = LocalEventHandler.current
    val geoIds = remember(metadataState.value.metadata) { geoTaggedIds(metadataState.value.metadata) }
    val withoutLocation = remember(mediaState, geoIds) {
        derivedStateOf { mediaState.value.filteredBy(TimelineFilter(location = LocationFilter.WITHOUT), geoIds) }
    }
    // Until the metadata pass has run, every photo would look unlocated: say so instead of listing them all.
    if (metadataState.value.isLoading) {
        ReadingLocations()
        return
    }
    TimelineMediaContent(
        mediaState = withoutLocation,
        metadataState = metadataState,
        scaffoldPadding = PaddingValues(0.dp),
        bottomPadding = 16.dp,
        screenTopPadding = 0.dp,
        showSearchBar = false,
        aboveGridContent = null,
        isScrolling = isScrolling,
        sharedTransitionScope = sharedTransitionScope,
        animatedContentScope = animatedContentScope,
        onMediaClick = { eventHandler.navigate(Screen.MediaViewScreen.idAndAlbum(it.id, -1L)) },
        emptyContent = {
            ShellEmptyState(
                icon = Icons.Outlined.LocationOff,
                title = stringResource(R.string.shell_maps_none_title),
                body = stringResource(R.string.shell_maps_none_body),
            )
        },
        scrollToTopRoute = Screen.MapsTabScreen.route,
    )
}
