package com.diegonmarcos.cloudwriter

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AltRoute
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Spellcheck
import androidx.compose.material.icons.filled.Translate
import androidx.compose.ui.graphics.vector.ImageVector
import com.diegonmarcos.superapp.bottomnav.NavDecl

/**
 * #868 Cloud Writer's navigation, read from THE one declaration: build.json::ui (bottom_nav,
 * sections, default_section), baked by app/build.gradle and parsed by libs:bottomnav's [NavDecl].
 * MainActivity draws the bar (BottomNavIsland) and the Tools strip (PageTabs) from it; nothing
 * here lists a section twice. What stays code is the vocabulary a declared `icon` name resolves
 * to, and the localized label of each id (the declared `label` is the English fallback).
 */
object WriterNav {

    val decl: NavDecl by lazy {
        NavDecl.fromBuildConfig(BuildConfig.UI_SECTIONS_B64, BuildConfig.UI_BOTTOM_NAV, BuildConfig.UI_DEFAULT_SECTION)
    }

    /** The section ids build.json declares; a `when` over them is how MainActivity opens one. */
    const val DOCUMENTS = "documents"
    const val WRITER = "writer"
    const val TOOLS = "tools"
    const val SETTINGS = "settings"

    /** The declared ui.sections[tools].pages id of a tool's activity, by class name: MainActivity names each activity once. */
    fun pageId(screen: Class<*>): String? = when (screen.simpleName) {
        "TextEnhanceActivity" -> "enhance"
        "TranslationActivity" -> "translation"
        "GrammarCheckActivity" -> "grammar"
        "AiRoutingActivity" -> "ai_routing"
        "RoutesActivity" -> "routes"
        else -> null
    }

    /** A declared icon name as a vector; an unknown name is a plain document, never a crash. */
    fun icon(name: String): ImageVector = when (name) {
        "documents" -> Icons.Filled.Description
        "writer" -> Icons.Filled.Edit
        "tools" -> Icons.Filled.Build
        "settings" -> Icons.Filled.Settings
        "enhance" -> Icons.Filled.AutoAwesome
        "translation" -> Icons.Filled.Translate
        "grammar" -> Icons.Filled.Spellcheck
        "ai_routing" -> Icons.Filled.AltRoute
        "routes" -> Icons.Filled.Hearing
        else -> Icons.Filled.Description
    }
}
