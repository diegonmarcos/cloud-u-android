// Behavioural check of libs:appstore StoreSuggest + PhoneTaxonomyLayout (pure Kotlin), compiled WITH
// them by test-store-search-landing.sh. Prints PASS/FAIL lines; exit code = number of failures.
//
// args[0] (optional) = the REAL taxonomy as TSV, written by the shell script from SuperApp's
// build.json: "S<TAB>title<TAB>prefix" per ui.phone_sections entry, "F<TAB>id<TAB>label" per
// ui.phone_folders entry in display order. args[1] = where to write the landing's layout for it,
// one "SECTION <title>" / "SHELF <folder id>" line each, which the script compares with SuperApp's
// own order computed independently.
import com.diegonmarcos.superapp.apps.PhoneTaxonomyLayout
import com.diegonmarcos.superapp.appstore.StoreSuggest
import com.diegonmarcos.superapp.appstore.StoreSuggest.Folder
import com.diegonmarcos.superapp.appstore.StoreSuggest.Hit
import com.diegonmarcos.superapp.appstore.StoreSuggest.Suggestion
import java.io.File
import kotlin.system.exitProcess

var fails = 0
fun check(name: String, ok: Boolean, got: Any? = null) {
    if (ok) println("  PASS: $name") else { println("  FAIL: $name (got: $got)"); fails++ }
}
fun eq(name: String, want: Any?, got: Any?) = check("$name = $want", want == got, got)

fun sug(pkg: String, label: String, installed: Boolean = false) = Suggestion(pkg, label, listOf("Our list"), installed)

