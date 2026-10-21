package com.diegonmarcos.cloudlib.rclone.engine

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #1084 the engine ships exactly what data/unison-binary.json pins: ONE version, ONE OCaml, a hash per ABI,
 * a name the installer will extract. The cross-peer comparison is cloud-android-unison-pin-guard.py (every
 * push); this is the module's own half, in the suite that runs with the engine.
 */
class UnisonPinTest {
    private val pin = JSONObject(File("data/unison-binary.json").readText())

    @Test fun theVersionAndTheOcamlAreOneExactRelease() {
        assertTrue(pin.getString("version").matches(Regex("\\d+\\.\\d+\\.\\d+")))
        assertTrue(pin.getString("ocaml").matches(Regex("\\d+\\.\\d+\\.\\d+")))
    }

    @Test fun everyAbiPinsATarballAndABinaryByHashAndCarriesTheVersionInItsUrl() {
        val v = pin.getString("version")
        val bins = pin.getJSONObject("binaries")
        for (abi in listOf("arm64-v8a", "x86_64")) {
            val b = bins.getJSONObject(abi)
            assertTrue(abi, b.getString("tar_sha256").matches(Regex("[0-9a-f]{64}")))
            assertTrue(abi, b.getString("binary_sha256").matches(Regex("[0-9a-f]{64}")))
            assertTrue(abi + " url", b.getString("url").contains(v))
            assertTrue(abi + " dir", b.getString("dir").contains(v))
        }
    }

    @Test fun theBinaryIsStaticAndNamedSoTheInstallerExtractsIt() {
        assertEquals("none", pin.getString("interp"))
        assertTrue(pin.getString("jni_name").matches(Regex("lib[\\w.-]+\\.so")))
        assertTrue("distinct from rclone's", pin.getString("jni_name") != "librclone.so")
    }

    @Test fun theArm64BinaryIsTheOneThisRepositoryBuilds() {
        assertTrue(pin.getJSONObject("binaries").getJSONObject("arm64-v8a").getString("url")
            .contains("/releases/download/unison-static-${pin.getString("version")}-"))
    }

    @Test fun everyPeerIsNamedByRepoAndPath() {
        val peers = pin.getJSONArray("peers")
        assertTrue(peers.length() >= 2)
        for (i in 0 until peers.length()) {
            val p = peers.getJSONObject(i)
            assertTrue(p.getString("repo").isNotBlank() && p.getString("path").endsWith(".json"))
        }
    }
}
