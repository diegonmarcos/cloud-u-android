package com.diegonmarcos.cloudc3

import com.diegonmarcos.cloudc3.pages.DeclarationModel
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The More page's sections and raw JSON, over a synthetic declaration and the shipped data/declarations.json. */
class DeclarationModelTest {

    private val decl: JsonObject = Json.parseToJsonElement("""
        {"container":"gitea","service":"gitea","description":"Git","category":"data","folder":"infra-dat_gitea",
         "vm":{"name":"vm-a","addr":"10.0.0.6:3002","ip":"1.2.3.4","wg_ip":"10.0.0.6"},
         "image":"gitea/gitea:latest","ports":{"container":3002,"env":"PORT","protocol":"http","extra":[{"port":22,"protocol":"tcp","service":"ssh"}]},
         "volumes":["d:/data"],"env":{"env_file":false,"names":["A"],"secret_names":["B_PASSWORD"]},
         "resources":{"memory":"256M"},"healthcheck":"curl x","depends_on":["db"],"read_only":false,
         "routes":{"public_url":"git.example.com","domain":"git.example.com","base_path":"","auth_tier":"woauth","declared_auth":"two_factor","wg_only":false,"private_url":"gitea.mesh.diegonmarcos.com:3002","dns":"gitea.mesh.diegonmarcos.com"}}
    """.trimIndent()).jsonObject

    private fun section(t: String) = DeclarationModel.sections(decl).first { it.title.startsWith(t) }

    @Test
    fun `the declaration is cut into the readable sections`() {
        assertEquals(listOf("Overview", "VM", "Image and runtime", "Ports", "Volumes", "Environment (names only, never values)", "Resources",
            "Health check", "Routes and auth", "Dependencies"), DeclarationModel.sections(decl).map { it.title })
        assertTrue(section("Image").lines.contains("Image" to "gitea/gitea:latest"))
        assertTrue(section("Ports").lines.contains("Container port" to "3002"))
        assertTrue(section("Ports").lines.contains("Extra 22" to "tcp ssh"))
        assertTrue(section("Volumes").lines.contains("Mount" to "d:/data"))
        assertTrue(section("Routes").lines.contains("Auth tier" to "woauth"))
        assertTrue(section("Dependencies").lines.contains("Depends on" to "db"))
        assertTrue(section("VM").lines.contains("Mesh IP" to "10.0.0.6"))
    }

    @Test
    fun `environment shows names only`() {
        val env = section("Environment").lines
        assertTrue(env.contains("Variable" to "A"))
        assertTrue(env.contains("Secret (name only)" to "B_PASSWORD"))
    }

    @Test
    fun `empty sections are left out and the raw json round-trips`() {
        val sparse = Json.parseToJsonElement("""{"container":"x","vm":{"name":"vm-a"}}""").jsonObject
        assertEquals(listOf("Overview", "VM"), DeclarationModel.sections(sparse).map { it.title })
        assertEquals(decl, Json.parseToJsonElement(DeclarationModel.raw(decl)).jsonObject)
        assertTrue(DeclarationModel.raw(decl).contains("\n  \"container\""))
    }

    @Test
    fun `every shipped declaration renders and carries no env values`() {
        var dir = File("").absoluteFile
        while (dir.parentFile != null && !File(dir, "data/declarations.json").isFile) dir = dir.parentFile
        val all = Json.parseToJsonElement(File(dir, "data/declarations.json").readText()).jsonObject
        assertTrue(all.size > 50)
        for ((name, d) in all) {
            val o = d.jsonObject
            assertEquals(name, (o["container"] as kotlinx.serialization.json.JsonPrimitive).content)
            assertTrue("$name has sections", DeclarationModel.sections(o).isNotEmpty())
            val env = o["env"]!!.jsonObject
            assertTrue("env is names (a list), never a map of values", env["names"] is kotlinx.serialization.json.JsonArray)
        }
    }
}
