/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.diegonmarcos.mediacenter.shell

import com.diegonmarcos.mediacenter.BuildConfig
import com.diegonmarcos.superapp.bottomnav.NavDecl

/**
 * build.json::ui, decoded once. app/build.gradle.kts bakes ui.sections, ui.bottom_nav and
 * ui.default_section into BuildConfig; this object is their only reader, so no tab id, label or
 * icon is spelled in Kotlin.
 */
object GalleryDeclarations {
    val nav: NavDecl by lazy {
        NavDecl.fromBuildConfig(BuildConfig.UI_SECTIONS_B64, BuildConfig.UI_BOTTOM_NAV, BuildConfig.UI_DEFAULT_SECTION)
    }
}
