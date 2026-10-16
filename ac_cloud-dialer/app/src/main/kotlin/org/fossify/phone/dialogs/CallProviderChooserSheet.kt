package org.fossify.phone.dialogs

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.ViewGroup
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row as LayoutRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentManager
import com.diegonmarcos.superapp.uikit.CloudKitTheme
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import org.fossify.commons.fragments.BaseBottomSheetDialogFragment
import org.fossify.phone.R
import org.fossify.phone.extensions.dialerKitPalette

/**
 * Cloud Dialer: bottom-sheet call-provider chooser — one row per way to reach
 * the dialed number (cellular + every available VoIP/deep-link provider from
 * assets/call_providers.json), each with the provider app's real launcher icon.
 *
 * Exists because commons' SimpleListItem sheet only supports resource-id text
 * and icons, while provider labels/icons are runtime values. Rows are passed
 * as a property (not fragment arguments): on system re-creation the lambdas
 * are gone, so the sheet dismisses itself — the same trade-off upstream's
 * DynamicBottomSheetChooserDialog.onItemClick already makes.
 *
 * 2026-10-16 owner decision: all apps must be Compose. The rows are composables under CloudKitTheme
 * (libs:ui-kit) hosted in the sheet commons draws.
 */
class CallProviderChooserSheet : BaseBottomSheetDialogFragment() {

    class Row(val icon: Drawable?, val label: String, val onTap: () -> Unit)

    var rows: List<Row> = emptyList()

    override fun setupContentView(parent: ViewGroup) {
        if (rows.isEmpty()) {
            dismissAllowingStateLoss()
            return
        }
        val palette = requireContext().dialerKitPalette()
        parent.addView(ComposeView(requireContext()).apply {
            setContent {
                CloudKitTheme(palette) {
                    Column(Modifier.fillMaxWidth()) {
                        rows.forEach { row ->
                            ProviderRow(row) {
                                row.onTap()
                                dismissAllowingStateLoss()
                            }
                        }
                    }
                }
            }
        })
    }

    @Composable
    private fun ProviderRow(row: Row, onClick: () -> Unit) {
        val p = LocalKitPalette.current
        val icon = remember(row.icon) { row.icon?.let { iconBitmap(it) } }
        LayoutRow(
            Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Image(bitmap = icon.asImageBitmap(), contentDescription = null, modifier = Modifier.size(40.dp))
            } else {
                Spacer(Modifier.size(40.dp))
            }
            Text(row.label, color = p.textPrimary, fontSize = 16.sp, modifier = Modifier.padding(start = 16.dp))
        }
    }

    /** The provider app's launcher icon, drawn once into a bitmap at the row's 40dp size. */
    private fun iconBitmap(d: Drawable): Bitmap {
        val px = (ICON_DP * resources.displayMetrics.density).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        d.setBounds(0, 0, px, px)
        d.draw(canvas)
        return bmp
    }

    companion object {
        private const val TAG = "CallProviderChooserSheet"
        private const val ICON_DP = 40

        fun show(fragmentManager: FragmentManager, rows: List<Row>) {
            CallProviderChooserSheet().apply {
                arguments = Bundle().apply {
                    putInt(BOTTOM_SHEET_TITLE, R.string.call_using)
                }
                this.rows = rows
                show(fragmentManager, TAG)
            }
        }
    }
}
