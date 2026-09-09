package app.sterna.ui.settings

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.material.icons.filled.Translate
import androidx.compose.ui.graphics.vector.ImageVector
import app.sterna.R

/**
 * Configs ▸ Text — AI Routing, Text Enhancement, Text Resume, Translation.
 *
 * THESE ARE SCREENS IN THIS APP, and that is the change. Each row used to fire an Intent into the
 * Cloud Keyboard's SettingsActivity: one copy of each page existed on the device, it belonged to
 * the keyboard, and a value changed from here changed the keyboard everywhere. The owner asked for
 * cloud-mail to have its own set of text tools and got a deep link to somebody else's, which meant
 * that in this app they could edit nothing. Each row now opens a page of THIS app's, over
 * cloud-mail's own store — see [MailAiRoutingScreen] and its neighbours in TextToolsScreens.kt.
 *
 * Two apps, two sets of settings, neither affecting the other. The prompts and model lists behind
 * them began as a copy of the keyboard's (`build.json::mail_ai`) and are free to diverge; that
 * divergence is the feature, not drift to be reconciled.
 *
 * WHAT IS STILL ONE COPY: the engine that executes a run, and the provider key it spends. Those
 * live in the keyboard's process behind ITextTools, and this app sends its settings to them rather
 * than holding a second HTTP client. See TextToolsScreens.kt for where that line is drawn.
 *
 * The section is DATA. The rest of this app's Configs hub is hand-written Compose rows against a
 * hand-written NavHost, and this does not change that — but a section whose only content is
 * "name, icon, and which page it opens" has nothing to hand-write, and as data the rows and their
 * destinations can be asserted against one another instead of being a fourth place the app's
 * structure is restated.
 */
internal class TextToolsEntry(
    /**
     * The row's destination in this app's own settings NavHost — a route in SettingsScreen's
     * graph. It used to be a KEY of the keyboard's `SettingsDestination.external` allowlist,
     * carried in an Intent extra; nothing here leaves cloud-mail any more.
     */
    val route: String,
    val icon: ImageVector,
    @StringRes val title: Int,
    @StringRes val summary: Int,
)

/** The four entries, in the order they are shown. */
internal val TEXT_TOOLS_ENTRIES = listOf(
    TextToolsEntry(
        route = "textAiRouting",
        icon = Icons.Filled.Hub,
        title = R.string.settings_text_ai_routing_title,
        summary = R.string.settings_text_ai_routing_summary,
    ),
    TextToolsEntry(
        route = "textEnhance",
        icon = Icons.Filled.AutoFixHigh,
        title = R.string.settings_text_enhance_title,
        summary = R.string.settings_text_enhance_summary,
    ),
    // "Text Resume" — the owner's product name for SUMMARISE, kept exactly. It is not a curriculum
    // vitae and it does not resume a paused operation; it is the settings page for the AI Resume
    // button on a message, and the prompt it sends is shown on it. After Text Enhancement, as asked.
    TextToolsEntry(
        route = "textResume",
        icon = Icons.Filled.Summarize,
        title = R.string.settings_text_resume_title,
        summary = R.string.settings_text_resume_summary,
    ),
    TextToolsEntry(
        route = "textTranslation",
        icon = Icons.Filled.Translate,
        title = R.string.settings_text_translation_title,
        summary = R.string.settings_text_translation_summary,
    ),
)
