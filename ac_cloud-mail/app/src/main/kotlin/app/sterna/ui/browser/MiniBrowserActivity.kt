package app.sterna.ui.browser

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.viewinterop.AndroidView
import app.sterna.R
import app.sterna.container
import app.sterna.core.data.settings.PURE_BLACK_DEFAULT
import app.sterna.core.data.settings.ThemeMode
import app.sterna.ui.rememberLeaveOnce
import app.sterna.ui.theme.SternaTheme
import java.util.Locale

/**
 * The app's own browser, for reading a link without leaving the message it came from.
 *
 * An Activity and not a route in the mail NavHost, deliberately. The link sites are not all on one
 * screen — the reader, the sender's unsubscribe page, the release notes, a notification — and a
 * route would have to be threaded down through six layers of composables from the NavHost to reach
 * the body WebView, and would still be unreachable from the notification, which has no NavHost at
 * all. An Intent reaches all of them, gets its own entry on the system Back stack for free, and
 * leaves [InAppBrowser.openLink] as the ONE thing every call site has to know about.
 *
 * It is a browser, so JavaScript and DOM storage are ON — the opposite of the message body, where
 * they are off because an email is not an application. What stays off is every way out of the web:
 * no file access, no content:// access, no third-party cookies, and no scheme but http(s) is ever
 * navigated to in here (see [shouldOverrideUrlLoading]). A link arriving from a hostile email
 * must not be able to reach the device through the window that was opened to contain it.
 */
class MiniBrowserActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // No address means nothing to show. Finishing beats an empty browser that the user has to
        // work out how to dismiss.
        val url = intent.getStringExtra(EXTRA_URL)
        if (url.isNullOrBlank()) {
            finish()
            return
        }
        // The owner's own theme, read the same way MainActivity reads it. A browser window that
        // opens in the stock palette while the rest of the app is pure black reads as another app.
        val settings = application.container.settingsRepository
        setContent {
            val themeMode by settings.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
            val dynamicColor by settings.dynamicColor.collectAsState(initial = false)
            val pureBlack by settings.pureBlack.collectAsState(initial = PURE_BLACK_DEFAULT)
            SternaTheme(themeMode = themeMode, dynamicColor = dynamicColor, pureBlack = pureBlack) {
                MiniBrowserScreen(initialUrl = url, onClose = { finish() })
            }
        }
    }

    companion object {
        private const val EXTRA_URL = "app.sterna.browser.URL"

        /**
         * Show [uri] in this browser, reporting whether it opened — the same contract as
         * [InAppBrowser.openExternally], so a caller can treat "stayed in the app" and "handed to
         * another app" as one outcome and still latch a link that went nowhere.
         *
         * Named in full rather than `start`: NavHostSourceRulesTest finds the app's link openers by
         * reading the sources for `startActivity(` and then treats the enclosing function's NAME as
         * another opener to look for. A function called `start` would make the substring `start(`
         * stand for "hand-off to another app" across the whole tree, and flag every unrelated
         * `.start(` in it.
         */
        fun openMiniBrowser(context: Context, uri: Uri): Boolean = try {
            context.startActivity(
                Intent(context, MiniBrowserActivity::class.java)
                    .putExtra(EXTRA_URL, uri.toString())
                    // Required when the caller is not an Activity — a notification tap, a service.
                    // Harmless from an Activity of this same app, which reuses the current task.
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            true
        } catch (e: Exception) {
            false
        }
    }
}

/**
 * A desktop browser's own claim about itself, sent so that a site serves its wide layout.
 *
 * The user agent IS the "View as Desktop" switch, and nothing else needs to change with it: the
 * viewport settings below already fit whatever width arrives to the screen, so a desktop page
 * zooms out to fit rather than overflowing. Swapping layout flags as well would mean maintaining
 * two viewport configurations to express one choice.
 */
private const val DESKTOP_USER_AGENT =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/124.0.0.0 Safari/537.36"

/** Schemes handed to the system from inside the browser: things a web page cannot do itself. */
private val HANDOFF_SCHEMES = setOf("mailto", "tel", "sms", "geo")

/**
 * An address the user typed, as something loadable. A bare host — the normal way anyone types an
 * address — has no scheme, and `loadUrl` treats a schemeless string as a file path.
 *
 * It does not guess at searching. A typed phrase that is not an address stays a failed page load
 * rather than being silently sent to a search engine the user did not choose.
 */
