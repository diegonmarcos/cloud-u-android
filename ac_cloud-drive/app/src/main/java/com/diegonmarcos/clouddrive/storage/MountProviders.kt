package com.diegonmarcos.clouddrive.storage

import com.diegonmarcos.clouddrive.Declarations.MountProviderDecl

/**
 * #1216 the provider lines of Mounts > FUSE and Configs > Mounts. The lines are DATA (build.json ui.sync.mounts.providers);
 * this object only derives from one line what the existing FUSE engine needs: the mount [FuseWire.Spec], the values the rclone
 * setup wizard starts from, and the state the line shows. No key, token or password is ever derived or stored here: the
 * credential lives in rclone.conf (set up by the wizard) and Cloud Account.
 */
object MountProviders {
    const val MOUNT_ROOT = "/root/mnt"

    /** Where a line stands: no such remote yet, a remote of the wrong backend, ready to mount, or mounted now. */
    enum class State { MISSING, WRONG_TYPE, READY, MOUNTED }

    /** The FUSE mount for a line, or null for the free `others` line (it has no remote of its own). */
    fun specFor(d: MountProviderDecl): FuseWire.Spec? =
        if (d.others || d.remote.isBlank()) null
        else FuseWire.Spec(d.id, FuseWire.Kind.RCLONE, d.remote + ":" + d.path, "$MOUNT_ROOT/${d.id}", FuseWire.DEFAULT_CACHE_MODE, d.readOnly)

    /** [remotes] maps an rclone remote name to its backend type ("s3", "http", ...). */
    fun state(d: MountProviderDecl, remotes: Map<String, String>, live: List<FuseWire.Live>): State {
        val spec = specFor(d) ?: return State.MISSING
        val type = remotes[d.remote] ?: return State.MISSING
        if (d.rcloneType.isNotBlank() && type != d.rcloneType) return State.WRONG_TYPE
        return if (FuseWire.isMounted(live, spec)) State.MOUNTED else State.READY
    }

    /** The option values the setup wizard starts from for this line (public facts only, never a secret). */
    fun wizardPreset(d: MountProviderDecl): Map<String, String> = buildMap {
        if (d.rcloneProvider.isNotBlank()) put("provider", d.rcloneProvider)
        if (d.endpoint.isNotBlank()) put(if (d.rcloneType == "http") "url" else "endpoint", d.endpoint)
        if (d.storageClass.isNotBlank()) put("storage_class", d.storageClass)
    }
}
