package app.sterna.widget

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — same instrument and the same disclaimer as
 */
class UnreadWidgetPaletteWiringLintTest {

    /**
     * `setBackgroundResource`, NEVER `setBackgroundColor`.
     */
    @Test fun `the drawn cell is painted from the palette, and keeps its rounded corner`() {
        assertEquals(
            "UnreadWidgetDraw.renderTotal must paint the small cell from the WidgetPalette it is " +
                "handed: the " +
                "background as a pre-built DRAWABLE, then the figure's accent and the label's " +
                "colour. ⛔ setBackgroundColor here replaces the shape with a flat fill and the " +
                "cell comes out square on the home screen; a missing setTextColor leaves the " +
                "layout's compile-time colour on a surface it was never meant for. " +
                "⛔ AND THE THREE OF THEM SIT UNDER `if (palette != null)`, all three, never two: " +
                "null is the reader's own answer that the app does not diverge from the system, " +
                "and posting ANY of the three then freezes that side of the cell against a system " +
                "that flips at dusk. A palette made non-null again, or the guard dropped, is that " +
                "regression — see WidgetThemeTest's widgetFollowsResources.",
            listOf(
                "private fun renderTotal(",
                "context: Context,",
                "state: UnreadWidgetState,",
                "palette: WidgetPalette?,",
                "): RemoteViews =",
                "RemoteViews(context.packageName, R.layout.widget_unread).apply {",
                "if (palette != null) {",
                BACKGROUND,
                "setTextColor(R.id.widget_unread_count, palette.accent)",
                "setTextColor(R.id.widget_unread_label, palette.label)",
                "}",
                "setTextViewText(R.id.widget_unread_count, state.count.toString())",
                "setTextViewText(R.id.widget_unread_label, text(context, state.label))",
            ),
            body(DRAW, "private fun renderTotal("),
        )
        assertEquals(
            "nothing in UnreadWidgetDraw may call setBackgroundColor. It is one word away from " +
                "the line above and it costs the cell its rounded corner — the shape drawable is " +
                "thrown away and the home screen shows a bare rectangle.",
            emptyList<String>(),
            linesOf(DRAW).filter { "setBackgroundColor" in it },
        )
    }

    /**
     * The three settings are READ, every draw, and the wallpaper scheme stays CONDITIONED on the
     */
    @Test fun `the cell's colours are read from the app's own settings, never assumed`() {
        assertEquals(
            "WidgetThemeReader.colours must read themeMode, pureBlack and dynamicColor from the " +
                "app's settings, take the system's night mode from the Configuration, and keep " +
                "the wallpaper-derived scheme behind the dynamicColor setting. A constant in any " +
                "of them silently ungoverns the home screen: the reader forces the app to light " +
                "and the cell stays dark, or switches pure black on and the cell stays navy. " +
                "Dropping `dynamicColor &&` is the same defect reversed — the cell would follow " +
                "Material You while the app, where it is opt-in and off by default, does not.",
            listOf(
                SIGNATURE,
                "val settings = app.container.settingsRepository",
                "val themeMode = settings.themeMode.first()",
                "val pureBlack = settings.pureBlack.first()",
                "val dynamicColor = settings.dynamicColor.first()",
                "val night = app.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK",
                "val dark = widgetIsDark(themeMode, systemNight = night == Configuration.UI_MODE_NIGHT_YES)",
                "val scheme = when {",
                "dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->",
                "if (dark) dynamicDarkColorScheme(app) else dynamicLightColorScheme(app)",
                "dark -> PelagicColorScheme",
                "else -> ArcticColorScheme",
                "}",
                "WidgetColours(",
                FOLLOWS_RESOURCES,
                "else widgetPalette(scheme, dark, pureBlack),",
                MONOGRAM_RAMPS,
                ")",
            ),
            body(READER, "suspend fun colours(app: Application)"),
        )
        assertEquals(
            "WidgetThemeReader.background must map each of the three cell states to its OWN " +
                "drawable. Two states pointing at one file is a reader whose pure-black setting, " +
                "or whose dark theme, draws the wrong surface behind the right text.",
            listOf(
                "fun background(surface: WidgetSurface): Int = when (surface) {",
                "WidgetSurface.Light -> R.drawable.widget_surface_light",
                "WidgetSurface.Dark -> R.drawable.widget_surface_dark",
                "WidgetSurface.Black -> R.drawable.widget_surface_black",
            ),
            body(READER, "fun background(surface: WidgetSurface): Int"),
        )
    }

