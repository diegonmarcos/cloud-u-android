package app.sterna.ui.components

import androidx.compose.runtime.compositionLocalOf
import app.sterna.core.data.settings.LIST_MONOGRAM_DEFAULT
import app.sterna.core.data.settings.ListDensity
import app.sterna.core.data.settings.PreviewLines

/**
 * The message-list density chosen in Settings → Appearance, provided at the app
 */
val LocalListDensity = compositionLocalOf { ListDensity.NORMAL }

/** How many body-preview lines list rows show (Settings → Appearance). */
val LocalPreviewLines = compositionLocalOf { PreviewLines.ONE }

/**
 * Whether a list row starts with the sender's initials (Settings → Appearance → Message list).
 */
val LocalListMonogram = compositionLocalOf { LIST_MONOGRAM_DEFAULT }
