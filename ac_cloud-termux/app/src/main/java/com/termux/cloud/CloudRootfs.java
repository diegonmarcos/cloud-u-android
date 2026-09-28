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
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Stages the glibc root filesystem this terminal runs (#470) and makes it the
 * terminal's login shell.
 *
 * The APK carries assets/&lt;asset_dir&gt;/ (asset_dir from rootfs/rootfs.json):
 * proot, enter.sh, rootfs.sha256 and rootfs.url — four small files. #618: the
 * ~400 MB tarball is NOT among them. It used to be, and that made every
 * one-line app fix a 400 MB update that every phone re-downloaded in full.
 * It is now a separately-addressed asset on this app's own rolling release,
 * fetched once here, gated on the baked rootfs.sha256, and cached: when the
 * staged digest already equals the baked one nothing is fetched at all, so an
 * app update that does not move the rootfs costs no bytes and works offline.
 *
 * Everything is copied/fetched to $PREFIX/var/lib/&lt;asset_dir&gt;/ whenever the
 * baked digest differs from the staged one, so a fresh install AND an update of
 * an existing install both get the new tree. Existing installs matter: the
 * Termux bootstrap only ever runs once, so anything hung off it would never
 * reach a phone that already had this app — built, shipped, and never switched
 * on (#436).
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
    /** #618 — where the tarball is fetched from, written by CI from rootfs.json::artifact. Never a literal here. */
    private static final String URL_ASSET = "rootfs.url";
    private static final String TARBALL = "rootfs.tar.zst";
    /** The small files, from assets. The digest is written LAST and only after a verified fetch, so an interrupted update restages. */
    private static final String[] FILES = {"proot", "enter.sh", URL_ASSET};

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

    /** Fetches ~400 MB the first time and after a rootfs change; call off the UI thread. */
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

        fetchTarball(dir, read(assets.open(BuildConfig.CLOUD_ROOTFS_ASSET_DIR + "/" + URL_ASSET)), baked(assets));

        // LAST, and only now: enter.sh unpacks exactly when this file disagrees
        // with what it already unpacked, so writing it before a verified tarball
        // is in place would tell it to unpack bytes that are not there.
        try (OutputStream out = new FileOutputStream(new File(dir, DIGEST))) {
            out.write((baked(assets) + "\n").getBytes(StandardCharsets.UTF_8));
        }
        Logger.logInfo(LOG_TAG, "Staged the rootfs " + baked(assets) + " into " + dir);

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

    /**
     * #618 — fetch the tarball and REFUSE it unless it hashes to {@code want}.
     *
     * The digest comes from the APK, the bytes come from the network, and the
     * comparison is the only thing that makes the second trustworthy: without it
     * this would be the first-run download that #348 removed from the other
     * terminal, marking unread bytes executable. A mismatch throws, so the old
     * digest stays on disk, enter.sh keeps using the tree it already unpacked,
     * and the next start tries again.
     *
     * A tarball already on disk that hashes correctly is kept: an interrupted
     * update resumes without another 400 MB.
     */
    private static void fetchTarball(File dir, String url, String want) throws IOException {
        File tarball = new File(dir, TARBALL);
        if (tarball.isFile() && want.equals(sha256(tarball))) {
            Logger.logInfo(LOG_TAG, "The fetched rootfs " + want + " is already here");
            return;
        }

        File part = new File(dir, TARBALL + ".part");
        //noinspection ResultOfMethodCallIgnored
        part.delete();
        Logger.logInfo(LOG_TAG, "Fetching the rootfs " + want + " from " + url);

        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setInstanceFollowRedirects(true);
        connection.setConnectTimeout(30_000);
        connection.setReadTimeout(60_000);
        try {
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK)
                throw new IOException("fetching " + url + " answered HTTP " + status);
            MessageDigest digest = sha256();
            try (InputStream in = new DigestInputStream(connection.getInputStream(), digest);
                 OutputStream out = new FileOutputStream(part)) {
                byte[] buffer = new byte[1 << 16];
                int n;
                while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            }
            String got = hex(digest.digest());
            if (!want.equals(got)) {
                //noinspection ResultOfMethodCallIgnored
                part.delete();
                throw new IOException("the rootfs fetched from " + url + " is sha256 " + got
                    + ", not the " + want + " this build was signed off against");
            }
        } finally {
            connection.disconnect();
        }
        //noinspection ResultOfMethodCallIgnored
        tarball.delete();
        if (!part.renameTo(tarball)) throw new IOException("cannot move " + part + " to " + tarball);
    }

    private static MessageDigest sha256() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    private static String sha256(File file) throws IOException {
        MessageDigest digest = sha256();
        try (InputStream in = new DigestInputStream(new FileInputStream(file), digest)) {
            byte[] buffer = new byte[1 << 16];
            //noinspection StatementWithEmptyBody
            while (in.read(buffer) != -1) { }
        }
        return hex(digest.digest());
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) out.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
        return out.toString();
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
