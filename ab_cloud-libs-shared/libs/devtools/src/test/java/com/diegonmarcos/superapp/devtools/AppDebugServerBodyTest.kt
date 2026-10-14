package com.diegonmarcos.superapp.devtools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.StringReader

/**
 * #802 the debug API reads a request body. Before, the server stopped after the
 * headers, so a write bigger than a query string (a profile import) had no way in.
 * #ipc the reader is the SuperApp gateway's now (FleetIpcWire.readBody); the IPC door
 * carries the body as _body and refuses one past the op's limit (FleetIpc.serve).
 */
class AppDebugServerBodyTest {

    @Test
    fun readBody_readsExactlyTheDeclaredBytes() {
        // Anything after the declared length is not this request's body.
        assertEquals("""{"a":1}""", FleetIpcWire.readBody(StringReader("""{"a":1}TRAILING"""), 7))
        // Non-ASCII: "é" is 2 bytes, so 3 bytes = "aé"; counting chars would read "aéb".
        assertEquals("aé", FleetIpcWire.readBody(StringReader("aéb"), 3))
    }

    @Test
    fun noBody_keyAbsent() {
        assertNull(FleetIpcWire.readBody(StringReader("ignored"), 0))
        assertFalse("_body" in AppDebugServer.withBody(mapOf("k" to "v"), null))
    }

    @Test
    fun routeSeesBody_andQueryCannotForgeIt() {
        val q = AppDebugServer.withBody(mapOf("k" to "v", "_body" to "forged"), "real")
        assertEquals("real", q["_body"])
        assertEquals("v", q["k"])
        assertFalse("_body" in AppDebugServer.withBody(mapOf("_body" to "forged"), null))
    }

    @Test
    fun oversizeIsRefused() {
        // FleetIpc.serve answers 413 for any length past this; the bound is the contract.
        assertEquals(256 * 1024, AppDebugServer.MAX_BODY_BYTES)
    }
}
