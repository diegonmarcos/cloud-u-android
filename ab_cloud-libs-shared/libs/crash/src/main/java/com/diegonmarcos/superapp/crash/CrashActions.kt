package com.diegonmarcos.superapp.crash

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast

/** What the crash screen's buttons do. */
object CrashActions {
    private fun title(a: Activity): String = a.getString(R.string.crash_report_title, a.applicationInfo.loadLabel(a.packageManager))

    /** The report the screen shows: the newest one, marked seen so a launch does not redirect to it twice. */
    fun load(a: Activity): String {
        val dir = CrashReports.dir(a)
        val report = CrashReports.shareText(dir)
        runCatching { CrashReports.markAllSeen(dir) }
        return report
    }

    fun label(a: Activity): String = a.applicationInfo.loadLabel(a.packageManager).toString()

    fun copy(a: Activity, report: String) {
        (a.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(title(a), report))
        Toast.makeText(a, a.getString(R.string.crash_report_copied), Toast.LENGTH_SHORT).show()
    }

    fun share(a: Activity, report: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, title(a)).putExtra(Intent.EXTRA_TEXT, report)
        a.startActivity(Intent.createChooser(send, a.getString(R.string.crash_report_share)))
    }

    /** Relaunches the app with [CrashReports.EXTRA_SAFE_MODE]; an app skips its optional startup work on it. */
    fun retryInSafeMode(a: Activity) {
        val launch = a.packageManager.getLaunchIntentForPackage(a.packageName)
        if (launch != null) a.startActivity(launch.putExtra(CrashReports.EXTRA_SAFE_MODE, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        a.finish()
    }
}
