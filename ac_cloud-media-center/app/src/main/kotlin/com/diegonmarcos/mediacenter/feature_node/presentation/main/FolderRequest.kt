/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.diegonmarcos.mediacenter.feature_node.presentation.main

import android.content.ContentResolver
import android.content.Intent
import android.provider.MediaStore
import com.diegonmarcos.mediacenter.feature_node.data.data_source.mediastore.MediaQuery

/**
 * The receiving half of the "open this folder" contract used by ac_cloud-camera.
 *
 * WHY THIS EXISTS. Before it, nothing outside this app could open a single album.
 * [MainActivity] never read its own [Intent] at all, its only intent-filters were
 * MAIN/LAUNCHER aliases, and StandaloneActivity's VIEW/REVIEW filters each resolve to
 * one image or video — there was no ACTION_VIEW shape that means "a directory". So the
 * camera's button had no existing mechanism and no standard intent to reach for, and
 * this narrow entry point was added instead. The camera's half is
 * cld.camera.util.MediaCenter; the two must be changed together.
 *
 * The payload is deliberately a MediaStore RELATIVE_PATH and not a bucket id. A bucket
 * id is a hash of the folder's path that the platform computes and does not promise
 * across volumes or versions, and the camera cannot compute one for a folder it has not
 * written to yet. A path is the thing the camera actually configures.
 */
object FolderRequest {

    const val ACTION_VIEW_FOLDER = "com.diegonmarcos.mediacenter.action.VIEW_FOLDER"

    const val EXTRA_RELATIVE_PATH = "com.diegonmarcos.mediacenter.extra.RELATIVE_PATH"

    /** The requested folder, or null when this intent is not a folder request. */
    fun relativePathFrom(intent: Intent?): String? {
        if (intent?.action != ACTION_VIEW_FOLDER) return null
        val path = intent.getStringExtra(EXTRA_RELATIVE_PATH)?.trim('/')
        return if (path.isNullOrEmpty()) null else path
    }

    /**
     * Resolves a RELATIVE_PATH to the album that holds it.
     *
     * Returns null when the folder holds no media this app can show — an empty
     * DCIM/Camera on a fresh install is the ordinary case. Callers must surface that as
     * a message: silently staying on the home screen is indistinguishable from success
     * and hides a broken lookup.
     */
    fun resolve(contentResolver: ContentResolver, relativePath: String): Album? {
        // MediaStore stores RELATIVE_PATH with a trailing separator.
        val stored = relativePath.trim('/') + "/"

        val selection =
            "${MediaStore.Files.FileColumns.RELATIVE_PATH} = ? AND " +
                "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?)"

        val args = arrayOf(
            stored,
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
        )

        return contentResolver.query(
            MediaQuery.MediaStoreFileUri,
            arrayOf(
                MediaStore.Files.FileColumns.BUCKET_ID,
                MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME,
            ),
            selection,
            args,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val id = cursor.getLong(0)
            val label = cursor.getString(1) ?: relativePath.substringAfterLast('/')
            Album(id, label)
        }
    }

    data class Album(val id: Long, val label: String)
}
