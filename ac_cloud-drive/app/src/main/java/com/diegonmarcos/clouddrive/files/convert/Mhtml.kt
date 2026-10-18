package com.diegonmarcos.clouddrive.files.convert

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.URI
import java.nio.charset.Charset
import java.util.Base64

/**
 * MHTML / MHT (a MIME `multipart/related` page, what Chrome's "Save as MHTML" and cloud-browser
 * write) → one `.html` file plus an assets folder next to it.
 *
 * The parts are written to the folder; every `cid:` reference and every reference that matches a
 * part's `Content-Location` (absolute, or relative to the page or its `<base>`) in the page, in
 * stylesheets and in saved frames is rewritten to a RELATIVE path, so the result opens from any
 * file manager or browser with no network. Pure JVM (no Android type), so MhtmlConverterTest runs
 * the real parser and the real rewriting on the CI runner.
 */
object Mhtml {

    /** A decoded leaf part. [headers] keys are lower-case; the first occurrence of a header wins. */
    class Part(val headers: Map<String, String>, val body: ByteArray) {
        val type: String get() = (headers["content-type"] ?: "").substringBefore(';').trim().lowercase()
        val location: String? get() = headers["content-location"]?.trim()?.takeIf { it.isNotEmpty() }
        val contentId: String? get() = headers["content-id"]?.trim()?.removePrefix("<")?.removeSuffix(">")?.trim()?.takeIf { it.isNotEmpty() }
        val charset: String? get() = param(headers["content-type"], "charset")
    }

    class Output(val html: File, val assetsDir: File?, val assets: List<File>)

    const val MAX_BYTES = 48L * 1024 * 1024

    // ── parse ────────────────────────────────────────────────────────────────

    /** Every leaf part of [raw], nested multiparts flattened, bodies already transfer-decoded. */
    fun parse(raw: ByteArray): List<Part> {
        if (raw.size > MAX_BYTES) throw IOException("this MHTML is larger than ${MAX_BYTES / (1024 * 1024)} MB; it cannot be converted on the phone")
        val text = String(raw, Charsets.ISO_8859_1) // 1 char = 1 byte: slices convert back losslessly
        val out = ArrayList<Part>()
        entity(text, 0, text.length, out, 0)
        return out
    }

    private fun entity(text: String, start: Int, end: Int, out: MutableList<Part>, depth: Int) {
        if (depth > 8) return
        val headerEnd: Int
        val bodyStart: Int
        when {
            text.startsWith("\r\n", start) -> { headerEnd = start; bodyStart = start + 2 }
            text.startsWith("\n", start) -> { headerEnd = start; bodyStart = start + 1 }
            else -> {
                val a = text.indexOf("\r\n\r\n", start)
                val b = text.indexOf("\n\n", start)
                val useA = a >= 0 && (b < 0 || a <= b)
                val idx = if (useA) a else b
                if (idx < 0 || idx >= end) { headerEnd = end; bodyStart = end }
                else { headerEnd = idx; bodyStart = idx + (if (useA) 4 else 2) }
            }
        }
        val headers = parseHeaders(text.substring(start, minOf(headerEnd, end)))
        if (depth == 0 && headers.isEmpty()) return // no MIME headers at all: not an MHTML file
        val bodyEnd = end
        val ctype = headers["content-type"] ?: ""
        val lowType = ctype.substringBefore(';').trim().lowercase()
        val boundary = param(ctype, "boundary")
        if (lowType.startsWith("multipart/") && boundary != null) {
            for (seg in split(text, minOf(bodyStart, bodyEnd), bodyEnd, boundary)) entity(text, seg[0], seg[1], out, depth + 1)
            return
        }
        val encoding = (headers["content-transfer-encoding"] ?: "").trim().lowercase()
        val slice = text.substring(minOf(bodyStart, bodyEnd), bodyEnd)
        out += Part(headers, decode(slice, encoding))
    }

