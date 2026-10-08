package com.diegonmarcos.cloudsearch.data

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import com.diegonmarcos.cloudsearch.core.agents.AgentsConfig
import com.diegonmarcos.cloudsearch.core.agents.Ipc
import com.diegonmarcos.cloudsearch.core.agents.MailBody
import com.diegonmarcos.cloudsearch.core.agents.MailHeader
import com.diegonmarcos.cloudsearch.core.agents.MailSource
import com.diegonmarcos.cloudsearch.core.agents.PageSource
import com.diegonmarcos.cloudsearch.core.agents.PageText
import com.diegonmarcos.cloudsearch.core.agents.Report
import com.diegonmarcos.cloudsearch.core.agents.ReportBook
import com.diegonmarcos.cloudsearch.core.agents.ReportSink
import com.diegonmarcos.cloudsearch.core.agents.RunRecord
import com.diegonmarcos.cloudsearch.core.agents.BudgetLedger
import org.json.JSONArray
import java.io.File
import java.time.ZoneId

/**
 * #913 the Agents tab's durable state. Everything the owner types or an agent produces stays on this phone:
 * the profile and templates in preferences, runs and reports in files under filesDir. The OpenRouter token is
 * not here and never is: it is read from the fleet Account for each model call.
 */
class AgentPrefs(private val p: SharedPreferences, private val cfg: AgentsConfig, private val defaultModelFallback: String) {
    var model: String
        get() = p.getString("agents_model", cfg.defaults.model) ?: defaultModelFallback
        set(v) = p.edit().putString("agents_model", v).apply()
    var budgetRunUsd: Double
        get() = p.getFloat("agents_budget_run", cfg.defaults.budgetRunUsd.toFloat()).toDouble()
        set(v) = p.edit().putFloat("agents_budget_run", v.toFloat()).apply()
    var budgetDayUsd: Double
        get() = p.getFloat("agents_budget_day", cfg.defaults.budgetDayUsd.toFloat()).toDouble()
        set(v) = p.edit().putFloat("agents_budget_day", v.toFloat()).apply()
    var maxListings: Int
        get() = p.getInt("agents_max_listings", cfg.defaults.maxListings)
        set(v) = p.edit().putInt("agents_max_listings", v).apply()
    var lookbackDays: Int
        get() = p.getInt("agents_lookback_days", cfg.defaults.lookbackDays)
        set(v) = p.edit().putInt("agents_lookback_days", v).apply()

    fun profile(): Map<String, String> = cfg.profileFields.associate { it.id to (p.getString("agents_profile_${it.id}", "") ?: "") }
    fun setProfile(id: String, value: String) = p.edit().putString("agents_profile_$id", value).apply()

    /** The owner's version of a template, else the one the app ships. */
    fun templateBody(id: String): String = p.getString("agents_template_$id", null) ?: cfg.template(id)?.body.orEmpty()
    fun setTemplate(id: String, body: String) = p.edit().putString("agents_template_$id", body).apply()
    fun resetTemplate(id: String) = p.edit().remove("agents_template_$id").apply()
    fun isCustomised(id: String): Boolean = p.contains("agents_template_$id")

    fun seen(agent: String): Set<String> = runCatching {
        val a = JSONArray(p.getString("agents_seen_$agent", "[]")); (0 until a.length()).map { a.getString(it) }.toSet()
    }.getOrDefault(emptySet())

    fun addSeen(agent: String, ids: Set<String>) {
        if (ids.isEmpty()) return
        val all = (seen(agent) + ids).toList().takeLast(MAX_SEEN)
        p.edit().putString("agents_seen_$agent", JSONArray(all).toString()).apply()
    }

    /** US dollars spent today by every agent run, from the model calls' recorded costs. */
    fun spentToday(now: Long): Double = p.getFloat("agents_spent_" + BudgetLedger.dayKey(now, ZoneId.systemDefault()), 0f).toDouble()

    @Synchronized fun addSpend(now: Long, usd: Double) {
        val key = "agents_spent_" + BudgetLedger.dayKey(now, ZoneId.systemDefault())
        val e = p.edit()
        // Yesterday's figures are not kept.
        p.all.keys.filter { it.startsWith("agents_spent_") && it != key }.forEach { e.remove(it) }
        e.putFloat(key, (p.getFloat(key, 0f) + usd).toFloat()).apply()
    }

    private companion object { const val MAX_SEEN = 2000 }
}

/** A JSON text file under filesDir, read and written whole. */
class JsonFile(private val file: File) {
    @Synchronized fun read(): String = runCatching { file.readText() }.getOrDefault("")
    @Synchronized fun write(text: String) { file.parentFile?.mkdirs(); file.writeText(text) }
}

class RunStore(private val f: JsonFile) {
    @Synchronized fun all(): List<RunRecord> = RunRecord.listFromJson(f.read())
    @Synchronized fun add(r: RunRecord) {
        val next = (listOf(r) + all().filterNot { it.id == r.id }).sortedByDescending { it.startedAt }.take(RunRecord.MAX_RUNS)
        f.write(JSONArray(next.map { it.toJson() }).toString())
    }
}

