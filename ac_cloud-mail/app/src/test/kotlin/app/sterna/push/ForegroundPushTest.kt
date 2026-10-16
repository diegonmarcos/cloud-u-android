package app.sterna.push

import app.sterna.core.data.account.MailProtocol
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** New mail must reach an OPEN app within seconds via JMAP push, not after the periodic worker. */
class ForegroundPushTest {

    @Test fun `a JMAP account not carried by the push service gets a foreground stream`() {
        assertTrue(shouldHoldStream(MailProtocol.JMAP, pushServiceCarriesIt = false))
    }

    @Test fun `no second socket when the push service already carries the account`() {
        assertFalse(shouldHoldStream(MailProtocol.JMAP, pushServiceCarriesIt = true))
    }

    @Test fun `IMAP has no event source to hold`() {
        assertFalse(shouldHoldStream(MailProtocol.IMAP, pushServiceCarriesIt = false))
    }

    @Test fun `the activity arms it on start and releases it on stop`() {
        val src = listOf("src/main/kotlin/app/sterna/MainActivity.kt", "app/src/main/kotlin/app/sterna/MainActivity.kt")
            .map(::File).first { it.exists() }.readText()
        val onStart = src.substringAfter("override fun onStart()").substringBefore("\n    }")
        val onStop = src.substringAfter("override fun onStop()").substringBefore("\n    }")
        assertTrue("onStart must arm the foreground stream", "ForegroundPush.onForeground(this)" in onStart)
        assertTrue("onStop must release the foreground stream", "ForegroundPush.onBackground()" in onStop)
    }
}
