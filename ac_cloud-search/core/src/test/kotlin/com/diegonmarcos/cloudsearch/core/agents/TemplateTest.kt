package com.diegonmarcos.cloudsearch.core.agents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TemplateTest {
    @Test fun fillsEverySlot() {
        val r = Template.render("Hallo {{name}}, {{ place }}!", mapOf("name" to "Ada", "place" to "Berlin"))
        assertEquals("Hallo Ada, Berlin!", r.text)
        assertTrue(r.missing.isEmpty())
        assertEquals(listOf("name", "place"), r.used)
    }

    @Test fun fallbackWhenMissingOrBlank() {
        val t = "Einzug {{move_in|nach Absprache}}."
        assertEquals("Einzug nach Absprache.", Template.render(t, emptyMap()).text)
        assertEquals("Einzug nach Absprache.", Template.render(t, mapOf("move_in" to "   ")).text)
        assertEquals("Einzug 01.12..", Template.render(t, mapOf("move_in" to "01.12.")).text)
        assertTrue(Template.render(t, emptyMap()).missing.isEmpty())
    }

    @Test fun emptyFallbackDropsTheSlot() {
        val r = Template.render("a{{personal|}}b", emptyMap())
        assertEquals("ab", r.text)
        assertTrue(r.missing.isEmpty())
    }

    @Test fun aMissingVariableStaysVisibleAndIsReported() {
        val r = Template.render("Hi {{name}}, {{name}} {{city}}", mapOf("city" to "Köln"))
        assertEquals("Hi {{name}}, {{name}} Köln", r.text)
        assertEquals(listOf("name"), r.missing)
        assertEquals(listOf("city"), r.used)
    }

    @Test fun aValueIsNeverExpandedAgain() {
        val r = Template.render("{{listing_title}} / {{name}}", mapOf("listing_title" to "Room {{name}} {{x|oops}}", "name" to "Ada"))
        assertEquals("Room {{name}} {{x|oops}} / Ada", r.text)
    }

    @Test fun valuesWithRegexAndDollarCharactersSurvive() {
        val r = Template.render("{{a}}", mapOf("a" to "\$1 \\ \$0 (x)"))
        assertEquals("\$1 \\ \$0 (x)", r.text)
    }

    @Test fun variablesInOrderOfFirstUse() {
        assertEquals(listOf("b", "a", "c"), Template.variables("{{b}} {{a|x}} {{b}} {{ c }}"))
        assertTrue(Template.variables("no slots, {single} and {{Bad-Name}} and {{1x}}").isEmpty())
    }

    @Test fun onlyLowerCaseSlotNamesAreSlots() {
        assertEquals("{{Name}}", Template.render("{{Name}}", mapOf("Name" to "x")).text)
    }
}
