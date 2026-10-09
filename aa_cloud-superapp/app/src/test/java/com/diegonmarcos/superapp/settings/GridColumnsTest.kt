package com.diegonmarcos.superapp.settings

import android.app.Application
import android.content.Context
import android.view.ContextThemeWrapper
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.superapp.BuildConfig
import com.diegonmarcos.superapp.launcher.IndexTiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Icons per row: default, migration, the grids reading the pref, and 7 columns
 * fitting a 360 dp screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class GridColumnsTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    // MaterialCardView (IndexTiles) insists on a Material theme; the app context has none here.
    private val themed: Context get() = ContextThemeWrapper(ctx, com.google.android.material.R.style.Theme_MaterialComponents)

    @Before fun clean() {
        ctx.getSharedPreferences(GridColumns.STORE, Context.MODE_PRIVATE).edit().clear().commit()
    }

    // ── defaults and migration ──────────────────────────────────────────────

    @Test fun phoneDefaultIsSevenAndCloudKeepsItsDefault() {
        assertEquals(7, BuildConfig.UI_PHONE_GRID_COLUMNS)
        assertEquals(6, BuildConfig.UI_TILE_COLUMNS)
        assertEquals(7, GridColumns.phone(ctx))
        assertEquals(6, GridColumns.cloud(ctx))
    }

    @Test fun aPhoneThatNeverChoseHasNoStoredKeyAndFollowsTheDefault() {
        val sp = ctx.getSharedPreferences(GridColumns.STORE, Context.MODE_PRIVATE)
        // What a phone upgraded from the 6-column build looks like: nothing stored.
        assertTrue(!sp.contains(GridColumns.Kind.PHONE.key))
        assertEquals(7, GridColumns.read(sp, GridColumns.Kind.PHONE))
        // ...and it keeps following whatever the default is, rather than freezing.
        assertEquals(5, GridColumns.read(sp, GridColumns.Kind.PHONE, fallback = 5))
    }

    @Test fun anExplicitValueIsKeptEvenWhenItEqualsTheOldDefault() {
        GridColumns.set(ctx, GridColumns.Kind.PHONE, 6)
        GridColumns.set(ctx, GridColumns.Kind.CLOUD, 8)
        assertEquals(6, GridColumns.phone(ctx))
        assertEquals(8, GridColumns.cloud(ctx))
    }

    @Test fun cloudAndPhoneAreIndependent() {
        GridColumns.set(ctx, GridColumns.Kind.PHONE, 4)
        assertEquals(4, GridColumns.phone(ctx))
        assertEquals(6, GridColumns.cloud(ctx))
    }

    @Test fun storedValuesAreClampedToTheRange() {
        val sp = ctx.getSharedPreferences(GridColumns.STORE, Context.MODE_PRIVATE)
        sp.edit().putInt(GridColumns.Kind.PHONE.key, 99).putInt(GridColumns.Kind.CLOUD.key, 0).commit()
        assertEquals(GridColumns.MAX, GridColumns.phone(ctx))
        assertEquals(GridColumns.MIN, GridColumns.cloud(ctx))
        GridColumns.set(ctx, GridColumns.Kind.PHONE, 3)
        assertEquals(GridColumns.MIN, GridColumns.phone(ctx))
    }

    // ── the grid uses the pref ──────────────────────────────────────────────

    @Test fun theIndexGridLaysOutAsManyCardsPerRowAsThePrefSays() {
        val cells = (1..16).map { IndexTiles.Cell("c$it") {} }
        for (n in GridColumns.MIN..GridColumns.MAX) {
            GridColumns.set(ctx, GridColumns.Kind.CLOUD, n)
            val grid = IndexTiles.grid(themed, GridColumns.cloud(ctx), cells) as android.widget.LinearLayout
            val firstRow = grid.getChildAt(0) as android.widget.LinearLayout
            assertEquals("row width at $n columns", n, firstRow.childCount)
            assertEquals("rows at $n columns", (16 + n - 1) / n, grid.childCount)
        }
    }

    // ── 7 columns fit at 360 dp ─────────────────────────────────────────────

    @Test fun sevenColumnsFitA360dpScreen() {
        val d = 3f // xxhdpi; the arithmetic is in px, so density must not matter
        fun px(dp: Int) = (dp * d).toInt()
        val screen = px(360)
        for (cols in GridColumns.MIN..GridColumns.MAX) {
            // Phone strip tile: 52 dp icon wanted, 6 dp padding a side, page pad 8 dp a side.
            val cell = GridColumns.cellPx(screen - px(16), cols)
            val strip = GridColumns.iconPx(px(52), cell, px(12), px(24))
            assertTrue("strip icon $strip + pad fits cell $cell at $cols cols", strip + px(12) <= cell || strip == px(24))
            // Folder square: 60 dp wanted, 2 dp padding a side.
            val sq = GridColumns.iconPx(px(60), cell, px(4), px(28))
            assertTrue("folder square $sq fits cell $cell at $cols cols", sq + px(4) <= cell)
            // Folder dialog tile: 48 dp icon, pad per GridColumns, 72 dp of dialog chrome.
            val dcell = GridColumns.cellPx(screen - px(72), cols)
            val pad = px(GridColumns.cellPadDp(cols))
            val di = GridColumns.iconPx(px(48), dcell, 2 * pad, px(24))
            assertTrue("dialog icon $di fits cell $dcell at $cols cols", di + 2 * pad <= dcell)
        }
        val seven = GridColumns.cellPx(screen - px(16), 7)
        assertTrue("7 columns still leave a usable icon", GridColumns.iconPx(px(52), seven, px(12), px(24)) >= px(32))
    }

    @Test fun labelsEllipsizeInsteadOfWrappingIntoEachOther() {
        // Index card labels are 2 lines max and END-ellipsized.
        GridColumns.set(ctx, GridColumns.Kind.CLOUD, 7)
        val grid = IndexTiles.grid(themed, 7, listOf(IndexTiles.Cell("Averyveryverylongunbrokenlabel") {})) as android.widget.LinearLayout
        val card = (grid.getChildAt(0) as android.widget.LinearLayout).getChildAt(0) as android.view.ViewGroup
        val inner = card.getChildAt(0) as android.view.ViewGroup
        val label = (0 until inner.childCount).map { inner.getChildAt(it) }
            .filterIsInstance<android.widget.TextView>().last()
        assertEquals(2, label.maxLines)
        assertEquals(android.text.TextUtils.TruncateAt.END, label.ellipsize)
    }

    @Test fun configsGridIsEightWideAndItsIconFitsAt360dp() {
        // Configs declares grid_columns 8 (= MAX). (dp == px at density 1) A tile is margin 3+3 dp, padding 4+4 dp, and the
        // inline separator costs a few dp more: TileGridFragment hands fitTileIcon 18 dp of chrome.
        val eight = GridColumns.MAX
        val cell = GridColumns.cellPx(360, eight)
        assertEquals(45, cell)
        val icon = GridColumns.iconPx(32, cell, 18, 16)
        assertTrue("icon $icon + chrome fits the 45 dp cell", icon + 18 <= cell)
        assertTrue("and stays a usable icon", icon >= 24)
        // The widest label of the Network group is clipped, never wrapped past two lines.
        assertEquals(10f, GridColumns.labelSp(eight))
    }
}
