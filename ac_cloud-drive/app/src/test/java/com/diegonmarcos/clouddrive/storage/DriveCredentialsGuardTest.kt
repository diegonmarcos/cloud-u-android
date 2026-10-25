package com.diegonmarcos.clouddrive.storage

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * IPC CONTRACT GUARD, Drive's half. Cloud Account provides the credential contract (libs:fleet-endpoints DriveCredentialsContract,
 * served by libs:account's AccountData); Drive consumes it through [Wire] and [DriveCredentialsClient]. This reads the CONTRACT'S
 * SOURCE and the PROVIDER'S SOURCE from the repository checkout and holds Drive to them in both directions: every name Drive sends
 * or reads is the contract's, every operation Drive sends is one the contract parses, the provider serves the methods, and Drive
 * types no credential path of its own. A missing source FAILS, it is never skipped.
 */
class DriveCredentialsGuardTest {
    private val repo: File = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }.first { File(it, "ac_cloud-drive/build.json").isFile }
    private fun src(path: String) = File(repo, path).also { assertTrue("$path not found: the guard cannot run", it.isFile) }.readText()
    private val contract = src("ab_cloud-libs-shared/libs/fleet-endpoints/src/main/kotlin/com/diegonmarcos/superapp/fleetendpoints/DriveCredentialsContract.kt")
    private val endpoints = src("ab_cloud-libs-shared/libs/fleet-endpoints/src/main/kotlin/com/diegonmarcos/superapp/fleetendpoints/FleetEndpointsContract.kt")
    private val provider = src("ab_cloud-libs-shared/libs/account/src/main/java/com/diegonmarcos/superapp/profile/AccountData.kt")
    private val vault = src("ab_cloud-libs-shared/libs/account/src/main/java/com/diegonmarcos/superapp/settings/AccountVault.kt")

    private fun str(text: String, name: String) = Regex("""const val $name = "([^"]*)"""").find(text)?.groupValues?.get(1) ?: throw AssertionError("$name is not a string constant in the contract")

    @Test fun `Drive's wire names are the contract's`() {
        assertEquals(str(contract, "METHOD"), Wire.METHOD)
        assertEquals(str(contract, "METHOD_PUT"), Wire.METHOD_PUT)
        assertEquals(str(contract, "KEY_OK"), Wire.KEY_OK)
        assertEquals(str(contract, "KEY_ERROR"), Wire.KEY_ERROR)
        assertEquals(str(contract, "KEY_JSON"), Wire.KEY_JSON)
        assertEquals(Regex("""const val VERSION = (\d+)""").find(contract)!!.groupValues[1].toInt(), Wire.VERSION)
        assertEquals(Wire.MAX_UNISON_BYTES, Regex("""MAX_UNISON_BYTES = (\d+) \* (\d+)""").find(contract)!!.groupValues.let { it[1].toInt() * it[2].toInt() })
        assertEquals(Regex("""val RCLONE_TYPES = listOf\(([^)]*)\)""").find(contract)!!.groupValues[1].split(',').map { it.trim().trim('"') }, Wire.RCLONE_TYPES)
        assertEquals(str(contract, "GRANT_RCLONE"), Wire.GRANT_RCLONE)
        assertEquals(str(contract, "GRANT_SSH_PRIVATE"), Wire.GRANT_SSH_PRIVATE)
        assertEquals(str(endpoints, "ACCOUNT_PACKAGE"), Wire.ACCOUNT_PACKAGE)
        assertTrue("the authority is the accountdata provider", Regex("""const val AUTHORITY = "\$ACCOUNT_PACKAGE\.accountdata"""").containsMatchIn(endpoints))
        assertEquals("${Wire.ACCOUNT_PACKAGE}.accountdata", Wire.AUTHORITY)
    }

    @Test fun `every operation Drive sends is one the contract parses`() {
        val client = src("ac_cloud-drive/app/src/main/java/com/diegonmarcos/clouddrive/storage/DriveCredentialsClient.kt")
        val sent = Regex("""JSONObject\(\)\.put\("(rclone|remove_rclone|mount|remove_mount|unison)"""").findAll(client).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("rclone", "remove_rclone", "mount", "remove_mount", "unison"), sent)
        for (key in sent) assertTrue("parsePut does not read \"$key\"", Regex("""(optJSONObject|optString|has)\("$key"\)""").containsMatchIn(contract))
    }

    @Test fun `the provider serves both methods behind a grant and Drive is seeded with every layer`() {
        assertTrue("accountdata does not serve ${Wire.METHOD}", Regex("""METHOD_DRIVE_CREDENTIALS\s*=\s*com\.diegonmarcos\.superapp\.fleetendpoints\.DriveCredentialsContract\.METHOD\b""").containsMatchIn(provider))
        assertTrue("accountdata does not serve ${Wire.METHOD_PUT}", Regex("""METHOD_DRIVE_CREDENTIALS_PUT\s*=\s*com\.diegonmarcos\.superapp\.fleetendpoints\.DriveCredentialsContract\.METHOD_PUT\b""").containsMatchIn(provider))
        assertTrue("the write door lost its grant check", provider.contains("DriveCredentialsContract.GRANT_WRITE"))
        assertTrue("the read door lost its grant check", provider.contains("DriveCredentialsContract.GRANT_READ"))
        assertTrue("Drive is not seeded with the contract's grants", Regex("""DRIVE_PACKAGE to listOf\([^)]*\)\s*\+\s*com\.diegonmarcos\.superapp\.fleetendpoints\.DriveCredentialsContract\.GRANTS""").containsMatchIn(vault))
        assertEquals("com.diegonmarcos.clouddrive", Regex("""const val DRIVE_PACKAGE = "([^"]+)"""").find(vault)!!.groupValues[1])
        for (g in listOf("GRANT_READ", "GRANT_RCLONE", "GRANT_SSH_PRIVATE", "GRANT_MOUNTS", "GRANT_WRITE", "GRANT_UNISON")) assertTrue("$g is not in GRANTS", Regex("""val GRANTS = listOf\([^)]*$g""").containsMatchIn(contract))
    }

    @Test fun `Drive names the credential door only through Wire and holds no credential path of its own`() {
        val main = File(repo, "ac_cloud-drive/app/src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        val doors = main.filter { it.readText().contains("cloudaccount.accountdata") || it.readText().contains("\"drive_credentials") }.map { it.name }
        assertEquals("the Account door is named outside Wire", listOf("DriveCredentialsClient.kt"), doors)
        // DriveCredentialsClient.kt names two GRANTS (for a sentence), checked equal to the contract above; no other file may type one
        val paths = main.filter { it.name != "DriveCredentialsClient.kt" && Regex("\"(drive|ssh)\\.[a-z0-9_.]+\"").containsMatchIn(it.readText()) }.map { it.name }
        assertEquals("a vault path typed in Drive: those are Account's", emptyList<String>(), paths)
    }
}