    /**
     * THE READ IS BOUNDED, AND IT MAY ANSWER "NOTHING" — the two things this reader buys, and
     */
    @Test fun `the palette read is bounded, cancels nothing, and is allowed to answer nothing`() {
        val body = body(READER, "suspend fun colours(app: Application)")
        assertEquals(
            "WidgetThemeReader.colours must be BOUNDED by withTimeoutOrNull and must be allowed " +
                "to return null. Unbounded, a slow DataStore read sits inside the goAsync() " +
                "window of UnreadWidgetProvider.onUpdate — the PendingResult is not finished and " +
                "the system kills the process — and inside the tail of a delivery pass. And with " +
                "withTimeout instead, the timeout arrives as a CancellationException that " +
                "FetchAndNotify.run's rethrowIfCancelled would re-throw, cancelling the pass that " +
                "was delivering the mail.",
            listOf(SIGNATURE),
            body.take(1),
        )
        assertEquals(
            "WidgetThemeReader.colours must hand back a NULL PALETTE on both of its paths. The " +
                "timeout's null is the first (the whole function answers null); " +
                "widgetFollowsResources' is the second, and it is the one this pane exists for — " +
                "with ThemeMode.SYSTEM, pure black off and Material You off, the app cannot " +
                "diverge from the system, so posting nothing draws the same pixels AND leaves the " +
                "launcher free to re-resolve values/values-night when the system flips at dusk. " +
                "Delete this line and both cells stay light through the evening switch until the " +
                "next message arrives. ⛔ AND IT DECIDES THE PALETTE AND NOTHING ELSE: as a " +
                "`return@withTimeoutOrNull null` it took the badge tones with it, and a bitmap " +
                "has no values-night behind it — the DEFAULT settings are exactly this row, so " +
                "the default install would draw no badge at all.",
            listOf(FOLLOWS_RESOURCES),
            body.filter { "widgetFollowsResources" in it },
        )
        assertEquals(
            "⭐ AND THE BADGE TONES ARE READ ON EVERY PATH THAT ANSWERS, unconditionally. They are " +
                "not nullable and they are not behind the divergence test: a WidgetPalette may be " +
                "withheld because res/values and values-night already hold those colours, and " +
                "there is no such resource behind a BITMAP. Move this line under a condition and " +
                "the badge disappears on the settings nobody changed.",
            listOf(MONOGRAM_RAMPS),
            body.filter { "widgetMonogramRamps" in it },
        )
        assertEquals(
            "⛔ AND palette() IS NOW ONE LINE, a projection of colours() and nothing else. It is " +
                "what the COUNTER still calls, and its behaviour must not have moved by a hair. A " +
                "second settings read reinstalled here would answer from another moment than the " +
                "rows' — the drift the single read exists to prevent — and would spend a second " +
                "DataStore open inside a goAsync() window.",
            listOf("suspend fun palette(app: Application): WidgetPalette? = colours(app)?.palette"),
            linesOf(READER).filter { it.startsWith("suspend fun palette(") },
        )
        assertEquals(
            "nothing in WidgetThemeReader may call withTimeout: it reports a slow read as a " +
                "CancellationException, and this code runs inside a goAsync() window and inside " +
                "the delivery pass's own runCatching { … }.rethrowIfCancelled().",
            emptyList<String>(),
            linesOf(READER).filter { "withTimeout(" in it },
        )
        assertEquals(
            "and the BUDGET is the point of the bound, not the keyword. It must stay far below " +
                "every budget that encloses it: UnreadWidgetProvider.DRAW_TIMEOUT_MS and both " +
                "REFRESH_TIMEOUT_MS are 5 s, RecentMailWidgetFactory.READ_TIMEOUT_MS is 4 s on a " +
                "binder thread the launcher waits on, and the system kills a goAsync() receiver " +
                "at about 10 s. Raised to the neighbours' order of magnitude, a colour read that " +
                "never answers is once again what makes the MAIL read miss its budget, or what " +
                "pushes the broadcast past the kill — the defect the bound was added for, with " +
                "the right function still called. Expiring is free: the cell is then drawn from " +
                "values / values-night, exactly as before this pane.",
            listOf("private const val PALETTE_TIMEOUT_MS = 1_000L"),
            linesOf(READER).filter { it.startsWith("private const val PALETTE_TIMEOUT_MS") },
        )
    }

