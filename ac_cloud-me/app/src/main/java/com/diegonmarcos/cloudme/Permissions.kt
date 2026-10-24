package com.diegonmarcos.cloudme

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Base64
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray

/** One runtime permission the app declares (`build.json::ui.permissions.runtime`, which mirrors the manifest). */
data class DeclaredPermission(val label: String, val perm: String, val required: Boolean, val minSdk: Int)

/** A declared permission as it stands now. [asked]: the system dialog has been shown for it at least once. */
data class PermState(val perm: DeclaredPermission, val granted: Boolean, val asked: Boolean, val rationale: Boolean) {
    /** Denied and the system will not show its dialog again: only the app settings can grant it. */
    val blocked: Boolean get() = !granted && asked && !rationale
}

/** What the app does about its required permissions at start: ask once, or point at the settings. */
data class GateDecision(val ask: List<String>, val banner: List<String>)

object Permissions {
    private const val PREFS = "permissions_asked"

    fun declared(): List<DeclaredPermission> = runCatching {
        val arr = JSONArray(String(Base64.decode(BuildConfig.UI_PERMISSIONS_RUNTIME_B64, Base64.DEFAULT)))
        (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map {
            DeclaredPermission(it.optString("label"), it.optString("perm"), it.optBoolean("required", true), it.optInt("min_sdk", 1))
        }.filter { it.perm.isNotBlank() && Build.VERSION.SDK_INT >= it.minSdk }
    }.getOrDefault(emptyList())

    fun state(ctx: Context, p: DeclaredPermission): PermState {
        val granted = ContextCompat.checkSelfPermission(ctx, p.perm) == PackageManager.PERMISSION_GRANTED
        val asked = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(p.perm, false)
        val rationale = (ctx as? Activity)?.let { ActivityCompat.shouldShowRequestPermissionRationale(it, p.perm) } ?: false
        return PermState(p, granted, asked, rationale)
    }

    fun states(ctx: Context): List<PermState> = declared().map { state(ctx, it) }

    /** Remember that the system dialog was shown, so the start-up prompt is never repeated for it. */
    fun markAsked(ctx: Context, perms: Collection<String>) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply { perms.forEach { putBoolean(it, true) } }.apply()
    }

    /**
     * The start-up rule. A required permission that is missing and was never asked for is asked for, ONCE, in one
     * system prompt. One that was already asked for and is still missing is never asked for again at start: it goes
     * to the banner, which links to the app settings (where a permanently denied permission can be granted).
     */
    fun gate(states: List<PermState>): GateDecision {
        val missing = states.filter { it.perm.required && !it.granted }
        return GateDecision(
            ask = missing.filter { !it.asked }.map { it.perm.perm },
            banner = missing.filter { it.asked }.map { it.perm.label },
        )
    }

    fun openSettings(ctx: Context) {
        ctx.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/** The shell's hooks for permissions: [request] shows the system dialog for one permission; [tick] changes when answers may have changed. */
class MePermissions(val request: (String) -> Unit, val openSettings: () -> Unit, val tick: Int)

val LocalMePermissions = staticCompositionLocalOf { MePermissions({}, {}, 0) }

object PermissionTags {
    const val BANNER = "me:perm:banner"
    const val BANNER_ACTION = "me:perm:banner:settings"
    fun row(perm: String) = "me:perm:$perm"
}

/** Configs > Permissions: every declared runtime permission, granted or denied; a tap requests it, or opens the app settings when the system will not ask. */
@Composable
fun PermissionsList(title: String, subtitle: String) {
    val ctx = LocalContext.current
    val hooks = LocalMePermissions.current
    val states = androidx.compose.runtime.remember(hooks.tick) { Permissions.states(ctx) }
    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        if (title.isNotBlank()) Text(title, color = colorResource(R.color.me_text), fontSize = 15.sp)
        if (subtitle.isNotBlank()) Text(subtitle, color = colorResource(R.color.me_text_dim), fontSize = 13.sp, modifier = Modifier.padding(top = 3.dp))
        if (states.isEmpty()) Text("None declared.", color = colorResource(R.color.me_text_dim), fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
        for (s in states) {
            val status = when { s.granted -> "granted"; s.blocked -> "denied, open settings"; else -> "denied, tap to allow" }
            Row(
                Modifier.fillMaxWidth().padding(top = 7.dp).testTag(PermissionTags.row(s.perm.perm))
                    .clickable { if (s.granted || s.blocked) hooks.openSettings() else hooks.request(s.perm.perm) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(s.perm.label, color = colorResource(R.color.me_text_dim), fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text(status, color = colorResource(if (s.granted) R.color.me_primary else R.color.me_text), fontSize = 13.sp)
            }
        }
    }
}

/** The strip under the top bar when a required permission was asked for and is still missing: one line and a way to the settings. */
@Composable
fun PermissionBanner(missing: List<String>) {
    if (missing.isEmpty()) return
    val hooks = LocalMePermissions.current
    Row(
        Modifier.fillMaxWidth().background(colorResource(R.color.me_surface_high)).padding(horizontal = 16.dp, vertical = 8.dp).testTag(PermissionTags.BANNER),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Permission needed: ${missing.joinToString()}", color = colorResource(R.color.me_text), fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text("Settings", color = colorResource(R.color.me_primary), fontSize = 13.sp,
            modifier = Modifier.clickable { hooks.openSettings() }.padding(8.dp).testTag(PermissionTags.BANNER_ACTION))
    }
}
