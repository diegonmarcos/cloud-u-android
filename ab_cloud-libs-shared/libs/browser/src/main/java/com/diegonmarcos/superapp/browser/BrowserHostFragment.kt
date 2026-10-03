package com.diegonmarcos.superapp.browser

import com.diegonmarcos.superapp.core.Collapsible
import com.diegonmarcos.superapp.core.SuppressHorizontalSwipe
import com.diegonmarcos.superapp.core.SuppressVerticalSwipe

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Filter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.fragment.app.Fragment
import com.diegonmarcos.superapp.uikit.KitPalette
import com.diegonmarcos.superapp.uikit.kitComposeView
import org.json.JSONObject
import com.diegonmarcos.superapp.browser.R
import java.io.File
import java.security.MessageDigest

/**
 * Tabs host — three modes:
 *
 *   GRID     tab switcher. Cards of every open tab, drawn by
 *            [BrowserTabGrid] (a RecyclerView, so long-press drag can
 *            reorder them), with pinned tabs first and named groups as
 *            collapsible headers.
 *   DETAIL   WebView fullscreen. The address bar searches as well as
 *            navigates, and suggests from local history + open tabs.
 *   (history, bookmarks and downloads are #802 Compose pages drawn over these.)
 *
 * WHAT IS SHARED AND WHAT IS NOT. Every mechanism above lives in this
 * module and reaches every app that links it. None of the CONTENT does:
 * which tabs are pinned on a fresh install and which search engines
 * exist arrive as a [BrowserConfig] from the consuming app, and the
 * library default is an empty pin list. See [BrowserConfig].
 *
 * Implements [Collapsible] so re-tapping the Tabs bottom-nav slot
 * snaps back to GRID.
 */
