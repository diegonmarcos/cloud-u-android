package com.diegonmarcos.clouddrive

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.Settings
import android.webkit.MimeTypeMap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.diegonmarcos.clouddrive.apps.AppsScreen
import com.diegonmarcos.clouddrive.backups.BackupsScreen
import com.diegonmarcos.clouddrive.backups.MirrorRunner
import com.diegonmarcos.clouddrive.configs.ConfigsScreen
import com.diegonmarcos.clouddrive.files.FilesController
import com.diegonmarcos.clouddrive.files.FilesScreen
import com.diegonmarcos.clouddrive.files.FilesUiState
import com.diegonmarcos.clouddrive.files.Places
import com.diegonmarcos.clouddrive.sync.GitSyncCoordinator
import com.diegonmarcos.clouddrive.sync.RcloneCoordinator
import com.diegonmarcos.clouddrive.sync.SyncScreen
import com.diegonmarcos.clouddrive.ui.DriveShell
import com.diegonmarcos.clouddrive.ui.DriveTheme
import com.diegonmarcos.clouddrive.ui.EmptyState
import com.diegonmarcos.clouddrive.ui.IconCatalog
import com.diegonmarcos.clouddrive.viewer.ImageViewerActivity
import com.diegonmarcos.superapp.updater.Updater
import java.io.File
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext

/**
 * #579 the chrome's host: a Compose activity that renders [DriveShell] and implements
 * [DriveActions] for the screens — the result launchers (SAF tree grant, engines), the
 * FileProvider hand-offs, the system intents. Nothing here draws; nothing in a screen
 * builds an Intent.
 */
class MainActivity : ComponentActivity(), DriveActions {

    private lateinit var prefs: DrivePrefs
    private var filesController: FilesController? = null

    private val openTreeLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? -> persistTreeGrant(uri) }

    /** The engines come back with a path to reveal in the active Files pane (#567 push 5). */
    private val engineLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val path = result.data?.getStringExtra(EngineActivity.RESULT_PATH) ?: return@registerForActivityResult
        filesController?.reveal(path)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            // Dark page top and bottom (Theme.CloudDrive, #336/#337) → light system-bar glyphs.
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        prefs = DrivePrefs(this)
        EngineActivity.declareFromBuild(this)
        setContent { DriveTheme { Root() } }
        Updater.start(this)
        // #575 the GitSync scheduler: repositories that opted in sync in the background.
        GitSyncWorker.schedule(this)
    }

    @Composable
    private fun Root() {
        val scope = rememberCoroutineScope()
        val ctx = LocalContext.current
        var hasAccess by remember { mutableStateOf(Places.hasAllFilesAccess(ctx)) }
        // Re-read the grant when the user comes back from the system settings screen.
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        DisposableEffect(lifecycle) {
            val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) hasAccess = Places.hasAllFilesAccess(ctx) }
            lifecycle.addObserver(observer)
            onDispose { lifecycle.removeObserver(observer) }
        }
        val saved = rememberSaveable { mutableStateOf("") }
        val files = remember {
            val snap = prefs.snapshot.value
            val (store, external) = Places.initialLocations()
            val initial = FilesUiState.decode(saved.value) ?: FilesUiState.initial(store, external, snap.defaultSort, snap.showHidden, snap.dualPane)
            FilesController(applicationContext, initial, scope, prefs).also { filesController = it }
        }
        val filesState by files.state.collectAsState()
        LaunchedEffect(filesState) { saved.value = filesState.encode() }
        val git = remember { GitSyncCoordinator(applicationContext, scope) }
        val rclone = remember { RcloneCoordinator(applicationContext, scope, prefs) }
        val mirrors = remember { MirrorRunner(scope, prefs) }
        val rcloneVersion by rclone.version.collectAsState()

        DriveShell { tabId, _ ->
            when (tabId) {
                "files" -> FilesScreen(files, this, hasAccess)
                "apps" -> AppsScreen(this)
                "sync" -> SyncScreen(git, rclone, prefs, this)
                "backups" -> BackupsScreen(mirrors, prefs)
                "configs" -> ConfigsScreen(prefs, this, hasAccess, rcloneVersion)
                else -> EmptyState(IconCatalog.vectorOrDefault(Declarations.iconDefault), stringResource(R.string.chrome_unknown_tab), "", Modifier)
            }
        }
    }

    // ── DriveActions ────────────────────────────────────────────────────────

    override fun requestStorageAccess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        runCatching { startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))) }
    }

    override fun requestTreeGrant() { runCatching { openTreeLauncher.launch(null) } }

    private fun persistTreeGrant(uri: Uri?) {
        if (uri == null) return
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        val name = runCatching { DocumentsContract.getTreeDocumentId(uri).substringBeforeLast(':') }.getOrNull() ?: uri.lastPathSegment ?: getString(R.string.files_place_sd_card)
        prefs.rememberTreeGrant(uri, name)
    }

    override fun openEngine(engine: String, target: String, url: String) {
        engineLauncher.launch(EngineActivity.intent(this, engine, target, url))
    }

    override fun openImage(path: String, siblings: List<String>) { startActivity(ImageViewerActivity.intent(this, path, siblings)) }

    /** #577 the fleet's ONE PDF reader (native pdfium); the Files tab's PDF rows open there. */
    override fun openPdf(path: String) { startActivity(PdfReaderActivity.intent(this, path)) }

    override fun openWith(path: String) {
        val file = File(path)
        runCatching {
            val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mimeOf(file)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }
    }

    override fun share(paths: List<String>) {
        val files = paths.map(::File).filter { it.isFile }
        if (files.isEmpty()) return
        runCatching {
            val uris = files.map { FileProvider.getUriForFile(this, "$packageName.files", it) }
            val intent = if (uris.size == 1) Intent(Intent.ACTION_SEND).setType(mimeOf(files.first())).putExtra(Intent.EXTRA_STREAM, uris.first())
            else Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            startActivity(Intent.createChooser(intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null))
        }
    }

    override fun shareText(title: String, text: String) {
        runCatching { startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, title).putExtra(Intent.EXTRA_TEXT, text), null)) }
    }

    override fun copyText(text: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("cloud-drive", text))
    }

    override fun openUrl(url: String) {
        val parsed = Uri.parse(url.trim())
        if (parsed.scheme != "http" && parsed.scheme != "https") return
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, parsed)) }
    }

    override fun launchApp(packageName: String, fallbackUrl: String): Boolean {
        val launch = if (packageName.isBlank()) null else runCatching { packageManager.getLaunchIntentForPackage(packageName) }.getOrNull()
        if (launch != null) return runCatching { startActivity(launch) }.isSuccess
        if (fallbackUrl.isNotBlank()) return runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(fallbackUrl))) }.isSuccess
        return false
    }

    private fun mimeOf(file: File): String = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"
}
