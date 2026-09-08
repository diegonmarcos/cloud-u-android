package com.diegonmarcos.superapp.apps

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout

// Launcher-style folder popup, shared by PhoneAppsFragment (All Apps) and
// SuitePhoneAppsFragment (Quickmarks).
//
// Both used to open folders as Theme_Black_NoTitleBar_Fullscreen dialogs whose
// sheet painted an opaque near-black over the entire screen. Two things were
// wrong with that: it looked nothing like an Android launcher folder, and the
// sheet's own hint said "tap outside to dismiss" when there was no outside —
// the sheet WAS the screen, so the only way out was hitting a bare patch of
// its background. This presents the same sheet the way the platform launcher
// does: a rounded card floating over the still-visible, dimmed page, with the
// whole surrounding area live for dismissal.

/** Dim, see-through dialog that closes when the area around the card is tapped. */
fun launcherFolderDialog(ctx: Context): Dialog =
    Dialog(ctx, android.R.style.Theme_Translucent_NoTitleBar).apply {
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window?.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window?.setDimAmount(0.6f)
        setCanceledOnTouchOutside(true)
    }

/**
 * Use in place of [Dialog.setContentView] — centres [sheet] as a rounded card
 * and turns everything around it into dismiss-on-tap dead space.
 *
 * The card is inset from the screen edges so the page behind it stays visible;
 * that visible border IS the "outside" the folder hint refers to. Taps landing
 * on the card are consumed so they never reach the scrim.
 */
fun Dialog.setFolderContent(ctx: Context, sheet: View) {
    val density = ctx.resources.displayMetrics.density
    fun dp(value: Float) = value * density

    sheet.background = GradientDrawable().apply {
        cornerRadius = dp(28f)
        setColor(0xF21A1A24.toInt())
    }
    // Without this the tap falls through to the scrim below and the folder
    // closes the moment you reach for an icon.
    sheet.isClickable = true

    val scrim = FrameLayout(ctx).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        setBackgroundColor(Color.TRANSPARENT)
        setOnClickListener { dismiss() }
    }

    val margin = dp(20f).toInt()
    scrim.addView(
        sheet,
        FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER,
        ).also { it.setMargins(margin, margin, margin, margin) },
    )

    setContentView(scrim)
}
