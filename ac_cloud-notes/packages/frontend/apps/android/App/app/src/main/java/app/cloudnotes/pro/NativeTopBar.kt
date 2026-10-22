package app.cloudnotes.pro

import android.webkit.JavascriptInterface
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.coordinatorlayout.widget.CoordinatorLayout
import com.diegonmarcos.superapp.ai.AiFleetChatPanel
import com.diegonmarcos.superapp.bottomnav.AppTopBarScaffold
import com.diegonmarcos.superapp.bottomnav.NavDecl
import com.diegonmarcos.superapp.bottomnav.TopBarDecl
import com.diegonmarcos.superapp.bottomnav.TopBarDrawer
import com.diegonmarcos.superapp.bottomnav.rememberAccountLauncher
import org.json.JSONArray
import kotlin.math.roundToInt

/** One page the editor reports for the drawer's tree: its id and title. */
internal data class DrawerDoc(val id: String, val title: String)

/**
 * What the web half pushes into the drawer: `window.CloudNotesHost.setDocs(json)` with
 * `[{"id","title"}, ...]` (see native-nav-bridge.tsx). Malformed JSON leaves the list as it was.
 */
internal object DrawerDocs {
    fun parse(json: String): List<DrawerDoc>? = runCatching {
        val a = JSONArray(json)
        (0 until a.length()).map {
            val o = a.getJSONObject(it)
            DrawerDoc(o.getString("id"), o.optString("title", "").ifBlank { "Untitled" })
        }
    }.getOrNull()
}

/**
 * Cloud Notes' first row: libs:bottomnav's AppTopBar (declared in build.json::ui.top_bar, read as
 * [TopBarDecl]): hamburger with the doc tree / workspace drawer, the title, AI (libs:ai's shared
 * chat) and Settings (Account launches Cloud Account, Configs opens Cloud Notes's own settings through
 * the web bridge). The Compose layer sits over the editor and passes every touch it does not use
 * through; it reports its height (status bar included) so [NativeNav] can place the page strip
 * and the WebView below it: this is the ONE source of the top inset.
 */
internal class NativeTopBar(
    private val decl: TopBarDecl,
    private val title: String,
    private val nav: NativeNav,
    private val navDecl: NavDecl,
    private val gatewayUrl: String,
    private val run: (String) -> Unit,
) {
    /** The editor's pages, newest first, as the web half last reported them. */
    private var docs by mutableStateOf(emptyList<DrawerDoc>())

    /** The object registered on the WebView as `CloudNotesHost`; the bridge thread may write the state. */
    inner class Host {
        @JavascriptInterface
        fun setDocs(json: String) {
            DrawerDocs.parse(json)?.let { docs = it }
        }
    }

    fun host(): Host = Host()

    fun attach(parent: CoordinatorLayout, onTopHeight: (Int) -> Unit) {
        val view = ComposeView(parent.context).apply {
            setContent {
                MaterialTheme(colorScheme = darkColorScheme()) { Bar(onTopHeight) }
            }
        }
        parent.addView(
            view,
            CoordinatorLayout.LayoutParams(
                CoordinatorLayout.LayoutParams.MATCH_PARENT,
                CoordinatorLayout.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    @Composable
    private fun Bar(onTopHeight: (Int) -> Unit) {
        Box(Modifier.fillMaxSize()) {
            AppTopBarScaffold(
                decl = decl,
                title = title,
                onAccount = rememberAccountLauncher(),
                onConfigs = { run(NativeNavScript.openSettings()) },
                modifier = Modifier.statusBarsPadding(),
                aiPanel = { AiFleetChatPanel(gatewayUrl) },
            ) {
                TopBarDrawer { close -> Drawer(close) }
                // The content slot starts where the bar ends: its top IS the top stack's height.
                Box(
                    Modifier.fillMaxSize().onGloballyPositioned {
                        onTopHeight(it.positionInRoot().y.roundToInt())
                    },
                )
            }
            // The status bar's own backdrop, so the bar reads as one surface with it.
            Box(
                Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars)
                    .background(MaterialTheme.colorScheme.surface),
            )
        }
    }

    @Composable
    private fun ColumnScope.Drawer(close: () -> Unit) {
        Eyebrow("Notes")
        navDecl.bottomSections().forEach { s ->
            NavigationDrawerItem(
                label = { Text(s.label) },
                selected = s.id == nav.sectionId,
                onClick = { nav.open(s.id); close() },
                modifier = Modifier.padding(horizontal = 12.dp).testTag("notes_drawer_section_${s.id}"),
            )
        }
        Eyebrow("Pages")
        if (docs.isEmpty()) {
            Text(
                "No pages yet",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp),
            )
        }
        docs.forEach { d ->
            NavigationDrawerItem(
                label = { Text(d.title, maxLines = 1) },
                selected = false,
                onClick = { run(NativeNavScript.openDoc(d.id)); close() },
                modifier = Modifier.padding(horizontal = 12.dp).testTag("notes_drawer_doc_${d.id}"),
            )
        }
    }

    @Composable
    private fun Eyebrow(text: String) {
        Text(
            text.uppercase(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp),
        )
    }
}
