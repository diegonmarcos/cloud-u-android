package com.diegonmarcos.mediacenter.shell

import com.diegonmarcos.mediacenter.feature_node.presentation.location.MapPhotoClusterer
import com.diegonmarcos.mediacenter.feature_node.presentation.location.MapPhotoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoSplitTest {

    private data class Photo(val id: Long, val lat: Double?, val lon: Double?, val taken: Long)

    private fun split(photos: List<Photo>) = GeoPositions.split(photos) { it.lat to it.lon }

    @Test
    fun aPositionIsUsableWhenBothCoordinatesAreInRange() {
        assertTrue(GeoPositions.isUsable(46.77, 23.59))
        assertTrue(GeoPositions.isUsable(-90.0, 180.0))
        assertTrue(GeoPositions.isUsable(0.0, 12.5))
    }

    @Test
    fun missingOutOfRangeAndNaNCoordinatesAreNotAPlace() {
        assertFalse(GeoPositions.isUsable(null, 10.0))
        assertFalse(GeoPositions.isUsable(10.0, null))
        assertFalse(GeoPositions.isUsable(91.0, 10.0))
        assertFalse(GeoPositions.isUsable(10.0, -181.0))
        assertFalse(GeoPositions.isUsable(Double.NaN, 10.0))
        assertFalse(GeoPositions.isUsable(10.0, Double.NaN))
    }

    @Test
    fun zeroZeroIsTheNoFixValueNotTheGulfOfGuinea() {
        assertFalse(GeoPositions.isUsable(0.0, 0.0))
    }

    @Test
    fun photosSplitIntoLocatedAndUnlocatedKeepingTheirOrder() {
        val photos = listOf(
            Photo(1, 46.77, 23.59, 300),
            Photo(2, null, null, 290),
            Photo(3, 40.71, -74.0, 280),
            Photo(4, 0.0, 0.0, 270),
            Photo(5, null, 12.0, 260),
        )
        val result = split(photos)
        assertEquals(listOf(1L, 3L), result.located.map { it.id })
        assertEquals(listOf(2L, 4L, 5L), result.unlocated.map { it.id })
    }

    @Test
    fun noPhotosGiveTwoEmptyLists() {
        val result = split(emptyList())
        assertTrue(result.located.isEmpty())
        assertTrue(result.unlocated.isEmpty())
    }

    @Test
    fun everyPhotoLandsInExactlyOneList() {
        val photos = (1L..50L).map { Photo(it, if (it % 3 == 0L) null else it.toDouble(), it.toDouble(), it) }
        val result = split(photos)
        assertEquals(photos.size, result.located.size + result.unlocated.size)
        assertTrue(result.located.map { it.id }.toSet().intersect(result.unlocated.map { it.id }.toSet()).isEmpty())
    }

    @Test
    fun onlyLocatedPhotosReachTheClusters() {
        val photos = listOf(
            Photo(1, 46.7700, 23.5900, 100),
            Photo(2, 46.7701, 23.5901, 200),
            Photo(3, null, null, 300),
            Photo(4, 0.0, 0.0, 400),
            Photo(5, 40.71, -74.0, 500),
        )
        val located = split(photos).located
        val clusters = MapPhotoClusterer.cluster(
            located.map { MapPhotoPoint(it.id, it.lat!!, it.lon!!, it.taken) },
            zoom = 12.0,
        )
        val onMap = clusters.flatMap { c -> c.members.map { it.mediaId } }.toSet()
        assertEquals(setOf(1L, 2L, 5L), onMap)
        assertEquals(2, clusters.size)
        assertEquals(listOf(1, 2), clusters.map { it.count }.sorted())
    }

    @Test
    fun theUnlocatedListIsWhatTheMapLeavesOut() {
        val photos = listOf(Photo(1, 10.0, 10.0, 1), Photo(2, null, null, 2), Photo(3, 91.0, 0.0, 3))
        val result = split(photos)
        val onMap = MapPhotoClusterer.cluster(result.located.map { MapPhotoPoint(it.id, it.lat!!, it.lon!!, it.taken) }, 10.0)
            .flatMap { it.members.map { m -> m.mediaId } }
        assertEquals(photos.map { it.id }.toSet(), (onMap + result.unlocated.map { it.id }).toSet())
    }
}
