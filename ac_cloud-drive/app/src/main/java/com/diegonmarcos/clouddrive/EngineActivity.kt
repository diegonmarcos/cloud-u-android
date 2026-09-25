package com.diegonmarcos.clouddrive

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import com.diegonmarcos.cloudlib.fileeditor.FileEditorScreen
import com.diegonmarcos.cloudlib.gitsync.GitSyncScreen
import com.diegonmarcos.cloudlib.mounts.MountSpec
import com.diegonmarcos.cloudlib.mounts.MountStore
import com.diegonmarcos.cloudlib.mounts.MountUri
import com.diegonmarcos.cloudlib.mounts.MountsScreen
import com.diegonmarcos.cloudlib.rclone.RcloneConfig
import com.diegonmarcos.cloudlib.rclone.RcloneJob
import com.diegonmarcos.cloudlib.rclone.RcloneJobStore
import com.diegonmarcos.cloudlib.rclone.RcloneRemote
import com.diegonmarcos.cloudlib.rclone.RcloneRunner
import com.diegonmarcos.cloudlib.rclone.RcloneScreen
import java.io.File
import org.json.JSONArray

/**
 * The CHROME's one door into the four engines (#567 push 5). The drive stays a
 * WebView page; this activity is the Compose host each engine library's screen
 * renders in. The page asks the bridge for an engine by id, the bridge starts
 * this activity, and the engine's "open this file" hand-off comes back as the
 * result so the page can reveal it. Nothing in any library names this class.
 *
 * Engine ids are the ONE vocabulary the page, the bridge and this `when` share;
 * test/test-drive-engine-wiring.sh derives the set from the page and checks it
 * here, so an id the page uses and this activity does not route fails the build.
 */
class EngineActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val engine = intent.getStringExtra(EXTRA_ENGINE) ?: ""
        // #575: the git manager with no target opens ON the shared store, so an "add
        // repository" from there lands under <shared_root>/… and nowhere else.
        val target = intent.getStringExtra(EXTRA_TARGET)?.takeIf { it.isNotBlank() }
            ?: if (engine == ENGINE_GIT) SharedStore.root().absolutePath else null
        val cloneUrl = intent.getStringExtra(EXTRA_URL)?.takeIf { it.isNotBlank() }
        declareFromBuild(this)
        val openFile: (String) -> Unit = { path ->
            setResult(Activity.RESULT_OK, Intent().putExtra(RESULT_PATH, path))
            finish()
        }
        val close: () -> Unit = { finish() }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                EngineRoute(engine, target, cloneUrl, openFile, close)
            }
        }
    }

    @Composable
    private fun EngineRoute(engine: String, target: String?, cloneUrl: String?, onOpenFile: (String) -> Unit, onClose: () -> Unit) {
        when (engine) {
            ENGINE_GIT -> GitSyncScreen(target, onOpenFile, onClose, cloneUrl = cloneUrl)
            ENGINE_EDITOR -> FileEditorScreen(target, onOpenFile, onClose)
            ENGINE_RCLONE -> RcloneScreen(target, onOpenFile, onClose)
            ENGINE_MOUNTS -> MountsScreen(target, onOpenFile, onClose)
            else -> { finish() }
        }
    }

    companion object {
        const val EXTRA_ENGINE = "engine"
        const val EXTRA_TARGET = "target"
        /** #575 a clone URL for the git engine; empty for every other engine. */
        const val EXTRA_URL = "url"
        const val RESULT_PATH = "open_path"
        const val ENGINE_GIT = "git"
        const val ENGINE_EDITOR = "editor"
        const val ENGINE_RCLONE = "rclone"
        const val ENGINE_MOUNTS = "mounts"

        fun intent(context: Context, engine: String, target: String, url: String = ""): Intent =
            Intent(context, EngineActivity::class.java).putExtra(EXTRA_ENGINE, engine).putExtra(EXTRA_TARGET, target).putExtra(EXTRA_URL, url)

        /**
         * Hand the engines the drive's build-time declarations, as the models the
         * libraries define (lib -> app is forbidden, so the mapping lives HERE):
         *  - data/drive-rclone-jobs.json → RcloneJobStore.declare: every job whose
         *    `kind` is an rclone verb the engine runs (copy/sync/move/check/bisync);
         *    a `mount` kind is an always-on fleet sidecar, not a phone job, and stays
         *    on the page as information.
         *  - data/drive-connections.json → MountStore.declare: every connection that
         *    carries a `uri` this engine speaks (sftp/ssh/ftp/ftps/dav/davs).
         *  - #575 data/drive-remotes.json → RcloneConfig.declare: every remote that
         *    carries an `rclone` block lands in the phone's rclone.conf as a
         *    secret-less skeleton (type + endpoint), added once and never
         *    overwritten — the key the user adds on the device stays.
         *  - #575 a job's local leg written relative (no leading `/`, no `:`) is a
         *    folder of the shared store: SharedStore.resolve.
         * Idempotent: called on every engine open, so a data edit lands on the next
         * build with no migration.
         */
        fun declareFromBuild(context: Context) {
            fun decode(b64: String): JSONArray = runCatching { JSONArray(String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8)) }.getOrElse { JSONArray() }
            val remotes = decode(BuildConfig.RCLONE_REMOTES_B64)
            val declaredRemotes = (0 until remotes.length()).mapNotNull { i ->
                val r = remotes.optJSONObject(i) ?: return@mapNotNull null
                val spec = r.optJSONObject("rclone") ?: return@mapNotNull null
                val name = r.optString("name").ifBlank { return@mapNotNull null }
                val type = spec.optString("type").ifBlank { return@mapNotNull null }
                if (!RcloneConfig.isValidName(name)) return@mapNotNull null
                val options = spec.optJSONObject("options")?.let { o -> o.keys().asSequence().associateWith { k -> o.optString(k) } } ?: emptyMap()
                RcloneRemote(name, type, options)
            }
            RcloneConfig.declare(RcloneRunner(context).configFile, declaredRemotes)

            val jobs = decode(BuildConfig.RCLONE_JOBS_B64)
            val declaredJobs = (0 until jobs.length()).mapNotNull { i ->
                val j = jobs.optJSONObject(i) ?: return@mapNotNull null
                val kind = j.optString("kind")
                if (kind !in RcloneJob.OPS) return@mapNotNull null
                val name = j.optString("name").ifBlank { return@mapNotNull null }
                RcloneJob(
                    id = "declared-" + name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-'),
                    name = name, op = kind, source = SharedStore.resolve(j.optString("source")), destination = SharedStore.resolve(j.optString("destination")),
                    flags = j.optString("flags").split(Regex("\\s+")).filter { it.isNotEmpty() }, declared = true,
                )
            }
            RcloneJobStore(File(context.filesDir, "rclone/jobs.json")).declare(declaredJobs)

            val connections = decode(BuildConfig.CONNECTIONS_B64)
            val declaredMounts = (0 until connections.length()).mapNotNull { i ->
                val c = connections.optJSONObject(i) ?: return@mapNotNull null
                val uri = c.optString("uri").ifBlank { return@mapNotNull null }
                MountUri.parse(uri, name = c.optString("name"))?.let { m: MountSpec -> m.copy(declared = true) }
            }
            MountStore(File(context.filesDir, "mounts/mounts.json")).declare(declaredMounts)
        }
    }
}
