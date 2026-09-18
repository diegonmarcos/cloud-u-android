package com.diegonmarcos.superapp.appstore

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import com.diegonmarcos.superapp.updater.Fleet
import com.diegonmarcos.superapp.updater.VersionOrder
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlin.concurrent.thread

/**
 * Tap a Constellation row's "Details" action → this sheet. Two stacked
 * sections, INSTALLED then AVAILABLE, same fields in the same order, so the
 * one line that actually matters — installed versionCode next to available
 * versionCode — sits where a human can compare them without doing the
 * arithmetic in their head. That was the whole ask behind #496: the
 * cloud-notes incident this ticket is named for was a REFUSED downgrade that
 * showed up as a wall of prose in logcat instead of two numbers on screen.
 *
 * A READER, not a second implementation. Every fact on this sheet comes
 * straight out of [Fleet] / [VersionOrder] / ApkIntegrity (via [Fleet]'s
 * public wrappers — ApkIntegrity itself stays internal to libs:updater).
 * Nothing here recomputes a version ordering or an install decision; the one
 * comparison performed here ([VersionOrder.compare], in [renderDrift]) is the
 * same function [Fleet.commit] calls for the real gate — this sheet only
 * renders its answer earlier, for a candidate it fetched itself to find out.
 */
object ApkDetailSheet {

    private val cUpd = 0xFFED8936.toInt(); private val cBad = 0xFFF56565.toInt()
    private val cDim = 0xFF9B93AB.toInt(); private val cText = 0xFFECE6F5.toInt()

