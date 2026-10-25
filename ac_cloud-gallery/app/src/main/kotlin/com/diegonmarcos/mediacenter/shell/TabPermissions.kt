/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.diegonmarcos.mediacenter.shell

import android.Manifest

/**
 * The runtime permissions each tab needs, asked for in context (the tab shows the request itself
 * when one is missing, rather than only in the setup wizard). All of them are already declared in
 * the manifest.
 */
object TabPermissions {
    private const val TIRAMISU = 33

    /** Reading the library: images and video from API 33, the storage permission before. */
    fun media(sdk: Int): List<String> =
        if (sdk >= TIRAMISU) listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        else listOf(Manifest.permission.READ_EXTERNAL_STORAGE)

    /** Photo GPS tags are redacted by MediaStore without this one. */
    const val MEDIA_LOCATION: String = Manifest.permission.ACCESS_MEDIA_LOCATION

    /** Every tab reads the library; Maps also reads the position tags. */
    fun forTab(tab: GalleryTab, sdk: Int): List<String> =
        if (tab == GalleryTab.MAPS) media(sdk) + MEDIA_LOCATION else media(sdk)
}
