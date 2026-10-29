package com.diegonmarcos.superapp.perms

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * #1274 the Permissions page libs:perms hosts in any app without a [PermsHost] page of its own (Calc, Browser, Nav,
 * Keyboard, Drive, Store, ...), and in contexts with no activity at all (an IME, a plugin, a service). It is the one place
 * in that app that asks: opened by [PermsLink.open] it lists the requesting feature's entries first and highlighted,
 * then every other applicable entry this app declares. Runtime entries go through the system dialog, the rest through
 * [PermsSettings.open] (the exact settings page). Plain Views on purpose: no Compose or theme dependency in the host.
 */
class PermsActivity : Activity() {
    private lateinit var list: LinearLayout
    private var feature = ""
    private var ids: Set<String> = emptySet()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        feature = intent.getStringExtra(EXTRA_FEATURE).orEmpty()
        ids = intent.getStringArrayExtra(EXTRA_IDS)?.toSet().orEmpty()
        val pad = dp(16)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        setContentView(ScrollView(this).apply { addView(list) })
    }

    override fun onResume() { super.onResume(); render() }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render()
    }

    private fun render() {
        list.removeAllViews()
        val vars = Perms.vars(this)
        val decl = Perms.declared(this)
        val rows = rows(Perms.catalogue(this).entries, ids) { PermStatusReader.status(this, it, this, vars, decl) }
        list.addView(TextView(this).apply {
            text = if (feature.isEmpty()) "Permissions" else "Permissions needed by $feature"
            textSize = 20f; setTypeface(typeface, Typeface.BOLD); tag = "perms:title"
        })
        val askable = rows.filter { it.wanted && it.status.missing && it.entry.kind == PermKind.RUNTIME }.map { it.entry }
        if (askable.isNotEmpty()) list.addView(Button(this).apply {
            text = "Grant all needed"; tag = "perms:grant-all"
            setOnClickListener { ask(askable) }
        })
        if (rows.isEmpty()) list.addView(TextView(this).apply { text = "This app needs no permission." })
        for (r in rows) list.addView(row(r))
    }

    private fun row(r: Row): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(0, dp(10), 0, dp(10)); tag = "perms:row:${r.entry.id}"
        addView(TextView(context).apply {
            text = r.entry.label; textSize = 16f
            if (r.wanted) setTypeface(typeface, Typeface.BOLD)
        })
        addView(TextView(context).apply {
            text = listOf(stateText(r.status.state), r.status.reason, r.entry.lookFor).filter { it.isNotBlank() }.joinToString(" · ")
            textSize = 13f
        })
        if (r.status.missing && r.entry.kind != PermKind.ADB_ONLY) addView(Button(context).apply {
            text = if (r.entry.kind == PermKind.RUNTIME && r.status.state != PermState.PERMANENTLY_DENIED) "Grant" else "Open settings"
            tag = "perms:grant:${r.entry.id}"
            setOnClickListener {
                if (r.entry.kind == PermKind.RUNTIME && r.status.state != PermState.PERMANENTLY_DENIED) ask(listOf(r.entry))
                else PermsSettings.open(this@PermsActivity, r.entry)
            }
        })
    }

    private fun ask(entries: List<PermEntry>) {
        val perms = entries.map { it.permission }.filter { it.isNotEmpty() }.distinct()
        if (perms.isEmpty()) return
        PermAskLog.markAsked(this, perms)
        requestPermissions(perms.toTypedArray(), REQ)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    data class Row(val entry: PermEntry, val status: PermStatus, val wanted: Boolean)

    companion object {
        const val EXTRA_FEATURE = "com.diegonmarcos.perms.FEATURE"
        const val EXTRA_IDS = "com.diegonmarcos.perms.IDS"
        private const val REQ = 1274

        fun intent(ctx: Context, feature: String, ids: Collection<String>): Intent =
            Intent(ctx, PermsActivity::class.java)
                .putExtra(EXTRA_FEATURE, feature)
                .putExtra(EXTRA_IDS, ids.toTypedArray())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        /** The page's rows: the requested entries first (catalogue order), then the rest; not-applicable ones dropped. */
        fun rows(entries: List<PermEntry>, wanted: Set<String>, status: (PermEntry) -> PermStatus): List<Row> =
            entries.map { Row(it, status(it), it.id in wanted) }
                .filter { it.status.state != PermState.NOT_APPLICABLE }
                .sortedBy { if (it.wanted) 0 else 1 }

        fun stateText(s: PermState): String = when (s) {
            PermState.GRANTED -> "Granted"
            PermState.DENIED -> "Not granted"
            PermState.PERMANENTLY_DENIED -> "Denied: grant it in settings"
            PermState.NOT_APPLICABLE -> "Not applicable"
            PermState.UNKNOWN -> "Unknown"
        }
    }
}
