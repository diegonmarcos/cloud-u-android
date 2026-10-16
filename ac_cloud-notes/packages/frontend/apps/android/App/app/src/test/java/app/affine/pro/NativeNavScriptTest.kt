package app.affine.pro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeNavScriptTest {
    @Test
    fun dispatchCarriesIdsOnly() {
        assertEquals(
            "window.dispatchEvent(new CustomEvent('cloud-notes:nav',{detail:{section:\"pages\",page:\"all\"}}))",
            NativeNavScript.dispatch("pages", "all"),
        )
    }

    @Test
    fun sectionTapSendsNullPage() {
        assertTrue(NativeNavScript.dispatch("home", null).contains("page:null"))
    }

    @Test
    fun idsCannotBreakOutOfTheString() {
        val js = NativeNavScript.dispatch("a\"});alert(1);//", "b\\")
        assertTrue(js, js.contains("section:\"a\\\"});alert(1);//\""))
        assertTrue(js, js.contains("page:\"b\\\\\""))
    }
}