    fun show(activity: FragmentActivity, app: Fleet.App, state: Fleet.State?) {
        val ctx: Context = activity
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF140E1F.toInt())
        }
        root.addView(header(ctx, app))

        val pane = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(ctx).apply {
            isFillViewport = true
            addView(pane)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 460))
        })
        val dialog = BottomSheetDialog(ctx).apply { setContentView(root) }

        // INSTALLED renders instantly — one local PackageManager read, no network.
        val installedCard = card(ctx); pane.addView(installedCard)
        val installed = Fleet.installedDetails(ctx, app)
        renderInstalled(ctx, installedCard, installed)

        // AVAILABLE starts with only what [state] already carries (computed by
        // the last Check/checkAll pass — no new network call to open the
        // sheet), then fills in the release-asset facts (HEAD probe + sha256
        // sidecar) once they land.
        val availableCard = card(ctx); pane.addView(availableCard)
        renderAvailable(ctx, availableCard, app, state, installed, asset = null, sha256 = null) {
            verifyExact(activity, app, installed, availableCard)
        }

        pane.addView(note(ctx,
            "INSTALLED is read straight from PackageManager. AVAILABLE's size/timestamp/sha256 " +
            "come from one HEAD probe of the release asset. The available build's exact " +
            "versionCode is not knowable without fetching it — \"Check exact version\" below " +
            "does that fetch (the same verified download Install would do) and compares it " +
            "through VersionOrder, the one comparator Fleet.commit's downgrade gate also uses."))

        dialog.show()

        if (app.releaseUrl.isNotBlank()) {
            thread(name = "apk-detail-${app.id}") {
                val asset = Fleet.releaseAsset(app)
                val sha256 = Fleet.releaseSha256(app)
                root.post {
                    availableCard.removeAllViews()
                    renderAvailable(ctx, availableCard, app, state, installed, asset, sha256) {
                        verifyExact(activity, app, installed, availableCard)
                    }
                }
            }
        }
    }

    // ── INSTALLED ────────────────────────────────────────────────────────

    private fun renderInstalled(ctx: Context, into: LinearLayout, d: Fleet.InstalledDetails?) {
        into.addView(blockTitle(ctx, "Installed"))
        if (d == null) {
            into.addView(kv(ctx, "Status", "not installed on this device"))
            return
        }
        into.addView(kv(ctx, "Package", d.pkg))
        into.addView(kv(ctx, "Version", "${d.versionName}  (code ${d.versionCode})"))
        into.addView(kv(ctx, "First installed", ts(d.firstInstallAtMs)))
        into.addView(kv(ctx, "Last updated", ts(d.lastUpdateAtMs)))
        into.addView(kv(ctx, "APK size", human(d.bytes)))
        into.addView(kv(ctx, "sha256", d.sha256.ifBlank { "—" }))
        into.addView(kv(ctx, "Signing cert", d.signingCertSha256?.take(16)?.plus("…") ?: "—"))
        into.addView(kv(ctx, "Installer", d.installerPackage ?: "—"))
        into.addView(kv(ctx, "ABI", d.abis.joinToString().ifBlank { "—" }))
        into.addView(kv(ctx, "minSdk / targetSdk", "${d.minSdk} / ${d.targetSdk}"))
    }

    // ── AVAILABLE ────────────────────────────────────────────────────────

    private fun renderAvailable(
        ctx: Context, into: LinearLayout, app: Fleet.App, state: Fleet.State?,
        installed: Fleet.InstalledDetails?, asset: Fleet.ReleaseAsset?, sha256: String?,
        onVerifyExact: () -> Unit,
    ) {
        into.removeAllViews()
        into.addView(blockTitle(ctx, "Available"))
        if (state == null) { into.addView(kv(ctx, "Status", "not checked yet")); return }
        // Smart-cast to non-null Fleet.State from here on (the null branch above
        // already returned) — every read below is [state], not [state!!].
        when (state) {
            is Fleet.State.Installed -> {
                into.addView(kv(ctx, "Status", "up to date — nothing newer offered"))
                return
            }
            is Fleet.State.Blocked -> { into.addView(kv(ctx, "Status", "blocked — not installable")); return }
            is Fleet.State.Error -> { into.addView(kv(ctx, "Status", "check failed: ${state.message}")); return }
            else -> {}
        }
        val source = (state as? Fleet.State.UpdateAvailable)?.source
            ?: if (app.releaseUrl.isNotBlank()) "release" else "ghcr"
        into.addView(kv(ctx, "Served by", if (source == "release") "GitHub release" else "GHCR (OCI registry)"))
        when (state) {
            is Fleet.State.UpdateAvailable ->
                into.addView(kv(ctx, "Remote digest", "sha256:${state.remoteDigest12}…"))
            is Fleet.State.Missing ->
                into.addView(kv(ctx, "Status", "not installed — available to install"))
            else -> {}
        }
        into.addView(kv(ctx, "Size", human(state.bytes)))
        if (asset != null) {
            into.addView(kv(ctx, "Published", if (asset.publishedAtMillis > 0) ts(asset.publishedAtMillis) else "—"))
            into.addView(kv(ctx, "Release URL", app.abiReleaseUrl))
        } else if (source == "ghcr") {
            into.addView(kv(ctx, "GHCR page", app.ghcrPage.ifBlank { "—" }))
        }
        into.addView(kv(ctx, "sha256", sha256 ?: "— (not sidecar-published; digest above is authoritative)"))
        into.addView(kv(ctx, "Available versionCode", "unknown until fetched — see below"))

        val verify = action(ctx, "Check exact version", "fetch + verify, no install") { onVerifyExact() }
        into.addView(verify)
    }

    /**
     * Fetch the real candidate through the SAME verified pipeline [Fleet.install]
     * uses ([Fleet.download]: source ladder + sha/length verification), read
     * its manifest via [Fleet.candidateIdentity], then ask [VersionOrder] —
     * once — whether that is a downgrade. Nothing here decides anything; it
     * only shows what the engine already knows how to compute.
     */
    private fun verifyExact(
        activity: FragmentActivity, app: Fleet.App, installed: Fleet.InstalledDetails?,
        into: LinearLayout,
    ) {
        val ctx: Context = activity
        val status = TextView(ctx).apply {
            text = "Fetching…"; textSize = 12f; setTextColor(cDim)
            setPadding(0, dp(ctx, 6), 0, 0)
        }
        into.addView(status)
        thread(name = "apk-detail-verify-${app.id}") {
            val result = runCatching {
                val apk = Fleet.download(ctx, app)
                Fleet.candidateIdentity(ctx, apk.file)
            }
            status.post {
                val identity = result.getOrNull()
                if (identity == null) {
                    status.setTextColor(cBad)
                    status.text = "Could not read the candidate's manifest" +
                        (result.exceptionOrNull()?.message?.let { ": $it" } ?: "")
                    return@post
                }
                renderDrift(ctx, into, identity.versionCode, installed?.versionCode)
            }
        }
    }

    /** The one place this sheet compares two versionCodes — by asking
     *  [VersionOrder], never by subtracting them itself. */
    private fun renderDrift(ctx: Context, into: LinearLayout, candidateCode: Long, installedCode: Long?) {
        val order = VersionOrder.compare(candidateCode, installedCode)
        val (glyph, color, msg) = when (order) {
            VersionOrder.Order.OLDER -> Triple("⚠ DOWNGRADE", cBad,
                "available versionCode $candidateCode is OLDER than installed $installedCode. " +
                "Installing will ask you to confirm — refusing is the default.")
            VersionOrder.Order.NEWER -> Triple("⬆ update", cUpd,
                "available versionCode $candidateCode is newer than installed ${installedCode ?: "—"}.")
            VersionOrder.Order.SAME -> Triple("= same", cDim,
                "available versionCode $candidateCode matches what is installed — reinstalling repairs it.")
            VersionOrder.Order.UNKNOWN -> Triple("? unknown", cDim,
                "cannot compare (installed versionCode is unknown).")
        }
        into.addView(kv(ctx, "Available versionCode", "$candidateCode"))
        into.addView(TextView(ctx).apply {
            text = "$glyph — $msg"
            textSize = 12f; setTextColor(color); typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(ctx, 6), 0, 0)
        })
    }

    // ── small view helpers (this module's own flat/dark style; ContainerSheet
    //    lives in the app module and libs:appstore may not depend on it) ────

    private fun header(ctx: Context, app: Fleet.App) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(ctx, 18), dp(ctx, 16), dp(ctx, 18), dp(ctx, 8))
        addView(TextView(ctx).apply {
            text = app.label; setTextColor(cText); textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
        })
        addView(TextView(ctx).apply { text = app.pkg; setTextColor(cDim); textSize = 12f })
    }

    private fun card(ctx: Context) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            cornerRadius = dp(ctx, 14).toFloat()
            setColor(0xFF1C1C24.toInt())
            setStroke(dp(ctx, 1), 0xFF322A44.toInt())
        }
        setPadding(dp(ctx, 14), dp(ctx, 10), dp(ctx, 14), dp(ctx, 12))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(dp(ctx, 14), dp(ctx, 10), dp(ctx, 14), 0) }
    }

    private fun blockTitle(ctx: Context, text: String) = TextView(ctx).apply {
        this.text = text; setTextColor(0xFFB794F6.toInt()); textSize = 13f
        setTypeface(typeface, Typeface.BOLD); setPadding(0, 0, 0, dp(ctx, 4))
    }

    private fun kv(ctx: Context, k: String, v: String) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(ctx, 5), 0, 0)
        addView(TextView(ctx).apply { text = k; setTextColor(cDim); textSize = 12f },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(TextView(ctx).apply {
            text = v; setTextColor(cText); textSize = 12f
            gravity = Gravity.END; setTextIsSelectable(true)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.6f))
    }

    private fun action(ctx: Context, label: String, detail: String, onTap: () -> Unit) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        isClickable = true; isFocusable = true
        setPadding(0, dp(ctx, 10), 0, dp(ctx, 4))
        setOnClickListener { onTap() }
        addView(TextView(ctx).apply { text = label; setTextColor(0xFFB794F6.toInt()); textSize = 13f
            typeface = Typeface.DEFAULT_BOLD }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(TextView(ctx).apply { text = detail; setTextColor(cDim); textSize = 11f; gravity = Gravity.END })
    }

    private fun note(ctx: Context, text: String) = TextView(ctx).apply {
        this.text = text; setTextColor(cDim); textSize = 11f
        setLineSpacing(dp(ctx, 2).toFloat(), 1f)
        setPadding(dp(ctx, 18), dp(ctx, 12), dp(ctx, 18), dp(ctx, 16))
    }

    private fun human(b: Long): String = when {
        b <= 0L -> "—"
        b >= 1_000_000 -> String.format(java.util.Locale.US, "%.1f MB", b / 1_000_000.0)
        else -> String.format(java.util.Locale.US, "%.0f KB", b / 1000.0)
    }

    private fun ts(ms: Long): String =
        if (ms <= 0L) "—"
        else java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(ms))

    private fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()
}
