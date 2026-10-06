package com.diegonmarcos.clouddrive.files

import com.diegonmarcos.clouddrive.files.PathFormatter.Target
import org.junit.Assert.assertEquals
import org.junit.Test

/** #875 Copy path: one formatter, three volume kinds, and nothing but the location in the text. */
class PathFormatterTest {

    @Test fun localVolumeCopiesTheAbsolutePath() {
        assertEquals("/mnt/vol/Download", PathFormatter.format(Target.Local("/mnt/vol/Download")))
        assertEquals("trailing slash dropped", "/mnt/vol/DCIM", PathFormatter.format(Target.Local("/mnt/vol/DCIM/")))
        assertEquals("/", PathFormatter.format(Target.Local("/")))
        assertEquals("/mnt/shared/CloudDrive", PathFormatter.forLocation(Location.Local("/mnt/shared/CloudDrive")))
    }

    @Test fun safVolumeCopiesTheContentUriAsGranted() {
        val uri = "content://com.android.externalstorage.documents/tree/primary%3ADocuments"
        assertEquals(uri, PathFormatter.format(Target.Saf(uri)))
        assertEquals("surrounding space trimmed", uri, PathFormatter.format(Target.Saf("  $uri \n")))
    }

    @Test fun rcloneMountCopiesTheRemoteSpec() {
        assertEquals("gdrive:backups/2026", PathFormatter.format(Target.Rclone("gdrive", "backups/2026")))
        assertEquals("slashes normalised", "oci-s3:bucket/dir", PathFormatter.format(Target.Rclone("oci-s3:", "/bucket/dir/")))
        assertEquals("remote root", "gdrive:", PathFormatter.format(Target.Rclone("gdrive")))
    }

    @Test fun archiveFolderAndMultiSelection() {
        assertEquals("/sdcard/a.zip!/docs/x", PathFormatter.forLocation(Location.Archive("/sdcard/a.zip", "docs/x")))
        assertEquals("/a\n/b", PathFormatter.forLocations(listOf(Location.Local("/a"), Location.Local("/b/"))))
    }
}