    /** The [start, end) ranges between the `--boundary` lines of a multipart body. */
    private fun split(text: String, bs: Int, be: Int, boundary: String): List<IntArray> {
        val delim = "--$boundary"
        val segs = ArrayList<IntArray>()
        var segStart = -1
        var pos = bs
        while (pos < be) {
            val nl = text.indexOf('\n', pos)
            val lineEnd = if (nl < 0 || nl >= be) be else nl
            if (text.startsWith(delim, pos)) {
                val tail = text.substring(pos + delim.length, lineEnd).trim()
                if (tail.isEmpty() || tail == "--") {
                    if (segStart >= 0) {
                        var e = pos
                        if (e > segStart && text[e - 1] == '\n') e--
                        if (e > segStart && text[e - 1] == '\r') e--
                        segs += intArrayOf(segStart, e)
                    }
                    if (tail == "--") { segStart = -1; break }
                    segStart = minOf(lineEnd + 1, be)
                }
            }
            pos = lineEnd + 1
        }
        if (segStart in 0 until be) segs += intArrayOf(segStart, be)
        return segs
    }

    private fun parseHeaders(block: String): Map<String, String> {
        val lines = ArrayList<String>()
        for (line in block.split("\n")) {
            val l = line.trimEnd('\r')
            if ((l.startsWith(" ") || l.startsWith("\t")) && lines.isNotEmpty()) lines[lines.size - 1] = lines.last() + " " + l.trim()
            else if (l.isNotEmpty()) lines += l
        }
        val map = LinkedHashMap<String, String>()
        for (l in lines) {
            val i = l.indexOf(':')
            if (i <= 0) continue
            map.getOrPut(l.substring(0, i).trim().lowercase()) { l.substring(i + 1).trim() }
        }
        return map
    }

    /** `name=value` or `name="value"` out of a header such as `multipart/related; boundary="x"`. */
    internal fun param(header: String?, name: String): String? {
        if (header == null) return null
        val m = Regex("""(?:^|[;\s])${Regex.escape(name)}\s*=\s*(?:"([^"]*)"|([^;\s]+))""", RegexOption.IGNORE_CASE).find(header) ?: return null
        return m.groups[1]?.value ?: m.groups[2]?.value
    }

    private fun decode(slice: String, encoding: String): ByteArray = when (encoding) {
        "base64" -> try { Base64.getMimeDecoder().decode(slice) } catch (e: IllegalArgumentException) { ByteArray(0) }
        "quoted-printable" -> quotedPrintable(slice)
        else -> slice.toByteArray(Charsets.ISO_8859_1)
    }

