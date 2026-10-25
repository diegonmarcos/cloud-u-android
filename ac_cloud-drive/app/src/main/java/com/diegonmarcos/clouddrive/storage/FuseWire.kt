package com.diegonmarcos.clouddrive.storage

import com.diegonmarcos.cloudlib.sharedstore.StoreExec.Companion.q

/**
 * Mounts > FUSE, the pure half: the scripts Cloud Drive runs INSIDE Cloud Terminal's Termux proot guest (reached
 * through the same CLOUD_EXEC channel the git and store scripts use), and the parsers for what they print.
 *
 * A FUSE mount needs /dev/fuse and a mount(2) the guest may call. Inside proot on a device without root neither is
 * granted, so [probe] asks the guest and [verdict] turns the answer into either "usable" or ONE sentence saying why
 * not. Nothing here pretends: when FUSE is not usable the page says so and offers [serveScript] (rclone serve webdav
 * on loopback) instead. Scripts take every value single-quoted; credentials never appear in a script: the rclone.conf
 * travels on stdin ([confStage]) into a 0600 file in the guest's own home.
 */
object FuseWire {

    /** Where a mount's pid, log and staged rclone.conf live in the guest (its $HOME, owner-only). */
    const val STATE_DIR = "\$HOME/.cache/cloud-drive-fuse"

    val CACHE_MODES = listOf("off", "minimal", "writes", "full")
    const val DEFAULT_CACHE_MODE = "writes"

    enum class Kind(val label: String) { RCLONE("rclone mount"), SSHFS("sshfs") }

    /** One mount the user asked for. [source] is `remote:path` for rclone, `user@host:/path` for sshfs. */
    data class Spec(val id: String, val kind: Kind, val source: String, val mountpoint: String, val cacheMode: String = DEFAULT_CACHE_MODE, val readOnly: Boolean = false)

    private val ID = Regex("[a-z0-9][a-z0-9-]{0,30}")
    private val SSH_SOURCE = Regex("[A-Za-z0-9._-]+@[A-Za-z0-9._-]+:/[^\\n\u0000]*")
    private val RCLONE_SOURCE = Regex("[^\\n\u0000:\\s-][^\\n\u0000:]*:[^\\n\u0000]*")

    fun validId(id: String) = ID.matches(id)

    fun validRcloneSource(s: String) = RCLONE_SOURCE.matches(s)

    /** The loopback port a mount's WebDAV fallback uses: stable per id, so a restart finds the same one, and away from well-known ports. */
    fun servePort(id: String): Int = 18200 + (id.hashCode() and 0x1ff)

    /** A mountpoint inside the guest: absolute, no `..`, not a system directory, no control characters. */
    fun validMountpoint(p: String): Boolean =
        p.startsWith("/") && p.length > 1 && p.none { it == '\n' || it == '\u0000' } && p.split('/').none { it == ".." } &&
            p.trimEnd('/') !in setOf("/", "/bin", "/boot", "/dev", "/etc", "/lib", "/proc", "/root", "/run", "/sbin", "/sys", "/usr", "/var", "/tmp")

    /** Null when [s] may be run, else the sentence that refuses it. */
    fun refusal(s: Spec): String? = when {
        !validId(s.id) -> "mount id must be [a-z0-9-], 1..31 characters"
        !validMountpoint(s.mountpoint) -> "mountpoint must be an absolute folder inside the guest (not / or a system directory, no ..)"
        s.cacheMode !in CACHE_MODES -> "vfs cache mode must be one of ${CACHE_MODES.joinToString()}"
        s.kind == Kind.RCLONE && !RCLONE_SOURCE.matches(s.source) -> "rclone source must look like remote:path"
        s.kind == Kind.SSHFS && !SSH_SOURCE.matches(s.source) -> "sshfs source must look like user@host:/path"
        else -> null
    }

    /** What the guest answers to [probe]. */
    data class Caps(
        val uid: Int, val devFuse: Boolean, val devFuseRw: Boolean, val kernelFuse: Boolean, val proot: Boolean,
        val bins: Map<String, Boolean>,
    ) {
        fun has(b: String) = bins[b] == true
        val fusermount: String? get() = listOf("fusermount3", "fusermount").firstOrNull { has(it) }
    }

    val PROBE_BINS = listOf("rclone", "sshfs", "fusermount3", "fusermount", "rsync", "unison", "ssh")

