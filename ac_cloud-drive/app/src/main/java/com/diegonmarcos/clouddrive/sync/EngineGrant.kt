package com.diegonmarcos.clouddrive.sync

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.diegonmarcos.clouddrive.R
import com.diegonmarcos.clouddrive.ui.DriveCard
import com.diegonmarcos.clouddrive.ui.DriveMetrics
import com.diegonmarcos.clouddrive.ui.Pill
import com.diegonmarcos.clouddrive.ui.PillRow
import com.diegonmarcos.cloudlib.gitsync.GitLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * #1358 THE GIT ENGINE'S ALL FILES ACCESS, asked up front.
 *
 * Two apps run git for this page. A repository inside the shared store (cld.termux's private home) runs in cld.termux
 * through CLOUD_EXEC and needs no all-files grant. A repository in SHARED STORAGE (/storage/emulated/0/..., the Obsidian
 * vaults) runs in Cloud-Lib-Git-Sync-Engine.apk ([PACKAGE]), another uid, which reads and writes shared storage only with
 * its OWN MANAGE_EXTERNAL_STORAGE app-op. Drive's grant does not cover it. Without it every verb answered "the git sync
 * engine has no All files access", which the row classified as "the git engine did not answer".
 *
 * The engine itself is the only reliable reader of its grant (AppOps refuses another uid's op without a privileged
 * permission), so [refresh] asks its `info` call ({files_access}) on IO and [state] holds the answer for the banner
 * ([EngineGrantBanner]), the first-run prompt ([EngineGrantPrompt]), Configs > General > Perms (check `engine_all_files`)
 * and the row reason ([REASON]). The pure parts ([fromInfo], [touchesSharedStorage]) are JVM-tested (EngineGrantTest).
 */
object EngineGrant {
    enum class Grant { UNKNOWN, NOT_INSTALLED, MISSING, GRANTED }

    /** The engine's package (libs:git-sync resolves it from the fleet manifest at build time). */
    val PACKAGE: String get() = com.diegonmarcos.cloudlib.gitsync.BuildConfig.GITSYNC_ENGINE_PACKAGE

    /** The row reason when the grant is missing: RepoStatus.errorKind reads "all files access" as FILES_ACCESS. */
    const val REASON = "the git sync engine has no All files access, so it cannot read or write shared storage"

    private val _state = MutableStateFlow(Grant.UNKNOWN)
    val state: StateFlow<Grant> = _state

    /** Pure: the grant from the engine's `info` answer (null = no answer). An answer without `files_access` (an old engine) says nothing. */
    fun fromInfo(info: JSONObject?, sdk: Int = Build.VERSION.SDK_INT): Grant = when {
        sdk < 30 -> Grant.GRANTED
        info == null || !info.optBoolean("ok", false) || !info.has("files_access") -> Grant.UNKNOWN
        info.optBoolean("files_access", false) -> Grant.GRANTED
        else -> Grant.MISSING
    }

    /** Pure: the engine's own rule (GitCalls.touchesSharedStorage) for the paths that need its grant. */
    fun touchesSharedStorage(path: String): Boolean =
        path.startsWith("/storage/") || path.startsWith("/sdcard") || path.startsWith("/mnt/")

    /** The row is in shared storage and the engine is known not to hold the grant. */
    fun blocks(path: String): Boolean = _state.value == Grant.MISSING && touchesSharedStorage(path)

    /** Blocking (binds the engine): call on IO. Updates [state] and returns it. */
    fun probe(ctx: Context): Grant {
        val g = if (Build.VERSION.SDK_INT < 30) Grant.GRANTED else {
            val link = GitLink(ctx)
            if (link.unavailable() != null) Grant.NOT_INSTALLED
            else fromInfo(runCatching { link.session().use { it.info() } }.getOrNull())
        }
        _state.value = g
        return g
    }

    suspend fun refresh(ctx: Context): Grant = withContext(Dispatchers.IO) { probe(ctx.applicationContext) }

    /** The engine's launcher label (what Settings lists), else its package. */
    fun label(ctx: Context): String = runCatching {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(PACKAGE, 0)).toString()
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: PACKAGE

    /** The settings screens, in order: the engine's own All files access toggle, the list, its App info. */
    fun intents(pkg: String = PACKAGE): List<Intent> = listOf(
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$pkg")),
        Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")),
    )

    /** Opens the first settings screen that starts. */
    fun open(ctx: Context): Boolean = intents().any { i -> runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess }

    const val TAG_BANNER = "engine_grant_banner"
    const val TAG_BUTTON = "engine_grant_open"
    private const val PREFS = "cloud-drive-prefs"
    private const val KEY_PROMPTED = "engine_grant.prompted"

    fun prompted(ctx: Context): Boolean = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_PROMPTED, false)
    fun markPrompted(ctx: Context) { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_PROMPTED, true).apply() }
}

/** Re-asks the engine every time the host resumes (the owner comes back from Settings), and returns the live grant. */
@Composable
fun rememberEngineGrant(): EngineGrant.Grant {
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { runCatching { EngineGrant.refresh(ctx) } } }
    val g by EngineGrant.state.collectAsState()
    return g
}

/** THE ONE BANNER of the Git page: shown only while the engine is installed and lacks the grant. */
@Composable
fun EngineGrantBanner() {
    val ctx = LocalContext.current
    if (rememberEngineGrant() != EngineGrant.Grant.MISSING) return
    val label = remember { EngineGrant.label(ctx) }
    DriveCard(stringResource(R.string.engine_grant_title, label), Modifier.testTag(EngineGrant.TAG_BANNER), tag = EngineGrant.TAG_BANNER) {
        Text(stringResource(R.string.engine_grant_why, EngineGrant.PACKAGE), Modifier.padding(top = DriveMetrics.gap), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        PillRow { Pill(stringResource(R.string.engine_grant_button), { EngineGrant.open(ctx) }, Modifier.testTag(EngineGrant.TAG_BUTTON), filled = true) }
    }
}

/** The first-run prompt, next to Drive's own All files access request: once, and only when the engine says it lacks the grant. */
@Composable
fun EngineGrantPrompt() {
    val ctx = LocalContext.current
    val g = rememberEngineGrant()
    var dismissed by remember { mutableStateOf(EngineGrant.prompted(ctx)) }
    if (dismissed || g != EngineGrant.Grant.MISSING) return
    val label = remember { EngineGrant.label(ctx) }
    val done = { EngineGrant.markPrompted(ctx); dismissed = true }
    AlertDialog(
        onDismissRequest = done,
        title = { Text(stringResource(R.string.engine_grant_title, label)) },
        text = { Text(stringResource(R.string.engine_grant_why, EngineGrant.PACKAGE)) },
        confirmButton = { TextButton(onClick = { done(); EngineGrant.open(ctx) }, modifier = Modifier.testTag(EngineGrant.TAG_BUTTON)) { Text(stringResource(R.string.engine_grant_button)) } },
        dismissButton = { TextButton(onClick = done) { Text(stringResource(R.string.engine_grant_later)) } },
        modifier = Modifier.testTag(EngineGrant.TAG_BANNER + "_prompt"),
    )
}
