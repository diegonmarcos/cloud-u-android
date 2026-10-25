package com.diegonmarcos.cloudsuperintel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonasTest {
    private val all = Personas.parse(Data.asset("personas.json"))

    @Test fun personaListComesFromData() {
        assertEquals(
            listOf("SI", "Scientist", "CTO", "Doctor", "Lawyer", "Asset Manager", "Wiki AI"),
            all.map { it.label },
        )
    }

    @Test fun uiPagesAreExactlyThePersonas() {
        assertEquals(all.map { it.id }, Data.pages("si"))
    }

    @Test fun everyPersonaIsFullyDeclared() {
        for (p in all) {
            assertTrue("${p.id} system", p.system.length > 40)
            assertTrue("${p.id} suggestions", p.suggestions.size >= 3 && p.suggestions.all { it.isNotBlank() })
            assertTrue("${p.id} icon", p.icon.isNotBlank())
            assertTrue("${p.id} role", p.role.isNotBlank())
        }
    }

    @Test fun routerListsTheOtherPersonasAndNoPlaceholderSurvives() {
        val si = all.first { it.router }
        assertEquals("si", si.id)
        val sys = Personas.system(si, all)
        assertFalse(sys.contains("{{"))
        for (o in all.filter { it.id != si.id }) assertTrue("${o.label} listed", sys.contains("- ${o.label}: ${o.role}"))
        assertFalse("the router does not list itself", sys.contains("- SI:"))
        assertEquals(1, all.count { it.router })
    }

    @Test fun specialistPromptsAreTheirOwn() {
        val doc = all.first { it.id == "doctor" }
        assertEquals(doc.system, Personas.system(doc, all))
        assertTrue(doc.system.contains("urgent care"))
    }

    @Test fun preferredModelIsTheFirstListedOne() {
        val p = all.first().copy(models = listOf("a", "b", "c"))
        assertEquals("b", Personas.preferredModel(p, listOf("c", "b")))
        assertNull(Personas.preferredModel(p, listOf("z")))
        assertNull(Personas.preferredModel(p.copy(models = emptyList()), listOf("a")))
    }

    @Test fun defaultPersonaExists() {
        val d = org.json.JSONObject(Data.asset("personas.json")).getString("default")
        assertTrue(all.any { it.id == d })
    }
}
