package com.diegonmarcos.clouddrive

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.provider.OpenableColumns
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.Executors

/**
 * The PDF reader screen (#577): a native activity around [PdfReaderView] and the pdfium
 * [PdfEngine], replacing the pdf.js page that used to live inside drive.html.
 *
 * It is what the manifest offers other apps for application/pdf (cloud-mail's attachment chooser
 * lands here, #458/#463) and what the Files tab opens for a PDF row (FilesBridge.openPdf).
 * Continuous and paged scrolling, pinch zoom, search with highlights, outline, night mode, share,
 * print and the four PDF→text conversions are all reached from this one toolbar.
 *
 * The document is opened from a file descriptor on a background thread, so a 500 MB PDF shows its
 * first page as fast as a 50 KB one: nothing here reads the file into memory.
 */
internal class PdfReaderActivity : AppCompatActivity(), PdfReaderView.Host {

    private lateinit var reader: PdfReaderView
    private lateinit var titleView: TextView
    private lateinit var status: TextView
    private lateinit var pill: TextView
    private lateinit var searchBar: LinearLayout
    private lateinit var searchInput: EditText
    private lateinit var searchCount: TextView
    private lateinit var nightButton: TextView
    private lateinit var modeButton: TextView

    private val io = Executors.newSingleThreadExecutor()
    private val searchExec = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    private var engine: PdfEngine? = null
    private lateinit var source: Uri

    /** The file behind [source] when it is a real path (file://); null for a content:// hand-off. */
    private var sourceFile: File? = null
    private var displayName = "document.pdf"

