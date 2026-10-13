package com.diegonmarcos.clouddrive.manage

/**
 * Manage ▸ Data: the owner's four filter groups and their subtypes, and the ONE classifier that
 * puts a file in a subtype. Extension first (the cheap, exact signal), then the MIME type for a
 * file whose extension says nothing. A file that matches no subtype is [DataKind] null and is
 * never listed: the page shows what the owner asked to see, not "Other".
 */
enum class DataGroup { MEDIA, OFFICE, STRUCTURE, BINS }

enum class DataKind(val group: DataGroup, val extensions: Set<String>) {
    VIDEOS(DataGroup.MEDIA, setOf("mp4", "mkv", "webm", "mov", "avi", "m4v", "3gp", "3g2", "wmv", "flv", "mpg", "mpeg", "m2ts", "vob")),
    PICTURES(DataGroup.MEDIA, setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "tif", "tiff", "svg", "dng", "raw", "cr2", "nef", "arw", "avif", "ico")),
    MUSIC(DataGroup.MEDIA, setOf("mp3", "m4a", "aac", "flac", "ogg", "opus", "wav", "wma", "amr", "mid", "midi", "aiff", "alac")),
    PDF(DataGroup.OFFICE, setOf("pdf")),
    OFFICE(DataGroup.OFFICE, setOf("doc", "docx", "xls", "xlsx", "xlsm", "ppt", "pptx", "odt", "ods", "odp", "odg", "rtf", "pages", "numbers", "key", "epub")),
    STRUCTURED(DataGroup.STRUCTURE, setOf("json", "jsonl", "ndjson", "xml", "yaml", "yml", "toml", "csv", "tsv", "ini", "conf", "cfg", "plist", "properties", "env")),
    MARKDOWN(DataGroup.STRUCTURE, setOf("md", "markdown", "mdx", "rst", "org")),
    TEXT(DataGroup.STRUCTURE, setOf("txt", "log", "text")),
    CODE(DataGroup.STRUCTURE, setOf("kt", "kts", "java", "c", "h", "cpp", "cc", "cxx", "hpp", "cs", "go", "rs", "swift", "ts", "tsx", "jsx", "scala", "dart", "m", "mm", "vue", "svelte", "html", "htm", "css", "scss", "sass", "less", "sql", "gradle", "nix", "hs", "ex", "exs", "erl", "clj", "zig")),
    BINARIES(DataGroup.BINS, setOf("bin", "exe", "dll", "so", "o", "a", "dex", "elf", "img", "iso", "dylib", "class", "jar", "aar", "wasm", "odex", "vdex", "oat", "dat", "db", "sqlite")),
    APK(DataGroup.BINS, setOf("apk", "apks", "xapk", "apkm", "aab")),
    ARCHIVES(DataGroup.BINS, setOf("zip", "7z", "tar", "gz", "tgz", "bz2", "tbz2", "xz", "txz", "zst", "lz4", "lzma", "rar", "cab", "z")),
    SCRIPTS(DataGroup.BINS, setOf("sh", "bash", "zsh", "fish", "py", "js", "mjs", "cjs", "rb", "pl", "php", "lua", "ps1", "bat", "cmd", "awk", "r", "tcl"));

    companion object {
        fun inGroup(g: DataGroup): List<DataKind> = entries.filter { it.group == g }
    }
}

object FileClassifier {
    private val byExtension: Map<String, DataKind> = buildMap { DataKind.entries.forEach { k -> k.extensions.forEach { put(it, k) } } }

    fun extensionOf(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot <= 0 || dot == name.length - 1) "" else name.substring(dot + 1).lowercase()
    }

    /** The subtype of a file named [name] with MIME type [mime] (null when unknown), or null when it is none of them. */
    fun classify(name: String, mime: String? = null): DataKind? {
        byExtension[extensionOf(name)]?.let { return it }
        val m = mime?.lowercase()?.substringBefore(';')?.trim().orEmpty()
        return when {
            m.isEmpty() -> null
            m.startsWith("video/") -> DataKind.VIDEOS
            m.startsWith("image/") -> DataKind.PICTURES
            m.startsWith("audio/") -> DataKind.MUSIC
            m == "application/pdf" -> DataKind.PDF
            m == "application/vnd.android.package-archive" -> DataKind.APK
            m.startsWith("application/vnd.openxmlformats-officedocument") || m.startsWith("application/vnd.oasis.opendocument") || m == "application/msword" || m.startsWith("application/vnd.ms-") -> DataKind.OFFICE
            m in setOf("application/zip", "application/x-7z-compressed", "application/x-tar", "application/gzip", "application/x-rar-compressed", "application/vnd.rar", "application/x-bzip2", "application/x-xz") -> DataKind.ARCHIVES
            m == "application/json" || m.endsWith("+json") || m.endsWith("/xml") || m.endsWith("+xml") || m == "text/csv" || m.contains("yaml") || m.contains("toml") -> DataKind.STRUCTURED
            m == "text/markdown" -> DataKind.MARKDOWN
            m == "text/x-shellscript" || m == "text/x-python" || m == "text/javascript" || m == "application/javascript" || m == "application/x-sh" -> DataKind.SCRIPTS
            m.startsWith("text/x-") -> DataKind.CODE
            m == "text/plain" -> DataKind.TEXT
            m == "application/x-executable" || m == "application/x-sharedlib" || m == "application/octet-stream" -> DataKind.BINARIES
            else -> null
        }
    }
}

/** Tri-state of a group toggle: every subtype on, some on, none on. */
enum class GroupState { ALL, PARTIAL, NONE }

/**
 * The Data page's filter: the set of subtypes switched on. Default is the Media group only. Each
 * subtype toggles on its own; the group toggle turns the whole group on (from NONE or PARTIAL) or
 * off (from ALL), and reports PARTIAL when only some of its subtypes are on.
 */
data class DataFilter(val on: Set<DataKind>) {
    fun groupState(g: DataGroup): GroupState {
        val kinds = DataKind.inGroup(g)
        val n = kinds.count { it in on }
        return when (n) { 0 -> GroupState.NONE; kinds.size -> GroupState.ALL; else -> GroupState.PARTIAL }
    }

    fun toggleGroup(g: DataGroup): DataFilter =
        if (groupState(g) == GroupState.ALL) DataFilter(on - DataKind.inGroup(g).toSet()) else DataFilter(on + DataKind.inGroup(g))

    fun toggle(k: DataKind): DataFilter = DataFilter(if (k in on) on - k else on + k)

    fun accepts(k: DataKind?): Boolean = k != null && k in on

    fun encode(): String = DataKind.entries.filter { it in on }.joinToString(",") { it.name }

    companion object {
        val DEFAULT = DataFilter(DataKind.inGroup(DataGroup.MEDIA).toSet())
        /** [text] as written by [encode]; null or unreadable → [DEFAULT]. An explicit empty string is a filter with nothing on. */
        fun decode(text: String?): DataFilter {
            if (text == null) return DEFAULT
            if (text.isBlank()) return DataFilter(emptySet())
            val kinds = text.split(',').mapNotNull { n -> DataKind.entries.firstOrNull { it.name == n.trim() } }.toSet()
            return if (kinds.isEmpty()) DEFAULT else DataFilter(kinds)
        }
    }
}