    internal fun quotedPrintable(s: String): ByteArray {
        val out = ByteArrayOutputStream(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '=') { out.write(c.code); i++; continue }
            if (s.startsWith("\r\n", i + 1)) { i += 3; continue }
            if (s.startsWith("\n", i + 1)) { i += 2; continue }
            val h = hexPair(s, i + 1)
            if (h >= 0) { out.write(h); i += 3 } else { out.write('='.code); i++ }
        }
        return out.toByteArray()
    }

    private fun hexPair(s: String, at: Int): Int {
        if (at + 1 >= s.length) return -1
        val hi = Character.digit(s[at], 16)
        val lo = Character.digit(s[at + 1], 16)
        return if (hi < 0 || lo < 0) -1 else hi * 16 + lo
    }

    // ── convert ──────────────────────────────────────────────────────────────

    private val EXT_BY_TYPE = mapOf(
        "text/html" to "html", "application/xhtml+xml" to "html", "text/css" to "css",
        "text/javascript" to "js", "application/javascript" to "js", "application/x-javascript" to "js",
        "image/png" to "png", "image/jpeg" to "jpg", "image/jpg" to "jpg", "image/gif" to "gif", "image/webp" to "webp",
        "image/svg+xml" to "svg", "image/x-icon" to "ico", "image/vnd.microsoft.icon" to "ico", "image/avif" to "avif",
        "font/woff" to "woff", "font/woff2" to "woff2", "application/font-woff" to "woff", "font/ttf" to "ttf", "font/otf" to "otf",
        "application/json" to "json", "text/plain" to "txt", "video/mp4" to "mp4", "audio/mpeg" to "mp3",
    )

    private fun isMainType(t: String) = t == "text/html" || t == "application/xhtml+xml"

    private fun charsetOf(p: Part): Charset = try { p.charset?.let { Charset.forName(it) } ?: Charsets.UTF_8 } catch (e: Exception) { Charsets.UTF_8 }

    /**
     * Writes [htmlFile] and, when the page has more than its own HTML, [assetsDir] with one file per
     * other part. Both are created by this call; the caller picks names that do not exist yet.
     */
    @Throws(IOException::class)
    fun convert(raw: ByteArray, htmlFile: File, assetsDir: File): Output {
        val parts = parse(raw)
        if (parts.isEmpty()) throw IOException("no MIME parts found: this is not an MHTML file")
        val main = parts.firstOrNull { isMainType(it.type) } ?: parts.first()
        val assets = parts.filter { it !== main }

        // Names first: every reference needs its target name before any text is rewritten.
        val used = HashSet<String>()
        val nameOf = LinkedHashMap<Part, String>()
        assets.forEachIndexed { i, p -> nameOf[p] = uniqueName(used, fileNameFor(p, i + 1)) }
        val byId = HashMap<String, String>()
        val byLocation = HashMap<String, String>()
        for ((p, name) in nameOf) {
            p.contentId?.let { byId.putIfAbsent(it, name) }
            p.location?.let { byLocation.putIfAbsent(stripFragment(it), name) }
        }

        var html = String(main.body, charsetOf(main))
        var base: String? = main.location
        Regex("""<base\b[^>]*?\bhref\s*=\s*(?:"([^"]*)"|'([^']*)')[^>]*>""", RegexOption.IGNORE_CASE).find(html)?.let { m ->
            base = resolve(main.location, m.groups[1]?.value ?: m.groups[2]?.value ?: "")
            html = html.replace(m.value, "")
        }
        html = withUtf8Meta(rewrite(html, base, byId, byLocation, assetsDir.name.replace(" ", "%20").replace("#", "%23") + "/"))

        htmlFile.parentFile?.mkdirs()
        htmlFile.writeText(html, Charsets.UTF_8)
        val written = ArrayList<File>()
        if (nameOf.isNotEmpty()) {
            assetsDir.mkdirs()
            for ((p, name) in nameOf) {
                val target = File(assetsDir, name)
                val bytes = if (p.type == "text/css" || isMainType(p.type)) {
                    // A stylesheet or a saved frame points at its siblings; they share one folder.
                    rewrite(String(p.body, charsetOf(p)), p.location, byId, byLocation, "").toByteArray(Charsets.UTF_8)
                } else p.body
                target.writeBytes(bytes)
                written += target
            }
        }
        return Output(htmlFile, assetsDir.takeIf { nameOf.isNotEmpty() }, written)
    }

    private fun fileNameFor(p: Part, index: Int): String {
        var n = p.location?.takeUnless { it.startsWith("cid:", ignoreCase = true) }?.substringBefore('#')?.substringBefore('?')?.trimEnd('/')?.substringAfterLast('/') ?: ""
        if (n.isEmpty()) n = p.contentId?.substringBefore('@') ?: ""
        if (n.isEmpty()) n = "part-$index"
        n = n.replace(Regex("[^A-Za-z0-9._-]"), "_").trimStart('.').ifEmpty { "part-$index" }
        if (n.length > 80) n = n.takeLast(80)
        if (!n.contains('.')) n += "." + (EXT_BY_TYPE[p.type] ?: "bin")
        return n
    }

    private fun uniqueName(used: MutableSet<String>, wanted: String): String {
        var candidate = wanted
        val stem = wanted.substringBeforeLast('.', wanted)
        val ext = if (wanted.contains('.')) "." + wanted.substringAfterLast('.') else ""
        var n = 2
        while (!used.add(candidate.lowercase())) { candidate = "$stem-$n$ext"; n++ }
        return candidate
    }

    private fun withUtf8Meta(html: String): String {
        val stripped = html.replace(Regex("""<meta\b[^>]*\bcharset\s*=[^>]*>""", RegexOption.IGNORE_CASE), "")
        val head = Regex("""<head\b[^>]*>""", RegexOption.IGNORE_CASE).find(stripped)
        return if (head != null) stripped.substring(0, head.range.last + 1) + "<meta charset=\"utf-8\">" + stripped.substring(head.range.last + 1)
        else "<meta charset=\"utf-8\">\n$stripped"
    }

    // ── reference rewriting ──────────────────────────────────────────────────

    private val ATTR = Regex("""(?<![\w-])(src|href|poster|background|data-src|data|action)(\s*=\s*)("([^"]*)"|'([^']*)')""", RegexOption.IGNORE_CASE)
    private val SRCSET = Regex("""(?<![\w-])srcset(\s*=\s*)("([^"]*)"|'([^']*)')""", RegexOption.IGNORE_CASE)
    private val CSS_URL = Regex("""url\(\s*(["']?)([^"')]+)\1\s*\)""", RegexOption.IGNORE_CASE)
    private val CSS_IMPORT = Regex("""@import\s+(["'])([^"']+)\1""", RegexOption.IGNORE_CASE)

    /** [text] with every reference that names a saved part pointed at [prefix] + that part's file. */
    internal fun rewrite(text: String, base: String?, byId: Map<String, String>, byLocation: Map<String, String>, prefix: String): String {
        fun target(ref: String): String? {
            val url = ref.replace("&amp;", "&").trim()
            if (url.isEmpty() || url.startsWith("#") || url.startsWith("data:", true) || url.startsWith("javascript:", true)) return null
            val name = if (url.startsWith("cid:", true)) {
                val id = url.substring(4).trim().removePrefix("<").removeSuffix(">")
                byId[id] ?: byId[percentDecode(id)]
            } else {
                byLocation[stripFragment(resolve(base, url))] ?: byLocation[stripFragment(url)]
            }
            return name?.let { prefix + it }
        }
        var out = ATTR.replace(text) { m ->
            val quoted = m.groups[3]!!.value
            val q = quoted[0]
            val value = m.groups[4]?.value ?: m.groups[5]?.value ?: ""
            val t = target(value)
            if (t == null) m.value else m.groups[1]!!.value + m.groups[2]!!.value + q + t + q
        }
        out = SRCSET.replace(out) { m ->
            val quoted = m.groups[2]!!.value
            val q = quoted[0]
            val value = m.groups[3]?.value ?: m.groups[4]?.value ?: ""
            val rebuilt = value.split(",").joinToString(", ") { cand ->
                val bits = cand.trim().split(Regex("\\s+"), limit = 2)
                val t = target(bits[0])
                if (bits[0].isEmpty()) cand.trim() else (t ?: bits[0]) + (if (bits.size > 1) " " + bits[1] else "")
            }
            "srcset" + m.groups[1]!!.value + q + rebuilt + q
        }
        out = CSS_URL.replace(out) { m ->
            val t = target(m.groups[2]!!.value)
            if (t == null) m.value else "url(" + m.groups[1]!!.value + t + m.groups[1]!!.value + ")"
        }
        out = CSS_IMPORT.replace(out) { m ->
            val t = target(m.groups[2]!!.value)
            if (t == null) m.value else "@import " + m.groups[1]!!.value + t + m.groups[1]!!.value
        }
        return out
    }

    private fun resolve(base: String?, ref: String): String {
        if (base == null) return ref
        return try { URI(base).resolve(URI(ref)).toString() } catch (e: Exception) { ref }
    }

    private fun stripFragment(url: String) = url.substringBefore('#')

    private fun percentDecode(s: String): String = try { java.net.URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") } catch (e: Exception) { s }
}
