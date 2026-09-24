package com.termux.cloud;

import android.content.Context;
import android.content.res.AssetManager;
import android.system.ErrnoException;
import android.system.Os;

import com.termux.BuildConfig;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Stages the glibc root filesystem baked into this APK (#470) and makes it the
 * terminal's login shell.
 *
 * The APK carries assets/&lt;asset_dir&gt;/ (asset_dir from rootfs/rootfs.json):
 * rootfs.tar.zst, rootfs.sha256, proot and enter.sh. This copies them to
 * $PREFIX/var/lib/&lt;asset_dir&gt;/ whenever the baked digest differs from the
 * staged one, so a fresh install AND an update of an existing install both get
 * the new tree. Existing installs matter: the Termux bootstrap only ever runs
 * once, so anything hung off it would never reach a phone that already had this
 * app — built, shipped, and never switched on (#436).
 *
 * The first time anything is staged on a device, ~/.termux/shell is pointed at
 * enter.sh, which is Termux's own mechanism for choosing the login shell (it is
 * what `chsh` writes and what $PREFIX/bin/login reads). Later updates leave it
 * alone, so a user who switched back to bash keeps bash. enter.sh does the
 * unpacking itself on the next login; the failsafe session skips it.
 */
public final class CloudRootfs {

    private static final String LOG_TAG = "CloudRootfs";
    private static final String DIGEST = "rootfs.sha256";
    /** The digest is copied LAST: an interrupted copy leaves the old digest, so the next start restages. */
    private static final String[] FILES = {"proot", "enter.sh", "rootfs.tar.zst", DIGEST};

    private CloudRootfs() {}

    private static File stageDir() {
        return new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH, "var/lib/" + BuildConfig.CLOUD_ROOTFS_ASSET_DIR);
    }

    /** Cheap enough for the UI thread: two 65-byte reads. */
    public static boolean isStaged(Context context) {
        try {
            File staged = new File(stageDir(), DIGEST);
            return staged.isFile() && baked(context.getAssets()).equals(read(new FileInputStream(staged)));
        } catch (IOException e) {
            return false;
        }
    }

    /** Copies ~600 MB; call off the UI thread. */
    public static void stage(Context context) throws IOException, ErrnoException {
        AssetManager assets = context.getAssets();
        File dir = stageDir();
        boolean firstEver = !new File(dir, DIGEST).exists();
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);

        for (String name : FILES) {
            try (InputStream in = assets.open(BuildConfig.CLOUD_ROOTFS_ASSET_DIR + "/" + name);
                 OutputStream out = new FileOutputStream(new File(dir, name))) {
                byte[] buffer = new byte[1 << 16];
                int n;
                while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            }
        }
        //noinspection OctalInteger
        Os.chmod(new File(dir, "proot").getAbsolutePath(), 0700);
        //noinspection OctalInteger
        Os.chmod(new File(dir, "enter.sh").getAbsolutePath(), 0700);
        Logger.logInfo(LOG_TAG, "Staged the baked rootfs " + baked(assets) + " into " + dir);

        if (firstEver) {
            File termuxDir = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".termux");
            if (!termuxDir.isDirectory() && !termuxDir.mkdirs()) throw new IOException("cannot create " + termuxDir);
            File shell = new File(termuxDir, "shell");
            if (shell.exists() || isSymlink(shell)) //noinspection ResultOfMethodCallIgnored
                shell.delete();
            Os.symlink(new File(dir, "enter.sh").getAbsolutePath(), shell.getAbsolutePath());
            Logger.logInfo(LOG_TAG, "Login shell is now " + shell + " -> enter.sh (delete it to return to bash)");
        }
    }

    private static String baked(AssetManager assets) throws IOException {
        return read(assets.open(BuildConfig.CLOUD_ROOTFS_ASSET_DIR + "/" + DIGEST));
    }

    private static boolean isSymlink(File file) {
        try {
            return android.system.OsConstants.S_ISLNK(Os.lstat(file.getAbsolutePath()).st_mode);
        } catch (ErrnoException e) {
            return false;
        }
    }

    private static String read(InputStream stream) throws IOException {
        try (InputStream in = stream) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[256];
            int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8).trim();
        }
    }
}
