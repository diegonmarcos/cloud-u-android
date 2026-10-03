package com.diegonmarcos.superapp.contacts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Black-box tests written from a0_docs/eng-specs/social-import.md (#828). */
class SocialImportTest {

    private val linkedIn = "﻿Notes:\r\n" +
        "\"When exporting your connection data, you may notice that some of the email addresses are missing.\"\r\n" +
        "\r\n" +
        "First Name,Last Name,URL,Email Address,Company,Position,Connected On\r\n" +
        "Ana,Ferreira,https://www.linkedin.com/in/anaferreira,ana@example.com,\"Acme, Inc.\",Engineer,01 Jan 2024\r\n" +
        "Marcus,Chen,https://www.linkedin.com/in/marcuschen,,Globex,\"Head of \"\"Ops\"\"\",02 Feb 2024\r\n" +
        ",,,,,,\r\n"

    @Test fun linkedInRowsMapToRawContacts() {
        val r = SocialImport.parse(linkedIn)!!
        assertEquals("linkedin", r.source)
        assertEquals(2, r.contacts.size)
        val ana = r.contacts[0]
        assertEquals("Ana Ferreira", ana.name)
        assertEquals(listOf("ana@example.com"), ana.emails)
        assertEquals("Acme, Inc.", ana.org)
        assertEquals("Engineer", ana.title)
        assertEquals(listOf("https://www.linkedin.com/in/anaferreira"), ana.urls)
        assertEquals("anaferreira", ana.handles["linkedin"])
        val marcus = r.contacts[1]
        assertTrue(marcus.emails.isEmpty())
        assertEquals("Head of \"Ops\"", marcus.title)
        assertTrue(r.contacts.all { it.source == "linkedin" })
    }

    @Test fun linkedInColumnsFoundByNameAndMultilineQuotes() {
        val csv = "Company,Last Name,First Name\n\"Multi\nLine Co\",Doe,Jane\n"
        val c = SocialImport.parse(csv)!!.contacts.single()
        assertEquals("Jane Doe", c.name)
        assertEquals("Multi\nLine Co", c.org)
        assertTrue(c.urls.isEmpty())
    }

    @Test fun linkedInHeaderOnlyIsEmptyResult() {
        val r = SocialImport.parse("First Name,Last Name,URL\n")
        assertNotNull(r)
        assertTrue(r!!.contacts.isEmpty())
    }

    @Test fun instagramFollowersArray() {
        val json = """[{"title":"","media_list_data":[],"string_list_data":[{"href":"https://www.instagram.com/priyan","value":"priyan","timestamp":1700000000}]}]"""
        val r = SocialImport.parse(json)!!
        assertEquals("instagram", r.source)
        val c = r.contacts.single()
        assertEquals("priyan", c.name)
        assertEquals("priyan", c.handles["instagram"])
        assertEquals(listOf("https://www.instagram.com/priyan"), c.urls)
    }

    @Test fun instagramFollowingObjectWithTitleAndDedup() {
        val json = """{"relationships_following":[
            {"title":"bob","string_list_data":[{"href":"https://www.instagram.com/_u/bob","timestamp":1}]},
            {"title":"","string_list_data":[{"href":"https://www.instagram.com/carol/"}]},
            {"title":"BOB","string_list_data":[]}
        ]}"""
        val r = SocialImport.parse(json)!!
        assertEquals(listOf("bob", "carol"), r.contacts.map { it.name })
    }

    @Test fun unrecognisedInputIsNull() {
        assertNull(SocialImport.parse(""))
        assertNull(SocialImport.parse("   "))
        assertNull(SocialImport.parse("just,some,csv\n1,2,3\n"))
        assertNull(SocialImport.parse("""{"foo":1}"""))
        assertNull(SocialImport.parse("{not json"))
    }
}
