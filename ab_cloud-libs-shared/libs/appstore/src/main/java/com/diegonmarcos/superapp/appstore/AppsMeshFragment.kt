package com.diegonmarcos.superapp.appstore

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.diegonmarcos.superapp.uikit.KitComposeFragment
import com.diegonmarcos.superapp.uikit.KitPalette

/**
 * #733 Configs ▸ Setup ▸ Network ▸ Apps Mesh (page id `apps-mesh`). The SAME
 * page Store ▸ Apps Mesh draws — both call [AppsMesh.Page] — hosted on its own.
 * There is no Store row to open from here, so `store` is not offered.
 */
class AppsMeshFragment : KitComposeFragment() {
    override fun palette(): KitPalette = StoreSearchPage.palette(requireContext())

    @Composable
    override fun Content() {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(sdp(StoreDensity.S12))) {
            // #1211 App API Endpoints moved to Store > Access > Apps MCP-API: not offered on this view.
            AppsMesh.Page(this@AppsMeshFragment, showEndpoints = false)
        }
    }
}
