package com.diegonmarcos.cloudlib.mounts

import android.content.Context
import java.io.File

/**
 * Opens a mount (#871 move 12): the connection is made by the mounts engine, so this builds the [EngineFs]
 * that holds it and connects NOW, which makes a refused login, an unreachable host or an absent engine the
 * caller's exception at open, as it always was. The Context finds the engine ([MountLink]); the JVM suite
 * passes a fake [MountTransport].
 */
object MountFsFactory {
    fun open(context: Context, spec: MountSpec, secret: MountSecret, knownHosts: File): RemoteFs =
        open(MountLink(context), spec, secret, knownHosts)

    fun open(
        transport: MountTransport, spec: MountSpec, secret: MountSecret, knownHosts: File,
        readText: (String) -> String? = { p -> File(p).takeIf { it.isFile }?.readText() },
    ): RemoteFs = EngineFs(transport, spec, secret, knownHosts, readText).also { it.connect() }
}
