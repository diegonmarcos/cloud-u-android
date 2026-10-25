/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.diegonmarcos.mediacenter.feature_node.presentation.timeline

import com.diegonmarcos.mediacenter.feature_node.domain.model.FilterableMedia
import com.diegonmarcos.mediacenter.feature_node.domain.model.Media
import com.diegonmarcos.mediacenter.feature_node.domain.model.MediaItem
import com.diegonmarcos.mediacenter.feature_node.domain.model.MediaMetadata
import com.diegonmarcos.mediacenter.feature_node.domain.model.MediaState
import com.diegonmarcos.mediacenter.feature_node.domain.model.TimelineFilter
import com.diegonmarcos.mediacenter.feature_node.domain.model.bucketOf
import com.diegonmarcos.mediacenter.feature_node.domain.util.isFavorite
import com.diegonmarcos.mediacenter.feature_node.domain.util.isImage
import com.diegonmarcos.mediacenter.feature_node.domain.util.isVideo
import com.diegonmarcos.mediacenter.shell.GeoPositions

/** The ids of the media whose metadata carries a GPS position. */
fun geoTaggedIds(metadata: List<MediaMetadata>): Set<Long> =
    metadata.filter { GeoPositions.isUsable(it.gpsLatitude, it.gpsLongitude) }.mapTo(HashSet()) { it.mediaId }

/** The filter's view of [this]; [geoIds] is only read when the filter has a location criterion. */
fun Media.UriMedia.toFilterable(geoIds: Set<Long>, needYear: Boolean): FilterableMedia {
    val taken = definedTimestamp
    val year = if (needYear) {
        java.util.Calendar.getInstance().apply { timeInMillis = taken * 1000L }.get(java.util.Calendar.YEAR)
    } else 0
    return FilterableMedia(
        albumId = albumID,
        bucket = bucketOf(relativePath),
        isImage = isImage,
        isVideo = isVideo,
        favorite = isFavorite,
        takenSec = taken,
        year = year,
        hasLocation = id in geoIds,
    )
}

/**
 * [this] narrowed to the media [filter] accepts, with the grouped/mapped views rebuilt from the same
 * ids. Returns [this] when the filter is inactive.
 */
fun MediaState<Media.UriMedia>.filteredBy(filter: TimelineFilter, geoIds: Set<Long>): MediaState<Media.UriMedia> {
    if (!filter.isActive) return this
    val needYear = filter.selectedYears.isNotEmpty()
    val kept = media.filter { filter.matches(it.toFilterable(geoIds, needYear)) }
    val keptIds = kept.mapTo(HashSet(kept.size)) { it.id }
    fun List<MediaItem<Media.UriMedia>>.narrow(): List<MediaItem<Media.UriMedia>> = filter { item ->
        when (item) {
            is MediaItem.MediaViewItem -> item.media.id in keptIds
            is MediaItem.Header -> item.data.any { it in keptIds }
        }
    }
    return copy(
        media = kept,
        pagerMedia = pagerMedia.filter { it.id in keptIds },
        mappedMedia = mappedMedia.narrow(),
        mappedMediaWithMonthly = mappedMediaWithMonthly.narrow(),
        mappedMediaWithYearly = mappedMediaWithYearly.narrow(),
        headers = headers.filter { header -> header.data.any { it in keptIds } },
    )
}
