package com.diegonmarcos.clouddrive.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.diegonmarcos.clouddrive.storage.FuseWire.Kind
import com.diegonmarcos.clouddrive.storage.FuseWire.Spec

/** Mounts > FUSE: the scripts run in the Termux proot guest, and the honest verdict on whether FUSE can work there at all. */
class FuseWireTest {
    private fun caps(devFuse: Boolean = true, rw: Boolean = true, kernel: Boolean = true, proot: Boolean = true, fm3: Boolean = true, rclone: Boolean = true, sshfs: Boolean = true) =
        FuseWire.parseCaps(buildString {
            appendLine("uid=0"); appendLine("dev_fuse=${if (devFuse) "yes" else "no"}"); appendLine("dev_fuse_rw=${if (rw) "yes" else "no"}")
            appendLine("kernel_fuse=${if (kernel) "yes" else "no"}"); appendLine("proot=${if (proot) "yes" else "no"}")
            appendLine("bin_rclone=${if (rclone) "yes" else "no"}"); appendLine("bin_sshfs=${if (sshfs) "yes" else "no"}")
            appendLine("bin_fusermount3=${if (fm3) "yes" else "no"}"); appendLine("bin_fusermount=no"); appendLine("bin_ssh=yes")
        })

    @Test fun `a guest that answers nothing is not usable and says to open the terminal`() {
        assertNull(FuseWire.parseCaps("garbage"))
        val v = FuseWire.verdict(null)
        assertFalse(v.usable); assertTrue(v.reason.contains("Termux"))
    }

    @Test fun `proot without dev fuse is refused with the reason, not faked`() {
        val v = FuseWire.verdict(caps(devFuse = false, rw = false))
        assertFalse(v.usable); assertTrue(v.reason, v.reason.contains("/dev/fuse") && v.reason.contains("proot"))
    }

    @Test fun `each blocker has its own sentence and only a complete guest is usable`() {
        assertTrue(FuseWire.verdict(caps(kernel = false)).reason.contains("kernel"))
        assertTrue(FuseWire.verdict(caps(rw = false)).reason.contains("cannot open"))
        assertTrue(FuseWire.verdict(caps(fm3 = false)).reason.contains("fusermount"))
        assertTrue(FuseWire.verdict(caps()).usable)
    }

    @Test fun `missing tools are named per mount kind`() {
        assertEquals(listOf("rclone"), FuseWire.missingTools(Kind.RCLONE, caps(rclone = false)))
        assertEquals(listOf("sshfs"), FuseWire.missingTools(Kind.SSHFS, caps(sshfs = false)))
        assertEquals(emptyList<String>(), FuseWire.missingTools(Kind.RCLONE, caps()))
    }

    @Test fun `the probe only looks, it never mounts`() {
        val p = FuseWire.probe()
        assertTrue("/dev/fuse" in p && "/proc/filesystems" in p)
        // a command position is the start, or after ; & | : none of them may be a mount, an unmount or a device node (the tool NAMES in the `for` list are fine)
        assertFalse(p, Regex("""(^|[;&|]\s*)(mount|umount|mknod|fusermount3?\s+-u|rclone\s+mount|sshfs\s)""").containsMatchIn(p))
        assertTrue("it asks command -v for each tool", "command -v" in p)
    }

    private val rc = Spec("gd", Kind.RCLONE, "gdrive:Docs", "/root/mnt/gd", "writes")

    @Test fun `an rclone mount uses the staged config, the vfs cache mode and runs as a daemon`() {
        val s = FuseWire.mountScript(rc)
        assertTrue(s, "rclone mount 'gdrive:Docs' '/root/mnt/gd'" in s && "--vfs-cache-mode writes" in s && "--daemon" in s && "--config \$HOME/.cache/cloud-drive-fuse/gd.conf" in s)
        assertTrue("--read-only" in FuseWire.mountScript(rc.copy(readOnly = true)))
        assertTrue("already-mounted" in s)
    }

    @Test fun `an sshfs mount reconnects, pins the staged key only when asked`() {
        val ss = Spec("box", Kind.SSHFS, "me@box.mesh.diegonmarcos.com:/srv", "/root/mnt/box")
        val plain = FuseWire.mountScript(ss)
        assertTrue("sshfs 'me@box.mesh.diegonmarcos.com:/srv' '/root/mnt/box'" in plain && "reconnect" in plain && "IdentityFile" !in plain)
        assertTrue("IdentityFile=\$HOME/.cache/cloud-drive-fuse/box.key" in FuseWire.mountScript(ss, withKey = true))
    }