fun main(args: Array<String>) {
    // ── landing: sections in declared order, one shelf per NON-EMPTY sub-category, Other last ──
    val sections = listOf("Services" to "-", "Tools" to ".", "Media" to ">")
    val folders = listOf(
        Folder("gov", "-Gov"), Folder("money", "-Money"), Folder("docs", ".Docs"), Folder("storage", ".Storage"),
        Folder("video", ">Video"), Folder("misc", "Misc"), Folder("others", "Others"),
    )
    val byPkg = mapOf("a.gov" to "gov", "b.docs" to "docs", "c.docs" to "docs", "d.video" to "video", "e.other" to "others",
        "f.gov" to "gov")
    val ours = listOf(sug("b.docs", "Beta", installed = true), sug("c.docs", "Alpha"), sug("a.gov", "Gov app"),
        sug("d.video", "Vid"), sug("e.other", "Elsewhere"), sug("f.gov", "Another gov", installed = true),
        sug("b.docs", "Beta again"))
    val l = StoreSuggest.landing(sections, folders, ours) { byPkg.getValue(it.pkg) }
    eq("section order follows ui.phone_sections, empty section (none) dropped, unclaimed last",
        listOf("Services", "Tools", "Media", null), l.map { it.title })
    eq("one shelf per non-empty sub-category, in folder order", listOf("gov", "docs", "video", "others"),
        l.flatMap { s -> s.shelves.map { it.folderId } })
    check("empty sub-categories (money, storage, misc) are hidden",
        l.flatMap { s -> s.shelves.map { it.folderId } }.none { it in setOf("money", "storage", "misc") })
    eq("shelf title drops the section prefix", "Docs", l[1].shelves[0].title)
    eq("a prefix-less folder keeps its label", "Others", l[3].shelves[0].title)
    eq("to-install first, then installed, each by label", listOf("c.docs", "b.docs"), l[1].shelves[0].apps.map { it.pkg })
    eq("installed sorts after the suggestion in its shelf", listOf("a.gov", "f.gov"), l[0].shelves[0].apps.map { it.pkg })
    eq("a package listed twice shows once (first entry wins)", "Beta", l[1].shelves[0].apps.first { it.pkg == "b.docs" }.label)
    eq("every app lands on exactly one shelf", 6, l.sumOf { s -> s.shelves.sumOf { it.apps.size } })
    eq("nothing of ours: no section at all", 0, StoreSuggest.landing(sections, folders, emptyList()) { "gov" }.size)

    // the shared bucketing on its own
    val b = PhoneTaxonomyLayout.bucket(sections, listOf(">Video", "Misc", "-Gov", ".Docs")) { it }
    eq("bucket: sections in declared order regardless of folder order", listOf("Services", "Tools", "Media", null), b.map { it.title })
    eq("bucket: unclaimed folders go last", listOf("Misc"), b.last().folders)

    // ── merge: one result per package, every source's badge, declared order ──
    val order = listOf("fleet", "declared", "fdroid", "izzy", "github", "play")
    val hits = listOf(
        Hit("play", "org.x", "X on Play", "X Corp"),
        Hit("fdroid", "org.x", "X", "GPL-3.0", foss = true, repo = "https://github.com/x/X"),
        Hit("declared", "org.x", "", ""),
        Hit("izzy", "org.y", "Y", foss = true, repo = "https://gitlab.com/y/y"),
        Hit("play", "org.y", "Y Play"),
        Hit("github", "", "x/X", "the X repo", foss = true, repo = "http://www.GitHub.com/x/X.git/", link = "https://github.com/x/X/releases"),
        Hit("github", "", "z/Z", "unrelated", foss = true, repo = "https://github.com/z/Z", link = "https://github.com/z/Z/releases"),
        Hit("nowhere", "org.q", "Undeclared source"),
        Hit("play", " org.y ", "Y again"),
    )
    val m = StoreSuggest.merge(order, hits)
    val x = m.firstOrNull { it.pkg == "org.x" }
    eq("merged results: one per package plus one unmatched repo", listOf("org.x", "org.y", ""), m.map { it.pkg })
    eq("org.x carries every source, in declared order (GitHub joined by its sourceCode)",
        listOf("declared", "fdroid", "github", "play"), x?.sources)
    eq("title from the first source that has one", "X", x?.title)
    check("FOSS when any source says so", x?.foss == true, x?.foss)
    eq("a repo-only hit that matches nothing stays a link of its own", "https://github.com/z/Z/releases", m.last().link)
    eq("the GitHub link reaches the merged package", "https://github.com/x/X/releases", x?.link)
    check("a hit from an undeclared source is dropped", m.none { it.pkg == "org.q" })
    eq("whitespace around a package name is the same package", listOf("izzy", "play"), m.first { it.pkg == "org.y" }.sources)
    eq("normalised repo", "github.com/x/x", StoreSuggest.normaliseRepo("http://www.GitHub.com/x/X.git/"))

    // ── groups: under the first source that found it, installed first, every source keeps its place ──
    val g = StoreSuggest.groups(order, StoreSuggest.merge(order, listOf(
        Hit("play", "p.one", "One"), Hit("play", "p.two", "Two"), Hit("play", "p.three", "Three"),
        Hit("fdroid", "p.two", "Two"),
    ))) { it == "p.three" }
    eq("every declared source has a group, in order", order, g.map { it.first })
    eq("a package sits under the FIRST source that found it", listOf("p.two"), g.first { it.first == "fdroid" }.second.map { it.pkg })
    eq("installed first within a group, the source's ranking otherwise", listOf("p.three", "p.one"),
        g.first { it.first == "play" }.second.map { it.pkg })

    // ── a readable name for an unlabelled, uninstalled package ──
    eq("prettyLabel photos", "Photos", StoreSuggest.prettyLabel("com.google.android.apps.photos"))
    eq("prettyLabel perplexity", "Perplexity", StoreSuggest.prettyLabel("ai.perplexity.app.android"))
    eq("prettyLabel whatsapp", "Whatsapp", StoreSuggest.prettyLabel("com.whatsapp"))

    // ── the REAL taxonomy: write the landing's layout for the script to compare with SuperApp's ──
    if (args.size >= 2) {
        val secs = ArrayList<Pair<String, String>>()
        val fs = ArrayList<Folder>()
        File(args[0]).readLines().filter { it.isNotBlank() }.forEach { line ->
            val c = line.split('\t')
            when (c[0]) { "S" -> secs += c[1] to c[2]; "F" -> fs += Folder(c[1], c[2]) }
        }
        // Every third folder left empty, so hiding is exercised on the real data too.
        val real = fs.withIndex().filter { it.index % 3 != 1 }.map { sug("pkg.${it.value.id}", it.value.label) }
        val land = StoreSuggest.landing(secs, fs, real) { it.pkg.removePrefix("pkg.") }
        File(args[1]).writeText(land.joinToString("") { s ->
            "SECTION ${s.title ?: "<other>"}\n" + s.shelves.joinToString("") { "SHELF ${it.folderId}\n" }
        })
    }
    exitProcess(fails)
}
