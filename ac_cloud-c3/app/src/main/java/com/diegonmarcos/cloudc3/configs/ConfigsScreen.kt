package com.diegonmarcos.cloudc3.configs

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.diegonmarcos.cloudc3.BuildConfig
import com.diegonmarcos.cloudc3.Declarations
import com.diegonmarcos.cloudc3.R
import com.diegonmarcos.cloudc3.ui.C3Card
import com.diegonmarcos.cloudc3.ui.C3Metrics
import com.diegonmarcos.cloudc3.ui.C3Row
import com.diegonmarcos.cloudc3.ui.NotBuiltYet
import com.diegonmarcos.cloudc3.ui.PageStrip

/**
 * #648 CONFIGS — this app's own settings and About, from ONE declaration
 * (build.json::ui.configs.pages), drawn by the SAME shared PageStrip the other two content
 * tabs use.
 *
 * NOTE ON THE NAME "Watchdog": the SuperApp's Configs section independently grew a section
 * HEADING called "Watchdog" (#649) at the same time this app grew an Apps-tab entry of that
 * name pointing at the ac_c3-watchdog APK. They are two different things wearing one word.
 * Nothing is renamed here unilaterally — it is reported in the ticket for the owner to
 * settle, the way #381 handled "Messenger" naming two things.
 *
 * About is real in this commit because everything it shows is already baked; General is
 * declared and says so.
 */
@Composable
fun ConfigsScreen(reselectTick: Int) {
    val pages = Declarations.configsPages
    var selected by rememberSaveable { mutableStateOf(pages.firstOrNull()?.id ?: "") }

    Column(Modifier.fillMaxSize().testTag(TAG_CONFIGS)) {
        Spacer(Modifier.height(C3Metrics.gutter))
        PageStrip(pages = pages, selectedId = selected, onSelect = { selected = it })
        Spacer(Modifier.height(C3Metrics.gutter))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            // The ONE place this tab names a sub-page id; the tester diffs it against
            // ui.configs.pages in both directions, so a declared page with no body and a
            // body for an undeclared page are each a build failure.
            when (selected) {
                "about" -> AboutPage()
                else -> pages.firstOrNull { it.id == selected }?.let { NotBuiltYet(it) }
            }
        }
    }
}

/** Everything here is already baked by app/build.gradle, so it reports rather than claims. */
@Composable
private fun AboutPage() {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = C3Metrics.gutter),
    ) {
        item {
            C3Card(title = Declarations.tabs.firstOrNull { it.id == "configs" }?.label ?: "") {
                Column {
                    C3Row(stringResource(R.string.about_version), BuildConfig.VERSION_NAME)
                    C3Row(stringResource(R.string.about_commit), BuildConfig.GIT_SHORT_SHA)
                    C3Row(stringResource(R.string.about_build), BuildConfig.BUILD_TIMESTAMP)
                }
            }
        }
    }
}

internal const val TAG_CONFIGS: String = "c3_configs"
