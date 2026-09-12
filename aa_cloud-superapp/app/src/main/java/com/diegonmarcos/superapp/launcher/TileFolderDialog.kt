package com.diegonmarcos.superapp.launcher

import android.app.Dialog
import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.diegonmarcos.superapp.apps.launcherFolderDialog
import com.diegonmarcos.superapp.apps.setFolderContent
import com.diegonmarcos.superapp.ui.Haptics

/**
 * The popup behind a FOLDER tile on an aggregator page (Cloud ▸ Apps today).
 *
 * A folder is a tile whose [Sections.AggTile.children] is non-empty. Tapping it
 * opens this instead of navigating, and the entries inside dispatch through the
 * caller's [onPick] — the same TileClickListener the row itself uses, so a
 * destination behaves identically whether it sits in the row or in a folder.
 *
 * Two layouts, chosen by [Sections.AggTile.childrenUi]:
 *   - `""`      icon grid, for a handful of entries an icon can distinguish.
 *   - `"table"` one row per entry, for lists where the TEXT is the identity.
 *     Sixteen task models all drawn as the same chat glyph would be sixteen
 *     identical icons, so that folder asks for the table.
 *
 * The dim card, its corner radius and its dismiss-on-scrim behaviour come from
 * [launcherFolderDialog] / [setFolderContent], which the Phone ▸ Apps folders
 * already use: one folder look for the whole launcher, not two that drift.
 */
object TileFolderDialog {

    /** Icons per row in the grid layout — matches the tile row's ~6 across. */
    private const val GRID_COLUMNS = 4

    fun open(ctx: Context, folder: Sections.AggTile, onPick: (Sections.AggTile) -> Unit) {
        val dialog = launcherFolderDialog(ctx)
        val sheet = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(ctx, 16)
            setPadding(pad, pad, pad, pad)
        }
        sheet.addView(TextView(ctx).apply {
            text = folder.label
            setTextColor(0xFFFFFFFF.toInt())
            setTextAppearance(android.R.style.TextAppearance_Material_Subhead)
            setPadding(dp(ctx, 4), 0, 0, dp(ctx, 12))
        })

        val body = if (folder.childrenUi == "table") {
            tableBody(ctx, folder, dialog, onPick)
        } else {
            gridBody(ctx, folder, dialog, onPick)
        }
        // A folder tall enough to overflow must scroll rather than clip its last
        // entry: the table one holds sixteen rows and would not fit any phone.
        sheet.addView(
            ScrollView(ctx).apply {
                isFillViewport = true
                addView(body)
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        dialog.setFolderContent(ctx, sheet)
        dialog.show()
    }

    /** Icon grid: [GRID_COLUMNS] cells per line, wrapping downwards. */
    private fun gridBody(
        ctx: Context,
        folder: Sections.AggTile,
        dialog: Dialog,
        onPick: (Sections.AggTile) -> Unit,
    ): View {
        val column = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        folder.children.filterNot { it.separator }.chunked(GRID_COLUMNS).forEach { line ->
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            line.forEach { child -> row.addView(gridCell(ctx, child, dialog, onPick)) }
            column.addView(
                row,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        return column
    }

    private fun gridCell(
        ctx: Context,
        child: Sections.AggTile,
        dialog: Dialog,
        onPick: (Sections.AggTile) -> Unit,
    ): View {
        val cell = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val pad = dp(ctx, 8)
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                Haptics.tap(it)
                // Close first: the entry may launch another app, and a folder
                // left open behind it is still there on the way back.
                dialog.dismiss()
                onPick(child)
            }
        }
        val iconRes = Sections.iconResFor(ctx, child.iconName)
        if (iconRes != 0) {
            cell.addView(android.widget.ImageView(ctx).apply {
                setImageResource(iconRes)
                imageTintList = android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
                val size = dp(ctx, 36)
                layoutParams = LinearLayout.LayoutParams(size, size)
            })
        }
        cell.addView(TextView(ctx).apply {
            text = child.label
            setTextColor(0xCCFFFFFF.toInt())
            setTextAppearance(android.R.style.TextAppearance_Material_Caption)
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(ctx, 4), 0, 0)
        })
        return cell
    }

    /**
     * Table list: one row per entry, four columns of information in two lines —
     * the category above, then the task name with the model right-aligned
     * beside it. All four are what the owner listed, and dropping any of them
     * would leave rows that cannot be told apart: two rows share a model, two
     * share a category, and the slug is what the bot actually receives.
     */
    private fun tableBody(
        ctx: Context,
        folder: Sections.AggTile,
        dialog: Dialog,
        onPick: (Sections.AggTile) -> Unit,
    ): View {
        val column = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        folder.children.filterNot { it.separator }.forEach { child ->
            column.addView(tableRow(ctx, child, dialog, onPick))
        }
        return column
    }

    private fun tableRow(
        ctx: Context,
        child: Sections.AggTile,
        dialog: Dialog,
        onPick: (Sections.AggTile) -> Unit,
    ): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val padX = dp(ctx, 6)
            val padY = dp(ctx, 8)
            setPadding(padX, padY, padX, padY)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                Haptics.tap(it)
                dialog.dismiss()
                onPick(child)
            }
        }
        if (child.caption.isNotEmpty()) {
            row.addView(TextView(ctx).apply {
                text = child.caption
                setTextColor(0x99FFFFFF.toInt())
                setTextAppearance(android.R.style.TextAppearance_Material_Caption)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
        }
        row.addView(TextView(ctx).apply {
            text = child.label
            setTextColor(0xFFFFFFFF.toInt())
            setTextAppearance(android.R.style.TextAppearance_Material_Body2)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        val line = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        line.addView(
            TextView(ctx).apply {
                text = child.id
                setTextColor(0xB3FFFFFF.toInt())
                setTextAppearance(android.R.style.TextAppearance_Material_Caption)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        if (child.note.isNotEmpty()) {
            line.addView(TextView(ctx).apply {
                text = child.note
                setTextColor(0xB3FFFFFF.toInt())
                setTextAppearance(android.R.style.TextAppearance_Material_Caption)
                gravity = Gravity.END
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
        }
        row.addView(
            line,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        return row
    }

    private fun dp(ctx: Context, value: Int): Int =
        (value * ctx.resources.displayMetrics.density).toInt()
}
