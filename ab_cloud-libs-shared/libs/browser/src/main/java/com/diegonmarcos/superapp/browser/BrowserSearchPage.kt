package com.diegonmarcos.superapp.browser

import androidx.compose.runtime.Composable

/**
 * #823 the seam to the Search add-on's on-screen page (libs:ai's AI page, Cloud Search's page and chat):
 * the APP draws it (libs:browser links no AI chat UI and no model client), the screen shows it
 * over the page. [page] gets [open] (open a URL as a tab) and [close]. Unset = the row says so.
 */
object BrowserSearchPageHost {
    @Volatile var page: (@Composable (open: (String) -> Unit, close: () -> Unit) -> Unit)? = null
}
