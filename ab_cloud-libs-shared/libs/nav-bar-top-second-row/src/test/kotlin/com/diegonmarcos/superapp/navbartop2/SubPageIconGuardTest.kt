package com.diegonmarcos.superapp.navbartop2

import com.diegonmarcos.superapp.bottomnav.NavDecl
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #1157 guard: EVERY declared nav item of every app on the shared nav bars carries an icon, and every sub-page's icon is one the
 * second row can draw (SecondRowIcons), so a tab can never render without a glyph. Reads each app's own build.json (the repo is
 * checked out whole in CI; a missing app file FAILS, it is never skipped).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SubPageIconGuardTest {
    private val apps = listOf(
        "ac_cloud-mail", "ac_cloud-me", "ac_cloud-drive", "ac_cloud-nav", "ac_cloud-account", "ac_cloud-store", "ac_cloud-c3", "ac_cloud-tasks",
    )

    private fun buildJson(app: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val f = File(dir, "$app/build.json")
            if (f.isFile) return f
            dir = dir.parentFile
        }
        throw AssertionError("$app/build.json not found above ${File("").absolutePath}")
    }

    private fun decl(app: String): NavDecl {
        val ui = JSONObject(buildJson(app).readText()).getJSONObject("ui")
        return NavDecl.parse(ui.getJSONArray("sections"), ui.optString("bottom_nav"), ui.optString("default_section"))
    }

    @Test fun `every section and sub-page of every app declares an icon`() {
        for (app in apps) assertEquals("$app: icon-less declarations", emptyList<String>(), decl(app).iconProblems())
    }

    @Test fun `every sub-page icon of every app is one the second row draws`() {
        for (app in apps) {
            val unknown = decl(app).sections.flatMap { s -> s.allPages().filter { !SecondRowIcons.has(it.icon) }.map { "${s.id}/${it.id}='${it.icon}'" } }
            assertEquals("$app: sub-pages whose icon SecondRowIcons does not hold", emptyList<String>(), unknown)
        }
    }

    @Test fun `the guard sees pages at all (a vacuous pass is a failure)`() {
        for (app in apps) assertTrue("$app declares no pages", decl(app).sections.sumOf { it.allPages().size } > 0)
    }
}
