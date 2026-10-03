package com.diegonmarcos.superapp.browser

import androidx.compose.runtime.Composable

/**
 * #823 the seam to the Search add-on's on-screen page (Cloud Search's Search page, libs:search-page):
 * the APP draws it (libs:browser links no search page and no model client), the screen shows it
 * over the page. [page] gets [open] (open a URL as a tab) and [close]. Unset = the row says so.
 */
object BrowserSearchPageHost {
    @Volatile var page: (@Composable (open: (String) -> Unit, close: () -> Unit) -> Unit)? = null
}