    /** One script, key=value lines out. No mount is attempted: /dev/fuse and the tools are looked at, not used. */
    fun probe(): String = buildString {
        append("echo uid=\$(id -u); ")
        append("[ -c /dev/fuse ] && echo dev_fuse=yes || echo dev_fuse=no; ")
        append("[ -r /dev/fuse ] && [ -w /dev/fuse ] && echo dev_fuse_rw=yes || echo dev_fuse_rw=no; ")
        append("grep -qw fuse /proc/filesystems 2>/dev/null && echo kernel_fuse=yes || echo kernel_fuse=no; ")
        append("{ [ -n \"\${PROOT_TMP_DIR:-}\" ] || grep -qa proot /proc/1/cmdline 2>/dev/null || [ -e /proc/self/root/.proot ]; } && echo proot=yes || echo proot=no; ")
        append("for b in ${PROBE_BINS.joinToString(" ")}; do command -v \$b >/dev/null 2>&1 && echo bin_\$b=yes || echo bin_\$b=no; done")
    }

    fun parseCaps(out: String): Caps? {
        val kv = out.lines().mapNotNull { l -> l.trim().split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
        if (kv["dev_fuse"] == null) return null
        fun yes(k: String) = kv[k] == "yes"
        return Caps(kv["uid"]?.toIntOrNull() ?: -1, yes("dev_fuse"), yes("dev_fuse_rw"), yes("kernel_fuse"), yes("proot"), PROBE_BINS.associateWith { yes("bin_$it") })
    }

    /** The honest answer: FUSE works here, or exactly why it does not. */
    data class Verdict(val usable: Boolean, val reason: String)

    fun verdict(c: Caps?): Verdict {
        if (c == null) return Verdict(false, "Cloud Terminal's Termux did not answer the FUSE check: open it once, then retry.")
        return when {
            !c.kernelFuse -> Verdict(false, "This kernel has no FUSE support (/proc/filesystems lists no fuse), so no mount can work here.")
            !c.devFuse -> Verdict(false, if (c.proot) "The Termux guest is a proot and has no /dev/fuse: proot cannot grant it without root, so a true FUSE mount is not possible on this device." else "The guest has no /dev/fuse.")
            !c.devFuseRw -> Verdict(false, "/dev/fuse exists in the guest but this user (uid ${c.uid}) cannot open it: FUSE needs root or a device that exposes it.")
            c.fusermount == null -> Verdict(false, "FUSE is present but neither fusermount3 nor fusermount is installed in the guest (apt install fuse3).")
            else -> Verdict(true, "FUSE is usable in the Termux guest.")
        }
    }

    /** Which tools a kind still needs in the guest; empty = ready. */
    fun missingTools(kind: Kind, c: Caps?): List<String> = when {
        c == null -> emptyList()
        kind == Kind.RCLONE -> listOfNotNull("rclone".takeUnless { c.has(it) })
        else -> listOfNotNull("sshfs".takeUnless { c.has(it) }, "ssh".takeUnless { c.has(it) })
    }

    private fun conf(id: String) = "$STATE_DIR/$id.conf"
    private fun pid(id: String) = "$STATE_DIR/$id.pid"
    private fun log(id: String) = "$STATE_DIR/$id.log"
    private fun key(id: String) = "$STATE_DIR/$id.key"

    /** Stdin -> the staged SSH private key (owner-only), for an sshfs mount whose key Cloud Account holds. */
    fun keyStage(id: String): String {
        require(validId(id)) { "refused key stage for $id" }
        return "mkdir -p $STATE_DIR && chmod 700 $STATE_DIR && umask 177 && cat > ${key(id)}"
    }

    /** Stdin -> the staged rclone.conf (owner-only). The text is the caller's, handed in on stdin by the runner. */
    fun confStage(id: String): String = "mkdir -p $STATE_DIR && chmod 700 $STATE_DIR && umask 177 && cat > ${conf(id)}"

    /** The mount, started as a daemon in the guest. Prints `started` or the failure; the log is kept for [logTail]. */
    fun mountScript(s: Spec, mountpointInGuest: String = s.mountpoint, withKey: Boolean = false): String {
        refusal(s)?.let { throw IllegalArgumentException(it) }
        val mp = q(mountpointInGuest)
        val ro = if (s.readOnly) " --read-only" else ""
        val start = when (s.kind) {
            Kind.RCLONE ->
                "rclone mount ${q(s.source)} $mp --config ${conf(s.id)} --vfs-cache-mode ${s.cacheMode}$ro --daemon --log-file ${log(s.id)} --log-level NOTICE"
            Kind.SSHFS ->
                "sshfs ${q(s.source)} $mp -o reconnect,ServerAliveInterval=15,StrictHostKeyChecking=accept-new${if (s.readOnly) ",ro" else ""}${if (withKey) ",IdentitiesOnly=yes,IdentityFile=${key(s.id)}" else ""}"
        }
        return "set -e; mkdir -p $STATE_DIR $mp; chmod 700 $STATE_DIR; if grep -qs \" \$(readlink -f $mp) fuse\" /proc/mounts; then echo already-mounted; exit 0; fi; " +
            "$start && echo started"
    }

    /** Unmount (fusermount -u, falling back to the lazy form and umount) and drop the staged config. */
    fun unmountScript(s: Spec, fusermount: String = "fusermount3"): String {
        require(validId(s.id) && validMountpoint(s.mountpoint)) { "refused unmount of ${s.id}" }
        val mp = q(s.mountpoint)
        val fm = if (fusermount in setOf("fusermount", "fusermount3")) fusermount else "fusermount3"
        return "$fm -u $mp 2>/dev/null || $fm -uz $mp 2>/dev/null || umount $mp 2>/dev/null || { echo busy; exit 1; }; rm -f ${conf(s.id)} ${pid(s.id)} ${key(s.id)}; echo unmounted"
    }

    /** Which FUSE mounts the guest holds right now: `mountpoint fstype source` per line. */
    fun statusScript(): String = "grep -E ' fuse(\\.[a-z0-9.-]+)? ' /proc/mounts | while read -r src mp type rest; do echo \"\$mp \$type \$src\"; done"

    data class Live(val mountpoint: String, val type: String, val source: String)

    fun parseStatus(out: String): List<Live> = out.lines().mapNotNull { l ->
        val p = l.trim().split(' ', limit = 3)
        if (p.size == 3 && p[0].startsWith("/") && p[1].startsWith("fuse")) Live(p[0].replace("\\040", " "), p[1], p[2]) else null
    }

    fun isMounted(live: List<Live>, s: Spec): Boolean = live.any { it.mountpoint.trimEnd('/') == s.mountpoint.trimEnd('/') }

    fun logTail(s: Spec): String = "tail -n 20 ${log(s.id)} 2>/dev/null || true"

    /**
     * The fallback when FUSE is not usable: rclone serves the remote as WebDAV on loopback inside the guest. It is not a
     * mount (no path in the filesystem), it is a way to reach the remote from apps that speak WebDAV. Cache mode applies.
     */
    fun serveScript(id: String, source: String, port: Int, cacheMode: String): String {
        require(validId(id) && RCLONE_SOURCE.matches(source) && port in 1024..65535 && cacheMode in CACHE_MODES) { "refused serve of $id" }
        return "set -e; mkdir -p $STATE_DIR; chmod 700 $STATE_DIR; if [ -f ${pid(id)} ] && kill -0 \$(cat ${pid(id)}) 2>/dev/null; then echo already-serving; exit 0; fi; " +
            "nohup rclone serve webdav ${q(source)} --addr 127.0.0.1:$port --config ${conf(id)} --vfs-cache-mode $cacheMode --log-file ${log(id)} --log-level NOTICE >/dev/null 2>&1 & echo \$! > ${pid(id)}; sleep 1; " +
            "if kill -0 \$(cat ${pid(id)}) 2>/dev/null; then echo serving; else echo failed; tail -n 5 ${log(id)} 2>/dev/null; exit 1; fi"
    }

    fun stopServeScript(id: String): String {
        require(validId(id)) { "refused stop of $id" }
        return "if [ -f ${pid(id)} ]; then kill \$(cat ${pid(id)}) 2>/dev/null; fi; rm -f ${pid(id)} ${conf(id)}; echo stopped"
    }

    fun serveRunningScript(id: String): String {
        require(validId(id)) { "refused status of $id" }
        return "[ -f ${pid(id)} ] && kill -0 \$(cat ${pid(id)}) 2>/dev/null && echo running || echo stopped"
    }

    // ── persistence: the mounts the user defined (no secret: the config and key come from Cloud Account at mount time) ──

    fun toJson(specs: List<Spec>): String = org.json.JSONObject().put("mounts", org.json.JSONArray(specs.map {
        org.json.JSONObject().put("id", it.id).put("kind", it.kind.name).put("source", it.source).put("mountpoint", it.mountpoint)
            .put("cache_mode", it.cacheMode).put("read_only", it.readOnly)
    })).toString(2)

    /** A mount that does not parse or does not pass [refusal] is dropped, so a hand-edited file can never run something odd. */
    fun fromJson(text: String): List<Spec> {
        val arr = runCatching { org.json.JSONObject(text).optJSONArray("mounts") }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val kind = runCatching { Kind.valueOf(o.optString("kind")) }.getOrNull() ?: return@mapNotNull null
            Spec(o.optString("id"), kind, o.optString("source"), o.optString("mountpoint"), o.optString("cache_mode", DEFAULT_CACHE_MODE), o.optBoolean("read_only", false))
                .takeIf { refusal(it) == null }
        }
    }
}