    private val hits = ArrayList<SearchHit>()
    private var currentHit = -1
    private var searchGen = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val data = intent?.data
        if (data == null) {
            finish()
            return
        }
        val file = if (data.scheme == "file") File(data.path ?: "") else null
        if (file != null && !isReadablePlace(file)) {
            Toast.makeText(this, "That location cannot be opened here.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        source = data
        sourceFile = file
        displayName = file?.name ?: queryName(data) ?: "document.pdf"

        buildUi()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (searchBar.visibility == View.VISIBLE) closeSearch() else finish()
            }
        })
        openDocument(null)
    }

    override fun onPause() {
        // Resume where the reader left off: the next open of this document lands on the same page.
        // Only for a real file: a content:// hand-off has a throwaway URI that would never match again.
        if (engine != null && sourceFile != null) {
            prefs.edit().putInt(LAST_PAGE + source, reader.currentPageIndex()).apply()
        }
        super.onPause()
    }

    override fun onDestroy() {
        searchGen++
        searchExec.shutdownNow()
        io.shutdownNow()
        if (::reader.isInitialized) reader.destroy()
        engine?.close()
        engine = null
        super.onDestroy()
    }

    // ── opening ──────────────────────────────────────────────────────────────────────────────

    private fun openDocument(password: String?) {
        showStatus("Opening…")
        io.execute {
            try {
                val opened = PdfEngine.open(applicationContext, source, password)
                val first = opened.pageSize(0)
                runOnUiThread {
                    if (isDestroyed || isFinishing) opened.close() else onOpened(opened, first)
                }
            } catch (error: PdfEngine.PasswordRequired) {
                runOnUiThread { askPassword(retry = password != null) }
            } catch (error: Throwable) {
                // Throwable, not Exception: a missing native library is an Error, and a blank
                // screen with no reason is the failure this reader exists to stop having.
                runOnUiThread { showStatus("Cannot open this PDF: " + (error.message ?: error.javaClass.simpleName)) }
            }
        }
    }

    private fun onOpened(opened: PdfEngine, first: FloatArray?) {
        if (opened.pageCount == 0 || first == null) {
            opened.close()
            showStatus("This PDF has no pages.")
            return
        }
        engine = opened
        status.visibility = View.GONE
        val night = prefs.getBoolean(NIGHT, false)
        val paged = prefs.getBoolean(PAGED, false)
        nightButton.alpha = if (night) 1f else 0.55f
        modeButton.text = if (paged) "📄" else "📜"
        reader.open(opened, first, if (sourceFile != null) prefs.getInt(LAST_PAGE + source, 0) else 0, paged, night)
        titleView.text = displayName
    }

    private fun askPassword(retry: Boolean) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle(if (retry) "Wrong password — try again" else "This PDF is password protected")
            .setView(input)
            .setCancelable(false)
            .setPositiveButton("Open") { _, _ -> openDocument(input.text.toString()) }
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .show()
    }

    private fun showStatus(text: String) {
        status.text = text
        status.visibility = View.VISIBLE
    }

    // ── host callbacks ───────────────────────────────────────────────────────────────────────

    override fun onPageChanged(page: Int, count: Int) {
        pill.text = "${page + 1} / $count"
        pill.visibility = if (count > 1) View.VISIBLE else View.GONE
    }

    override fun onWordSelected(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("PDF text", text))
        Toast.makeText(this, "Copied “$text”", Toast.LENGTH_SHORT).show()
    }

    override fun onOpenLink(uri: String) {
        // A PDF is untrusted input: only links a person would expect a reader to follow.
        val parsed = Uri.parse(uri)
        val scheme = parsed.scheme?.lowercase() ?: return
        if (scheme !in SAFE_LINK_SCHEMES) return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, parsed))
        } catch (error: ActivityNotFoundException) {
            Toast.makeText(this, "Nothing on this phone can open that link.", Toast.LENGTH_SHORT).show()
        }
    }

    // ── toolbar actions ──────────────────────────────────────────────────────────────────────

    private fun toggleNight() {
        val on = !reader.night
        reader.setNight(on)
        nightButton.alpha = if (on) 1f else 0.55f
        prefs.edit().putBoolean(NIGHT, on).apply()
    }

    private fun toggleMode() {
        val paged = !reader.paged
        reader.setPaged(paged)
        modeButton.text = if (paged) "📄" else "📜"
        prefs.edit().putBoolean(PAGED, paged).apply()
    }

    private fun showOutline() {
        val e = engine ?: return
        io.execute {
            val items = try { e.outline() } catch (error: Exception) { emptyList() }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (items.isEmpty()) {
                    Toast.makeText(this, "This PDF has no outline.", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                val labels = items.map { "    ".repeat(it.depth) + it.title }.toTypedArray()
                AlertDialog.Builder(this)
                    .setTitle("Outline")
                    .setItems(labels) { _, which -> reader.goToPage(items[which].page) }
                    .show()
            }
        }
    }

    private fun askPage() {
        val e = engine ?: return
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "1 – ${e.pageCount}"
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle("Go to page")
            .setView(input)
            .setPositiveButton("Go") { _, _ ->
                val n = input.text.toString().trim().toIntOrNull()
                if (n != null) reader.goToPage(n - 1)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun share() {
        io.execute {
            try {
                val uri = shareableUri()
                val send = Intent(Intent.ACTION_SEND)
                    .setType("application/pdf")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                runOnUiThread { startActivity(Intent.createChooser(send, null)) }
            } catch (error: Exception) {
                runOnUiThread { toast("Cannot share this PDF: " + (error.message ?: "unknown error")) }
            }
        }
    }

    /**
     * A content:// URI another app may read. A file inside the provider's roots is shared in
     * place; anything else (a hand-off from another app, or a path outside those roots) goes
     * through a private copy, because the read grant we were given is not ours to pass on.
     */
    private fun shareableUri(): Uri {
        val authority = "$packageName.files"
        val file = sourceFile
        if (file != null) {
            try { return FileProvider.getUriForFile(this, authority, file) } catch (outside: IllegalArgumentException) { /* copy below */ }
        }
        return FileProvider.getUriForFile(this, authority, PdfEngine.spool(this, source))
    }

    /** Prints the ORIGINAL bytes through the system print service: the PDF is already the print format. */
    private fun printDocument() {
        val manager = getSystemService(Context.PRINT_SERVICE) as PrintManager
        manager.print(displayName, object : PrintDocumentAdapter() {
            override fun onLayout(
                oldAttributes: PrintAttributes?,
                newAttributes: PrintAttributes?,
                cancellationSignal: CancellationSignal?,
                callback: LayoutResultCallback?,
                extras: Bundle?
            ) {
                if (cancellationSignal?.isCanceled == true) {
                    callback?.onLayoutCancelled()
                    return
                }
                val info = PrintDocumentInfo.Builder(displayName)
                    .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                    .setPageCount(PrintDocumentInfo.PAGE_COUNT_UNKNOWN)
                    .build()
                callback?.onLayoutFinished(info, true)
            }

            override fun onWrite(
                pages: Array<out PageRange>?,
                destination: ParcelFileDescriptor?,
                cancellationSignal: CancellationSignal?,
                callback: WriteResultCallback?
            ) {
                try {
                    val input: InputStream = contentResolver.openInputStream(source)
                        ?: throw java.io.IOException("the document cannot be read")
                    input.use { src ->
                        FileOutputStream(destination?.fileDescriptor).use { out -> src.copyTo(out) }
                    }
                    callback?.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                } catch (error: Exception) {
                    callback?.onWriteFailed(error.message)
                }
            }
        }, null)
    }

    private fun chooseConversion() {
        if (sourceFile == null) {
            AlertDialog.Builder(this)
                .setMessage("A document handed in from another app has no folder to save next to — copy it into Files first.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        val targets = PdfConvert.TARGETS
        AlertDialog.Builder(this)
            .setTitle("Convert to")
            .setItems(targets.toTypedArray()) { _, which -> convert(targets[which]) }
            .show()
    }

    private fun convert(target: String) {
        val path = sourceFile?.absolutePath ?: return
        toast("Converting…")
        io.execute {
            val outcome = try {
                JSONObject(FilesBridge(applicationContext).convertPdf(path, target))
            } catch (error: Exception) {
                JSONObject().put("ok", false).put("error", error.message ?: "conversion failed")
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (outcome.optBoolean("ok")) toast("Saved as " + outcome.optString("name") + " next to the original.")
                else AlertDialog.Builder(this).setMessage(outcome.optString("error")).setPositiveButton("OK", null).show()
            }
        }
    }

    // ── search ───────────────────────────────────────────────────────────────────────────────

    private fun openSearch() {
        searchBar.visibility = View.VISIBLE
        searchInput.requestFocus()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(searchInput, 0)
    }

    private fun closeSearch() {
        searchGen++
        hits.clear()
        currentHit = -1
        reader.setHits(searchGen, emptyList(), -1)
        searchBar.visibility = View.GONE
        searchCount.text = ""
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(searchInput.windowToken, 0)
    }

    private fun startSearch(query: String) {
        val e = engine ?: return
        val id = ++searchGen
        hits.clear()
        currentHit = -1
        reader.setHits(id, emptyList(), -1)
        if (query.isBlank()) {
            searchCount.text = ""
            return
        }
        searchCount.text = "…"
        val from = reader.currentPageIndex()
        searchExec.execute {
            val n = e.pageCount
            val batch = ArrayList<SearchHit>()
            var lastPost = 0L
            for (k in 0 until n) {
                if (id != searchGen) return@execute
                // From the reader's own page to the end, then wrapping: the first match is the
                // nearest one below, and Next keeps going in reading order.
                val page = (from + k) % n
                for (f in e.findOnPage(page, query, false)) batch.add(SearchHit(page, f[0], f[1]))
                val now = System.currentTimeMillis()
                val last = k == n - 1
                if ((batch.isNotEmpty() && now - lastPost > BATCH_MS) || last) {
                    val chunk = ArrayList(batch)
                    batch.clear()
                    lastPost = now
                    runOnUiThread { if (id == searchGen) addHits(id, chunk, last) }
                }
            }
        }
    }

    private fun addHits(id: Int, chunk: List<SearchHit>, done: Boolean) {
        hits.addAll(chunk)
        val first = currentHit < 0 && hits.isNotEmpty()
        if (first) currentHit = 0
        reader.setHits(id, ArrayList(hits), currentHit)
        if (first) reader.showHit(0)
        searchCount.text = when {
            hits.isEmpty() -> if (done) "No matches" else "…"
            else -> "${currentHit + 1} / ${hits.size}" + if (done) "" else "+"
        }
    }

    private fun stepHit(delta: Int) {
        if (hits.isEmpty()) return
        currentHit = (currentHit + delta + hits.size) % hits.size
        reader.showHit(currentHit)
        searchCount.text = "${currentHit + 1} / ${hits.size}"
    }

    // ── UI ───────────────────────────────────────────────────────────────────────────────────

    private fun buildUi() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val root = FrameLayout(this).apply { setBackgroundColor(CHROME) }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(CHROME)
        }
        bar.addView(iconButton("‹", "Back") { finish() })
        titleView = TextView(this).apply {
            setTextColor(TEXT)
            textSize = 16f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            text = displayName
        }
        bar.addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(iconButton("🔍", "Search") { openSearch() })
        bar.addView(iconButton("☰", "Outline") { showOutline() })
        nightButton = iconButton("🌙", "Night mode") { toggleNight() }
        bar.addView(nightButton)
        modeButton = iconButton("📜", "Continuous or paged") { toggleMode() }
        bar.addView(modeButton)
        val more = iconButton("⋮", "More", null)
        more.setOnClickListener { showMore(more) }
        bar.addView(more)
        column.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))

        searchBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(CHROME)
            visibility = View.GONE
        }
        searchInput = EditText(this).apply {
            hint = "Search in document"
            setHintTextColor(HINT)
            setTextColor(TEXT)
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { view, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    startSearch(text.toString())
                    (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                        .hideSoftInputFromWindow(view.windowToken, 0)
                    true
                } else false
            }
        }
        searchBar.addView(searchInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(12) })
        searchCount = TextView(this).apply { setTextColor(HINT); textSize = 13f; setPadding(dp(8), 0, dp(8), 0) }
        searchBar.addView(searchCount)
        searchBar.addView(iconButton("▲", "Previous match") { stepHit(-1) })
        searchBar.addView(iconButton("▼", "Next match") { stepHit(1) })
        searchBar.addView(iconButton("✕", "Close search") { closeSearch() })
        column.addView(searchBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))

        reader = PdfReaderView(this).also { it.host = this }
        column.addView(reader, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        pill = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(dp(14), dp(6), dp(14), dp(6))
            background = GradientDrawable().apply { setColor(0xCC000000.toInt()); cornerRadius = dp(16).toFloat() }
            visibility = View.GONE
        }
        root.addView(pill, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(24) })

        status = TextView(this).apply {
            setTextColor(TEXT)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(dp(24), 0, dp(24), 0)
        }
        root.addView(status, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    private fun showMore(anchor: View) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, MENU_SHARE, 0, "Share")
        menu.menu.add(0, MENU_PRINT, 1, "Print")
        menu.menu.add(0, MENU_GOTO, 2, "Go to page…")
        menu.menu.add(0, MENU_CONVERT, 3, "Convert…")
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_SHARE -> share()
                MENU_PRINT -> printDocument()
                MENU_GOTO -> askPage()
                MENU_CONVERT -> chooseConversion()
            }
            true
        }
        menu.show()
    }

    private fun iconButton(glyph: String, description: String, onClick: (() -> Unit)?): TextView {
        val value = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, value, true)
        return TextView(this).apply {
            text = glyph
            textSize = 20f
            setTextColor(TEXT)
            gravity = Gravity.CENTER
            minWidth = dp(44)
            contentDescription = description
            setBackgroundResource(value.resourceId)
            if (onClick != null) setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
    }

    private fun dp(value: Int): Int = Math.round(value * resources.displayMetrics.density)

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    // ── source ───────────────────────────────────────────────────────────────────────────────

    /** file:// is honoured only inside places this app already browses; a content:// is the sender's grant. */
    private fun isReadablePlace(file: File): Boolean {
        val candidate = try { file.canonicalFile } catch (error: Exception) { return false }
        val roots = ArrayList<File>()
        roots.add(android.os.Environment.getExternalStorageDirectory())
        getExternalFilesDirs(null).filterNotNull().forEach { roots.add(it) }
        roots.add(cacheDir)
        return roots.any {
            val root = try { it.canonicalFile } catch (error: Exception) { return@any false }
            candidate.path.startsWith(root.path + File.separator)
        }
    }

    private fun queryName(uri: Uri): String? {
        val named = try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (error: Exception) {
            null
        }
        return named ?: uri.lastPathSegment
    }

    companion object {
        private const val PREFS = "cloud-drive-pdf-reader"
        private const val NIGHT = "night"
        private const val PAGED = "paged"
        private const val LAST_PAGE = "last:"
        private const val BATCH_MS = 150L

        private const val MENU_SHARE = 1
        private const val MENU_PRINT = 2
        private const val MENU_GOTO = 3
        private const val MENU_CONVERT = 4

        private val SAFE_LINK_SCHEMES = setOf("http", "https", "mailto", "tel")

        private val CHROME = 0xFF1B1F24.toInt()
        private val TEXT = 0xFFE6EAEE.toInt()
        private val HINT = 0xFF8A949E.toInt()

        /** The intent the Files tab uses: an explicit launch of this reader on a path it listed. */
        fun intent(context: Context, path: String): Intent =
            Intent(context, PdfReaderActivity::class.java)
                .setDataAndType(Uri.fromFile(File(path)), "application/pdf")
    }
}
