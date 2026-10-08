package app.sterna.core.data.account

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SOURCE LINT, same instrument as [CollapsedFoldersStoreWiringTest]. A vault password restored onto
 * an account that came back from a settings backup typed OAUTH (expired access token, no refresh
 * token) must switch it to BASIC first; otherwise the password lands in the refresh-token slot and
 * the account shows "This account isn't signed in yet" forever (me@dnm-JMAP, 2026-10-08).
 */
class VaultMailImportOAuthRestoreTest {
    @Test fun `a vault password converts an OAUTH login to BASIC before storing it`() {
        val src = source()
        val convert = src.indexOf("store.convertToBasicAuth(login)")
        val store = src.indexOf("store.updatePassword(existing.id, d.password)")
        assertTrue("VaultMailImport no longer converts an OAUTH login before restoring the vault password", convert in 0 until store)
        assertTrue("the OAUTH guard is gone: a BASIC login must keep its slot", src.contains("?.authType == AuthType.OAUTH) {"))
    }

    private fun source(): String {
        val path = "core/data/src/main/kotlin/app/sterna/core/data/account/VaultMailImport.kt"
        val root = generateSequence(java.io.File("").absoluteFile) { it.parentFile }
            .firstOrNull { java.io.File(it, path).isFile }
            ?: error("cannot locate the repo root from ${java.io.File("").absolutePath}")
        return java.io.File(root, path).readText()
    }
}
