package app.sterna.ui.settings

import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.material.icons.filled.Translate
import androidx.compose.ui.graphics.vector.ImageVector
import app.sterna.R
import com.diegonmarcos.superapp.texttools.TextTools

/**
 * Configs ▸ Text — AI Routing, Text Enhancement, Text Resume, Translation.
 *
 * THESE ARE NOT SCREENS IN THIS APP, and that is the whole design. Each row opens the
 * Cloud Keyboard's own settings page, deep-linked by name. One copy of each page exists
 * on the device; the settings a user changes here are the keyboard's, in the keyboard's
 * store, because they ARE the keyboard's screens. Two lists of models that looked the
 * same and drifted apart is the outcome this avoids, and a rebuilt lookalike backed by
 * mail's own preferences would have been that outcome with an extra step: the same
 * screen twice, quietly disagreeing about which model is selected.
 *
 * The section is DATA. The rest of this app's Configs hub is hand-written Compose rows
 * against a hand-written NavHost, and this does not change that — but a section whose
 * only content is "name, icon, and which page it opens" has nothing to hand-write, and
 * as data the rows and their destinations can be asserted against one another instead
 * of being a fourth place the app's structure is restated.
 */
internal class TextToolsEntry(
    /**
     * The value of `SettingsActivity.EXTRA_OPEN_AT` — a KEY of the keyboard's
     * `SettingsDestination.external` allowlist, never a nav route. The route is the
     * keyboard's internal business and renaming one must not break this intent.
     */
    val screen: String,
    val icon: ImageVector,
    @StringRes val title: Int,
    @StringRes val summary: Int,
)

/** The four entries, in the order they are shown. */
internal val TEXT_TOOLS_ENTRIES = listOf(
    TextToolsEntry(
        screen = "ai_routing",
        icon = Icons.Filled.Hub,
        title = R.string.settings_text_ai_routing_title,
        summary = R.string.settings_text_ai_routing_summary,
    ),
    TextToolsEntry(
        screen = "text_enhance",
        icon = Icons.Filled.AutoFixHigh,
        title = R.string.settings_text_enhance_title,
        summary = R.string.settings_text_enhance_summary,
    ),
    // "Text Resume" — the owner's product name for SUMMARISE, kept exactly. It is not a curriculum
    // vitae and it does not resume a paused operation; it is the settings page for the AI Resume
    // button on a message, and the prompt it sends is shown on it. After Text Enhancement, as asked.
    TextToolsEntry(
        screen = "text_resume",
        icon = Icons.Filled.Summarize,
        title = R.string.settings_text_resume_title,
        summary = R.string.settings_text_resume_summary,
    ),
    TextToolsEntry(
        screen = "translation",
        icon = Icons.Filled.Translate,
        title = R.string.settings_text_translation_title,
        summary = R.string.settings_text_translation_summary,
    ),
)

/**
 * The intent that opens [entry]'s page.
 *
 * Explicit by package and class. Not an action, because an implicit intent for a
 * settings screen is a chooser waiting to happen, and the destination here is one
 * specific app by design — the one holding the settings.
 */
internal fun textToolIntent(entry: TextToolsEntry): Intent =
    Intent(Intent.ACTION_MAIN)
        .setClassName(TextTools.SERVICE_PKG, KEYBOARD_SETTINGS_ACTIVITY)
        .putExtra(EXTRA_OPEN_AT, entry.screen)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

/**
 * Open [entry]'s page; false when nothing took the intent, which in practice means the
 * Cloud Keyboard is not installed. The caller SAYS so — a settings row that does
 * nothing when tapped reads as a broken app rather than as a missing one.
 */
internal fun openTextTool(context: Context, entry: TextToolsEntry): Boolean =
    runCatching { context.startActivity(textToolIntent(entry)) }.isSuccess

/**
 * Named here rather than imported: the class lives in :libs:keyboard, which this app
 * deliberately does not depend on (an IME with a native decoder is not a dependency an
 * email client takes on to show a settings row). The string is the price of not linking
 * it, and test-mail-text-tools.sh checks it against the keyboard's real manifest so a
 * rename cannot leave it pointing at nothing.
 */
private const val KEYBOARD_SETTINGS_ACTIVITY = "helium314.keyboard.settings.SettingsActivity"

/** Mirrors `SettingsActivity.EXTRA_OPEN_AT`, and asserted equal to it by the same test. */
private const val EXTRA_OPEN_AT = "open_at"
