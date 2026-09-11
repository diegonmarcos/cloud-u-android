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
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
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
 *   HISTORY  the on-device visit list, with a clear button.
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

    /** Desktop-mode toggle — WebView UA + width override + initial scale. */
    private var desktopMode: Boolean = false

    private lateinit var prefs: BrowserTabPrefs
    private lateinit var history: BrowserHistory
    private lateinit var browserSettings: BrowserSettings
    private lateinit var config: BrowserConfig
    private lateinit var rootContainer: FrameLayout
    private var webView: WebView? = null
    private var mode: Mode = Mode.GRID

    private sealed class Mode {
        object GRID : Mode()
        object HISTORY : Mode()
        data class DETAIL(val url: String) : Mode()
    }

    /** The engine a typed query goes to: his pick, else the app's default. */
    private fun engine(): BrowserSearchEngine = config.engine(browserSettings.searchEngineId())

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val ctx = inflater.context
        prefs = BrowserTabPrefs(ctx)
        history = BrowserHistory(ctx)
        browserSettings = BrowserSettings(ctx)
        config = BrowserConfig.parseBase64(arguments?.getString(ARG_CONFIG_B64))

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
        if (!openUrl.isNullOrBlank()) {
            prefs.add(openUrl, openUrl); prefs.setActive(openUrl)
            showDetail(openUrl)
        } else {
            showGrid()
        }
        return rootContainer
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

    /** Per-tab actions. Long-press is spoken for by the drag, so: ⋮. */
    private fun showTabMenu(tab: BrowserTab, anchor: View) {
        val ctx = anchor.context
        val pop = android.widget.PopupMenu(ctx, anchor)
        val pin = pop.menu.add(if (tab.pinned) "Unpin tab" else "Pin tab")
        val group = pop.menu.add(if (tab.group.isBlank()) "Add to group…" else "Move to group…")
        val ungroup = if (tab.group.isNotBlank()) pop.menu.add("Remove from “${tab.group}”") else null
        val close = if (tab.pinned) null else pop.menu.add("Close tab")
        pop.setOnMenuItemClickListener { item ->
            when (item) {
                pin -> { prefs.setPinned(tab.url, !tab.pinned); showGrid() }
                group -> promptForGroup(tab)
                ungroup -> { prefs.setGroup(tab.url, ""); showGrid() }
                close -> closeTab(tab)
            }
            true
        }
        pop.show()
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

    /** Resolve what was typed to a destination, open it as a new tab. */
    private fun openEntry(raw: String) {
        val url = BrowserSearch.resolve(raw, engine())
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
        bar.addView(urlBar)

        bar.addView(TextView(ctx).apply {
            text = "⋮"
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setTextAppearance(android.R.style.TextAppearance_Material_Title)
            val pad = dp(8); setPadding(pad, 0, pad, 0)
            setOnClickListener { v -> showBrowserMenu(v, url) }
        })
        column.addView(bar)

        webView = WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            applyViewMode(this)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                    super.onPageFinished(view, finishedUrl)
                    val u = finishedUrl ?: return
                    // Item 5. Local store, no sink, no sync — see BrowserHistory.
                    history.record(u, view?.title ?: u)
                    postDelayed({ capturePreview(this@apply, u) }, 600)
                }
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

        rootContainer.removeAllViews()
        rootContainer.addView(column)
    }

    // ── HISTORY mode (item 5) ────────────────────────────────────────

    private fun showHistory() {
        mode = Mode.HISTORY
        teardownWebView()
        val ctx = requireContext()

        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(12); setPadding(pad, dp(8), pad, pad)
        }
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(ctx).apply {
            text = " ← Tabs "
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setOnClickListener { showGrid() }
        })
        header.addView(TextView(ctx).apply {
            text = "History"
            setTextColor(0xFFE9D8FD.toInt())
            typeface = Typeface.DEFAULT_BOLD
            setTextAppearance(android.R.style.TextAppearance_Material_Title)
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            val m = dp(8); setPadding(m, 0, m, 0)
        })
        header.addView(pill(ctx, "Clear") {
            androidx.appcompat.app.AlertDialog.Builder(ctx)
                .setTitle("Clear history?")
                .setMessage("Removes every recorded visit from this device.")
                .setPositiveButton("Clear") { _, _ -> history.clear(); showHistory() }
                .setNegativeButton("Cancel", null)
                .show()
        })
        column.addView(header)

        val visits = history.all()
        val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        if (visits.isEmpty()) {
            list.addView(TextView(ctx).apply {
                text = "No history yet."
                setTextColor(0xCCFFFFFF.toInt())
                alpha = 0.7f
                val pad = dp(20); setPadding(pad, pad, pad, pad)
            })
        } else {
            for (v in visits) {
                list.addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(6), dp(10), dp(6), dp(10))
                    addView(TextView(ctx).apply {
                        text = v.title.ifBlank { v.url }
                        setTextColor(Color.WHITE)
                        isSingleLine = true
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    })
                    addView(TextView(ctx).apply {
                        text = v.url
                        setTextColor(0x99FFFFFF.toInt())
                        isSingleLine = true
                        ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                        setTextAppearance(android.R.style.TextAppearance_Material_Caption)
                    })
                    setOnClickListener { navigateTo(v.url) }
                })
            }
        }
        column.addView(ScrollView(ctx).apply {
            addView(list)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        })

        rootContainer.removeAllViews()
        rootContainer.addView(column)
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

    private fun applyViewMode(wv: WebView) {
        val s = wv.settings
        if (desktopMode) {
            s.userAgentString = "Mozilla/5.0 (X11; Linux x86_64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36 CloudSuperApp/1.0"
            s.useWideViewPort = true
            s.loadWithOverviewMode = true
            wv.setInitialScale(1)
        } else {
            s.userAgentString = "Mozilla/5.0 (Linux; Android 14; Pixel 8) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36 CloudSuperApp/1.0"
            s.useWideViewPort = false
            s.loadWithOverviewMode = false
            wv.setInitialScale(0)
        }
    }

    private fun showBrowserMenu(anchor: View, url: String) {
        val ctx = anchor.context
        val tab = prefs.all().firstOrNull { it.url == url }
        val pop = android.widget.PopupMenu(ctx, anchor)
        val miPin    = pop.menu.add(if (tab?.pinned == true) "Unpin tab" else "Pin tab")
        val miHist   = pop.menu.add("History")
        val miEngine = pop.menu.add("Search engine: ${engine().label}")
        val miExt    = pop.menu.add("Open in browser…")
        val miReload = pop.menu.add("Reload")
        val miView   = pop.menu.add(if (desktopMode) "View: Mobile" else "View: Desktop")
        val miCopy   = pop.menu.add("Copy URL")
        val miShare  = pop.menu.add("Share…")
        pop.setOnMenuItemClickListener { item ->
            when (item) {
                miPin -> {
                    prefs.setPinned(url, !(tab?.pinned ?: false))
                    toast(if (tab?.pinned == true) "Unpinned" else "Pinned")
                }
                miHist -> showHistory()
                miEngine -> showEnginePicker(anchor)
                miExt -> openInExternalBrowser(url)
                miReload -> webView?.reload()
                miView -> {
                    desktopMode = !desktopMode
                    webView?.let { applyViewMode(it); it.reload() }
                }
                miCopy -> {
                    val clip = ctx.getSystemService(Context.CLIPBOARD_SERVICE)
                        as? android.content.ClipboardManager
                    clip?.setPrimaryClip(android.content.ClipData.newPlainText("url", url))
                    toast("URL copied")
                }
                miShare -> startActivity(
                    android.content.Intent.createChooser(
                        android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(android.content.Intent.EXTRA_TEXT, url)
                        }, "Share URL"))
            }
            true
        }
        pop.show()
    }

    /** Item 7's setting. The list is the app's config, never a list here. */
    private fun showEnginePicker(anchor: View) {
        val pop = android.widget.PopupMenu(anchor.context, anchor)
        val items = config.engines.map { it to pop.menu.add(it.label) }
        pop.setOnMenuItemClickListener { item ->
            items.firstOrNull { it.second == item }?.let { (e, _) ->
                browserSettings.setSearchEngineId(e.id)
                toast("Search engine: ${e.label}")
            }
            true
        }
        pop.show()
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