class BrowserHostFragment : Fragment(), Collapsible,
    SuppressHorizontalSwipe,
    SuppressVerticalSwipe {

    override fun suppressHorizontalSwipe(): Boolean = mode is Mode.DETAIL
    override fun suppressVerticalSwipe(): Boolean = mode is Mode.DETAIL

    /** Desktop-mode toggle — WebView UA + width override + initial scale. #802 persisted
     *  as the catalogue's `desktop_mode`, so it survives a restart and moves with the Account. */
    private val desktopMode: Boolean get() = browserSettings.bool("desktop_mode") == true

    private lateinit var prefs: BrowserTabPrefs
    private lateinit var history: BrowserHistory
    private lateinit var bookmarks: BrowserBookmarks
    private lateinit var downloads: BrowserDownloads
    private lateinit var sitePerms: BrowserSitePermissions
    /** Origins a private tab visited this session — their site storage goes when the last one closes. */
    private val privateOrigins = HashSet<String>()
    private lateinit var browserSettings: BrowserSettings
    private lateinit var config: BrowserConfig
    private lateinit var rootContainer: FrameLayout
    private var webView: WebView? = null
    private var mode: Mode = Mode.GRID

    /** #802 reader view is showing an extraction of the current page. */
    private var readerOn = false
    /** The next page start is the reader's own render, not a navigation away from it. */
    private var readerPending = false
    /** #802 the Compose surfaces drawn over the page; back closes the top one first. */
    private val overlays = ArrayList<View>()
    /** Compose state, so the find bar redraws its count when a search (screen or API) lands. */
    private val findCount = androidx.compose.runtime.mutableStateOf<Int?>(null)

    private val roleRequest = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    /** #802 a site was allowed a permission the APP does not hold yet: Android asks first. */
    private var permDone: ((Boolean) -> Unit)? = null
    private val permRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        permDone?.invoke(r.values.all { it }); permDone = null
    }

    /** #802 Profile ▸ Import file: a CSV / Firefox / Bitwarden / own-JSON export, merged into the profile. */
    private val importProfile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        val text = runCatching { requireContext().contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() } }.getOrNull()
        val p = text?.let { runCatching { BrowserProfile.parse(null, it) }.getOrNull() }
        if (p == null || p.isEmpty) { toast("Nothing importable in that file"); return@registerForActivityResult }
        BrowserProfileStore(requireContext()).import(p)
        toast("Imported ${p.addresses.size} address(es), ${p.cards.size} card(s) (metadata only)")
    }

    private sealed class Mode {
        object GRID : Mode()
        data class DETAIL(val url: String) : Mode()
    }

    /** The engine a typed query goes to: his pick, else the app's default. */
    private fun engine(): BrowserSearchEngine = config.engine(browserSettings.searchEngineId())

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val ctx = inflater.context
        prefs = BrowserTabPrefs(ctx)
        history = BrowserHistory(ctx)
        bookmarks = BrowserBookmarks(ctx)
        downloads = BrowserDownloads(ctx)
        sitePerms = BrowserSitePermissions(ctx)
        config = BrowserConfig.parseBase64(arguments?.getString(ARG_CONFIG_B64))
        browserSettings = BrowserSettings(ctx, config.settings)

        // FIRST RUN ONLY, and only with what the app configured. A library
        // default of zero tabs means an app that supplies nothing gets
        // nothing — never another app's homepage set.
        val seeded = prefs.seedOnce(config.defaultPinnedTabs)
        if (seeded > 0) {
            android.util.Log.i(TAG, "seeded $seeded default pinned tab(s) on first run")
        }

        rootContainer = FrameLayout(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            background = androidx.core.content.ContextCompat.getDrawable(
                ctx, R.drawable.bg_gradient_black_purple)
        }

        val openUrl = arguments?.getString(ARG_OPEN_URL)
        val restore = prefs.activeUrl().takeIf { browserSettings.bool("restore_tabs_on_start") == true }
        if (!openUrl.isNullOrBlank()) {
            prefs.add(openUrl, openUrl); prefs.setActive(openUrl)
            showDetail(openUrl)
        } else if (!restore.isNullOrBlank() && prefs.all().any { it.url == restore }) {
            showDetail(restore)
        } else {
            showGrid()
        }
        return rootContainer
    }

    /** #802 a write from outside the screen (debug route, fleet import) shows up live. */
    override fun onResume() {
        super.onResume()
        BrowserBus.listener = { change -> if (isAdded) onBusChange(change) }
        BrowserBus.page = pageHost
    }

    override fun onPause() {
        BrowserBus.listener = null
        if (BrowserBus.page === pageHost) BrowserBus.page = null
        super.onPause()
    }

    private fun onBusChange(change: String) {
        when {
            change == BrowserBus.SETTINGS -> webView?.let { applySettings(it); it.reload() }
            change == BrowserBus.TABS -> if (mode is Mode.GRID) showGrid()
            change.startsWith(BrowserBus.OPEN) -> navigateTo(change.removePrefix(BrowserBus.OPEN))
        }
    }

    override fun toggleAllCollapsed(): Boolean {
        if (mode !is Mode.GRID) { showGrid(); return true }
        return false
    }

    // ── GRID mode ────────────────────────────────────────────────────

    private fun showGrid() {
        mode = Mode.GRID
        teardownWebView()
        val ctx = requireContext()

        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }

        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            val pad = dp(12); setPadding(pad, dp(8), pad, dp(4))
        }
        header.addView(TextView(ctx).apply {
            text = "Tabs"
            setTextColor(0xFFE9D8FD.toInt())
            typeface = Typeface.DEFAULT_BOLD
            setTextAppearance(android.R.style.TextAppearance_Material_Title)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(pill(ctx, "History") { showHistory() })
        header.addView(TextView(ctx).apply { text = "  " })
        header.addView(pill(ctx, "+ New tab") { promptForUrl() })
        column.addView(header)

        val tabs = prefs.all()
        if (tabs.isEmpty()) {
            column.addView(TextView(ctx).apply {
                text = "No tabs yet. Tap + to open a URL or search."
                setTextColor(0xCCFFFFFF.toInt())
                alpha = 0.7f
                val pad = dp(20); setPadding(pad, pad, pad, pad)
            })
        } else {
            val grid = BrowserTabGrid(
                ctx,
                onOpen = { tab -> prefs.setActive(tab.url); showDetail(tab.url) },
                onClose = { tab -> closeTab(tab) },
                onMenu = { tab, anchor -> showTabMenu(tab, anchor) },
                onToggleGroup = { g ->
                    prefs.setGroupCollapsed(g, g !in prefs.collapsedGroups()); showGrid()
                },
                onReorder = { urls -> prefs.reorder(urls) },
            )
            grid.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            grid.submit(BrowserGridRows.build(tabs, prefs.collapsedGroups()))
            column.addView(grid)
        }

        overlays.clear()
        rootContainer.removeAllViews()
        rootContainer.addView(column)
    }

    /**
     * Close, honouring the pin. [BrowserTabPrefs.remove] is what refuses;
     * this only reports the refusal, so the constraint cannot drift apart
     * from the button.
     */
    private fun closeTab(tab: BrowserTab) {
        if (!prefs.remove(tab.url)) {
            toast("“${tab.title.ifBlank { tab.url }}” is pinned — unpin it first")
            return
        }
        if (tab.previewPath.isNotBlank()) runCatching { File(tab.previewPath).delete() }
        val left = prefs.all()
        if (tab.isPrivate && left.none { it.isPrivate }) {
            BrowserClearData.endPrivateSession(privateOrigins, normalTabsOpen = left.isNotEmpty())
            privateOrigins.clear()
        }
        showGrid()
    }

    private fun promptForGroup(tab: BrowserTab) {
        val ctx = requireContext()
        val input = android.widget.EditText(ctx).apply {
            hint = "Group name"
            setText(tab.group)
            setTextColor(Color.WHITE)
            setHintTextColor(0x80FFFFFF.toInt())
        }
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle("Group")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                prefs.setGroup(tab.url, input.text.toString()); showGrid()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pill(ctx: Context, text: String, onTap: () -> Unit): View =
        TextView(ctx).apply {
            this.text = text
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(18).toFloat()
                setColor(0xFF7C3AED.toInt())
            }
            val px = dp(14); val py = dp(8)
            setPadding(px, py, px, py)
            setOnClickListener { onTap() }
        }

    /** New tab. Accepts a URL or a search — same rule as the address bar. */
    private fun promptForUrl(private_: Boolean = browserSettings.bool("private_by_default") == true) {
        val ctx = requireContext()
        val input = suggestField(ctx, "")
        input.hint = "Search ${engine().label}, or type a URL"
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle(if (private_) "New private tab" else "New tab")
            .setView(input)
            .setPositiveButton("Open") { _, _ -> openEntry(input.text.toString(), private_) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Resolve what was typed to a destination, open it as a new tab.
     *  Nothing typed opens the `homepage` setting, when he has set one. */
    private fun openEntry(raw: String, private_: Boolean = false) {
        val typed = raw.ifBlank { browserSettings.string("homepage").orEmpty() }
        val url = BrowserSearch.resolve(typed, engine())
        if (url.isBlank()) return
        prefs.add(url, url, isPrivate = private_)
        prefs.setActive(url)
        showDetail(url)
    }

    // ── suggestions (item 6) ─────────────────────────────────────────

    /**
     * An address field whose dropdown offers his own open tabs and his
     * own history, plus a row that runs the text through the configured
     * engine. Every source is on-device — see [BrowserSuggest] for why
     * there is no remote feed to switch off.
     */
    private fun suggestField(ctx: Context, initial: String): AutoCompleteTextView {
        val urls = ArrayList<String>()
        val labels = ArrayList<String>()

        // ArrayAdapter's own Filter would re-filter our ranked list by
        // prefix and throw the ranking away, so it is replaced with a
        // pass-through and the list is recomputed on each keystroke.
        val adapter = object : ArrayAdapter<String>(
            ctx, android.R.layout.simple_list_item_1, labels
        ) {
            private val passthrough = object : Filter() {
                override fun performFiltering(constraint: CharSequence?): Filter.FilterResults =
                    Filter.FilterResults().apply { values = labels; count = labels.size }
                override fun publishResults(c: CharSequence?, r: Filter.FilterResults?) {
                    notifyDataSetChanged()
                }
            }
            override fun getFilter(): Filter = passthrough

            override fun getView(pos: Int, convert: View?, parent: ViewGroup): View =
                super.getView(pos, convert, parent).also { row ->
                    (row as? TextView)?.apply {
                        setTextColor(0xFFFFFFFF.toInt())
                        isSingleLine = true
                        ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                    }
                }
        }

        return AutoCompleteTextView(ctx).apply {
            setText(initial)
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0x80FFFFFF.toInt())
            setBackgroundColor(Color.TRANSPARENT)
            isSingleLine = true
            threshold = 1
            setAdapter(adapter)
            setDropDownBackgroundDrawable(ColorDrawable(0xFF1A0033.toInt()))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_GO
            setSelectAllOnFocus(true)

            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(e: android.text.Editable?) {
                    val q = e?.toString().orEmpty()
                    val out = BrowserSuggest.suggest(
                        q, prefs.all(), history.all(), engine())
                    urls.clear(); labels.clear()
                    out.forEach { urls.add(it.url); labels.add(it.label) }
                    adapter.notifyDataSetChanged()
                    if (labels.isNotEmpty() && isFocused) showDropDown()
                }
            })

            setOnItemClickListener { _, _, position, _ ->
                urls.getOrNull(position)?.let { picked ->
                    dismissDropDown()
                    hideKeyboard(this)
                    navigateTo(picked)
                }
            }
        }
    }

    // ── DETAIL mode (WebView) ────────────────────────────────────────

    /** Swap the active tab to [url] and draw it. */
    private fun navigateTo(url: String) {
        if (url.isBlank()) return
        prefs.add(url, url)
        prefs.setActive(url)
        showDetail(url)
    }

    private fun showDetail(url: String) {
        mode = Mode.DETAIL(url)
        val ctx = requireContext()
        val column = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setBackgroundColor(0xCC1A0033.toInt())
            val pad = dp(8); setPadding(pad, pad, pad, pad)
        }
        bar.addView(TextView(ctx).apply {
            text = " ← Tabs "
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setOnClickListener { showGrid() }
        })

        // Address bar: suggests while typing (item 6) and searches when
        // what was typed is not a URL (item 7).
        val urlBar = suggestField(ctx, url).apply {
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            val m = dp(8); setPadding(m, 0, m, 0)
            setOnEditorActionListener { v, actionId, _ ->
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_GO) {
                    val next = BrowserSearch.resolve(text.toString(), engine())
                    hideKeyboard(v)
                    if (next.isNotEmpty() && next != url) {
                        // The tab being typed in becomes the tab that
                        // lands — unless it is pinned, in which case the
                        // destination opens alongside it rather than
                        // replacing something he asked to keep.
                        val prior = prefs.activeUrl() ?: url
                        prefs.remove(prior)
                        navigateTo(next)
                    } else {
                        webView?.loadUrl(url)
                    }
                    true
                } else false
            }
        }
        bar.addView(barButton(ctx, "‹") { runAction("back") })
        bar.addView(barButton(ctx, "›") { runAction("forward") })
        bar.addView(urlBar)

        bar.addView(barButton(ctx, "⋮") { showMenuSheet() })
        column.addView(bar)

        webView = WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.setGeolocationEnabled(true)
            // #802 the Android Autofill Framework (Cloud Vault's service) sees the page's fields.
            importantForAutofill = if (browserSettings.bool("autofill_enabled") == false) View.IMPORTANT_FOR_AUTOFILL_NO
                else View.IMPORTANT_FOR_AUTOFILL_YES
            // #802 a private tab keeps nothing in the HTTP cache.
            if (currentTab()?.isPrivate == true) settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
            applySettings(this, url)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, startedUrl: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, startedUrl, favicon)
                    // A real navigation leaves reader view; the reader's own render does not.
                    if (readerPending) readerPending = false else readerOn = false
                    // Settings, then this host's shields: a host that was shielded must not leave JS off for the next.
                    view?.let { applySettings(it, startedUrl) }
                }

                override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                    super.onPageFinished(view, finishedUrl)
                    val u = finishedUrl ?: return
                    if (readerOn) return
                    // #802 a private tab is never recorded: no history, no preview.
                    if (!BrowserSitePolicy.shouldRecord(currentTab())) {
                        runCatching { java.net.URI(u) }.getOrNull()?.let { privateOrigins.add("${it.scheme}://${it.authority}") }
                        return
                    }
                    // Item 5. Local store, no sink, no sync — see BrowserHistory.
                    history.record(u, view?.title ?: u)
                    postDelayed({ capturePreview(this@apply, u) }, 600)
                }
            }
            setDownloadListener { dlUrl, ua, disposition, mime, _ ->
                val d = download(dlUrl, ua, disposition, mime)
                toast("Downloading ${d.file}")
            }
            webChromeClient = object : WebChromeClient() {
                override fun onReceivedTitle(view: WebView?, title: String?) {
                    val u = view?.url ?: return
                    prefs.updateTitle(u, title ?: u)
                }

                /** #802 camera / microphone: the site's rule, else ask; never silently granted. */
                override fun onPermissionRequest(request: android.webkit.PermissionRequest) {
                    val host = request.origin?.host.orEmpty().lowercase()
                    val wanted = request.resources.filter { r -> config.sitePerms.any { r in it.webkit } }
                    val perms = config.sitePerms.filter { p -> wanted.any { it in p.webkit } }
                    if (wanted.isEmpty()) { request.deny(); return }
                    decide(host, perms) { ok -> if (ok) request.grant(wanted.toTypedArray()) else request.deny() }
                }

                override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: android.webkit.GeolocationPermissions.Callback?) {
                    val perm = config.sitePerms.firstOrNull { "geolocation" in it.webkit }
                    if (perm == null || origin == null) { callback?.invoke(origin, false, false); return }
                    decide(BrowserSitePolicy.hostOf(origin), listOf(perm)) { ok -> callback?.invoke(origin, ok, false) }
                }
            }
            loadUrl(url)
        }
        column.addView(webView)

        overlays.clear()
        rootContainer.removeAllViews()
        rootContainer.addView(column)
    }

    // ── Library: history, bookmarks, downloads (#802: Compose, over the page) ──

    /** Item 5's view of the on-device history, with Clear. */
    private fun showHistory() {
        val visits = history.all()
        overlay { close ->
            BrowserListScreen("History", visits.map { ListRow(it.url, it.title.ifBlank { it.url }, it.url) },
                empty = "No history yet.", onOpen = { close(); navigateTo(it.id) }, onRemove = null,
                headerAction = "Clear" to { history.clear(); close(); showHistory() }, onClose = close)
        }
    }

    /** Bookmarks under folder headers; ✕ on a header deletes the folder, ✎ renames it. */
    private fun showBookmarks() {
        val all = bookmarks.all()
        val rows = (listOf("") + bookmarks.folders()).flatMap { f ->
            val inF = all.filter { it.folder == f }
            (if (f.isEmpty()) emptyList() else listOf(ListRow(f, f, "${inF.size} here", header = true))) +
                inF.map { ListRow(it.url, it.title, it.url) }
        }
        overlay { close ->
            BrowserListScreen("Bookmarks", rows, empty = "No bookmarks yet: ☆ in the menu adds this page.",
                onOpen = { if (!it.header) { close(); navigateTo(it.id) } },
                onRemove = { r -> if (r.header) bookmarks.deleteFolder(r.id) else bookmarks.remove(r.id); close(); showBookmarks() },
                onRename = { r, to -> bookmarks.moveFolder(r.id, to); close(); showBookmarks() },
                onClose = close)
        }
    }

    private fun showDownloads() {
        val rows = downloads.all().map { ListRow(it.id.toString(), it.file, "${downloads.status(it.id)} · ${it.url}") }
        overlay { close ->
            BrowserListScreen("Downloads", rows, empty = "Nothing downloaded yet.",
                onOpen = { startActivity(android.content.Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS)) },
                onRemove = null, headerAction = "Clear list" to { downloads.clear(); close(); showDownloads() },
                onClose = close)
        }
    }

    /** A page download, with the page's cookies and agent, into the `download_dir` setting. */
    private fun download(url: String, ua: String?, disposition: String?, mime: String?): BrowserDownload =
        downloads.enqueue(url, ua, disposition, mime, browserSettings.string("download_dir").orEmpty())

    /** Pin [url] to the launcher; it opens here (MainActivity takes VIEW intents). */
    private fun addToHome(url: String, title: String): Boolean {
        val ctx = requireContext()
        if (!androidx.core.content.pm.ShortcutManagerCompat.isRequestPinShortcutSupported(ctx)) return false
        val info = androidx.core.content.pm.ShortcutInfoCompat.Builder(ctx, "page:" + sha256(url).take(16))
            .setShortLabel(title.ifBlank { url }.take(24))
            .setIntent(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).setPackage(ctx.packageName))
            .setIcon(androidx.core.graphics.drawable.IconCompat.createWithResource(ctx, ctx.applicationInfo.icon))
            .build()
        return androidx.core.content.pm.ShortcutManagerCompat.requestPinShortcut(ctx, info, null)
    }

    // ── misc ─────────────────────────────────────────────────────────

    private fun capturePreview(wv: WebView, url: String) {
        runCatching {
            val w = wv.width; val h = wv.height
            if (w <= 0 || h <= 0) return
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
            wv.draw(Canvas(bmp))
            val dir = File(requireContext().cacheDir, "tabs").apply { mkdirs() }
            val file = File(dir, "${sha256(url)}.png")
            file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 70, it) }
            bmp.recycle()
            prefs.updatePreview(url, file.absolutePath)
        }
    }

    private fun sha256(s: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(40)
    }

    /** #802 the catalogue's page settings onto [wv]. An undeclared key leaves WebView's own. */
    private fun applySettings(wv: WebView, url: String? = wv.url) {
        val s = wv.settings
        browserSettings.bool("javascript")?.let { s.javaScriptEnabled = it }
        browserSettings.bool("load_images")?.let { s.loadsImagesAutomatically = it }
        browserSettings.int("text_zoom")?.let { s.textZoom = it }
        browserSettings.bool("block_third_party_cookies")?.let {
            android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(wv, !it)
        }
        applyViewMode(wv)
        applyShields(wv, url)
    }

    /** #802 per-site shields: a `deny` rule for javascript / images on this host turns it off here. */
    private fun applyShields(wv: WebView, url: String?) {
        val host = BrowserSitePolicy.hostOf(url)
        if (host.isEmpty()) return
        if (shieldDenied(host, "javascript")) wv.settings.javaScriptEnabled = false
        if (shieldDenied(host, "images")) wv.settings.loadsImagesAutomatically = false
    }

    private fun shieldDenied(host: String, perm: String): Boolean =
        config.sitePerms.firstOrNull { it.id == perm }?.let { sitePerms.resolve(host, it) == BrowserSitePolicy.DENY } == true

    private fun currentTab(): BrowserTab? {
        val u = (mode as? Mode.DETAIL)?.url ?: return null
        return prefs.all().firstOrNull { it.url == u }
    }

    /**
     * #802 may [host] have [perms]? Its rules decide; `ask` shows the question over the
     * page and remembers the answer; an allowed permission the APP lacks goes through
     * Android's own prompt first. Every path ends in exactly one [done].
     */
    private fun decide(host: String, perms: List<BrowserSitePerm>, done: (Boolean) -> Unit) {
        val vals = perms.map { sitePerms.resolve(host, it) }
        when {
            perms.isEmpty() || BrowserSitePolicy.DENY in vals -> done(false)
            vals.all { it == BrowserSitePolicy.ALLOW } -> ensureAndroid(perms, done)
            else -> overlay { close ->
                BrowserTextPanel("$host wants: ${perms.joinToString { it.label }}",
                    "Your answer is remembered for this site; Site settings changes it.",
                    listOf(SheetRow(BrowserSitePolicy.ALLOW, "Allow"), SheetRow(BrowserSitePolicy.DENY, "Deny")),
                    onPick = { v ->
                        close()
                        perms.forEach { sitePerms.set(host, it.id, v) }
                        if (v == BrowserSitePolicy.ALLOW) ensureAndroid(perms, done) else done(false)
                    },
                    onClose = { close(); done(false) })
            }
        }
    }

    private fun ensureAndroid(perms: List<BrowserSitePerm>, done: (Boolean) -> Unit) {
        val need = perms.flatMap { it.android }.filter {
            androidx.core.content.ContextCompat.checkSelfPermission(requireContext(), it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (need.isEmpty()) done(true) else { permDone = done; permRequest.launch(need.toTypedArray()) }
    }

    private fun showSiteSettings(host: String) {
        val cat = BrowserSettingsCatalogue(config.sitePerms.map {
            BrowserSetting(it.id, "enum", it.default, BrowserSitePolicy.VALUES, label = it.label, section = host)
        })
        overlay { close ->
            BrowserSettingsScreen(cat, value = { k -> config.sitePerms.first { it.id == k }.let { sitePerms.resolve(host, it) } },
                onSet = { k, v -> sitePerms.set(host, k, v.toString()); webView?.let { applySettings(it) } },
                extra = emptyList(), onExtra = {}, onClose = { close(); webView?.reload() })
        }
    }

    // ── #802 add-ons ─────────────────────────────────────────────────────

    /** Each add-on as a switch: what it is, what it may touch (in words), whether its fleet app is here. */
    private fun showAddons() {
        val cat = BrowserSettingsCatalogue(config.addons.all.map { a ->
            val may = a.permissions.joinToString("; ") { BrowserAddons.PERMISSIONS[it] ?: it }
            val needs = a.requiresPackage?.let { if (installed(it)) "" else " Needs its fleet app, not installed." }.orEmpty()
            BrowserSetting(a.id, "bool", a.defaultEnabled, label = a.label, section = "Add-ons", doc = "${a.doc} May: $may.$needs")
        })
        overlay { close ->
            BrowserSettingsScreen(cat,
                value = { id -> config.addons.enabled(id, browserSettings.stringSet("addons_enabled")) },
                onSet = { id, on ->
                    val cur = config.addons.all.filter { config.addons.enabled(it.id, browserSettings.stringSet("addons_enabled")) }.map { it.id }.toSet()
                    browserSettings.put("addons_enabled", if (on == true) cur + id else cur - id)
                },
                extra = emptyList(), onExtra = {}, onClose = close)
        }
    }

    /** #802 I9 one message to the assistant; its answer (or the confirmation it waits for) in a panel. */
    private fun showAgentChat() {
        val ask = BrowserAgentHost.ask ?: return toast("The assistant is not available in this app")
        val ctx = requireContext()
        val input = android.widget.EditText(ctx).apply { hint = "Ask about this page, or ask it to do something" }
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle("Ask the assistant")
            .setView(input)
            .setPositiveButton("Send") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) agentAnswer { ask(text) }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Run [work] (a model turn: network) off the main thread; show what it answers. */
    private fun agentAnswer(work: () -> String) {
        toast("Asking…")
        Thread { val out = runCatching(work).getOrElse { "error: ${it.javaClass.simpleName}" }; view?.post { if (isAdded) showTextPanel("Assistant", out) } }.start()
    }

    /** #802 I9 the consent sheet: the exact action and site; nothing runs until he picks. */
    private fun showAgentConfirm(callId: String, sentence: String) {
        val decide = BrowserAgentHost.decide ?: return
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Allow this action?")
            .setMessage(sentence)
            .setCancelable(false)
            .setPositiveButton("Allow") { _, _ -> agentAnswer { decide(callId, true) } }
            .setNegativeButton("Deny") { _, _ -> agentAnswer { decide(callId, false) } }
            .show()
    }

    /**
     * #802 I9 one tool on the live page. The agent loop has already applied [AgentPolicy] and his
     * confirmation; this only executes. No branch reads the profile store or the vault.
     */
    private fun runAgentTool(name: String, a: JSONObject, wv: WebView?, url: String, done: (JSONObject) -> Unit) {
        val ok = { JSONObject().put("ok", true).put("tool", name) }
        val needPage = { done(JSONObject().put("ok", false).put("error", "no page is open")) }
        val cap = config.addons["ai"]?.config?.optInt("page_text_cap_chars", 6000) ?: 6000
        when (name) {
            "read_page", "summarize_page" -> {
                wv ?: return needPage()
                BrowserPageActions.run(wv, BrowserPageActions.script(requireContext(), "page_text", cap)) { r -> done(r?.put("ok", true) ?: ok().put("ok", false)) }
            }
            "find_in_page" -> { wv ?: return needPage(); BrowserPageActions.find(wv, a.optString("q")) { n -> done(ok().put("matches", n)) } }
            "scrape" -> {
                wv ?: return needPage()
                val sc = config.addons["scraper"]?.config ?: JSONObject()
                val plan = ScrapeEngine.simple(a.optString("css"), a.optString("attr").ifBlank { null }, 1, null, 1)
                if (plan.columns.first().css.isBlank()) return done(ok().put("ok", false).put("error", "css is required"))
                runScrape(wv, plan, sc) { r -> done(r.put("ok", true)) }
            }
            "list_tabs" -> done(ok().put("tabs", org.json.JSONArray(prefs.all().map { JSONObject().put("url", it.url).put("title", it.title).put("pinned", it.pinned) })))
            "click" -> {
                wv ?: return needPage()
                BrowserPageActions.run(wv, BrowserPageActions.script(requireContext(), "agent_click").replace("__CSS__", JSONObject.quote(a.optString("css")))) { r -> done(r ?: ok().put("ok", false)) }
            }
            "fill_form" -> {
                wv ?: return needPage()
                val f = a.optJSONObject("fields") ?: JSONObject()
                BrowserPageActions.run(wv, BrowserPageActions.script(requireContext(), "agent_fill").replace("__FIELDS__", f.toString())) { r -> done(r ?: ok().put("ok", false)) }
            }
            "navigate" -> { wv ?: return needPage(); wv.loadUrl(a.optString("url")); done(ok().put("url", a.optString("url"))) }
            "open_tab" -> { openEntryUrl(a.optString("url")); done(ok().put("url", a.optString("url"))) }
            "close_tab" -> { val closed = prefs.remove(a.optString("url")); BrowserBus.post(BrowserBus.TABS); done(ok().put("ok", closed)) }
            "pin_tab" -> { prefs.setPinned(a.optString("url"), a.optBoolean("on", true)); BrowserBus.post(BrowserBus.TABS); done(ok()) }
            "bookmark_page" -> {
                if (!url.startsWith("http")) return needPage()
                BrowserBookmarks(requireContext()).add(url, wv?.title.orEmpty(), a.optString("folder"))
                done(ok().put("url", url))
            }
            else -> done(JSONObject().put("ok", false).put("error", "no tool named $name"))
        }
    }

    /** #802 I8 one query, one of cloud-search's engines (the Search add-on): its results open as a new tab. */
    private fun showSearchWith() {
        val engines = config.addons.searchEngines()
        if (engines.isEmpty()) return toast("The Search add-on declares no engines")
        val ctx = requireContext()
        val input = suggestField(ctx, "")
        input.hint = "Search for…"
        var pick = 0
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle("Search with")
            .setSingleChoiceItems(engines.map { it.label }.toTypedArray(), 0) { _, i -> pick = i }
            .setView(input)
            .setPositiveButton("Search") { _, _ ->
                val q = input.text.toString().trim()
                if (q.isNotEmpty()) openEntryUrl(BrowserSearch.searchUrl(q, engines[pick]))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openEntryUrl(url: String) {
        prefs.add(url, url)
        prefs.setActive(url)
        showDetail(url)
    }

    /** The result line the scraper sheet shows; Compose state so a finished run redraws it. */
    private val scrapeResult = androidx.compose.runtime.mutableStateOf("")

    private fun showScraper() {
        val sc = config.addons["scraper"]?.config ?: JSONObject()
        overlay { close ->
            BrowserScraperScreen(scrapeResult.value,
                onPick = { webView?.let { BrowserPageActions.run(it, BrowserPageActions.script(requireContext(), "pick")) { } }; toast("Tap an element on the page, then Use picked") },
                onUsePicked = { use -> webView?.evaluateJavascript("window.__cbPick || ''") { v -> runCatching { org.json.JSONTokener(v).nextValue() as? String }.getOrNull()?.takeIf { it.isNotBlank() }?.let(use) } },
                onRun = { css, attr, pages, next ->
                    val wv = webView ?: return@BrowserScraperScreen
                    scrapeResult.value = "Running…"
                    runScrape(wv, ScrapeEngine.simple(css, attr, pages, next, sc.optInt("max_pages", 5)), sc) { r ->
                        val rows = r.optJSONArray("rows")
                        scrapeResult.value = "${r.optInt("count")} row(s) from ${r.optInt("pages")} page(s)\n" +
                            (0 until minOf(10, rows?.length() ?: 0)).joinToString("\n") { rows!!.optJSONObject(it).toString() }
                    }
                },
                onRemote = { css ->
                    val target = webView?.url.orEmpty()
                    scrapeResult.value = "Asking the server…"
                    Thread { val r = ScrapeRemote.crawl(sc.optJSONObject("remote"), target, css); webView?.post { scrapeResult.value = r.toString(2) } }.start()
                },
                onExport = { scrapeResult.value = exportScrape(sc).toString(2) },
                onClose = close)
        }
    }

    /**
     * Run [plan] on the live page, then follow its next link page by page in a HIDDEN WebView
     * (the one on screen stays where he is), up to the plan's cap; answer the merged table.
     */
    private fun runScrape(wv: WebView, plan: ScrapeEngine.Plan, sc: JSONObject, done: (JSONObject) -> Unit) {
        val js = BrowserPageActions.script(requireContext(), "scrape").replace("__PLAN__", plan.toJson().toString())
        val pages = ArrayList<List<Map<String, String>>>()
        val settle = sc.optLong("settle_ms", 800)
        fun finish(hidden: WebView?) {
            hidden?.destroy()
            val rows = ScrapeEngine.merge(pages, sc.optInt("max_rows", 2000))
            val out = JSONObject().put("url", wv.url).put("columns", org.json.JSONArray(plan.columns.map { it.name }))
                .put("pages", pages.size).put("count", rows.size).put("rows", ScrapeEngine.json(rows))
            ScrapeEngine.last = out
            done(JSONObject(out.toString()))
        }
        fun onPage(r: JSONObject?, n: Int, hidden: WebView?) {
            pages.add(ScrapeEngine.rows(r, plan))
            val next = r?.optString("next")?.takeIf { it.startsWith("http") }
            if (next == null || n >= plan.maxPages) return finish(hidden)
            val h = hidden ?: WebView(requireContext()).apply { settings.javaScriptEnabled = true; settings.domStorageEnabled = true }
            var waiting = true
            h.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    if (!waiting) return
                    waiting = false
                    h.postDelayed({ BrowserPageActions.run(h, js) { onPage(it, n + 1, h) } }, settle)
                }
            }
            h.loadUrl(next)
        }
        BrowserPageActions.run(wv, js) { onPage(it, 1, null) }
    }

    /** The last result to Downloads as CSV (MediaStore, Android 10+). */
    private fun exportScrape(sc: JSONObject): JSONObject {
        val last = ScrapeEngine.last ?: return JSONObject().put("ok", false).put("error", "nothing scraped yet")
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q)
            return JSONObject().put("ok", false).put("error", "export needs Android 10+")
        val cols = (0 until last.getJSONArray("columns").length()).map { last.getJSONArray("columns").getString(it) }
        val rowsArr = last.getJSONArray("rows")
        val rows = (0 until rowsArr.length()).map { i -> rowsArr.getJSONObject(i).let { o -> cols.associateWith { o.optString(it) } } }
        val name = "scrape-${System.currentTimeMillis() / 1000}.csv"
        val dir = listOf(android.os.Environment.DIRECTORY_DOWNLOADS, BrowserBookmarkOps.normFolder(browserSettings.string("download_dir").orEmpty()))
            .filter { it.isNotEmpty() }.joinToString("/")
        val cv = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/csv")
            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, dir)
        }
        val cr = requireContext().contentResolver
        val uri = cr.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
            ?: return JSONObject().put("ok", false).put("error", "MediaStore refused the file")
        cr.openOutputStream(uri)!!.use { it.write(ScrapeEngine.csv(rows, cols).toByteArray()) }
        return JSONObject().put("ok", true).put("file", "$dir/$name").put("rows", rows.size)
    }

    /** #802 Profile: what is held (masked: counts, initials, last4s), Import file, Clear. */
    private fun showProfile() {
        val m = runCatching { BrowserProfileStore(requireContext()).load().masked() }.getOrNull()
        val summary = if (m == null) "The profile store could not be opened." else
            "Identity: ${if (m.getJSONObject("identity").optBoolean("present")) m.getJSONObject("identity").optString("initials") else "none"}\n" +
            "Addresses: ${m.optInt("addresses")}\nCards (metadata only, never numbers): ${m.optInt("cards_meta")}\n\n" +
            "The Account (SuperApp ▸ Account ▸ server → runtime) brings the vault profile; Import adds a CSV, Firefox or Bitwarden export."
        overlay { close ->
            BrowserTextPanel("Profile", summary,
                listOf(SheetRow("import", "Import file…"), SheetRow("clear", "Clear the profile on this phone")),
                onPick = { r ->
                    close()
                    when (r) {
                        "import" -> importProfile.launch(arrayOf("text/*", "application/json", "application/octet-stream"))
                        "clear" -> { BrowserProfileStore(requireContext()).clear(); toast("Profile cleared") }
                    }
                }, onClose = close)
        }
    }

    private fun showClearData() {
        overlay { close ->
            BrowserClearScreen(config.clearData,
                note = "Cookies and site storage are shared by every tab: clearing them signs you out everywhere.",
                onClear = { boxes ->
                    BrowserClearData.clear(requireContext(), boxes, null) { r ->
                        toast(if (r.optBoolean("ok")) "Cleared" else r.optString("error"))
                    }
                    close()
                }, onClose = close)
        }
    }

    private fun applyViewMode(wv: WebView) {
        val s = wv.settings
        if (desktopMode) {
            config.userAgents["desktop"]?.let { s.userAgentString = it }
            s.useWideViewPort = true
            s.loadWithOverviewMode = true
            wv.setInitialScale(1)
        } else {
            config.userAgents["mobile"]?.let { s.userAgentString = it }
            s.useWideViewPort = false
            s.loadWithOverviewMode = false
            wv.setInitialScale(0)
        }
    }

    // ── #802 the declared menu, its actions, and the live-page host ───────

    private fun palette(): KitPalette {
        val c = config.palette
        fun r(k: String, d: Int) = c[k] ?: d
        return KitPalette.fromArgb(
            r("surface", 0xFF1A0033.toInt()), r("surface_selected", 0xFF2D0A55.toInt()),
            r("text_primary", Color.WHITE), r("text_secondary", 0x99FFFFFF.toInt()),
            r("accent", 0xFF7C3AED.toInt()), r("hairline", 0x33FFFFFF), r("tile_ink", Color.BLACK),
        )
    }

    /** Draw [content] over the page; it is handed its own close. Back closes the top one. */
    private fun overlay(bottom: Boolean = false, content: @Composable (close: () -> Unit) -> Unit) {
        val ctx = context ?: return
        lateinit var v: View
        val close = { rootContainer.removeView(v); overlays.remove(v); Unit }
        v = ctx.kitComposeView(palette()) { content(close) }
        v.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            if (bottom) FrameLayout.LayoutParams.WRAP_CONTENT else FrameLayout.LayoutParams.MATCH_PARENT,
            if (bottom) android.view.Gravity.BOTTOM else android.view.Gravity.NO_GRAVITY,
        )
        overlays.add(v)
        rootContainer.addView(v)
    }

    private fun closeOverlays() {
        overlays.toList().forEach { rootContainer.removeView(it) }
        overlays.clear()
    }

    /** What the menu's `requires` and `checked` are resolved against, right now. */
    private fun facts(): Map<String, Boolean> {
        val wv = webView
        val page = mode is Mode.DETAIL && wv != null
        val active = prefs.activeUrl()
        return mapOf(
            "page" to page,
            "can_go_back" to (page && wv!!.canGoBack()),
            "can_go_forward" to (page && wv!!.canGoForward()),
            "reader_on" to readerOn,
            "desktop_mode" to desktopMode,
            "pinned" to (prefs.all().firstOrNull { it.url == active }?.pinned == true),
            "bookmarked" to (page && bookmarks.has(wv!!.url ?: "")),
            "private_tab" to (currentTab()?.isPrivate == true),
            "profile_present" to (runCatching { !BrowserProfileStore(requireContext()).load().isEmpty }.getOrDefault(false)),
            "shield_js" to (page && shieldDenied(BrowserSitePolicy.hostOf(wv!!.url), "javascript")),
            "shield_images" to (page && shieldDenied(BrowserSitePolicy.hostOf(wv!!.url), "images")),
            "text_tools" to BrowserPageActions.textTools(requireContext()).isServingAppInstalled(),
            "can_be_default" to BrowserPageActions.canRequestDefault(requireContext()),
        )
    }

    /** Every fact: the page's, plus each add-on's `addon:<id>` (enabled, and its fleet app installed). */
    private fun allFacts(): Map<String, Boolean> = facts() + config.addons.facts(browserSettings.stringSet("addons_enabled")) { installed(it) }

    private fun installed(pkg: String) = runCatching { requireContext().packageManager.getPackageInfo(pkg, 0) }.isSuccess

    private fun showMenuSheet() {
        val grouped = config.menu.grouped(allFacts())
        val icons = grouped.firstOrNull { it.first.id == ICON_SECTION }?.second.orEmpty().map { it.sheet() }
        val sections = grouped.filter { it.first.id != ICON_SECTION }
            .map { (s, rows) -> s.label to rows.map { it.sheet() } }
        overlay { close ->
            BrowserSheet(icons, sections, onPick = { id -> close(); runAction(id) }, onDismiss = close)
        }
    }

    /** Per-tab actions. Long-press is spoken for by the drag, so: ⋮. */
    private fun showTabMenu(tab: BrowserTab, @Suppress("UNUSED_PARAMETER") anchor: View) {
        val rows = listOfNotNull(
            SheetRow("pin", if (tab.pinned) "Unpin tab" else "Pin tab"),
            SheetRow("group", if (tab.group.isBlank()) "Add to group…" else "Move to group…"),
            if (tab.group.isNotBlank()) SheetRow("ungroup", "Remove from “${tab.group}”") else null,
            if (tab.pinned) null else SheetRow("close", "Close tab"),
        )
        overlay { close ->
            BrowserSheet(emptyList(), listOf(tab.title.ifBlank { tab.url } to rows), onPick = { id ->
                close()
                when (id) {
                    "pin" -> { prefs.setPinned(tab.url, !tab.pinned); showGrid() }
                    "group" -> promptForGroup(tab)
                    "ungroup" -> { prefs.setGroup(tab.url, ""); showGrid() }
                    "close" -> closeTab(tab)
                }
            }, onDismiss = close)
        }
    }

    private fun showSettings() {
        val extra = config.menu.grouped(facts()).firstOrNull { it.first.id == SETTINGS_SECTION }
            ?.second.orEmpty().filter { it.item.id != "settings" }.map { it.sheet() }
        overlay { close ->
            BrowserSettingsScreen(
                config.settings, value = { browserSettings.value(it) },
                onSet = { k, v -> browserSettings.put(k, v) },
                extra = extra, onExtra = { id -> close(); runAction(id) }, onClose = close,
            )
        }
    }

    private fun showFindBar() {
        overlay(bottom = true) { close ->
            BrowserFindBar(findCount.value,
                onQuery = { q -> webView?.let { BrowserPageActions.find(it, q) { n -> findCount.value = n } } },
                onNext = { forward -> webView?.findNext(forward) },
                onClose = { webView?.clearMatches(); close() })
        }
    }

    private fun showTextPanel(title: String, text: String) =
        overlay { close -> BrowserTextPanel(title, text, onClose = close) }

    private fun showGroups() {
        val groups = prefs.all().filter { it.group.isNotBlank() }.groupBy { it.group }
        val rows = groups.map { (g, tabs) -> SheetRow(g, g, why = "${tabs.size} tab(s)") }
        overlay { close ->
            BrowserTextPanel("Tab groups", if (rows.isEmpty()) "No groups yet: ⋮ on a tab card → Add to group." else "",
                rows, onPick = { g ->
                    close()
                    groups.keys.forEach { prefs.setGroupCollapsed(it, it != g) }
                    showGrid()
                }, onClose = close)
        }
    }

    private fun showZoom() {
        overlay { close ->
            BrowserSettingsScreen(
                BrowserSettingsCatalogue(config.settings.settings.filter { it.key == "text_zoom" }),
                value = { browserSettings.value(it) }, onSet = { k, v -> browserSettings.put(k, v) },
                extra = emptyList(), onExtra = {}, onClose = close,
            )
        }
    }

    private fun runAction(id: String) {
        runAction(id, emptyMap()) { r -> r.optString("toast").takeIf { it.isNotBlank() }?.let { toast(it) } }
    }

    /**
     * THE ONE DISPATCH of every declared menu action — the sheet, the bar buttons and
     * /api/browser/menu/act all land here. test-browser-menu-wired.sh holds that every
     * declared action id has its branch.
     */
    private fun runAction(id: String, args: Map<String, String>, done: (JSONObject) -> Unit) {
        val wv = webView
        // The tab is keyed by the URL it was opened with; the page may have navigated since.
        val tabUrl = (mode as? Mode.DETAIL)?.url ?: prefs.activeUrl().orEmpty()
        val url = wv?.url?.takeIf { it.startsWith("http") } ?: tabUrl
        val ok = { JSONObject().put("ok", true).put("id", id) }
        val needPage = { done(JSONObject().put("ok", false).put("error", "$id: no page is open")) }
        when (id) {
            "_facts" -> done(ok().put("facts", JSONObject(allFacts())))
            "back" -> if (wv?.canGoBack() == true) { wv.goBack(); done(ok()) } else done(ok().put("ok", false).put("why", "no earlier page"))
            "forward" -> if (wv?.canGoForward() == true) { wv.goForward(); done(ok()) } else done(ok().put("ok", false).put("why", "no later page"))
            "reload" -> { wv?.reload() ?: return needPage(); done(ok()) }
            "new_tab" -> { promptForUrl(); done(ok()) }
            "new_private_tab" -> { promptForUrl(private_ = true); done(ok().put("toast", "Private tabs keep no history; cookies are shared with normal tabs")) }
            "site_settings" -> { showSiteSettings(BrowserSitePolicy.hostOf(url).ifEmpty { return needPage() }); done(ok()) }
            "clear_data" -> { showClearData(); done(ok()) }
            "profile" -> { showProfile(); done(ok()) }
            "addons_manage" -> { showAddons(); done(ok()) }
            "scraper" -> { showScraper(); done(ok()) }
            "search_with" -> { showSearchWith(); done(ok()) }
            "ai_chat" -> { showAgentChat(); done(ok()) }
            "vault_fill", "vault_request_fill" -> { wv ?: return needPage(); VaultAutofill.requestFill(wv) { r -> done(r.put("ok", true)) } }
            "vault_open" -> done(VaultAutofill.open(requireContext(), config.addons["vault"]?.requiresPackage)?.let { ok().put("ok", false).put("error", it) } ?: ok())
            "vault_set_service" -> done(VaultAutofill.setAsService(requireContext(), config.addons["vault"]?.requiresPackage)?.let { ok().put("why", it) } ?: ok())
            "agent_confirm" -> { showAgentConfirm(args["call"].orEmpty(), args["sentence"].orEmpty()); done(ok().put("shown", true)) }
            "agent_tool" -> runAgentTool(args["name"].orEmpty(), AgentLoop.args(args["args"]), wv, url, done)
            "scrape_run" -> {
                wv ?: return needPage()
                val sc = config.addons["scraper"]?.config ?: JSONObject()
                val plan = ScrapeEngine.simple(args["css"].orEmpty(), args["attr"], args["pages"]?.toIntOrNull() ?: 1,
                    args["next"], sc.optInt("max_pages", 5))
                if (plan.columns.first().css.isBlank()) return done(ok().put("ok", false).put("error", "css= is required"))
                runScrape(wv, plan, sc) { r -> done(r.put("ok", true)) }
            }
            "fill_profile", "fill_dry" -> {
                wv ?: return needPage()
                val dry = id == "fill_dry"
                BrowserPageActions.run(wv, BrowserPageActions.script(requireContext(), "autofill_scan")) { r ->
                    val plan = BrowserAutofillMatch.plan(r?.optJSONArray("fields") ?: org.json.JSONArray())
                    val fields = org.json.JSONArray(plan.map { (i, k) -> JSONObject().put("i", i).put("fills", k) })
                    val p = BrowserProfileStore(requireContext()).load()
                    when {
                        dry -> done(ok().put("fields", fields).put("profile_present", !p.isEmpty))   // kinds only, never values
                        p.isEmpty -> done(ok().put("ok", false).put("error", "the profile is empty: import one, or apply the Account"))
                        plan.isEmpty() -> done(ok().put("ok", false).put("error", "no fillable field on this page"))
                        p.addresses.size > 1 && args["address"] == null -> overlay { close ->
                            BrowserTextPanel("Fill with which address?", "",
                                p.addresses.mapIndexed { n, a -> SheetRow(n.toString(), a.label.ifBlank { a.city }, why = a.city) },
                                onPick = { n -> close(); runAction(id, args + ("address" to n), done) },
                                onClose = { close(); done(ok().put("ok", false).put("error", "cancelled")) })
                        }
                        else -> {
                            val ai = args["address"]?.toIntOrNull() ?: 0
                            val fill = JSONObject()
                            plan.forEach { (i, k) -> BrowserAutofillMatch.value(k, p, ai).takeIf { it.isNotBlank() }?.let { fill.put(i.toString(), it) } }
                            val js = BrowserPageActions.script(requireContext(), "autofill_fill").replace("__FILL__", fill.toString())
                            BrowserPageActions.run(wv, js) { f -> done(ok().put("filled", f?.optInt("filled") ?: 0)) }
                        }
                    }
                }
            }
            "shield_js", "shield_images" -> {
                val host = BrowserSitePolicy.hostOf(url).ifEmpty { return needPage() }
                val perm = if (id == "shield_js") "javascript" else "images"
                val next = if (shieldDenied(host, perm)) BrowserSitePolicy.ALLOW else BrowserSitePolicy.DENY
                sitePerms.set(host, perm, next)
                wv?.let { applySettings(it); it.reload() }
                done(ok().put(perm, next))
            }
            "close_tab" -> {
                val tab = prefs.all().firstOrNull { it.url == tabUrl } ?: return needPage()
                closeTab(tab); done(ok().put("closed", prefs.all().none { it.url == tabUrl }))
            }
            "pin_tab" -> { prefs.setPinned(tabUrl, !facts().getValue("pinned")); done(ok().put("pinned", facts().getValue("pinned"))) }
            "tab_groups" -> { showGroups(); done(ok()) }
            "find" -> {
                wv ?: return needPage()
                if (overlays.isEmpty()) showFindBar()
                BrowserPageActions.find(wv, args["q"].orEmpty()) { n -> findCount.value = n; done(ok().put("matches", n)) }
            }
            "share" -> {
                startActivity(android.content.Intent.createChooser(
                    android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"; putExtra(android.content.Intent.EXTRA_TEXT, url)
                    }, "Share URL"))
                done(ok())
            }
            "copy_url" -> {
                val clip = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                clip?.setPrimaryClip(android.content.ClipData.newPlainText("url", url))
                done(ok().put("toast", "URL copied"))
            }
            "open_external" -> { openInExternalBrowser(url); done(ok()) }
            "print" -> { BrowserPageActions.print(wv ?: return needPage()); done(ok()) }
            "desktop" -> { browserSettings.put("desktop_mode", !desktopMode); done(ok().put("desktop_mode", desktopMode)) }
            "reader" -> {
                wv ?: return needPage()
                if (readerOn) { readerOn = false; wv.loadUrl(url); done(ok().put("reader", false)) }
                else BrowserPageActions.reader(wv, config.readerCss) { r ->
                    readerOn = r != null
                    readerPending = r != null
                    done(if (r == null) ok().put("ok", false).put("error", "nothing to extract")
                        else ok().put("reader", true).put("title", r.optString("title")).put("length", r.optInt("length")))
                }
            }
            "reader_extract" -> {
                wv ?: return needPage()
                BrowserPageActions.run(wv, BrowserPageActions.script(requireContext(), "reader")) { r ->
                    done(if (r == null) ok().put("ok", false) else ok().put("title", r.optString("title")).put("length", r.optInt("length")))
                }
            }
            "page_text" -> {
                wv ?: return needPage()
                val n = (args["n"]?.toIntOrNull() ?: 2000).coerceIn(1, 200_000)
                BrowserPageActions.run(wv, BrowserPageActions.script(requireContext(), "page_text", n)) { r ->
                    done(r?.put("ok", true) ?: ok().put("ok", false))
                }
            }
            "zoom" -> {
                val v = args["value"]
                if (v == null) { showZoom(); done(ok()) }
                else done(browserSettings.set("text_zoom", v)?.let { ok().put("ok", false).put("error", it) }
                    ?: ok().put("text_zoom", browserSettings.int("text_zoom")))
            }
            "translate" -> {
                wv ?: return needPage()
                BrowserPageActions.run(wv, BrowserPageActions.script(requireContext(), "page_text", 4000)) { r ->
                    val text = r?.optString("text").orEmpty()
                    BrowserPageActions.translate(wv, text) { out, err ->
                        if (out != null) showTextPanel("Translation", out)
                        done(if (out != null) ok().put("length", out.length) else ok().put("ok", false).put("error", err))
                    }
                }
            }
            "history" -> { showHistory(); done(ok()) }
            "bookmarks" -> { showBookmarks(); done(ok()) }
            "downloads" -> { showDownloads(); done(ok()) }
            "bookmark" -> {
                wv ?: return needPage()
                val on = !bookmarks.has(url)
                if (on) bookmarks.add(url, wv.title.orEmpty(), args["folder"].orEmpty()) else bookmarks.remove(url)
                done(ok().put("bookmarked", on).put("toast", if (on) "Bookmarked" else "Bookmark removed"))
            }
            "download_page" -> {
                wv ?: return needPage()
                val d = download(url, wv.settings.userAgentString, null, null)
                done(ok().put("id", d.id).put("file", d.file).put("toast", "Downloading ${d.file}"))
            }
            "add_to_home" -> {
                val asked = addToHome(url, wv?.title.orEmpty())
                done(ok().put("requested", asked).put("toast", if (asked) "" else "This launcher cannot pin shortcuts"))
            }
            "settings" -> { showSettings(); done(ok()) }
            "default_browser" -> {
                val i = BrowserPageActions.defaultBrowserIntent(requireContext())
                if (i != null) roleRequest.launch(i)
                done(ok().put("requested", i != null))
            }
            else -> done(JSONObject().put("ok", false).put("error", "$id: not a menu action"))
        }
    }

    /** #802 the live page, for /api/browser/menu/act and the page routes. */
    private val pageHost = object : BrowserPageHost {
        override fun facts(): Map<String, Boolean> = this@BrowserHostFragment.facts()
        override fun act(id: String, args: Map<String, String>, done: (JSONObject) -> Unit) = runAction(id, args, done)
    }

    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            val wv = webView
            when {
                overlays.isNotEmpty() -> { rootContainer.removeView(overlays.last()); overlays.removeAt(overlays.lastIndex) }
                mode is Mode.DETAIL && wv?.canGoBack() == true -> wv.goBack()
                mode !is Mode.GRID -> showGrid()
                else -> { isEnabled = false; requireActivity().onBackPressedDispatcher.onBackPressed(); isEnabled = true }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)
    }

    private fun barButton(ctx: Context, label: String, onTap: () -> Unit): View = TextView(ctx).apply {
        text = label
        setTextColor(Color.WHITE)
        typeface = Typeface.DEFAULT_BOLD
        setTextAppearance(android.R.style.TextAppearance_Material_Title)
        val pad = dp(8); setPadding(pad, 0, pad, 0)
        setOnClickListener { onTap() }
    }

    private fun openInExternalBrowser(url: String) {
        startActivity(
            android.content.Intent.createChooser(
                android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(url)),
                "Open with"))
    }

    private fun hideKeyboard(v: View) {
        val imm = v.context.getSystemService(Context.INPUT_METHOD_SERVICE)
            as? android.view.inputmethod.InputMethodManager
        imm?.hideSoftInputFromWindow(v.windowToken, 0)
    }

    private fun toast(msg: String) {
        android.widget.Toast.makeText(requireContext(), msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun teardownWebView() {
        webView?.let {
            runCatching { it.stopLoading() }
            runCatching { it.destroy() }
        }
        webView = null
    }

    override fun onDestroyView() {
        teardownWebView()
        super.onDestroyView()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "Browser"
        /** The declared menu section drawn as the icon row, and the one the Settings page lists. */
        private const val ICON_SECTION = "icons"
        private const val SETTINGS_SECTION = "settings"
        private const val ARG_OPEN_URL = "open_url"
        private const val ARG_CONFIG_B64 = "config_b64"

        /**
         * @param openUrl  go straight to this page instead of the grid.
         * @param configB64 the consuming app's base64 ui.browser block.
         *        Omitted means [BrowserConfig.DEFAULT] — no pinned tabs.
         */
        fun newInstance(openUrl: String? = null, configB64: String? = null) =
            BrowserHostFragment().apply {
                arguments = Bundle().apply {
                    if (!openUrl.isNullOrBlank()) putString(ARG_OPEN_URL, openUrl)
                    if (!configB64.isNullOrBlank()) putString(ARG_CONFIG_B64, configB64)
                }
            }
    }
}
