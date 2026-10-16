package com.diegonmarcos.cloudagenda.links

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import com.diegonmarcos.cloudagenda.agent.MailLink
import com.diegonmarcos.cloudagenda.data.Attachment
import com.diegonmarcos.cloudagenda.data.AttachmentKind
import com.diegonmarcos.cloudagenda.data.TasksRepository

/**
 * Opening what a task links to, in the app that owns it. Nothing is copied here: a Mail link is a message id, an
 * Image / File link is the document URI the picker returned.
 *
 *  - Mail: Cloud Mail exposes no per-message deep link yet (its manifest has mailto / oauth / share filters and the
 *    read-only agent provider, nothing that opens a message by id). The link IS built for that day -- a VIEW of
 *    cloudmail://message/<account>/<id> aimed at Cloud Mail's package -- and until Cloud Mail answers it the fallback
 *    opens Cloud Mail itself, so the tap never dead-ends.
 *  - Image / File: Cloud Drive exposes no picker or open-by-id contract either (its provider serves only the
 *    screenshot agent's records). The pick is the system document picker (a Cloud Drive document root appears in it
 *    when Drive offers one); the URI is stored, and opening it prefers Cloud Drive and falls back to any viewer.
 */
class Links(private val context: Context, private val mailPackage: String, private val drivePackage: String) {

    fun open(a: Attachment) {
        val ok = when (a.kind) {
            AttachmentKind.MAIL -> openMail(a.uri)
            AttachmentKind.IMAGE, AttachmentKind.FILE -> openDocument(a.uri)
        }
        if (!ok) toast("Nothing could open ${a.label.ifBlank { a.kind.label }}")
    }

    fun openMail(ref: String): Boolean {
        val (account, id) = MailLink.parse(ref) ?: return false
        val deep = Intent(Intent.ACTION_VIEW, Uri.parse("cloudmail://message/" + Uri.encode(account) + "/" + Uri.encode(id))).setPackage(mailPackage)
        if (start(deep)) return true
        val launch = context.packageManager.getLaunchIntentForPackage(mailPackage) ?: return false
        return start(launch)
    }

    fun openDocument(uri: String): Boolean {
        val u = Uri.parse(uri)
        val view = Intent(Intent.ACTION_VIEW).setData(u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return start(Intent(view).setPackage(drivePackage)) || start(view)
    }

    /** Keeps read access to a picked document across restarts (not every provider offers it; those still work this session). */
    fun keep(uri: Uri) {
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    /** The picked document's display name, else the last path segment. */
    fun nameOf(uri: Uri): String {
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { if (it.isNotBlank()) return it }
            }
        }
        return uri.lastPathSegment.orEmpty()
    }

    private fun start(intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }

    private fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

}

/** An id the pickers hand back, as the attachment a task stores. */
fun newAttachment(kind: AttachmentKind, uri: String, label: String) = Attachment(TasksRepository.newId(), kind, uri, label)
