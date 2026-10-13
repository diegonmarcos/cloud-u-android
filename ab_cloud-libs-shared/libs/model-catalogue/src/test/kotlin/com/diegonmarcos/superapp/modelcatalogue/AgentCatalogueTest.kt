package com.diegonmarcos.superapp.modelcatalogue

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The shared agent descriptor every chat reads: DeepSeek Harness is in it with its real capabilities. */
class AgentCatalogueTest {
    private val cat by lazy { AgentCatalogue.parse(File("src/main/assets/" + AgentCatalogue.ASSET).readText()) }

    @Test fun deepSeekHarnessIsDeclaredWithItsCapabilities() {
        assertNotNull(cat.byId("deepseek"))
        val d = cat.byId("deepseek")!!
        assertEquals("DeepSeek Harness", d.label)
        assertEquals("deepseek", d.mode)
        assertNull("a gateway mode, not a service", d.fleet)
        assertTrue(d.caps.tools); assertTrue(d.caps.mcp); assertTrue(d.caps.reasoning)
        assertFalse("DeepSeek chat models take no images", d.caps.images)
        assertFalse(d.caps.web)
        assertEquals(listOf("DeepSeek"), d.providers)
        assertEquals("tools (MCP) · reasoning · text only", AgentCatalogue.subtitle(d))
    }

    @Test fun theGatewayAgentsKeepTheirOrderAndGateway() {
        assertEquals(listOf("claude", "hermes", "goose", "deepseek"), cat.agents.map { it.id })
        assertEquals("cloud-agi-goose", cat.gateway.service)
        assertEquals("X-Agent-Mode", cat.gateway.modeHeader)
        assertTrue(cat.gateway.meshOnly)
    }

    @Test fun deepSeekOnlyEverRunsADeepSeekModel() {
        val d = cat.byId("deepseek")!!
        assertEquals("deepseek/deepseek-r1", AgentCatalogue.modelFor(d, "deepseek/deepseek-r1"))
        assertEquals(d.defaultModel, AgentCatalogue.modelFor(d, "openrouter/auto"))
        assertNull(AgentCatalogue.modelFor(cat.byId("goose")!!, "deepseek/deepseek-r1"))
    }
}
