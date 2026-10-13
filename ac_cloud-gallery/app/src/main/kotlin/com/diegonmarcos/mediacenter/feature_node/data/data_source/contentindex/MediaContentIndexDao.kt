/*
 * Task #460 — folder content index DAO. Suspended functions so the indexer
 * drives the whole walk off the main thread; upserts are REPLACE so a
 * re-index of a changed file simply overwrites its row.
 */

package com.diegonmarcos.mediacenter.feature_node.data.data_source.contentindex

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface MediaContentIndexDao {

    @Query("SELECT * FROM media_content_index WHERE mediaId = :mediaId")
    suspend fun byMediaId(mediaId: Long): MediaContentIndexEntity?

    @Query("SELECT * FROM media_content_index WHERE albumId = :albumId")
    suspend fun forAlbum(albumId: Long): List<MediaContentIndexEntity>

    @Query("SELECT COUNT(*) FROM media_content_index WHERE albumId = :albumId")
    suspend fun countForAlbum(albumId: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: MediaContentIndexEntity)

    @Query("DELETE FROM media_content_index WHERE albumId = :albumId")
    suspend fun deleteForAlbum(albumId: Long)
}