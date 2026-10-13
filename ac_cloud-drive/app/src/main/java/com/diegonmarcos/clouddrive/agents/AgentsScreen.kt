package com.diegonmarcos.clouddrive.agents

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.diegonmarcos.clouddrive.R
import com.diegonmarcos.clouddrive.ui.DriveTags
import com.diegonmarcos.clouddrive.ui.EmptyState
import com.diegonmarcos.clouddrive.ui.IconCatalog
import com.diegonmarcos.clouddrive.ui.ToolbarIsland

/**
 * AGENTS: the nav entry for the app's on-device agents. This page is a placeholder; the Screenshot
 * agent (OCR + ML metadata, contract in data/agent-screenshots-contract.json) fills it with its
 * status and controls.
 */
@Composable
fun AgentsScreen(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().testTag(DriveTags.AGENTS)) {
        ToolbarIsland(title = stringResource(R.string.agents_title))
        EmptyState(IconCatalog.vectorOrDefault("smart_toy"), stringResource(R.string.agents_placeholder), stringResource(R.string.agents_placeholder_hint), Modifier.weight(1f))
    }
}
