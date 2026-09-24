package com.diegonmarcos.cloudlib.mounts

import java.io.Closeable
import java.io.File

/**
 * What every mount can do, whatever protocol is underneath. Blocking; the
 * screen runs it on IO. One instance per open connection.
 */
interface RemoteFs : Closeable {
    fun list(path: String): List<RemoteEntry>
    fun download(path: String, into: File, onProgress: (Long) -> Unit = {})
    fun upload(local: File, path: String, onProgress: (Long) -> Unit = {})
    fun mkdir(path: String)
    fun delete(path: String, isDir: Boolean)
    fun rename(from: String, to: String)
    /** Only SSH mounts run commands; everything else returns null. */
    fun exec(command: String): String? = null
}

/** Password or key passphrase, resolved from the credential store at connect time. */
data class MountSecret(val password: String?, val passphrase: String?)

/** Normalised remote path joins the whole engine shares. */
object RemotePaths {
    fun join(base: String, name: String): String = (base.trimEnd('/') + "/" + name.trimStart('/')).ifEmpty { "/" }.let { if (it.startsWith("/")) it else "/$it" }
    fun parent(path: String): String { val t = path.trimEnd('/'); val i = t.lastIndexOf('/'); return if (i <= 0) "/" else t.substring(0, i) }
    fun name(path: String): String = path.trimEnd('/').substringAfterLast('/')
}
