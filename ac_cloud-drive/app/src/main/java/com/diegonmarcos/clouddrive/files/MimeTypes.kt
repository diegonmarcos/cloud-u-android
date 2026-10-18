package com.diegonmarcos.clouddrive.files

/**
 * The MIME type Open with… puts on its ACTION_VIEW. Android's MimeTypeMap is the first word; this
 * table is what it does not know (md, mht, 7z, tgz, jar…), because `application/octet-stream`
 * makes the system chooser offer nothing useful for those. Pure, so the JVM suite holds it.
 */
object MimeTypes {

    const val OCTET = "application/octet-stream"

    private val FALLBACK: Map<String, String> = mapOf(
        "md" to "text/markdown", "markdown" to "text/markdown",
        "mht" to "multipart/related", "mhtml" to "multipart/related",
        "html" to "text/html", "htm" to "text/html", "xhtml" to "application/xhtml+xml",
        "json" to "application/json", "txt" to "text/plain", "log" to "text/plain", "csv" to "text/csv",
        "xml" to "text/xml", "yml" to "text/plain", "yaml" to "text/plain", "toml" to "text/plain",
        "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "webp" to "image/webp", "gif" to "image/gif",
        "pdf" to "application/pdf",
        "zip" to "application/zip", "jar" to "application/java-archive", "apk" to "application/vnd.android.package-archive",
        "tar" to "application/x-tar", "gz" to "application/gzip", "tgz" to "application/gzip",
        "xz" to "application/x-xz", "txz" to "application/x-xz", "7z" to "application/x-7z-compressed",
        "rar" to "application/vnd.rar",
    )

    /**
     * [system] is the platform's answer for the extension (null when it has none). Wins when it is
     * specific; the table fills in when it is missing or only `octet-stream`.
     */
    fun forName(fileName: String, system: (String) -> String? = { null }): String {
        val ext = FileOps.extensionOf(fileName)
        if (ext.isEmpty()) return OCTET
        val known = system(ext)
        if (known != null && known != OCTET) return known
        return FALLBACK[ext] ?: OCTET
    }
}
