package com.diegonmarcos.superapp.decisions.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsentBookTest {
    private val p = Policy.parse(Fixtures.block())
    private val ui = p.uses.getValue("ui")
    private val mail = p.uses.getValue("mailuse")

    @Test fun theDefaultIsTheUsesDeclaration() {
        val b = ConsentBook(MemoryStore())
        assertTrue(b.granted("app", ui))
        assertFalse(b.granted("app", mail))
    }

    @Test fun aGrantIsPerAppAndPerUse() {
        val b = ConsentBook(MemoryStore())
        b.set("a", "mailuse", true)
        assertTrue(b.granted("a", mail))
        assertFalse(b.granted("b", mail))
        assertTrue(b.granted("a", ui))
        b.set("a", "ui", false)
        assertFalse(b.granted("a", ui))
        assertTrue(b.granted("b", ui))
    }

    @Test fun aGrantCanBeWithdrawnAndSurvivesARestart() {
        val s = MemoryStore()
        ConsentBook(s).set("a", "mailuse", true)
        val b = ConsentBook(s)
        assertTrue(b.granted("a", mail))
        b.set("a", "mailuse", false)
        assertFalse(ConsentBook(s).granted("a", mail))
    }
}
