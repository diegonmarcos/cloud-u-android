package com.diegonmarcos.superapp.fin

import android.os.Bundle
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import com.diegonmarcos.superapp.uikit.CloudKitTheme
import com.diegonmarcos.superapp.uikit.KitPalette
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import org.json.JSONObject
import java.text.NumberFormat
import java.util.Locale

/**
 * MyFin → Dashboard. Beta surface — renders entirely from
 * `build.json::ui.myfin_mock` (baked into BuildConfig.UI_MYFIN_MOCK_B64).
 * No real account aggregator yet; the goal is to validate layout +
 * navigation + data-driven contract before wiring Plaid / GoCardless /
 * OFX import.
 *
 * Sections (all rendered from JSON):
 *   • Net worth headline
 *   • Month overview (income / expenses / savings / savings-rate)
 *   • Accounts list
 *   • Top expense categories
 *   • Recent transactions
 *
 * Empty / null fields are silently skipped so the JSON block can be
 * trimmed without breaking the render.
 *
 * 2026-10-16 owner decision: all apps must be Compose. The page is a ComposeView under
 * CloudKitTheme (libs:ui-kit) with its palette in the fin_* colour resources; the host
 * contract (newInstance) is unchanged.
 */
class MyFinDashboardFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View =
        ComposeView(inflater.context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val palette = KitPalette(
                    surface = colorResource(R.color.fin_surface),
                    surfaceSelected = colorResource(R.color.fin_kpi),
                    textPrimary = colorResource(R.color.fin_text),
                    textSecondary = colorResource(R.color.fin_text_dim),
                    accent = colorResource(R.color.fin_accent),
                    hairline = colorResource(R.color.fin_hairline),
                    tileInk = colorResource(R.color.fin_ink),
                )
                CloudKitTheme(palette) { Dashboard() }
            }
        }

    @Composable
    private fun Dashboard() {
        val mock = remember { loadMock() }
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
            if (mock == null) {
                Header("MyFin · beta")
                Caption("No mock data — populate build.json::ui.myfin_mock and rebuild.")
                return@Column
            }
            val nf = remember(mock) { currencyFormatter(mock.optString("currency", "USD")) }

            // ── Net worth headline ─────────────────────────────────────
            Header("MyFin · beta")
            val netWorth = mock.optDouble("net_worth", Double.NaN)
            if (!netWorth.isNaN()) {
                BigKpi("Net worth", nf.format(netWorth))
                Spacer(Modifier.height(8.dp))
            }

            // ── Month overview ────────────────────────────────────────
            val month = mock.optJSONObject("month")
            if (month != null) {
                Subhead(mock.optString("month_label", "This month"))
                Grid4(
                    "Income"    to nf.format(month.optDouble("income", 0.0)),
                    "Expenses"  to nf.format(month.optDouble("expenses", 0.0)),
                    "Savings"   to nf.format(month.optDouble("savings", 0.0)),
                    "Save rate" to "${month.optInt("savings_rate_pct", 0)}%",
                )
            }

            // ── Accounts ──────────────────────────────────────────────
            val accounts = mock.optJSONArray("accounts")
            if (accounts != null && accounts.length() > 0) {
                Spacer(Modifier.height(16.dp))
                Subhead("Accounts")
                for (i in 0 until accounts.length()) {
                    val a = accounts.getJSONObject(i)
                    val name = a.optString("name", "—")
                    val type = a.optString("type", "")
                    val bal  = a.optDouble("balance", 0.0)
                    val label = if (type.isNotEmpty()) "$name  ·  $type" else name
                    Kv(label, nf.format(bal), neg = bal < 0)
                }
            }

            // ── Top categories ────────────────────────────────────────
            val cats = mock.optJSONArray("top_categories")
            if (cats != null && cats.length() > 0) {
                Spacer(Modifier.height(16.dp))
                Subhead("Top expense categories — ${mock.optString("month_label", "this month")}")
                for (i in 0 until cats.length()) {
                    val c = cats.getJSONObject(i)
                    val name = c.optString("name", "—")
                    val amt  = c.optDouble("amount", 0.0)
                    val pct  = c.optInt("pct", 0)
                    Kv("$name  ·  $pct%", nf.format(amt))
                }
            }

            // ── Recent transactions ───────────────────────────────────
            val recent = mock.optJSONArray("recent_tx")
            if (recent != null && recent.length() > 0) {
                Spacer(Modifier.height(16.dp))
                Subhead("Recent transactions")
                for (i in 0 until recent.length()) {
                    val t = recent.getJSONObject(i)
                    val date  = t.optString("date", "—")
                    val payee = t.optString("payee", "—")
                    val cat   = t.optString("category", "")
                    val amt   = t.optDouble("amount", 0.0)
                    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        .background(LocalKitPalette.current.surface).padding(10.dp)) {
                        Text("$date  ·  $payee", color = LocalKitPalette.current.textPrimary, fontSize = 16.sp,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(if (cat.isNotEmpty()) "$cat  ·  ${nf.format(amt)}" else nf.format(amt),
                            color = colorResource(if (amt < 0) R.color.fin_negative else R.color.fin_positive),
                            fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Caption("Beta · all numbers from build.json::ui.myfin_mock. Real aggregator (Plaid / GoCardless / OFX import) lands in a follow-up; this surface stays the same.")
        }
    }

    private fun loadMock(): JSONObject? = runCatching {
        JSONObject(String(Base64.decode(BuildConfig.UI_MYFIN_MOCK_B64, Base64.NO_WRAP)))
    }.getOrNull()

    private fun currencyFormatter(currency: String): NumberFormat {
        val nf = NumberFormat.getCurrencyInstance(Locale.US)
        runCatching { nf.currency = java.util.Currency.getInstance(currency) }
        nf.maximumFractionDigits = 2
        return nf
    }

    // ── Tiny render helpers ───────────────────────────────────────
    @Composable
    private fun BigKpi(label: String, value: String) {
        val p = LocalKitPalette.current
        Column(Modifier.fillMaxWidth().background(p.surfaceSelected).padding(14.dp)) {
            Text(label.uppercase(), color = p.textSecondary, fontSize = 12.sp, letterSpacing = 1.2.sp)
            Text(value, color = p.textPrimary, fontSize = 28.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 4.dp))
        }
    }

    @Composable
    private fun Grid4(vararg cells: Pair<String, String>) {
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            for (row in cells.toList().chunked(2)) {
                Row {
                    for ((k, v) in row) CounterTile(k, v)
                }
            }
        }
    }

    @Composable
    private fun androidx.compose.foundation.layout.RowScope.CounterTile(k: String, v: String) {
        val p = LocalKitPalette.current
        Column(Modifier.weight(1f).padding(4.dp).background(p.surface).padding(12.dp)) {
            Text(v, color = p.accent, fontSize = 24.sp)
            Text(k, color = p.textSecondary, fontSize = 12.sp)
        }
    }

    @Composable
    private fun Kv(k: String, v: String, neg: Boolean = false) {
        val p = LocalKitPalette.current
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Text(k, color = p.textPrimary, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f))
            Text(v, color = if (neg) colorResource(R.color.fin_negative) else p.accent, fontSize = 16.sp,
                fontFamily = FontFamily.Monospace)
        }
    }

    @Composable
    private fun Header(t: String) {
        Text(t, color = LocalKitPalette.current.textPrimary, fontSize = 24.sp,
            modifier = Modifier.padding(bottom = 8.dp))
    }

    @Composable
    private fun Subhead(t: String) {
        Text(t, color = LocalKitPalette.current.accent, fontSize = 16.sp,
            modifier = Modifier.padding(bottom = 4.dp))
    }

    @Composable
    private fun Caption(t: String) {
        Text(t, color = LocalKitPalette.current.textSecondary, fontSize = 12.sp)
    }

    companion object { fun newInstance() = MyFinDashboardFragment() }
}
