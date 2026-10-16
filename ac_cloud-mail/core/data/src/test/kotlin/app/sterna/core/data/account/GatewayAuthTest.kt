package app.sterna.core.data.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class GatewayAuthTest {
    private fun acc(t: AuthType) = StoredAccount(id = "a", server = "https://jmap.diegonmarcos.com", username = "me@diegonmarcos.com", authType = t)

    @Test fun `the mode follows the auth type and the authelia flag`() {
        assertEquals(GatewayMode.NONE, GatewayAuthStore.modeOf(acc(AuthType.BASIC), GatewayAuth()))
        assertEquals(GatewayMode.BEARER, GatewayAuthStore.modeOf(acc(AuthType.API_TOKEN), GatewayAuth()))
        assertEquals(GatewayMode.AUTHELIA, GatewayAuthStore.modeOf(acc(AuthType.BASIC), GatewayAuth(authelia = true)))
    }

    @Test fun `the chip follows the credentials`() {
        fun s(m: GatewayMode, a: GatewayAuth = GatewayAuth(), rejected: Boolean = false, failed: Boolean = false) =
            GatewayStatuses.resolve(m, a, rejected, failed)
        assertEquals(GatewayStatus.CONNECTED, s(GatewayMode.NONE))
        assertEquals(GatewayStatus.TOKEN, s(GatewayMode.BEARER))
        assertEquals(GatewayStatus.SIGNED_IN, s(GatewayMode.AUTHELIA, GatewayAuth(true, "c", 1)))
        assertEquals(GatewayStatus.NEEDS_SIGN_IN, s(GatewayMode.AUTHELIA, GatewayAuth(true)))
        assertEquals(GatewayStatus.NEEDS_SIGN_IN, s(GatewayMode.AUTHELIA, GatewayAuth(true, "c", 1), rejected = true))
        assertEquals(GatewayStatus.NEEDS_SIGN_IN, s(GatewayMode.BEARER, rejected = true))
        assertEquals(GatewayStatus.ERROR, s(GatewayMode.NONE, failed = true))
    }

    @Test fun `the cookie prints masked and the store never logs`() {
        assertFalse(GatewayAuth(true, "SECRET", 1).toString().contains("SECRET"))
        val src = File("src/main/kotlin/app/sterna/core/data/account/GatewayAuthStore.kt").readText()
        assertFalse(Regex("""(Log\.|println\(|Timber|printStackTrace)""").containsMatchIn(src))
    }

    @Test fun `the host comes out of a server url`() {
        assertEquals("jmap.diegonmarcos.com", GatewayAuthStore.hostOf(" https://jmap.diegonmarcos.com:8443/jmap/session "))
    }
}
