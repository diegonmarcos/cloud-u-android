package com.diegonmarcos.superapp.network

import android.app.Application
import android.content.Context
import com.diegonmarcos.superapp.network.mesh.MeshDecl
import com.wireguard.config.Config
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config as RoboConfig
import java.io.BufferedReader
import java.io.StringReader

/**
 * #1074 Cloudflare WARP / One: the registration request and the answer's parsing (with a mock API), the
 * generated WireGuard config (through the engine's own parser), the key store, and the Zero Trust gate.
 */
@RunWith(RobolectricTestRunner::class)
@RoboConfig(sdk = [34], application = Application::class)
class CloudflareWarpTest {

    private val d = MeshDecl.baked.cloudflare
    private val ctx: Context get() = org.robolectric.RuntimeEnvironment.getApplication()

    /** A WARP API answer shaped like the real one (wgcf documents the fields). */
    private val answer = """{
      "id": "7a3c1f9e-0000-4000-8000-000000000001", "token": "dev-token", "type": "a",
      "account": {"id": "acc-1", "license": "LIC-1234", "account_type": "free"},
      "config": {
        "client_id": "abcd",
        "peers": [{"public_key": "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=", "endpoint": {"v4": "162.159.192.1:0", "v6": "[2606:4700:d0::a29f:c001]:0", "host": "engage.cloudflareclient.com:2408"}}],
        "interface": {"addresses": {"v4": "172.16.0.2", "v6": "2606:4700:110:8a36:df92:102a:9602:fa18"}}
      }}"""

    private fun plainStore() = CfKeyStore(ctx) { c -> c.getSharedPreferences("cf_test", Context.MODE_PRIVATE) }

    @Test fun `the registration request carries the public key, the headers and the terms stamp`() {
        val r = CloudflareWarp.registerRequest(d, "PUBKEY=", "install-1", 0L)
        assertEquals("POST", r.method)
        assertEquals("https://api.cloudflareclient.com/v0a1922/reg", r.url)
        assertEquals("a-6.3-1922", r.headers["CF-Client-Version"]); assertEquals("application/json", r.headers["Content-Type"])
        assertFalse("no Access header for WARP", r.headers.containsKey("CF-Access-Jwt-Assertion"))
        val b = JSONObject(r.body!!)
        assertEquals("PUBKEY=", b.getString("key")); assertEquals("install-1", b.getString("install_id"))
        assertEquals("1970-01-01T00:00:00.000Z", b.getString("tos"))
        assertFalse("the request never carries a private key", r.body!!.contains("rivate"))
    }

    @Test fun `the answer is parsed into the peer, the endpoint and the addresses`() {
        val reg = CloudflareWarp.parseRegistration(answer)
        assertEquals("7a3c1f9e-0000-4000-8000-000000000001", reg.id); assertEquals("dev-token", reg.token); assertEquals("LIC-1234", reg.license)
        assertEquals("bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=", reg.peerPublicKey)
        assertEquals("engage.cloudflareclient.com:2408", reg.endpoint)
        assertEquals("172.16.0.2", reg.addressV4); assertEquals("2606:4700:110:8a36:df92:102a:9602:fa18", reg.addressV6)
    }

    @Test fun `an answer missing what the config needs is refused with the reason`() {
        assertThrows(IllegalArgumentException::class.java) { CloudflareWarp.parseRegistration("not json") }
        assertTrue(assertThrows(IllegalArgumentException::class.java) { CloudflareWarp.parseRegistration("""{"id":"x"}""") }.message!!.contains("no config"))
        assertTrue(assertThrows(IllegalArgumentException::class.java) { CloudflareWarp.parseRegistration("""{"config":{"peers":[{"public_key":""}],"interface":{"addresses":{"v4":"1.2.3.4"}}}}""") }.message!!.contains("peer public key"))
        assertTrue(assertThrows(IllegalArgumentException::class.java) { CloudflareWarp.parseRegistration("""{"config":{"peers":[{"public_key":"k"}],"interface":{"addresses":{}}}}""") }.message!!.contains("IPv4"))
    }

    @Test fun `the generated config is wg-quick the engine's own parser accepts`() {
        val priv = com.wireguard.crypto.KeyPair().privateKey.toBase64()
        val text = CloudflareWarp.warpConf(d, priv, CloudflareWarp.parseRegistration(answer))
        val cfg = Config.parse(BufferedReader(StringReader(text)))
        assertEquals(listOf("172.16.0.2/32", "2606:4700:110:8a36:df92:102a:9602:fa18/128"), cfg.getInterface().addresses.map { it.toString() })
        assertEquals(1280, cfg.getInterface().mtu.get()); assertEquals(4, cfg.getInterface().dnsServers.size)
        val peer = cfg.peers.single()
        assertEquals("bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=", peer.publicKey.toBase64())
        assertEquals("engage.cloudflareclient.com:2408", peer.endpoint.get().toString())
        assertEquals(listOf("0.0.0.0/0", "0:0:0:0:0:0:0:0/0"), peer.allowedIps.map { it.toString() })   // the parser prints ::/0 expanded
        assertEquals(25, peer.persistentKeepalive.get())
    }

    @Test fun `the endpoint falls back to the declared one when the answer has none`() {
        val reg = CloudflareWarp.parseRegistration(answer.replace(""""host": "engage.cloudflareclient.com:2408"""", """"host": """""))
        assertTrue(CloudflareWarp.warpConf(d, "K", reg).contains("Endpoint = engage.cloudflareclient.com:2408"))
    }

