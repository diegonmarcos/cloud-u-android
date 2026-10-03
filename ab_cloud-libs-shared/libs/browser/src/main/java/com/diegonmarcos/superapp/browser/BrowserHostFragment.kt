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
    private fun promptForUrl() {
        val ctx = requireContext()
        val input = suggestField(ctx, "")
        input.hint = "Search ${engine().label}, or type a URL"
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle("New tab")
            .setView(input)
            .setPositiveButton("Open") { _, _ -> openEntry(input.text.toString()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Resolve what was typed to a destination, open it as a new tab.
     *  Nothing typed opens the `homepage` setting, when he has set one. */
    private fun openEntry(raw: String) {
        val typed = raw.ifBlank { browserSettings.string("homepage").orEmpty() }
        val url = BrowserSearch.resolve(typed, engine())
        if (url.isBlank()) return
        prefs.add(url, url)
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
            applySettings(this)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, startedUrl: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, startedUrl, favicon)
                    // A real navigation leaves reader view; the reader's own render does not.
                    if (readerPending) readerPending = false else readerOn = false
                }

                override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                    super.onPageFinished(view, finishedUrl)
                    val u = finishedUrl ?: return
                    if (readerOn) return
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
    private fun applySettings(wv: WebView) {
        val s = wv.settings
        browserSettings.bool("javascript")?.let { s.javaScriptEnabled = it }
        browserSettings.bool("load_images")?.let { s.loadsImagesAutomatically = it }
        browserSettings.int("text_zoom")?.let { s.textZoom = it }
        browserSettings.bool("block_third_party_cookies")?.let {
            android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(wv, !it)
        }
        applyViewMode(wv)
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
            "text_tools" to BrowserPageActions.textTools(requireContext()).isServingAppInstalled(),
            "can_be_default" to BrowserPageActions.canRequestDefault(requireContext()),
        )
    }

    private fun showMenuSheet() {
        val grouped = config.menu.grouped(facts())
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
            "_facts" -> done(ok().put("facts", JSONObject(facts())))
            "back" -> if (wv?.canGoBack() == true) { wv.goBack(); done(ok()) } else done(ok().put("ok", false).put("why", "no earlier page"))
            "forward" -> if (wv?.canGoForward() == true) { wv.goForward(); done(ok()) } else done(ok().put("ok", false).put("why", "no later page"))
            "reload" -> { wv?.reload() ?: return needPage(); done(ok()) }
            "new_tab" -> { promptForUrl(); done(ok()) }
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