/** The reports agents publish: the local store and the [ReportSink] an agent writes through. */
class ReportStore(private val f: JsonFile) : ReportSink {
    @Synchronized private fun book() = ReportBook.fromJson(f.read())
    @Synchronized private fun save(b: ReportBook) = f.write(b.toJson().toString())

    @Synchronized override fun publish(report: Report) { val b = book(); b.publish(report); save(b) }
    @Synchronized fun newestFirst(): List<Report> = book().newestFirst()
    @Synchronized fun get(id: String): Report? = book().get(id)
    @Synchronized fun setItemStatus(reportId: String, itemId: String, status: String) { val b = book(); b.setItemStatus(reportId, itemId, status); save(b) }
    @Synchronized fun delete(id: String) { val b = book(); b.delete(id); save(b) }
}

/**
 * Cloud Mail's read-only agent door. A refusal (the permission not granted, Cloud Mail not installed) is
 * said in words and stops the run; nothing here can change a message.
 */
class MailDoor(private val ctx: Context, private val mail: AgentsConfig.MailEngine, private val mailPackage: String) : MailSource {
    private fun query(uri: String): android.database.Cursor? = try {
        ctx.contentResolver.query(Uri.parse(uri), null, null, null, null)
    } catch (e: SecurityException) {
        throw IllegalStateException("Cloud Mail refused the read: this app is not signed with the constellation key, or the permission is not granted")
    } catch (e: IllegalArgumentException) {
        throw IllegalStateException("Cloud Mail refused the question: ${e.message}")
    }

    override fun messages(from: String, subject: String, sinceMs: Long, limit: Int): List<MailHeader> {
        val c = query(Ipc.messagesUri(mail, mailPackage, from, subject, sinceMs, limit))
            ?: throw IllegalStateException("Cloud Mail is not installed or does not offer the agent door yet")
        c.use {
            Ipc.missingColumn(it.columnNames.toList(), mail.messageColumns)?.let { col -> throw IllegalStateException("Cloud Mail's answer has no $col column") }
            fun s(name: String) = it.getString(it.getColumnIndexOrThrow(name)).orEmpty()
            val out = ArrayList<MailHeader>()
            while (it.moveToNext()) {
                out += MailHeader(
                    s("id"), s("account_id"), s("subject"), s("from_name"), s("from_email"), s("received_at"),
                    it.getLong(it.getColumnIndexOrThrow("sort_key")), it.getInt(it.getColumnIndexOrThrow("seen")) != 0,
                )
            }
            return out
        }
    }

    override fun body(accountId: String, id: String): MailBody? {
        val c = query(Ipc.bodyUri(mail, mailPackage, accountId, id)) ?: return null
        c.use {
            if (!it.moveToFirst()) return null
            return MailBody(
                it.getString(it.getColumnIndexOrThrow("text")).orEmpty(), it.getString(it.getColumnIndexOrThrow("html")).orEmpty(),
                it.getInt(it.getColumnIndexOrThrow("truncated")) != 0,
            )
        }
    }
}

/**
 * Cloud Browser's agent door: [text] is one read-only GET done by the browser, [open] shows a page to the
 * person (optionally in a named tab group). Neither fills, clicks or submits anything.
 */
class BrowserDoor(private val ctx: Context, private val b: AgentsConfig.BrowserEngine, private val browserPackage: String) : PageSource {
    override fun text(url: String, maxChars: Int): PageText {
        val extras = Bundle()
        for ((k, v) in Ipc.fetchExtras(b, url, maxChars)) if (v is Int) extras.putInt(k, v) else extras.putString(k, v.toString())
        val r = try {
            ctx.contentResolver.call(Uri.parse("content://" + Ipc.authority(browserPackage, b.authoritySuffix)), b.fetchMethod, null, extras)
        } catch (e: SecurityException) {
            return PageText(false, url, "", "", false, "Cloud Browser refused: this app is not signed with the constellation key")
        } catch (e: Exception) {
            return PageText(false, url, "", "", false, "Cloud Browser is not reachable (${e.javaClass.simpleName})")
        } ?: return PageText(false, url, "", "", false, "Cloud Browser does not offer the agent door yet")
        return PageText(
            r.getBoolean("ok"), url, r.getString("title").orEmpty(), r.getString("text").orEmpty(), r.getBoolean("truncated"), r.getString("error").orEmpty(),
        )
    }

    /** Show [url] in Cloud Browser, in tab group [group] when given; the system browser if Cloud Browser lacks the door. */
    fun open(url: String, group: String) {
        val i = Intent(b.openAction).setPackage(browserPackage).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        for ((k, v) in Ipc.openExtras(b, url, group)) i.putExtra(k, v)
        try {
            ctx.startActivity(i)
        } catch (e: Exception) {
            Browser.open(ctx, url)
        }
    }
}

/** Opens an installed fleet app by package. */
object Launch {
    fun app(ctx: Context, pkg: String): Boolean {
        val i = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { ctx.startActivity(i) }.isSuccess
    }
}
