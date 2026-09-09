package app.sterna.core.imap

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Base64

class Xoauth2Test {
    @Test
    fun `payload decodes to the SASL XOAUTH2 structure`() {
        val payload = xoauth2Payload("alex@outlook.com", "AT-token-123")
        val decoded = String(Base64.getDecoder().decode(payload), Charsets.UTF_8)
        val a = Char(1)
        assertEquals("user=alex@outlook.com${a}auth=Bearer AT-token-123$a$a", decoded)
    }
}
