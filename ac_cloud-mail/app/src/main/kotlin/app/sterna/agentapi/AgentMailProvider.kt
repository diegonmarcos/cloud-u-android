package app.sterna.agentapi

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.util.Log
import app.sterna.SternaApplication
import app.sterna.core.data.text.htmlToText
import kotlinx.coroutines.runBlocking

/**
 * #913 the fleet agent door to mail (see [AgentMailContract]). Exported, but the manifest guards it with
 * the constellation's signature permission, so only a fleet app can ask. QUERY ONLY: there is no way in
 * here to send, move, mark or delete anything.
 */
class AgentMailProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    private fun repo() = ((context?.applicationContext as? SternaApplication)?.container?.mailRepository)
        ?: throw IllegalStateException("Cloud Mail is not started yet")

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?,
    ): Cursor? = try {
        when (uri.pathSegments.firstOrNull()) {
            AgentMailContract.PATH_MESSAGES -> messages(uri)
            AgentMailContract.PATH_BODY -> body(uri)
            else -> throw IllegalArgumentException("unknown path: ${uri.path}")
        }
    } catch (e: IllegalArgumentException) {
        throw e
    } catch (e: Exception) {
        // Never the content of a message in the log; the class is enough to find the cause.
        Log.w(TAG, "agent query failed: ${e.javaClass.simpleName}")
        throw IllegalStateException("mail query failed: ${e.javaClass.simpleName}")
    }

    private fun messages(uri: Uri): Cursor {
        val parsed = AgentMailContract.criteria(
            uri.getQueryParameter(AgentMailContract.P_FROM), uri.getQueryParameter(AgentMailContract.P_SUBJECT),
            uri.getQueryParameter(AgentMailContract.P_SINCE), uri.getQueryParameter(AgentMailContract.P_LIMIT),
        )
        val criteria = when (parsed) {
            is AgentMailContract.Parsed.Ok -> parsed.criteria
            is AgentMailContract.Parsed.Refused -> throw IllegalArgumentException(parsed.why)
        }
        val (sql, args) = AgentMailContract.sql(criteria)
        val rows = runBlocking { repo().agentMessages(sql, args) }
        val cursor = MatrixCursor(AgentMailContract.MESSAGE_COLUMNS, rows.size)
        for (r in rows) {
            cursor.addRow(arrayOf<Any?>(r.id, r.accountId, r.mailboxId, r.subject, r.fromName, r.fromEmail, r.receivedAt, r.sortKey, if (r.seen) 1 else 0))
        }
        return cursor
    }

    private fun body(uri: Uri): Cursor {
        val account = uri.getQueryParameter(AgentMailContract.P_ACCOUNT).orEmpty()
        val id = uri.getQueryParameter(AgentMailContract.P_ID).orEmpty()
        require(account.isNotBlank() && id.isNotBlank()) { "account and id are required" }
        val message = runBlocking { repo().agentBody(account, id) }
        val cursor = MatrixCursor(AgentMailContract.BODY_COLUMNS, 1)
        if (message != null) {
            val email = message.email
            val html = email.htmlContent().orEmpty()
            // textContent() is the html part itself on an html-only message: flatten it, keep the link targets.
            val raw = email.textContent().orEmpty()
            val plain = if (html.isNotEmpty() && (raw.isBlank() || raw == html)) htmlToText(html, keepLinkTargets = true) else raw
            val b = AgentMailContract.body(plain, html)
            cursor.addRow(arrayOf<Any?>(b.text, b.html, if (b.truncated) 1 else 0))
        }
        return cursor
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    private companion object { const val TAG = "AgentMail" }
}
