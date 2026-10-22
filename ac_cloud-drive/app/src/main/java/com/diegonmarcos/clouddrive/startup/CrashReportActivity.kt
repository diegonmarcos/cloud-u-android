package com.diegonmarcos.clouddrive.startup

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.diegonmarcos.clouddrive.MainActivity

/**
 * #1106 "Drive hit a startup error": the newest report in files/crashes with Copy, Share and
 * "Retry in safe mode". Deliberately NOTHING the app's own startup could have broken: plain
 * Views, no Compose, no AppCompat/Material theme, no prefs, no strings.xml, and its own process
 * (:crash, AndroidManifest) so it survives the crash it reports.
 */
class CrashReportActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dir = StartupGuard.dir(this)
        val report = StartupGuard.shareText(dir)
        runCatching { StartupGuard.markAllSeen(dir) }

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(16, 20, 24)); setPadding(pad, pad * 2, pad, pad) }
        root.addView(TextView(this).apply { text = "Drive hit a startup error"; textSize = 20f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD) })
        root.addView(TextView(this).apply { text = "The app kept running instead of closing. Copy or share the trace below, or retry without the optional startup work."; setTextColor(Color.LTGRAY); setPadding(0, pad / 2, 0, pad / 2) })
        val scroll = ScrollView(this)
        scroll.addView(TextView(this).apply { text = report; textSize = 11f; typeface = Typeface.MONOSPACE; setTextColor(Color.rgb(230, 237, 243)); setTextIsSelectable(true) })
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        fun button(label: String, onClick: () -> Unit) = Button(this).apply { text = label; setOnClickListener { onClick() } }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button("Copy") {
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Drive startup error", report))
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(button("Share") {
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, "Drive startup error").putExtra(Intent.EXTRA_TEXT, report)
            startActivity(Intent.createChooser(send, "Share startup error"))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(row)
        root.addView(button("Retry in safe mode") {
            startActivity(Intent(this, MainActivity::class.java).putExtra(StartupGuard.EXTRA_SAFE_MODE, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            finish()
        })
        root.addView(button("Close") { finish() })
        setContentView(root)
    }
}
