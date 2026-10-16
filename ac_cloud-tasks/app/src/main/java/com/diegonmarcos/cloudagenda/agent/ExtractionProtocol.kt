package com.diegonmarcos.cloudagenda.agent

import com.diegonmarcos.cloudagenda.data.ProposalDraft
import com.diegonmarcos.cloudagenda.data.ProposalKind
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/**
 * What the agent asks the model and how it reads the answer. Pure functions, so the wire is tested without a
 * device. The model is asked for JSON only; anything else it says is ignored, and anything in the JSON that does not
 * validate (an unknown kind, a kind the user switched off, a blank title, a date that is not a date) is dropped
 * rather than guessed at.
 */
object ExtractionProtocol {
    const val MAX_BODY_CHARS = 6000
    const val MAX_PER_MAIL = 5
    const val MAX_TITLE = 200
    const val MAX_DETAILS = 1000

    fun prompt(kinds: List<ProposalKind>, subject: String, from: String, receivedOn: String, body: String): String = buildString {
        append("You read one email and extract items the recipient may want as tasks. ")
        append("Extract ONLY these kinds: ").append(kinds.joinToString(", ") { it.id }).append(".\n")
        append("event = something happening at a date/time; booking = a reservation, ticket, appointment or trip; ")
        append("invoice = a bill or payment to make; deadline = something that must be done by a date.\n")
        append("Answer with a JSON array and nothing else. Each element: ")
        append("{\"kind\":\"<kind>\",\"title\":\"<short imperative title>\",\"details\":\"<one or two sentences>\",")
        append("\"date_target\":\"yyyy-MM-dd or null\",\"date_deadline\":\"yyyy-MM-dd or null\",\"location\":\"<place or empty>\",\"confidence\":<0..1>}.\n")
        append("Use null for a date the email does not state. Do not invent dates. If the email has nothing to extract answer [].\n\n")
        append("From: ").append(from).append('\n')
        append("Subject: ").append(subject).append('\n')
        append("Received: ").append(receivedOn).append("\n\n")
        append(body.take(MAX_BODY_CHARS))
    }

    /** The proposals in [reply]. [sourceRef] / [subject] say which mail they came from. Never throws. */
    fun parse(reply: String, wanted: Set<ProposalKind>, sourceRef: String, subject: String): List<ProposalDraft> {
        val open = reply.indexOf('[')
        val close = reply.lastIndexOf(']')
        if (open < 0 || close <= open) return emptyList()
        val array = runCatching { JSONArray(reply.substring(open, close + 1)) }.getOrNull() ?: return emptyList()
        val out = ArrayList<ProposalDraft>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val kind = ProposalKind.of(o.optString("kind").trim().lowercase()) ?: continue
            if (kind !in wanted) continue
            val title = o.optString("title").trim().take(MAX_TITLE)
            if (title.isEmpty()) continue
            out.add(ProposalDraft(
                kind = kind, title = title, details = o.optString("details").trim().take(MAX_DETAILS),
                dateTarget = date(o, "date_target"), dateDeadline = date(o, "date_deadline"),
                location = o.optString("location").trim().takeUnless { it.equals("null", true) } ?: "",
                sourceRef = sourceRef, sourceSubject = subject,
                confidence = o.optDouble("confidence", 0.0).let { if (it.isNaN()) 0.0 else it.coerceIn(0.0, 1.0) },
            ))
            if (out.size >= MAX_PER_MAIL) break
        }
        return out
    }

    private fun date(o: JSONObject, key: String): String? {
        if (o.isNull(key)) return null
        return validDate(o.optString(key))
    }

    /** [s] as yyyy-MM-dd when it is a real date, else null. */
    fun validDate(s: String?): String? {
        val t = s?.trim().orEmpty()
        if (t.isEmpty() || t.equals("null", true)) return null
        return runCatching { LocalDate.parse(t).toString() }.getOrNull()
    }
}
