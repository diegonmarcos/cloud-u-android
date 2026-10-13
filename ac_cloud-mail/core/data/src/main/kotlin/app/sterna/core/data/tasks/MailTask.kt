package app.sterna.core.data.tasks

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * A task attached to one mail ("Assigned"). It is a VTODO of the fleet's task store (the cal engine,
 * which Cloud Agenda shows under Tasks): [deadlineMillis] is its DUE, [targetMillis] its DTSTART, and
 * the details no VTODO field names travel as properties the engine preserves verbatim
 * (`unknownLines`): LOCATION, URL (the link back to the mail, `mid:`), ATTENDEE, X-CLOUD-LINK and
 * X-CLOUD-MAIL (account + message id, what reopens the mail).
 */
data class MailTask(
    val uid: String,
    val title: String,
    val accountId: String,
    val emailId: String,
    val mailSubject: String = "",
    /** The mail's Message-ID, without angle brackets; the portable link back (`mid:` URL, RFC 2392). */
    val messageId: String? = null,
    val deadlineMillis: Long? = null,
    val targetMillis: Long? = null,
    val notes: String = "",
    val links: List<String> = emptyList(),
    val location: String = "",
    /** 0 none, 1 high .. 9 low (RFC 5545). */
    val priority: Int = 0,
    /** NEEDS-ACTION | IN-PROCESS | COMPLETED | CANCELLED. */
    val status: String = STATUS_TODO,
    val people: List<String> = emptyList(),
    /** The task-store collection (Agenda project) it lives in; empty = the store's local default. */
    val collectionId: String = "",
    val createdMillis: Long? = null,
) {
    /** The URL that opens the mail in any client that knows RFC 2392, or null without a Message-ID. */
    val mailLink: String? get() = messageId?.trim('<', '>')?.takeIf { it.isNotBlank() }?.let { "mid:$it" }

    companion object {
        const val STATUS_TODO = "NEEDS-ACTION"
        const val STATUS_DOING = "IN-PROCESS"
        const val STATUS_DONE = "COMPLETED"
        const val STATUS_CANCELLED = "CANCELLED"
    }
}

/** How the Assigned list is ordered: by deadline or by target date, earliest first, undated last. */
enum class AssignedSort { DEADLINE, TARGET }

fun sortAssigned(tasks: List<MailTask>, by: AssignedSort): List<MailTask> {
    val key: (MailTask) -> Long? = if (by == AssignedSort.DEADLINE) { t -> t.deadlineMillis } else { t -> t.targetMillis }
    return tasks.sortedWith(compareBy<MailTask>({ key(it) == null }, { key(it) ?: 0L }, { it.title }))
}

// --- the task store's wire (CalTodo JSON) ---------------------------------------------------------

private const val X_MAIL = "X-CLOUD-MAIL"
private const val X_MAIL_SUBJECT = "X-CLOUD-MAIL-SUBJECT"
private const val X_LINK = "X-CLOUD-LINK"
private const val X_PERSON = "X-CLOUD-PERSON"
private val DATE = DateTimeFormatter.ofPattern("yyyyMMdd")

private fun escape(s: String): String = buildString {
    for (c in s) when (c) {
        '\\' -> append("\\\\"); ';' -> append("\;"); ',' -> append("\\,"); '\n' -> append("\\n"); '\r' -> Unit
        else -> append(c)
    }
}

private fun unescape(s: String): String = buildString {
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '\\' && i + 1 < s.length) {
            val n = s[i + 1]
            append(if (n == 'n' || n == 'N') '\n' else n)
            i += 2
        } else { append(c); i++ }
    }
}

private fun dateOf(millis: Long): String = DATE.format(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate())
private fun millisOf(yyyymmdd: String): Long? = runCatching {
    LocalDate.parse(yyyymmdd.trim().take(8), DATE).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
}.getOrNull()

/** The extra VTODO properties [task] is carried by, in the store's verbatim-preserved bucket. */
fun extraLines(task: MailTask): List<String> = buildList {
    task.targetMillis?.let { add("DTSTART;VALUE=DATE:${dateOf(it)}") }
    if (task.location.isNotBlank()) add("LOCATION:${escape(task.location)}")
    task.mailLink?.let { add("URL:$it") }
    add("$X_MAIL:${escape(task.accountId)};${escape(task.emailId)}")
    if (task.mailSubject.isNotBlank()) add("$X_MAIL_SUBJECT:${escape(task.mailSubject)}")
    for (l in task.links.filter { it.isNotBlank() }) add("$X_LINK:${escape(l.trim())}")
    for (p in task.people.map { it.trim() }.filter { it.isNotEmpty() }) {
        add(if ('@' in p && ' ' !in p) "ATTENDEE:mailto:$p" else "$X_PERSON:${escape(p)}")
    }
}

