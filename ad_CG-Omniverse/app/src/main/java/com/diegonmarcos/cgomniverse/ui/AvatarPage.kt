@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.diegonmarcos.cgomniverse.ui

import android.app.backup.BackupManager
import android.content.Context
import android.webkit.WebView
import com.diegonmarcos.cloudlib.web.CloudWeb
import com.diegonmarcos.cloudlib.web.CloudWebClient
import com.diegonmarcos.cloudlib.web.WebProfile
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.diegonmarcos.cgomniverse.Avatar
import com.diegonmarcos.cgomniverse.Decl
import com.diegonmarcos.cgomniverse.R
import com.diegonmarcos.superapp.uikit.KitTextButton
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import org.json.JSONObject

/** Where the configuration is kept: its own small prefs file, the one file the backup rules (res/xml) take. */
const val AVATAR_PREFS = "avatar"
private const val AVATAR_KEY = "config"

/**
 * The Avatar configurator: the 3D scene (assets/avatar, three.js in a WebView: drag rotates, pinch zooms) over the declared groups
 * of data/avatar.json. Every change is saved at once and announced to Android's backup.
 */
@Composable
fun AvatarPage() {
    val ctx = LocalContext.current
    val spec = Decl.avatar
    val prefs = remember { ctx.getSharedPreferences(AVATAR_PREFS, Context.MODE_PRIVATE) }
    var cfg by remember { mutableStateOf(Avatar.load(spec, prefs.getString(AVATAR_KEY, null))) }
    var groupId by rememberSaveable { mutableStateOf(spec.groups.firstOrNull()?.id.orEmpty()) }
    var web by remember { mutableStateOf<WebView?>(null) }
    var ready by remember { mutableStateOf(false) }
    val p = LocalKitPalette.current

    fun update(next: Map<String, String>) {
        if (next == cfg) return
        cfg = next
        prefs.edit().putString(AVATAR_KEY, Avatar.save(next)).apply()
        BackupManager(ctx).dataChanged()
    }

    LaunchedEffect(cfg, ready) {
        if (ready) web?.evaluateJavascript("window.applyAvatar&&window.applyAvatar(" + JSONObject.quote(Avatar.sceneJson(spec, cfg)) + ")", null)
    }
    DisposableEffect(Unit) { onDispose { web?.destroy(); web = null } }

    Column(Modifier.fillMaxSize().testTag(Tags.AVATAR)) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                factory = { c ->
                    CloudWeb.create(c, WebProfile.WEB_WORLD).also { wv ->
                        wv.webViewClient = object : CloudWebClient(WebProfile.WEB_WORLD, CloudWeb.assetLoader(c)) {
                            override fun onPageFinished(view: WebView, url: String) { ready = true }
                            override fun onRecovered(dead: WebView, fresh: WebView?) { web = fresh; ready = false }
                        }
                        wv.loadUrl(Web.AVATAR_URL)
                        web = wv
                    }
                },
                modifier = Modifier.fillMaxSize().testTag(Tags.AVATAR_SCENE),
            )
            Text(
                stringResource(R.string.avatar_hint), style = MaterialTheme.typography.labelSmall, color = p.textSecondary,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
            )
        }
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)).background(p.surface).padding(vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(spec.groups, key = { it.id }) { g ->
                    FilterChip(selected = g.id == groupId, onClick = { groupId = g.id }, label = { Text(g.label) },
                        modifier = Modifier.testTag(Tags.avatarGroup(g.id)))
                }
            }
            val group = spec.groups.firstOrNull { it.id == groupId }
            if (group != null) {
                LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    items(group.options, key = { it.id }) { o ->
                        val selected = cfg[group.id] == o.id
                        val pick = { update(Avatar.set(spec, cfg, group.id, o.id)) }
                        if (group.kind == Avatar.KIND_COLOR && o.color != null) {
                            Box(
                                Modifier.size(40.dp).clip(CircleShape)
                                    .border(if (selected) 3.dp else 1.dp, if (selected) p.accent else p.hairline, CircleShape)
                                    .padding(if (selected) 5.dp else 2.dp).clip(CircleShape)
                                    .background(Color(android.graphics.Color.parseColor(o.color)))
                                    .clickable(onClick = pick).testTag(Tags.avatarOption(group.id, o.id)),
                            )
                        } else {
                            FilterChip(selected = selected, onClick = pick, modifier = Modifier.testTag(Tags.avatarOption(group.id, o.id)),
                                label = { Text(o.label, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal) })
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    group?.options?.firstOrNull { it.id == cfg[group.id] }?.label.orEmpty(),
                    style = MaterialTheme.typography.bodySmall, color = p.textSecondary, modifier = Modifier.weight(1f),
                )
                KitTextButton(onClick = { update(Avatar.load(spec, null)) }, modifier = Modifier.testTag(Tags.AVATAR_RESET)) {
                    Text(stringResource(R.string.avatar_reset))
                }
            }
        }
    }
}
