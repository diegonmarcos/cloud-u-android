package com.diegonmarcos.superapp.cloud
import com.diegonmarcos.superapp.BuildConfig
import com.diegonmarcos.superapp.launcher.Sections
import com.diegonmarcos.superapp.launcher.TileGridFragment
import com.diegonmarcos.superapp.R

import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment

/**
 * Drive · Connections — TWO sections, Apps then Configs.
 *
 * APPS is the app row, resolved by [Sections.appTilesFor] from what the page
 * DECLARES in build.json (`apps_from_tile_group` + `apps_extra_tile_ids`).
 * The tiles are the launcher's own, so each icon carries the target the
 * launcher would fire and dispatches it through the same
 * [TileGridFragment.TileClickListener] every other tile surface uses — which
 * is the point: an app that is not installed gets the shell's existing
 * install-or-explain handling (extapp: offers the APK, app:// falls back to
 * its store URL, http(s): opens in the embedded browser) rather than a
 * bespoke one that would have to be kept in step with it.
 *
 * CONFIGS is the declarative listing of every storage backend reachable from
 * the stack: S3 (MinIO), Google Drive / Workspace, rclone remotes, Borg/Bup
 * backup servers, filebrowser HTTP, hedgedoc / vaultwarden attachment stores,
 * IMAP attachment blobs, etc. It is the whole of what this page showed before
 * Apps landed above it, moved under a heading and otherwise untouched.
 *
 * Data: build.json::ui.drive_connections (baked into BuildConfig via
 * app/build.gradle, parsed by [Sections.driveConnections]).
 *
 * Status dot:
 *   ok      green
 *   warn    amber
 *   down    red
 *   unknown grey
 */
class DriveConnectionsFragment : Fragment(R.layout.fragment_drive_connections) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val root   = view.findViewById<LinearLayout>(R.id.drive_root)
        val status = view.findViewById<TextView>(R.id.drive_status)
        val inflater = LayoutInflater.from(requireContext())

        buildAppsSection(view, inflater)

        val conns = Sections.driveConnections()
        val pub  = conns.count { it.scope == "public" }
        val priv = conns.count { it.scope == "private" }
        status.text = getString(R.string.drive_connections_status, conns.size, pub, priv)

        for (c in conns) {
            val row = inflater.inflate(R.layout.item_drive_connection, root, false)
            row.findViewById<View>(R.id.dc_status_dot).background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(when (c.status) {
                    "ok"   -> 0xFF2E7D32.toInt()
                    "warn" -> 0xFFEF6C00.toInt()
                    "down" -> 0xFFC62828.toInt()
                    else   -> 0xFF9E9E9E.toInt()
                })
            }
            row.findViewById<TextView>(R.id.dc_name).text     = c.name
            row.findViewById<TextView>(R.id.dc_kind).text     = "${c.kind} · ${c.scope}"
            row.findViewById<TextView>(R.id.dc_endpoint).text = c.endpoint
            row.findViewById<TextView>(R.id.dc_auth).text     = "auth: ${c.auth}"
            row.findViewById<TextView>(R.id.dc_vm).text       = c.vm
            row.findViewById<TextView>(R.id.dc_notes).text    = c.notes
            root.addView(row)
        }
    }

    /**
     * Fill the Apps row from this page's own declaration.
     *
     * The whole section — heading, row and all — is hidden when the page
     * declares no apps or every reference resolves to nothing. An empty
     * "Apps" heading over a blank strip would say the page has apps and then
     * fail to show one, which is worse than the section not being there.
     */
    private fun buildAppsSection(view: View, inflater: LayoutInflater) {
        val ctx = requireContext()
        val page = Sections.byId(SECTION_ID)?.allPages?.firstOrNull { it.id == PAGE_ID }
        val tiles = page?.let { Sections.appTilesFor(it) }.orEmpty()

        val header = view.findViewById<TextView>(R.id.drive_apps_header)
        val scroll = view.findViewById<View>(R.id.drive_apps_scroll)
        if (tiles.isEmpty()) {
            header.visibility = View.GONE
            scroll.visibility = View.GONE
            return
        }

        val row = view.findViewById<LinearLayout>(R.id.drive_apps_row)
        for (tile in tiles) {
            // item_tile is the app's one tile layout — the same one
            // TileGridFragment and HomeGroupedFragment inflate, so an app icon
            // here is the app icon the user already knows. Its 128dp default
            // height only applies outside a weight-based parent, which is
            // exactly this row, so the width is pinned to match the six
            // tiles-per-screen density every other strip uses.
            val cell = inflater.inflate(R.layout.item_tile, row, false)
            cell.layoutParams = LinearLayout.LayoutParams(dp(64), dp(84))
            cell.findViewById<TextView>(R.id.tile_label).text = tile.label
            cell.findViewById<FrameLayout>(R.id.tile_icon_bg).background = null
            cell.findViewById<ImageView>(R.id.tile_icon).apply {
                setImageResource(Sections.iconResFor(ctx, tile.iconName)
                    .takeIf { it != 0 } ?: R.drawable.ic_settings)
                imageTintList = ColorStateList.valueOf(0xFFE9D8FD.toInt())
            }
            cell.setOnClickListener { v ->
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                (activity as? TileGridFragment.TileClickListener)?.onTileClicked(tile.target)
            }
            row.addView(cell)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        /** Where this page is declared, so [buildAppsSection] can read its own
         *  entry back out of build.json. The pair that [SectionPages.factoryFor]
         *  routes to this fragment — one page, one declaration, one reader. */
        private const val SECTION_ID = "drive"
        private const val PAGE_ID = "connections"

        fun newInstance() = DriveConnectionsFragment()
    }
}