    @Test fun `values are quoted and a hostile one is refused before any script exists`() {
        assertNull(FuseWire.refusal(rc))
        val bad = listOf(
            rc.copy(id = "Gd"), rc.copy(id = "a;b"), rc.copy(mountpoint = "relative"), rc.copy(mountpoint = "/root/../etc"), rc.copy(mountpoint = "/"),
            rc.copy(mountpoint = "/etc"), rc.copy(cacheMode = "turbo"), rc.copy(source = "-oProxyCommand=x"), rc.copy(source = "no-colon"),
            Spec("b", Kind.SSHFS, "host:/p", "/root/m"), Spec("b", Kind.SSHFS, "u@h:relative", "/root/m"),
        )
        for (b in bad) { assertNotNull(b.toString(), FuseWire.refusal(b)); var threw = false; try { FuseWire.mountScript(b) } catch (e: IllegalArgumentException) { threw = true }; assertTrue(b.toString(), threw) }
        val quoted = FuseWire.mountScript(rc.copy(source = "my remote:it's"))
        assertTrue(quoted, "'my remote:it'\\''s'" in quoted)
    }

    @Test fun `no credential is ever inside a script, the config and key arrive on stdin`() {
        val all = listOf(FuseWire.mountScript(rc), FuseWire.confStage("gd"), FuseWire.keyStage("gd"), FuseWire.serveScript("gd", "gdrive:", 8181, "writes"))
        for (s in all) for (secret in listOf("token", "access_token", "PRIVATE KEY", "password")) assertFalse("$secret in $s", secret in s)
        assertTrue("cat > " in FuseWire.confStage("gd") && "umask 177" in FuseWire.confStage("gd"))
    }

    @Test fun `unmount falls back from fusermount to lazy to umount, and removes the staged secrets`() {
        val u = FuseWire.unmountScript(rc)
        assertTrue("fusermount3 -u '/root/mnt/gd'" in u && "-uz" in u && "umount" in u && "rm -f" in u && ".conf" in u && ".key" in u)
        assertTrue(FuseWire.unmountScript(rc, "fusermount").startsWith("fusermount -u"))
        assertTrue(FuseWire.unmountScript(rc, "evil; rm").startsWith("fusermount3 -u"))
    }

    @Test fun `status is read from the guest's own mount table`() {
        val live = FuseWire.parseStatus("/root/mnt/gd fuse.rclone gdrive:Docs\n/root/mnt/my\\040dir fuse.sshfs me@h:/srv\nnoise\n/x ext4 /dev/y")
        assertEquals(listOf("/root/mnt/gd", "/root/mnt/my dir"), live.map { it.mountpoint })
        assertTrue(FuseWire.isMounted(live, rc)); assertFalse(FuseWire.isMounted(live, rc.copy(mountpoint = "/root/mnt/other")))
        assertEquals(emptyList<FuseWire.Live>(), FuseWire.parseStatus(""))
    }

    @Test fun `the fallback serves the remote on loopback only, with a pid file to stop it`() {
        val s = FuseWire.serveScript("gd", "gdrive:Docs", 8181, "full")
        assertTrue("rclone serve webdav 'gdrive:Docs' --addr 127.0.0.1:8181" in s && "--vfs-cache-mode full" in s && "0.0.0.0" !in s)
        for (bad in listOf(Triple("gd", "gdrive:", 80), Triple("G d", "gdrive:", 8181), Triple("gd", "-x", 8181))) {
            var threw = false; try { FuseWire.serveScript(bad.first, bad.second, bad.third, "full") } catch (e: IllegalArgumentException) { threw = true }; assertTrue(bad.toString(), threw)
        }
        assertTrue("kill" in FuseWire.stopServeScript("gd") && ".pid" in FuseWire.stopServeScript("gd"))
    }

    @Test fun `no mount exists until the user defines one, and the saved list round-trips and drops a damaged entry`() {
        assertEquals(emptyList<Spec>(), FuseWire.fromJson(""))
        assertEquals(emptyList<Spec>(), FuseWire.fromJson("{\"mounts\":[]}"))
        val list = listOf(rc, Spec("box", Kind.SSHFS, "me@box.mesh.diegonmarcos.com:/srv", "/root/mnt/box", "off", true))
        assertEquals(list, FuseWire.fromJson(FuseWire.toJson(list)))
        assertEquals(listOf(list[1]), FuseWire.fromJson(FuseWire.toJson(list).replace("/root/mnt/gd", "../etc")))
        for (w in listOf("password", "token", "secret", "private")) assertFalse(w, w in FuseWire.toJson(list).lowercase())
    }
}
