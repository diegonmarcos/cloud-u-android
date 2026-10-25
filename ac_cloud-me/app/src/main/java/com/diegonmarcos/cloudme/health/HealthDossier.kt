package com.diegonmarcos.cloudme.health

import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class DossierSection(val id: String, val title: String, val lines: List<String>)

/**
 * The physician dossier: a plain-text report assembled from the declared plan, the user's own entries and the
 * Health Connect readings the page was handed, exported as a PDF. The PDF is WRITTEN with the platform's
 * PdfDocument (no renderer) and OPENED through the manifest by the fleet's one PDF engine, Cloud Drive's reader
 * (one-pdf-engine guard): this app ships no PDF renderer.
 */
object HealthDossier {
    const val AUTHORITY_SUFFIX = ".health.files"
    val ALL_SECTIONS = listOf("profile", "labs", "plan", "trackers", "notes")
    val TITLES = mapOf("profile" to "Anthropometrics", "labs" to "Biomarkers", "plan" to "Protocol", "trackers" to "Tracker summary", "notes" to "Notes for the physician")

    fun build(c: HealthContent, u: HealthUserData, include: Set<String>, trackerLines: List<String>): List<DossierSection> {
        val out = mutableListOf<DossierSection>()
        if ("profile" in include) {
            val m = u.latestMeasure()
            val a = m?.let { HealthFormulas.anthro(c.formulas, it.massKg, it.heightCm, it.bfPct, u.activity) }
            out += DossierSection("profile", TITLES.getValue("profile"), when {
                m == null -> listOf("No measurement entered.")
                a == null -> listOf("Latest entry is incomplete: mass, height and body fat are all needed.")
                else -> listOf(
                    "Mass ${HealthFormulas.num(m.massKg ?: 0.0)} kg, height ${HealthFormulas.num(m.heightCm ?: 0.0)} cm, body fat ${HealthFormulas.num(m.bfPct ?: 0.0)} %",
                    "Lean body mass %.1f kg, FFMI %.1f (%s)".format(Locale.US, a.lbm, a.ffmi, a.ffmiBand),
                    "BMR ${a.bmr} kcal, TDEE ${a.tdee} kcal",
                )
            })
        }
        if ("labs" in include) {
            out += DossierSection("labs", TITLES.getValue("labs"), c.plan.biomarkers.map { b ->
                val l = u.labsFor(b.id).lastOrNull()
                if (l == null) "${b.label}: no result entered (target ${HealthFormulas.rangeText(b)})"
                else "${b.label}: ${HealthFormulas.num(l.value)} ${b.unit} on ${date(l.t)}, ${HealthFormulas.biomarkerStatus(b, l.value).name} (target ${HealthFormulas.rangeText(b)})"
            })
        }
        if ("plan" in include) {
            out += DossierSection("plan", TITLES.getValue("plan"), c.plan.timeline.map { e ->
                val what = (e.items + e.supplements).joinToString(", ") { "${it.name} ${HealthFormulas.num(it.qty)} ${it.unit}" }
                "${e.time} ${e.window}: ${e.rule}" + if (what.isNotBlank()) " - $what" else ""
            })
        }
        if ("trackers" in include) {
            out += DossierSection("trackers", TITLES.getValue("trackers"), trackerLines.ifEmpty { listOf("No Health Connect data was available when this report was made.") })
        }
        if ("notes" in include && u.dossierNotes.isNotBlank()) out += DossierSection("notes", TITLES.getValue("notes"), u.dossierNotes.lines())
        return out
    }

    private fun date(t: Long) = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(t))

    /** Greedy word wrap to [maxChars]. */
    fun wrap(line: String, maxChars: Int): List<String> {
        if (line.length <= maxChars) return listOf(line)
        val out = mutableListOf<String>()
        var cur = StringBuilder()
        for (w in line.split(' ')) {
            if (cur.isNotEmpty() && cur.length + 1 + w.length > maxChars) { out += cur.toString(); cur = StringBuilder() }
            if (cur.isNotEmpty()) cur.append(' ')
            cur.append(w)
        }
        if (cur.isNotEmpty()) out += cur.toString()
        return out
    }

    /** Writes [sections] as an A4 PDF to [file]; returns the page count. */
    fun writePdf(file: File, heading: String, sections: List<DossierSection>): Int {
        val w = 595; val h = 842; val margin = 48f
        val title = Paint().apply { textSize = 18f; typeface = Typeface.DEFAULT_BOLD }
        val sub = Paint().apply { textSize = 12f; typeface = Typeface.DEFAULT_BOLD }
        val body = Paint().apply { textSize = 10f }
        val doc = PdfDocument()
        var pageNo = 0
        var page: PdfDocument.Page? = null
        var y = 0f
        fun newPage() {
            page?.let { doc.finishPage(it) }
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(w, h, pageNo).create())
            y = margin
        }
        fun line(text: String, p: Paint, step: Float) {
            if (page == null || y + step > h - margin) newPage()
            page!!.canvas.drawText(text, margin, y + step - 3f, p)
            y += step
        }
        newPage()
        line(heading, title, 26f)
        for (s in sections) {
            y += 8f
            line(s.title, sub, 18f)
            for (l in s.lines) for (part in wrap(l, 95)) line(part, body, 14f)
        }
        page?.let { doc.finishPage(it) }
        file.parentFile?.mkdirs()
        file.outputStream().use { doc.writeTo(it) }
        doc.close()
        return pageNo
    }

    /** Writes the PDF into the app cache and hands it to whichever app answers VIEW application/pdf (Cloud Drive's reader). */
    fun export(ctx: Context, heading: String, sections: List<DossierSection>, fileName: String = "dossier.pdf"): Boolean {
        val file = File(File(ctx.cacheDir, "health"), fileName)
        writePdf(file, heading, sections)
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + AUTHORITY_SUFFIX, file)
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return runCatching {
            ctx.startActivity(Intent.createChooser(view, heading).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }.isSuccess
    }
}