/** [task] as the JSON the cal engine's `saveTodo` takes (CalTodo.fromJson's shape). */
fun toTodoJson(task: MailTask): String = buildJsonObject {
    put("uid", task.uid)
    put("collectionId", task.collectionId)
    put("href", "")
    put("etag", "")
    put("summary", task.title)
    put("description", task.notes)
    put("status", task.status)
    put("dueUtcMillis", task.deadlineMillis ?: -1L)
    put("dueIsDate", true)
    put("priority", task.priority)
    put("percentComplete", if (task.status == MailTask.STATUS_DONE) 100 else -1)
    put("createdUtcMillis", task.createdMillis ?: -1L)
    put("lastModifiedUtcMillis", -1L)
    put("completedUtcMillis", -1L)
    put("unknownLines", buildJsonArray { extraLines(task).forEach { add(JsonPrimitive(it)) } })
}.toString()

/**
 * A task-store VTODO read back as a [MailTask], or null when it is not attached to a mail (no
 * X-CLOUD-MAIL line). Reads both shapes: the full CalTodo JSON (`dueUtcMillis`, `unknownLines`).
 */
fun mailTaskFromTodoJson(json: String): MailTask? = runCatching {
    val o = Json.parseToJsonElement(json).jsonObject
    mailTaskFromTodo(o)
}.getOrNull()

private fun JsonObject.str(k: String): String = this[k]?.jsonPrimitive?.contentOrNull.orEmpty()

private fun mailTaskFromTodo(o: JsonObject): MailTask? {
    val lines = (o["unknownLines"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
    var account: String? = null
    var email: String? = null
    var subject = ""
    var target: Long? = null
    var location = ""
    var messageId: String? = null
    val links = ArrayList<String>()
    val people = ArrayList<String>()
    for (line in lines) {
        val colon = line.indexOf(':')
        if (colon < 0) continue
        val name = line.substring(0, colon).substringBefore(';').uppercase()
        val value = line.substring(colon + 1)
        when (name) {
            X_MAIL -> {
                val cut = Regex("(?<!\\\\);").find(value)?.range?.first ?: continue
                account = unescape(value.substring(0, cut)); email = unescape(value.substring(cut + 1))
            }
            X_MAIL_SUBJECT -> subject = unescape(value)
            "DTSTART" -> target = millisOf(value)
            "LOCATION" -> location = unescape(value)
            "URL" -> if (value.startsWith("mid:")) messageId = value.removePrefix("mid:")
            X_LINK -> links += unescape(value)
            "ATTENDEE" -> people += value.removePrefix("mailto:")
            X_PERSON -> people += unescape(value)
        }
    }
    if (account == null || email == null) return null
    fun long(k: String): Long? = o[k]?.jsonPrimitive?.longOrNull?.takeIf { it >= 0 }
    return MailTask(
        uid = o.str("uid"),
        title = o.str("summary"),
        accountId = account,
        emailId = email,
        mailSubject = subject,
        messageId = messageId,
        deadlineMillis = long("dueUtcMillis"),
        targetMillis = target,
        notes = o.str("description"),
        links = links,
        location = location,
        priority = o["priority"]?.jsonPrimitive?.intOrNull ?: 0,
        status = o.str("status").ifBlank { MailTask.STATUS_TODO },
        people = people,
        collectionId = o.str("collectionId"),
        createdMillis = long("createdUtcMillis"),
    )
}

/** Mail's own copy of its tasks (what Assigned lists, engine or not): a JSON array of todo JSON. */
fun encodeTaskList(tasks: List<MailTask>): String =
    JsonArray(tasks.map { Json.parseToJsonElement(toTodoJson(it)) }).toString()

fun decodeTaskList(json: String?): List<MailTask> = runCatching {
    Json.parseToJsonElement(json ?: return emptyList()).jsonArray.mapNotNull { e ->
        (e as? JsonObject)?.let { mailTaskFromTodo(it) }
    }
}.getOrDefault(emptyList())

/** [tasks] with [task] put in (replacing the one with its uid). */
fun upsertTask(tasks: List<MailTask>, task: MailTask): List<MailTask> =
    tasks.filterNot { it.uid == task.uid } + task

/** The tasks attached to the mail [accountId]/[emailId]. */
fun tasksOfMail(tasks: List<MailTask>, accountId: String, emailId: String): List<MailTask> =
    tasks.filter { it.accountId == accountId && it.emailId == emailId }
