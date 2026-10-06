package com.diegonmarcos.superapp.browser


import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
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
 * [toggleAllCollapsed] snaps a re-tapped Tabs view back to GRID. #825 it used to implement
 * core's Collapsible / Suppress*Swipe markers for the SuperApp's pager, which no longer hosts
 * this fragment (only cloud-browser does, and nothing there queries them), so the markers left
 * libs:core for the SuperApp, their only remaining reader.
 */
class BrowserHostFragment : Fragment() {


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
    /** The ARG_OPEN_URL is consumed once: a rotation must not reopen it over where the user went. */
    private var openUrlHandled = false

    /** #802 reader view is showing an extraction of the current page. */
    private var readerOn = false
    /** The next page start is the reader's own render, not a navigation away from it. */
    private var readerPending = false
    /** #802 the Compose surfaces drawn over the page; back closes the top one first. */
    private val overlays = ArrayList<View>()

    /** #868 the host shell's island opens the host's own pages: the Search add-on's page, the settings, none. */
    fun openSearch() = showSearchPage()
    fun openSettings() = showSettings()
    fun dismissOverlays() = closeOverlays()

    /** #886 Tabs is a destination of the nav island: the tab switcher, over nothing (the page is parked). */
    fun openTabs() { closeOverlays(); if (mode !is Mode.GRID) showGrid() else onOverlaysClosed?.invoke() }
    /** #886 Browser from the island: back to the tab that was open (or a new tab when there is none). */
    fun openBrowser() {
        closeOverlays()
        if (mode is Mode.GRID) {
            val t = prefs.activeTab() ?: prefs.all().firstOrNull()
            if (t != null) { prefs.setActiveId(t.key); showDetail(t) } else promptForUrl()
        } else onOverlaysClosed?.invoke()
    }
    /** True while the tab switcher is what is on screen (no sheet or page over it): the island's Tabs pill. */
    val isTabsOpen: Boolean get() = mode is Mode.GRID && overlays.isEmpty()

    /** #868 the host shell's nav island follows the screen: called whenever the last overlay (the
     *  Search page, Settings, a sheet) is gone, so the island can put its pill back on the page. */
    var onOverlaysClosed: (() -> Unit)? = null
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
        /** #886 the tab is held by its stable id: its url changes as the tab navigates. */
        data class DETAIL(val id: String) : Mode()
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
        // #886 a fragment that outlives its view (rotation) comes back on the tab it was showing.
        val kept = (mode as? Mode.DETAIL)?.let { prefs.byId(it.id) }
        val restore = prefs.activeTab()?.takeIf { browserSettings.bool("restore_tabs_on_start") == true }
        if (!openUrl.isNullOrBlank() && !openUrlHandled) {
            openUrlHandled = true
            openUrlInTab(openUrl)
        } else if (kept != null) {
            showDetail(kept)
        } else if (restore != null) {
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

    override fun onStop() {
        saveTabState()
        super.onStop()
    }

    override fun onPause() {
        saveTabState()   // #886 the app going away is the last chance to keep where the tab was
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

    fun toggleAllCollapsed(): Boolean {
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
                onOpen = { tab -> prefs.setActiveId(tab.key); showDetail(tab) },
                onClose = { tab -> closeTab(tab) },
                onMenu = { tab, anchor -> showTabMenu(tab, anchor) },
                onToggleGroup = { g ->
                    prefs.setGroupCollapsed(g, g !in prefs.collapsedGroups()); showGrid()
                },
                onReorder = { urls -> prefs.reorder(urls) },
                onDropOnTab = { drag, target ->
                    prefs.dropOnTab(drag, target)?.let { c -> grouped(c); showGrid() }
                },
                onDropOnGroup = { drag, g ->
                    prefs.dropOnGroup(drag, g)?.let { c -> grouped(c); showGrid() }
                },
                onEditGroup = { g -> showGroupEditor(g) },
                groupColors = { prefs.groupColors() },
            )
            grid.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            grid.submit(BrowserGridRows.build(tabs, prefs.collapsedGroups()))
            column.addView(grid)
        }

        overlays.clear()
        onOverlaysClosed?.invoke()
        rootContainer.removeAllViews()
        rootContainer.addView(column)
    }

