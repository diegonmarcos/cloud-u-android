package com.diegonmarcos.superapp.rss

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #773 — the RSS page's grouping rule, now a pure function ([RssFeedFragment.plan]) behind the
 * Compose page that replaced fragment_rss_feed.xml / item_rss_topic.xml.
 *
 *   R1 scopes keep their declared order (USER above INFRA), topics group by snake_case prefix,
 *      prefixes sort, topics inside a prefix sort, and a prefix-less topic goes under "_"
 *   R2 an unrecognised prefix falls to the LAST scope instead of vanishing
 *   R3 a page that asks for some scopes shows only those, classification still runs on all of them
 *   R4 a scope the page asked for that matched nothing is kept as empty-by-design; a scope nobody
 *      asked for that matched nothing stays hidden
 */
class RssFeedPlanTest {

    private val user = NtfyScopes.Scope("user", "You", "this phone", setOf("me", "phone"))
    private val infra = NtfyScopes.Scope("infra", "Infra", "", setOf("ops", "deploy", "health"))
    private val scopes = listOf(user, infra)

    @Test
    fun `R1 scope order, prefix groups, sorted topics, the underscore bucket`() {
        val plan = RssFeedFragment.plan(listOf("ops_b", "me_x", "ops_a", "deploy_z", "health"), scopes, emptyList())
        assertEquals(listOf("user", "infra"), plan.map { it.scope.id })
        assertEquals(listOf("me" to listOf("me_x")), plan[0].groups)
        // "health" has no underscore: its prefix is itself, so it is grouped under "_", which sorts first.
        assertEquals(listOf("_" to listOf("health"), "deploy" to listOf("deploy_z"), "ops" to listOf("ops_a", "ops_b")),
            plan[1].groups)
    }

    @Test
    fun `R2 an unknown prefix lands in the last scope`() {
        val plan = RssFeedFragment.plan(listOf("zzz_new"), scopes, emptyList())
        assertEquals(listOf("infra"), plan.map { it.scope.id })
        assertEquals(listOf("zzz" to listOf("zzz_new")), plan.single().groups)
    }

    @Test
    fun `R3 a page asking for one scope shows only it, classification unchanged`() {
        val plan = RssFeedFragment.plan(listOf("me_x", "ops_a", "zzz_new"), scopes, listOf("user"))
        assertEquals(listOf("user"), plan.map { it.scope.id })
        assertEquals(listOf("me_x"), plan.single().groups.single().second)
    }

    @Test
    fun `R4 an asked-for scope with no topics is kept empty, an unasked one stays hidden`() {
        val asked = RssFeedFragment.plan(listOf("ops_a"), scopes, listOf("user", "infra"))
        assertEquals(listOf("user", "infra"), asked.map { it.scope.id })
        assertTrue(asked[0].empty)
        assertFalse(asked[1].empty)
        val unasked = RssFeedFragment.plan(listOf("ops_a"), scopes, emptyList())
        assertEquals(listOf("infra"), unasked.map { it.scope.id })
    }
}
