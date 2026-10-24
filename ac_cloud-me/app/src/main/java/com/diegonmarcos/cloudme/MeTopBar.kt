package com.diegonmarcos.cloudme

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.superapp.bottomnav.TopBarDecl
import com.diegonmarcos.superapp.bottomnav.TopBarDrawer
import com.diegonmarcos.superapp.bottomnav.TopBarSettings

/**
 * The first row of every page is libs:bottomnav's AppTopBar (ticket #1150), in its inline-actions form: a hamburger at
 * the left that opens the left side menu BY TAP ONLY, and icons without labels at the right: AI (sparkles),
 * Profile (round avatar) and Configs (vertical dots). There is no edge-swipe anywhere (#1144 removed one that
 * hijacked scrolling; MeShellTest holds that). Profile and Configs open Cloud Me's own sections (`ui.settings`), not
 * Cloud Account, so the declaration is built here rather than in build.json::ui.top_bar.
 */
object MeTopBar {
    val decl = TopBarDecl(
        drawer = true,
        settings = listOf(TopBarSettings.ACCOUNT, TopBarSettings.CONFIGS),
        inlineActions = true,
    )

    /** The AI button hands over to the SuperApp, whose assistant it is; Cloud Me links no chat of its own. */
    const val AI_TARGET = "extapp:cloud-superapp/ai"

    fun drawerItem(sectionId: String) = "me:drawer:$sectionId"
}

/** The left side menu: every section of the app, the bar's and the Settings entries'. A tap opens it and closes the menu. */
@Composable
fun MeDrawer(onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    TopBarDrawer { close ->
        for (s in Sections.all()) {
            @Suppress("DiscouragedApi")
            val icon = ctx.resources.getIdentifier(s.icon, "drawable", ctx.packageName)
            Row(
                Modifier.fillMaxWidth().clickable { onOpen(s.id); close() }.padding(horizontal = 20.dp, vertical = 14.dp)
                    .testTag(MeTopBar.drawerItem(s.id)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (icon != 0) Icon(painterResource(icon), null, Modifier.size(22.dp), tint = colorResource(R.color.me_text_dim))
                Text(s.label, Modifier.padding(start = 16.dp), color = colorResource(R.color.me_text), fontSize = 16.sp, maxLines = 1)
            }
        }
    }
}