private fun normalizeAddress(typed: String): String {
    val trimmed = typed.trim()
    return if (trimmed.contains("://")) trimmed else "https://$trimmed"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MiniBrowserScreen(initialUrl: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    var webView by remember { mutableStateOf<WebView?>(null) }
    // What is loaded, and what is in the address field, are two values on purpose: the field is
    // editable, so between a keystroke and Go they legitimately disagree.
    var loaded by rememberSaveable { mutableStateOf(initialUrl) }
    var address by rememberSaveable { mutableStateOf(initialUrl) }
    // The page follows the user's navigation into the field — EXCEPT while the field has focus,
    // because a page finishing its load must not overwrite an address halfway through being typed.
    var editing by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(100) }
    var canGoBack by remember { mutableStateOf(false) }
    var desktop by rememberSaveable { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    val leaveOnce = rememberLeaveOnce()
    val noAppMessage = stringResource(R.string.browser_no_app)
    // Hand off to one named app, and close this window if it took the link — the user asked for the
    // page somewhere else, so leaving it open here would leave them with two copies to dismiss.
    val handOff: (String) -> Unit = { packageName ->
        menuOpen = false
        leaveOnce {
            val opened = InAppBrowser.openExternally(context, Uri.parse(loaded), packageName)
            if (opened) onClose() else Toast.makeText(context, noAppMessage, Toast.LENGTH_SHORT).show()
            opened
        }
    }

    // System Back walks the page's own history first, and only leaves once there is none left.
    BackHandler(enabled = canGoBack) { webView?.goBack() }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = onClose) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = stringResource(R.string.browser_close),
                            )
                        }
                    },
                    title = {
                        // The editable nav bar. A BasicTextField and not a TextField: this is one
                        // line of text on a toolbar, and a filled container with an indicator line
                        // would draw a form field across the whole top of a browser.
                        BasicTextField(
                            value = address,
                            onValueChange = { address = it },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                color = MaterialTheme.colorScheme.onSurface,
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(
                                onGo = {
                                    loaded = normalizeAddress(address)
                                    focus.clearFocus()
                                },
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .onFocusChanged { editing = it.isFocused },
                        )
                    },
                    actions = {
                        IconButton(onClick = { webView?.reload() }) {
                            Icon(
                                Icons.Filled.Refresh,
                                contentDescription = stringResource(R.string.browser_reload),
                            )
                        }
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(
                                Icons.Filled.MoreVert,
                                contentDescription = stringResource(R.string.browser_menu),
                            )
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.browser_open_in_cloud_browser)) },
                                onClick = { handOff(InAppBrowser.CLOUD_BROWSER_PACKAGE) },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.browser_open_in_brave)) },
                                onClick = { handOff(InAppBrowser.BRAVE_PACKAGE) },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.browser_translate_page)) },
                                onClick = {
                                    menuOpen = false
                                    // The device's own language: the reader wants the page in the
                                    // language they read the phone in, not one this app picked.
                                    val target = Locale.getDefault().language
                                    loaded = InAppBrowser.translatedUrl(loaded, target)
                                },
                            )
                            // "View as {Desktop, Mobile}": both states listed with a tick on the
                            // active one, rather than one item that renames itself. A single
                            // toggling label never says which way round it currently is.
                            ViewAsItem(
                                label = stringResource(R.string.browser_view_desktop),
                                active = desktop,
                            ) { menuOpen = false; desktop = true }
                            ViewAsItem(
                                label = stringResource(R.string.browser_view_mobile),
                                active = !desktop,
                            ) { menuOpen = false; desktop = false }
                        }
                    },
                )
                // Only while something is in flight. A bar pinned at 100% on a finished page is a
                // permanent line under the toolbar that says nothing.
                if (progress in 1..99) {
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
    ) { padding ->
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    // Fit whatever the page declares to this screen, and keep pinch-zoom: the same
                    // pair the reading view settled on (#305, #278).
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    settings.setSupportZoom(true)
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                    // A page opened from an email has no business reading this device. Both of
                    // these default to true on older API levels, so they are set, not assumed.
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                    // A WebView renders pages; it does not download files. Without this a tapped
                    // download (the release APK on the update screen, an attachment on a web form)
                    // is silently nothing at all. Handed to the system rather than fetched here:
                    // writing a file to this device is not something a page from an email decides.
                    setDownloadListener { downloadUrl, _, _, _, _ ->
                        // unguarded: fires from the page, not from a tap in composable scope, and
                        // this browser deliberately stays open behind the download — the remembered
                        // one-shot leave latch is the wrong shape for a window that is not leaving.
                        InAppBrowser.openExternally(ctx, Uri.parse(downloadUrl))
                    }
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?,
                        ): Boolean {
                            val url = request?.url ?: return false
                            return when (url.scheme?.lowercase(Locale.ROOT)) {
                                // A web page stays in the window. That IS the feature.
                                "http", "https" -> false
                                // Something a browser cannot do. The system can.
                                in HANDOFF_SCHEMES -> {
                                    // unguarded: a navigation decision made by the page, not a tap
                                    // in composable scope, and the window stays open behind the
                                    // dialler or the draft — nothing here is leaving once.
                                    InAppBrowser.openExternally(ctx, url)
                                    true
                                }
                                // intent:, javascript:, file:, content:, anything else — swallowed.
                                // Don't navigate and don't hand it on: an <a href="intent://…"> is
                                // a redirect into another app, chosen by the page, not the reader.
                                else -> true
                            }
                        }

                        override fun doUpdateVisitedHistory(
                            view: WebView?,
                            url: String?,
                            isReload: Boolean,
                        ) {
                            canGoBack = view?.canGoBack() == true
                            if (url != null && !editing) address = url
                        }
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            progress = newProgress
                        }
                    }
                    webView = this
                }
            },
            // The WebView holds a native renderer and a JS context; the composition going away is
            // the only moment either can be released without touching a still-attached view.
            onRelease = { it.destroy() },
            modifier = Modifier.fillMaxSize().padding(padding),
        )
    }

    // Keyed on the view too: the first load has to wait for the factory to have produced one.
    LaunchedEffect(webView, loaded, desktop) {
        val wv = webView ?: return@LaunchedEffect
        // null restores the platform's own mobile user agent — this app does not get to author it.
        wv.settings.userAgentString = if (desktop) DESKTOP_USER_AGENT else null
        wv.loadUrl(loaded)
    }
}

/** One of the two "View as" choices, ticked when it is the one in force. */
@Composable
private fun ViewAsItem(label: String, active: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        trailingIcon = {
            if (active) Icon(Icons.Filled.Check, contentDescription = null)
        },
        onClick = onClick,
    )
}