    /**
     * Close, honouring the pin. [BrowserTabPrefs.remove] is what refuses;
     * this only reports the refusal, so the constraint cannot drift apart
     * from the button.
     */
    private fun closeTab(tab: BrowserTab) {
        if (!prefs.removeById(tab.key)) {
            toast("“${tab.title.ifBlank { tab.url }}” is pinned — unpin it first")
            return
        }
        BrowserWebState.delete(requireContext(), tab.key)
        if (tab.previewPath.isNotBlank()) runCatching { File(tab.previewPath).delete() }
        val left = prefs.all()
        if (tab.isPrivate && left.none { it.isPrivate }) {
            BrowserClearData.endPrivateSession(privateOrigins, normalTabsOpen = left.isNotEmpty())
            privateOrigins.clear()
        }
        showGrid()
    }

    private fun grouped(c: BrowserTabGroups.Change) =
        toast(if (c.created) "Grouped as “${c.group}” — ✎ on its header renames it" else "Added to “${c.group}”")

    /** #886 a group's name and colour. */
    private fun showGroupEditor(group: String) {
        val others = prefs.all().map { it.group }.filter { it.isNotBlank() && it != group }.toSet()
        overlay { close ->
            BrowserGroupEditor(group, BrowserTabGroups.colorOf(prefs.groupColors(), group), others,
                onSave = { name, color -> close(); prefs.renameGroup(group, name, color); showGrid() },
                onUngroup = { close(); prefs.ungroup(group); showGrid() },
                onClose = close)
        }
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
                prefs.setGroupById(tab.key, input.text.toString()); showGrid()
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

    /**
     * New tab, as a Compose screen: the field and the same two-section suggestions as the address bar
     * (#886: the old AutoCompleteTextView-in-a-dialog dropdown overlapped its own field and the
     * keyboard). Accepts a URL or a search — same rule as the address bar. [joinTabKey]: the strip's +,
     * the new tab joins (or starts) that tab's group.
     */
    private fun promptForUrl(private_: Boolean = browserSettings.bool("private_by_default") == true, joinTabKey: String? = null) {
        val st = SuggestState().also { it.engineLabel = engine().label; it.visible = true }
        overlay { close ->
            BrowserOpenScreen(
                title = if (private_) "New private tab" else if (joinTabKey != null) "New tab in this group" else "New tab",
                hint = "Search ${engine().label}, or type a URL",
                state = st,
                onQuery = { q -> refreshSuggestions(st, q, private_) },
                onSubmit = { q -> close(); openEntry(q, private_, joinTabKey) },
                onPick = { sug -> close(); openEntry(sug.url, private_, joinTabKey) },
                onClose = close,
            )
        }
    }

    /** Resolve what was typed to a destination, open it as a new tab.
     *  Nothing typed opens the `homepage` setting, when he has set one. */
    private fun openEntry(raw: String, private_: Boolean = false, joinTabKey: String? = null) {
        val typed = raw.ifBlank { browserSettings.string("homepage").orEmpty() }
        val url = BrowserSearch.resolve(typed, engine())
        if (url.isBlank()) return
        if (joinTabKey == null) return openUrlInTab(url, private_)
        // #886 the strip's +: always a NEW tab, placed in the group of the tab it was pressed on.
        val tab = prefs.addNew(url, url, isPrivate = private_)
        prefs.startOrJoinGroup(joinTabKey, tab.key)
        prefs.setActiveId(tab.key)
        showDetail(prefs.byId(tab.key) ?: tab)
    }

    /** #886 open [url] in the tab that already shows it, else in a new one, and make it the active tab. */
    private fun openUrlInTab(url: String, private_: Boolean = false) {
        val tab = prefs.add(url, url, isPrivate = private_)
        prefs.setActiveId(tab.key)
        showDetail(tab)
    }

    // ── suggestions (#886: two labelled sections, laid out IN the page area, never a PopupWindow) ──

    /** The query the remote suggestions in [remoteRows] answer. */
    private var remoteFor = ""
    private var remoteRows: List<String> = emptyList()
    private var pendingRemote: Runnable? = null
    private val ui = android.os.Handler(android.os.Looper.getMainLooper())
    private val suggestState = SuggestState()

    /**
     * Recompute [state] for [q]: the local sections at once, then (setting `search_suggestions`, a
     * non-URL query, not a private tab) the default engine's own query suggestions after a short
     * pause, which fold into the same state when they arrive and only if the query has not moved on.
     */
    private fun refreshSuggestions(state: SuggestState, q: String, private_: Boolean) {
        state.query = q
        val e = engine()
        state.engineLabel = e.label
        fun local(remote: List<String>) = BrowserSuggest.sections(q, prefs.all(), history.all(), e, remote)
        state.sections = local(if (remoteFor == q) remoteRows else emptyList())
        pendingRemote?.let { ui.removeCallbacks(it) }
        if (BrowserRemoteSuggest.shouldAsk(browserSettings.bool("search_suggestions") == true, q, private_)) {
            val r = Runnable {
                Thread {
                    val got = BrowserRemoteSuggest.fetch(e.suggest, q)
                    ui.post { if (isAdded && state.query == q) { remoteFor = q; remoteRows = got; state.sections = local(got) } }
                }.start()
            }
            pendingRemote = r
            ui.postDelayed(r, REMOTE_SUGGEST_DELAY_MS)
        }
    }

    // ── DETAIL mode (WebView) ────────────────────────────────────────

    /** Swap the active tab to [url] and draw it. */
    private fun navigateTo(url: String) {
        if (url.isBlank()) return
        openUrlInTab(url)
    }

    /** #886 what the strip draws, as Compose state the host refreshes when a tab's page, title, icon or group changes. */
    private class StripModel(val tabs: List<BrowserTab>, val activeKey: String, val group: String, val color: Int)
    private val strip = androidx.compose.runtime.mutableStateOf(StripModel(emptyList(), "", "", 0))
    private var addressField: android.widget.EditText? = null
    private var settingAddress = false

    private fun refreshStrip() {
        val all = prefs.all()
        val cur = currentTab() ?: return
        val shown = BrowserStripRules.visible(all, cur).map { t ->
            if (t.iconPath.isNotBlank()) t else faviconFile(t.url)?.let { t.copy(iconPath = it.absolutePath, id = t.key) } ?: t
        }
        strip.value = StripModel(shown, cur.key, cur.group, BrowserTabGroups.colorOf(prefs.groupColors(), cur.group))
    }

    private fun faviconFile(url: String): File? {
        val host = BrowserSitePolicy.hostOf(url).ifEmpty { return null }
        return File(File(requireContext().cacheDir, "favicons"), sha256(host) + ".png").takeIf { it.isFile }
    }

    /** #886 keep the page's favicon (small, by host) for the strip; never for a private tab. */
    private fun saveFavicon(tabKey: String, pageUrl: String?, icon: Bitmap?) {
        icon ?: return
        val tab = prefs.byId(tabKey) ?: return
        if (tab.isPrivate) return
        val host = BrowserSitePolicy.hostOf(pageUrl ?: tab.url).ifEmpty { return }
        runCatching {
            val dir = File(requireContext().cacheDir, "favicons").apply { mkdirs() }
            val f = File(dir, sha256(host) + ".png")
            val small = Bitmap.createScaledBitmap(icon, 48, 48, true)
            f.outputStream().use { small.compress(Bitmap.CompressFormat.PNG, 90, it) }
            prefs.setIcon(tabKey, f.absolutePath)
            refreshStrip()
        }
    }

    /**
     * #886 THE COMMIT: the tab [tabKey] is now on [url]. This is the write that was missing (see
     * [BrowserTabStore]): without it the store kept the url the tab was OPENED with, and a restart
     * brought back that older page. Also keeps the address text and the strip in step.
     */
    private fun onCommitted(tabKey: String, view: WebView?, url: String?) {
        if (!BrowserTabStore.shouldCommit(url) || readerOn) return
        prefs.commit(tabKey, url, view?.title)
        addressField?.let { f -> if (!f.isFocused) { settingAddress = true; f.setText(url); settingAddress = false } }
        refreshStrip()
    }

    /**
     * #886 write the showing tab's WebView state (its back/forward list) to its file, and its url to
     * the store one more time. Called when the page finishes, when the app pauses or stops, before the
     * WebView is destroyed (a tab switch, the grid) — i.e. at every point the state could be lost.
     * Never for a private tab.
     */
    private fun saveTabState() {
        val wv = webView ?: return
        val tab = currentTab() ?: return
        if (!BrowserTabStore.shouldCommit(wv.url) || readerOn) return
        prefs.commit(tab.key, wv.url, wv.title)
        if (!tab.isPrivate) BrowserWebState.save(requireContext(), wv, tab.key)
    }

    private fun showDetail(tab: BrowserTab) {
        teardownWebView()   // saves the tab being left (its back stack) and frees its WebView
        mode = Mode.DETAIL(tab.key)
        val tabKey = tab.key
        val url = tab.url
        val ctx = requireContext()
        suggestState.visible = false
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

        // Address bar: a plain field. Its suggestions are the Compose panel over the page (below), so
        // there is no PopupWindow to land on top of the bar or the keyboard.
        val urlBar = android.widget.EditText(ctx).apply {
            setText(url)
            setTextColor(Color.WHITE)
            setHintTextColor(0x80FFFFFF.toInt())
            setBackgroundColor(Color.TRANSPARENT)
            isSingleLine = true
            hint = "Search ${engine().label}, or type a URL"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_GO
            setSelectAllOnFocus(true)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            val m = dp(8); setPadding(m, 0, m, 0)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(e: android.text.Editable?) {
                    if (settingAddress || !isFocused) return
                    val q = e?.toString().orEmpty()
                    suggestState.visible = q.isNotBlank()
                    refreshSuggestions(suggestState, q, currentTab()?.isPrivate == true)
                }
            })
            // Losing focus does NOT hide the panel: touching a row of it moves focus to the Compose view
            // on ACTION_DOWN, and hiding it then would eat the tap. It leaves by the dismiss paths
            // (dimmed page, back, Go, a pick, a tab change).
            setOnFocusChangeListener { v, has ->
                if (has) (v as android.widget.EditText).text?.toString()?.takeIf { it.isNotBlank() && it != currentTab()?.url }?.let {
                    suggestState.visible = true; refreshSuggestions(suggestState, it, currentTab()?.isPrivate == true)
                }
            }
            setOnEditorActionListener { v, actionId, _ ->
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_GO) {
                    val next = BrowserSearch.resolve(text.toString(), engine())
                    dismissSuggestions()
                    // #886 the tab being typed in navigates IN PLACE (it keeps its pin, group, order and
                    // back stack); it used to be closed and re-added, which dropped all four.
                    if (next.isNotEmpty()) webView?.loadUrl(next)
                    true
                } else false
            }
        }
        addressField = urlBar
        bar.addView(barButton(ctx, "‹") { runAction("back") })
        bar.addView(barButton(ctx, "›") { runAction("forward") })
        bar.addView(urlBar)

        bar.addView(barButton(ctx, "⋮") { showMenuSheet() })
        column.addView(bar)

        // #886 the strip of tab icons for this tab's group, under the bar; on by default.
        refreshStripFor(tab)
        if (browserSettings.bool("tab_strip") != false) {
            column.addView(ctx.kitComposeView(palette()) {
                val m = strip.value
                BrowserTabStrip(m.tabs, m.activeKey, m.group, m.color,
                    onSelect = { t -> if (t.key != currentTab()?.key) { prefs.setActiveId(t.key); showDetail(prefs.byId(t.key) ?: t) } },
                    onNew = { promptForUrl(private_ = currentTab()?.isPrivate == true, joinTabKey = currentTab()?.key) })
            }.apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT) })
        }

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
            if (tab.isPrivate) settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
            applySettings(this, url)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, startedUrl: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, startedUrl, favicon)
                    // A real navigation leaves reader view; the reader's own render does not.
                    if (readerPending) readerPending = false else readerOn = false
                    // Settings, then this host's shields: a host that was shielded must not leave JS off for the next.
                    view?.let { applySettings(it, startedUrl) }
                }

                /** #886 fires once a navigation has COMMITTED (and for in-page routes): the url worth remembering. */
                override fun doUpdateVisitedHistory(view: WebView?, visitedUrl: String?, isReload: Boolean) {
                    super.doUpdateVisitedHistory(view, visitedUrl, isReload)
                    onCommitted(tabKey, view, visitedUrl)
                }

                override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                    super.onPageFinished(view, finishedUrl)
                    val u = finishedUrl ?: return
                    if (readerOn) return
                    onCommitted(tabKey, view, u)
                    postDelayed({ if (webView === this@apply) saveTabState() }, 400)
                    // #802 a private tab is never recorded: no history, no preview.
                    if (!BrowserSitePolicy.shouldRecord(prefs.byId(tabKey))) {
                        runCatching { java.net.URI(u) }.getOrNull()?.let { privateOrigins.add("${it.scheme}://${it.authority}") }
                        return
                    }
                    // Item 5. Local store, no sink, no sync — see BrowserHistory.
                    history.record(u, view?.title ?: u)
                    postDelayed({ capturePreview(this@apply, tabKey) }, 600)
                }
            }
            setDownloadListener { dlUrl, ua, disposition, mime, _ ->
                val d = download(dlUrl, ua, disposition, mime)
                toast("Downloading ${d.file}")
            }
            webChromeClient = object : WebChromeClient() {
                override fun onReceivedTitle(view: WebView?, title: String?) {
                    val u = view?.url ?: return
                    if (BrowserTabStore.shouldCommit(u)) prefs.commit(tabKey, u, title ?: u)
                    prefs.updateTitleById(tabKey, title ?: u)
                    refreshStrip()
                }

                override fun onReceivedIcon(view: WebView?, icon: Bitmap?) {
                    saveFavicon(tabKey, view?.url, icon)
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
            // #886 the tab comes back where it was: its saved back stack when WebView can restore it,
            // else the page it last COMMITTED (never the url it was first opened with).
            if (tab.isPrivate || !BrowserWebState.restore(ctx, this, tabKey)) loadUrl(url)
        }
        // The page area: the WebView, and over it the suggestions panel, which is a CHILD of this frame
        // (so it sits under the bar and ends at the frame's bottom: above the keyboard, never over it).
        val content = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(webView)
            addView(ctx.kitComposeView(palette()) {
                BrowserSuggestOverlay(suggestState, onPick = { s -> pickSuggestion(s) }, onDismiss = { dismissSuggestions() })
            }.apply {
                layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            })
        }
        column.addView(content)

        overlays.clear()
        onOverlaysClosed?.invoke()
        rootContainer.removeAllViews()
        rootContainer.addView(column)
    }

    private fun refreshStripFor(tab: BrowserTab) {
        val all = prefs.all()
        val cur = all.firstOrNull { it.key == tab.key } ?: tab
        strip.value = StripModel(BrowserStripRules.visible(all, cur), cur.key, cur.group,
            BrowserTabGroups.colorOf(prefs.groupColors(), cur.group))
        refreshStrip()
    }

    /** Hide the dropdown, give the field up, close the keyboard. */
    private fun dismissSuggestions() {
        suggestState.visible = false
        pendingRemote?.let { ui.removeCallbacks(it) }
        addressField?.let { f -> f.clearFocus(); hideKeyboard(f) }
    }

    /** A row picked: an open tab switches to it; a search or a visited page loads in THIS tab. */
    private fun pickSuggestion(s: BrowserSuggest.Suggestion) {
        dismissSuggestions()
        if (s.source == BrowserSuggest.Source.TAB) {
            prefs.all().firstOrNull { it.url == s.url }?.let { t ->
                if (t.key != currentTab()?.key) { prefs.setActiveId(t.key); showDetail(t); return }
            }
        }
        webView?.loadUrl(s.url)
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

    private fun capturePreview(wv: WebView, tabKey: String) {
        runCatching {
            val w = wv.width; val h = wv.height
            if (w <= 0 || h <= 0) return
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
            wv.draw(Canvas(bmp))
            val dir = File(requireContext().cacheDir, "tabs").apply { mkdirs() }
            val file = File(dir, "${sha256(tabKey)}.png")
            file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 70, it) }
            bmp.recycle()
            prefs.updatePreviewById(tabKey, file.absolutePath)
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
        val id = (mode as? Mode.DETAIL)?.id ?: return null
        return prefs.byId(id)
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

    /** #823 the assistant's conversation: kept here so it survives closing the side panel. */
    private val agentState = AgentPanelState()
    private var agentPanel: View? = null

    /**
     * #823 the assistant as a Compose side panel over the page (the page stays visible and usable
     * beside it). Its consent card is the ONLY place a waiting action can be allowed.
     */
    private fun showAgentChat() {
        if (BrowserAgentHost.ask == null) return toast("The assistant is not available in this app")
        if (agentPanel?.parent != null) return
        agentPanel = overlay(side = true) { close ->
            AgentChatPanel(agentState,
                onSend = { text ->
                    agentState.lines.add(AgentLine(AgentLine.USER, text))
                    agentRun { listOfNotNull(BrowserAgentHost.ask?.invoke(text)?.let(AgentPanelText::lineFor)) }
                },
                onDecide = { pend, allow ->
                    val decide = BrowserAgentHost.decide
                    agentState.pending = null
                    agentState.lines.add(AgentPanelText.decisionLine(allow, pend.sentence))
                    if (decide != null) agentRun { listOfNotNull(AgentPanelText.lineFor(decide(pend.callId, allow))) }
                },
                onSummarize = { summarizeIntoPanel() },
                onNewChat = { BrowserAgentHost.reset?.invoke(); agentState.lines.clear(); agentState.pending = null },
                onClose = close)
        }
    }

    /** #823 the page's summary on his route (Settings ▸ Summarize with), naming the route and engine that answered. */
    private fun showPageSummary() {
        if (BrowserAgentHost.summarize == null) return toast("Summaries are not available in this app")
        showAgentChat()
        summarizeIntoPanel()
    }

    private fun summarizeIntoPanel() {
        val summarize = BrowserAgentHost.summarize ?: return
        agentState.lines.add(AgentLine(AgentLine.USER, "Summarize this page"))
        agentRun { AgentPanelText.summaryLines(summarize()) }
    }

    /** Run [work] (a model turn: network) off the main thread; its lines join the panel's transcript. */
    private fun agentRun(work: () -> List<AgentLine>) {
        agentState.busy = true
        Thread {
            val out = runCatching(work).getOrElse { listOf(AgentLine(AgentLine.NOTE, "Error: ${it.javaClass.simpleName}")) }
            view?.post { agentState.lines.addAll(out); agentState.busy = false } ?: run { agentState.busy = false }
        }.start()
    }

    /** #802 I9 / #823 the consent card: the exact action and site, in the side panel; nothing runs until he picks. */
    private fun showAgentConfirm(callId: String, sentence: String) {
        agentState.pending = AgentPending(callId, sentence)
        showAgentChat()
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

    /** #823 Cloud Search's Search page over the browser: its engine boxes and AI chat; a result opens as a tab. */
    private fun showSearchPage() {
        val page = BrowserSearchPageHost.page ?: return toast("The Search page is not available in this app")
        overlay { close -> page({ url -> close(); openEntryUrl(url) }, close) }
    }

    /** #802 I8 one query, one of cloud-search's engines (the Search add-on): its results open as a new tab. */
    private fun showSearchWith() {
        val engines = config.addons.searchEngines()
        if (engines.isEmpty()) return toast("The Search add-on declares no engines")
        val ctx = requireContext()
        val input = android.widget.EditText(ctx).apply {
            setTextColor(Color.WHITE); setHintTextColor(0x80FFFFFF.toInt()); isSingleLine = true
        }
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

    private fun openEntryUrl(url: String) = openUrlInTab(url)

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
    /** [side]: a panel on the right edge, the page left visible and usable beside it (#823 the assistant). */
    private fun overlay(bottom: Boolean = false, side: Boolean = false, content: @Composable (close: () -> Unit) -> Unit): View? {
        val ctx = context ?: return null
        lateinit var v: View
        val close = { rootContainer.removeView(v); overlays.remove(v); if (overlays.isEmpty()) onOverlaysClosed?.invoke(); Unit }
        v = ctx.kitComposeView(palette()) { content(close) }
        v.layoutParams = if (side) FrameLayout.LayoutParams(
            minOf((resources.displayMetrics.widthPixels * 0.88f).toInt(), dp(480)),
            FrameLayout.LayoutParams.MATCH_PARENT, android.view.Gravity.END,
        ) else FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            if (bottom) FrameLayout.LayoutParams.WRAP_CONTENT else FrameLayout.LayoutParams.MATCH_PARENT,
            if (bottom) android.view.Gravity.BOTTOM else android.view.Gravity.NO_GRAVITY,
        )
        overlays.add(v)
        rootContainer.addView(v)
        return v
    }

    private fun closeOverlays() {
        overlays.toList().forEach { rootContainer.removeView(it) }
        overlays.clear()
        onOverlaysClosed?.invoke()
    }

    /** What the menu's `requires` and `checked` are resolved against, right now. */
    private fun facts(): Map<String, Boolean> {
        val wv = webView
        val page = mode is Mode.DETAIL && wv != null
        return mapOf(
            "page" to page,
            "can_go_back" to (page && wv!!.canGoBack()),
            "can_go_forward" to (page && wv!!.canGoForward()),
            "reader_on" to readerOn,
            "desktop_mode" to desktopMode,
            "pinned" to (currentTab()?.pinned == true),
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
                    "pin" -> { prefs.setPinnedById(tab.key, !tab.pinned); showGrid() }
                    "group" -> promptForGroup(tab)
                    "ungroup" -> { prefs.setGroupById(tab.key, ""); showGrid() }
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
        // #886 the tab is held by id; its url is whatever it last committed.
        val tabNow = currentTab() ?: prefs.activeTab()
        val tabUrl = tabNow?.url.orEmpty()
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
            "search_chat" -> { showSearchPage(); done(ok()) }
            "ai_chat" -> { showAgentChat(); done(ok()) }
            "ai_summarize" -> { showPageSummary(); done(ok()) }
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
                val tab = tabNow ?: return needPage()
                closeTab(tab); done(ok().put("closed", prefs.byId(tab.key) == null))
            }
            "pin_tab" -> { (tabNow ?: return needPage()).let { prefs.setPinnedById(it.key, !it.pinned) }; done(ok().put("pinned", facts().getValue("pinned"))) }
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
                suggestState.visible -> dismissSuggestions()
                overlays.isNotEmpty() -> {
                    rootContainer.removeView(overlays.last()); overlays.removeAt(overlays.lastIndex)
                    if (overlays.isEmpty()) onOverlaysClosed?.invoke()
                }
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
        saveTabState()
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
        private const val REMOTE_SUGGEST_DELAY_MS = 250L

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