    /**
     * The draw READS the palette and HANDS it to the render — the join between the two rules above.
     */
    @Test fun `the draw reads the palette and hands it to the render`() {
        assertEquals(
            "UnreadWidgetDraw.draw must read the palette from WidgetThemeReader and pass it to " +
                "render. Without that join the two colour rules are dead code and the cell keeps " +
                "the colours res/values-night froze into it — the very defect this pane fixes.",
            listOf(
                DRAW_SIGNATURE,
                "if (ids.isEmpty()) return",
                "val palette = WidgetThemeReader.palette(app)",
                "val store = app.container.accountStore",
                "val accounts = store.accounts()",
                "val target = UnreadWidgetTap.of(accounts)",
                "val small = tapped(app, renderTotal(app, UnreadWidgetContent.of(accounts, " +
                    "unreadByAccount.values.sum()), palette), target)",
                "val rows = UnreadWidgetContent.byAccount(accounts, unreadByAccount, store.appLockEnabled())",
                "val views =",
                "if (rows.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {",
                "val fontScale = app.resources.configuration.fontScale",
                "val ventilated = SizeF(VENTILATED_WIDTH_DP, ventilatedHeightDp(rows.size, fontScale))",
                "RemoteViews(mapOf(SMALL to small, ventilated to tapped(app, renderAccounts(app, rows, palette), target)))",
                "} else {",
                "small",
                "}",
                "val manager = AppWidgetManager.getInstance(app)",
                "ids.forEach { manager.updateAppWidget(it, views) }",
            ),
            body(DRAW, DRAW_SIGNATURE.removeSuffix(" {")),
        )
    }

    /**
     * THE ENLARGED LAYOUT IS PAINTED FROM THE SAME PALETTE, and this pane is the only thing
     */
    @Test fun `the enlarged cell is painted from the same palette as the small one`() {
        assertEquals(
            "UnreadWidgetDraw.renderAccounts must paint the enlarged layout and its rows from the " +
                "same WidgetPalette: the container's background as a pre-built DRAWABLE, then each " +
                "row's name and figure. Leave any of them out and the cell changes theme as the " +
                "user drags it larger — the small layout carries the app's own colours and the " +
                "enlarged one falls back to res/values-night, which is #117 on a new layout. " +
                "⛔ And they all sit under `if (palette != null)`, on the container and on the " +
                "rows alike: null is the reader's answer that the app does not diverge from the " +
                "system, and posting any colour then freezes that side of the cell against a " +
                "system that flips at dusk.",
            listOf(
                "private fun renderAccounts(",
                "context: Context,",
                "rows: List<UnreadAccountRow>,",
                "palette: WidgetPalette?,",
                "): RemoteViews =",
                "RemoteViews(context.packageName, R.layout.widget_unread_accounts).apply {",
                "if (palette != null) {",
                BACKGROUND,
                "}",
                "removeAllViews(R.id.widget_unread_accounts)",
                "rows.forEach { row ->",
                "val line = RemoteViews(context.packageName, R.layout.widget_unread_account_row)",
                "line.setTextViewText(R.id.widget_unread_account_name, row.name)",
                "line.setTextViewText(R.id.widget_unread_account_count, row.count.toString())",
                "if (palette != null) {",
                "line.setTextColor(R.id.widget_unread_account_name, palette.rowPrimary)",
                "line.setTextColor(R.id.widget_unread_account_count, palette.accent)",
                "}",
                "addView(R.id.widget_unread_accounts, line)",
                "}",
            ),
            body(DRAW, "private fun renderAccounts("),
        )
    }

    /**
     * The code lines of the function opening with [signature] in the file at [path], or [ABSENT]
     * when the file is not there at all — a missing file must print as a diff, not as an exception.
     */
    private fun body(path: String, signature: String): List<String> {
        val file = File(DeclarationSource.ROOT, path)
        if (!file.isFile) return listOf(ABSENT)
        return DeclarationSource.functionBody(file, signature)
    }

    private fun linesOf(path: String): List<String> {
        val file = File(DeclarationSource.ROOT, path)
        if (!file.isFile) return listOf(ABSENT)
        return DeclarationSource.kotlinLines(file)
    }

    private companion object {
        const val ABSENT = "(no such file)"

        const val DRAW = "app/src/main/kotlin/app/sterna/widget/UnreadWidgetDraw.kt"
        const val READER = "app/src/main/kotlin/app/sterna/widget/WidgetThemeReader.kt"

        /**
         * The signature, pinned as one string because THREE things live on it and each is a
         */
        const val SIGNATURE =
            "suspend fun colours(app: Application): WidgetColours? = " +
                "withTimeoutOrNull(PALETTE_TIMEOUT_MS) {"

        /**
         * The second of the two null paths: the app does not diverge from the system.
         */
        const val FOLLOWS_RESOURCES =
            "palette = if (widgetFollowsResources(themeMode, pureBlack, dynamicColor)) null"

        /** The tones a badge is painted in: read on every path that answers, never withheld. */
        const val MONOGRAM_RAMPS = "monogramRamps = widgetMonogramRamps(scheme, dark, pureBlack),"

        /**
         * Pinned whole, and the MAP is why: `draw` hands the launcher two layouts for one cell, so
         * this line is where a reader could quietly go back to painting one of them.
         */
        const val DRAW_SIGNATURE =
            "suspend fun draw(app: Application, ids: IntArray, unreadByAccount: Map<String, Int>) {"

        const val BACKGROUND =
            "setInt(R.id.widget_unread_root, \"setBackgroundResource\", " +
                "WidgetThemeReader.background(palette.surface))"
    }
}
