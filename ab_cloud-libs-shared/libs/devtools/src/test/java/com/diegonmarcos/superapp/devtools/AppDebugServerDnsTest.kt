package com.diegonmarcos.superapp.devtools

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #741 /api/net/dns is how the phone shows which upstream the SuperApp's DNS
 * menu gave an app. Its one failure mode worth a test is a reply that reads as
 * an answer but is not JSON a client can parse (an unquoted null, a missing
 * comma), which would make every app look broken at once.
 */
class AppDebugServerDnsTest {

    @Test
    fun underTheVpn_theMenuUpstreamsAreListed() {
        assertEquals(
            """"resolver":"android","network":"vpn","upstreams":["10.0.0.1","10.1.0.1"],"private_dns":{"active":false,"server":null}""",
            AppDebugServer.dnsFields("vpn", listOf("10.0.0.1", "10.1.0.1"), false, null),
        )
    }

    @Test
    fun noNetworkAndOldAndroid_areNullsNotStrings() {
        assertEquals(
            """"resolver":"android","network":null,"upstreams":[],"private_dns":{"active":null,"server":null}""",
            AppDebugServer.dnsFields(null, emptyList(), null, null),
        )
    }

    @Test
    fun privateDnsHost_isEscaped() {
        assertEquals(
            """"resolver":"android","network":"direct","upstreams":[],"private_dns":{"active":true,"server":"dns.\"x\""}""",
            AppDebugServer.dnsFields("direct", emptyList(), true, "dns.\"x\""),
        )
    }
}