    @Test fun `register once through a mock api, keys land in the store, a second use makes no call`() {
        val calls = mutableListOf<CloudflareWarp.HttpReq>()
        val http = CloudflareWarp.CfHttp { calls += it; CloudflareWarp.HttpRes(200, answer) }
        val keys = plainStore().also { it.clearWarp() }
        val client = CloudflareWarp.Client(d, http, keys, clock = { 0L })
        val (priv1, reg1) = client.warp()
        assertEquals(1, calls.size); assertEquals("POST", calls[0].method)
        assertEquals("the private key is held in the key store", priv1, keys.warpPrivateKey())
        assertEquals("the request carries the PUBLIC half of the stored key", com.wireguard.crypto.KeyPair(com.wireguard.crypto.Key.fromBase64(priv1)).publicKey.toBase64(), JSONObject(calls[0].body!!).getString("key"))
        val (priv2, reg2) = client.warp()
        assertEquals("no second registration", 1, calls.size); assertEquals(priv1, priv2); assertEquals(reg1, reg2)
    }

    @Test fun `a refused registration is an error and stores nothing`() {
        val keys = plainStore().also { it.clearWarp() }
        val client = CloudflareWarp.Client(d, { CloudflareWarp.HttpRes(403, "{}") }, keys)
        assertTrue(assertThrows(IllegalStateException::class.java) { client.warp() }.message!!.contains("HTTP 403"))
        assertNull(keys.warpRegistration())
    }

    @Test fun `forgetting deletes at cloudflare with the bearer and clears the keys`() {
        val calls = mutableListOf<CloudflareWarp.HttpReq>()
        val keys = plainStore().also { it.clearWarp() }
        val client = CloudflareWarp.Client(d, { calls += it; CloudflareWarp.HttpRes(200, answer) }, keys)
        client.warp()
        val msg = client.forgetWarp()
        assertTrue(msg, msg.contains("removed at Cloudflare"))
        val del = calls.last()
        assertEquals("DELETE", del.method); assertTrue(del.url.endsWith("/reg/7a3c1f9e-0000-4000-8000-000000000001")); assertEquals("Bearer dev-token", del.headers["Authorization"])
        assertNull(keys.warpRegistration()); assertEquals("", keys.warpPrivateKey())
    }

    @Test fun `cloudflare one is waiting while zero trust is not applied, and enrolment refuses`() {
        assertEquals(CloudflareWarp.OneState.WAITING, CloudflareWarp.oneState(d, enrolled = false))
        val client = CloudflareWarp.Client(d, { error("no call may be made while waiting") }, plainStore())
        assertTrue(assertThrows(IllegalStateException::class.java) { client.enrolOne("T") }.message!!.contains("#994"))
    }

    @Test fun `once enabled cloudflare one logs in on the team host and enrols with the access token`() {
        val on = d.copy(one = d.one.copy(enabled = true))
        assertEquals(CloudflareWarp.OneState.READY, CloudflareWarp.oneState(on, false))
        assertEquals(CloudflareWarp.OneState.ENROLLED, CloudflareWarp.oneState(on, true))
        assertEquals("https://diegonmarcos.cloudflareaccess.com/warp", CloudflareWarp.oneLoginUrl(on))
        assertEquals("JWT.abc", CloudflareWarp.parseCallback(on, "com.cloudflare.warp://diegonmarcos.cloudflareaccess.com/auth?token=JWT.abc&x=1"))
        assertNull(CloudflareWarp.parseCallback(on, "https://evil.example/auth?token=JWT"))
        val calls = mutableListOf<CloudflareWarp.HttpReq>()
        val keys = plainStore()
        val (priv, reg) = CloudflareWarp.Client(on, { calls += it; CloudflareWarp.HttpRes(200, answer) }, keys).enrolOne("JWT.abc")
        assertEquals("JWT.abc", calls.single().headers["CF-Access-Jwt-Assertion"])
        assertEquals(priv, keys.onePrivateKey()); assertEquals("dev-token", keys.oneRegistration()!!.token)
        val conf = Config.parse(BufferedReader(StringReader(CloudflareWarp.oneConf(on, priv, reg))))
        assertEquals("One routes the mesh subnets, not the internet", listOf("10.0.0.0/24", "10.1.0.0/24"), conf.peers.single().allowedIps.map { it.toString() })
    }

    @Test fun `the mesh key parked while a cloudflare profile is the tunnel is handed back`() {
        val keys = plainStore().also { it.meshKeyStash = "" }
        val prefs = WireGuardPrefs(ctx)
        prefs.interfacePrivateKey = com.wireguard.crypto.KeyPair().privateKey.toBase64()
        val mesh = prefs.interfacePrivateKey
        val warpKey = com.wireguard.crypto.KeyPair().privateKey.toBase64()
        val text = CloudflareWarp.warpConf(d, warpKey, CloudflareWarp.parseRegistration(answer))
        prefs.applyCloudflare("wg-cloudflare-warp", text, keys)
        assertEquals(warpKey, prefs.interfacePrivateKey); assertEquals("wg-cloudflare-warp", prefs.activeProfile); assertEquals(mesh, keys.meshKeyStash)
        prefs.applyCloudflare("wg-cloudflare-warp", text, keys)
        assertEquals("a second apply does not park the WARP key over the mesh key", mesh, keys.meshKeyStash)
        prefs.leaveCloudflare(keys)
        assertEquals(mesh, prefs.interfacePrivateKey); assertEquals("", keys.meshKeyStash)
        assertFalse("the Cloudflare profile is not copied into the plain stored set", prefs.profiles().containsKey("wg-cloudflare-warp"))
    }
}
