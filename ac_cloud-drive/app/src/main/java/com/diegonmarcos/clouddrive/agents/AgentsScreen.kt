package com.diegonmarcos.clouddrive.agents

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.diegonmarcos.clouddrive.R
import com.diegonmarcos.clouddrive.ui.DriveTags

/**
 * AGENTS: the nav entry for the app's on-device agents. Its first (and today only) agent is the
 * Screenshot agent: OCR with a selectable text layer in the viewer, on-device ML labels, objects,
 * colours, barcodes and entities for every screenshot, written to the published contract
 * (data/agent-screenshots-contract.json, docs/screenshot-agent-contract.md). The page is that
 * agent's status and controls (ScreenshotAgentPage); a second agent becomes a second card here.
 */
@Composable
fun AgentsScreen(modifier: Modifier = Modifier) {
    ScreenshotAgentPage(title = stringResource(R.string.agents_title), modifier = modifier.testTag(DriveTags.AGENTS))
}
