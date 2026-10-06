package com.diegonmarcos.superapp.network.mesh

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.diegonmarcos.superapp.bottomnav.NavPage
import com.diegonmarcos.superapp.bottomnav.PageTabs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * #877 THE Cloud Mesh page: libs:bottomnav's [PageTabs] strip over the declared pages
 * (`ui.mesh_page.pages`), one composable per page id, the notice line between them. The strip takes no
 * colour or size from here - the lib owns its look.
 *
 * [ticker] false leaves polling to the caller (tests drive [MeshStore.poll] themselves).
 */
@Composable
fun MeshScreen(store: MeshStore, modifier: Modifier = Modifier, ticker: Boolean = true) {
    if (ticker) MeshTicker(store)
    val pages = store.decl.pages.map { NavPage(it.id, declLabel("page", it.id, it.label), it.icon) }
    Column(modifier.fillMaxSize().testTag("mesh:screen")) {
        PageTabs(pages, store.page, onSelect = { store.page = it.id })
        MeshNotice(store)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (store.page) {
                "status" -> MeshStatusPage(store)
                "peers" -> MeshPeersPage(store)
                "profiles" -> MeshProfilesPage(store)
                "routes" -> MeshRoutesPage(store)
                "controls" -> MeshControlsPage(store)
                "log" -> MeshLogPage(store)
                else -> MText(store.page, Modifier.padding(MeshDensity.dp(MeshDensity.S8)), muted = true)
            }
        }
    }
}

@Composable
private fun MeshNotice(store: MeshStore) {
    if (store.notice.isBlank() && !store.busy) return
    MText(
        (if (store.busy) "… " else "") + store.notice,
        Modifier.fillMaxWidth().padding(horizontal = MeshDensity.dp(MeshDensity.S8)).testTag(MeshTags.NOTICE),
        size = MeshDensity.T_CAPTION, mono = true, muted = true, maxLines = 3,
    )
}

/**
 * The stats ticker: polls the engine every `poll_ms` while - and only while - the page's lifecycle is
 * RESUMED. Leaving the screen, locking the phone or switching app pauses it; the loop is cancelled, not
 * slept, so nothing runs in the background.
 */
@Composable
fun MeshTicker(store: MeshStore) {
    val owner = LocalLifecycleOwner.current
    var resumed by remember { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(owner) {
        val o = LifecycleEventObserver { _, _ -> resumed = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        owner.lifecycle.addObserver(o)
        onDispose { owner.lifecycle.removeObserver(o) }
    }
    LaunchedEffect(resumed) {
        if (!store.shouldPoll(resumed)) return@LaunchedEffect
        while (isActive) {
            withContext(Dispatchers.IO) { store.poll() }
            delay(store.decl.pollMs)
        }
    }
}
