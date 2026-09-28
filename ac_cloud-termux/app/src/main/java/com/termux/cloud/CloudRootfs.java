package com.termux.cloud;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.system.ErrnoException;
import android.system.Os;

import com.termux.BuildConfig;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Stages the glibc root filesystem this terminal runs (#470) and makes it the
 * terminal's login shell.
 *
 * #628: the ~400 MB tree is no longer fetched over the network (#618's
 * runtime download is gone). It now ships inside a SIBLING APK — the fleet
 * library com.diegonmarcos.cloudlib.rootfstermux (cloud-lib-rootfs-termux),
 * installed and updated by the Store exactly like any other library. This app
 * carries only proot and enter.sh in its own assets/&lt;asset_dir&gt;/; the
 * tarball is read straight out of the lib APK — assets/rootfs-lib.json for its
 * manifest, assets/rootfs.tar.zst for the payload — via {@link ZipFile}, and
 * streamed into $PREFIX/var/lib/&lt;asset_dir&gt;/. Nothing here opens a socket.
 *
 * Trust chain: the lib must be signed with the SAME key as this app
 * ({@code checkSignatures == SIGNATURE_MATCH}) before a single byte is read
 * out of it — unpacking ~400 MB of executables out of an APK signed by
 * somebody else is exactly the "marking unread bytes executable" hazard #348
 * fought elsewhere in this app. The lib's own manifest sha256 is then the
 * digest the extracted tarball is gated on, exactly as #618's fetch used to be
 * gated on a baked digest — only the transport changed.
 *
 * Everything is extracted to $PREFIX/var/lib/&lt;asset_dir&gt;/ whenever the lib's
 * declared digest differs from the staged one, so a fresh install AND an
 * update of an existing install both get the new tree; existing installs
 * matter because the Termux bootstrap only ever runs once (#436).
 *
 * The first time anything is staged on a device, ~/.termux/shell is pointed at
 * enter.sh — that mechanism is unchanged by #628.
 */
public final class CloudRootfs {

    private static final String LOG_TAG = "CloudRootfs";
    private static final String DIGEST = "rootfs.sha256";
    private static final String TARBALL = "rootfs.tar.zst";
    /** The manifest {@link #stageRootfsPayload} (app/build.gradle) writes beside the payload inside :rootfs-lib. */
    private static final String MANIFEST_ENTRY = "assets/rootfs-lib.json";
    /** The small files this app still carries in its own assets/ — the tarball does not, any more. */
    private static final String[] FILES = {"proot", "enter.sh"};

    private CloudRootfs() {}

    /**
     * Thrown when com.diegonmarcos.cloudlib.rootfstermux is not installed, so the
     * caller can tell this apart from every other failure and offer the Store
     * deep link instead of a generic error (see TermuxInstaller#stageCloudRootfs).
     */
    public static final class LibMissing extends IOException {
        public LibMissing(String message) { super(message); }
    }

    private static File stageDir() {
        return new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH, "var/lib/" + BuildConfig.CLOUD_ROOTFS_ASSET_DIR);
    }

    /**
     * Cheap enough for the UI thread: opens the lib's ZipFile only to read its
     * small manifest entry (a few dozen bytes) — never the ~400 MB payload entry.
     */
    public static boolean isStaged(Context context) {
        try {
            File staged = new File(stageDir(), DIGEST);
            if (!staged.isFile()) return false;
            String want = field(libManifest(context), "sha256");
            return want.equals(read(new FileInputStream(staged)));
        } catch (Exception e) {
            return false;
        }
    }

    /** Extracts ~400 MB out of the sibling lib APK the first time and after a rootfs change; call off the UI thread. */
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

        String libSourceDir = trustedLibSourceDir(context);
        JSONObject manifest = readManifest(libSourceDir);
        String want = field(manifest, "sha256");
        String payload = field(manifest, "payload");

        extractPayload(libSourceDir, payload, want, dir);

        // LAST, and only now: enter.sh unpacks exactly when this file disagrees
        // with what it already unpacked, so writing it before a verified tarball
        // is in place would tell it to unpack bytes that are not there.
        try (OutputStream out = new FileOutputStream(new File(dir, DIGEST))) {
            out.write((want + "\n").getBytes(StandardCharsets.UTF_8));
        }
        Logger.logInfo(LOG_TAG, "Staged the rootfs " + want + " into " + dir);

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
     * Resolves com.diegonmarcos.cloudlib.rootfstermux and refuses it unless it is
     * signed with this app's own key. #628's trust boundary: this app is about
     * to unpack and exec ~400 MB out of another APK's assets, and only a
     * matching signature makes that APK part of the same constellation build
     * rather than an unrelated package somebody else installed under the id
     * this app expects to trust.
     */
    private static String trustedLibSourceDir(Context context) throws IOException {
        PackageManager pm = context.getPackageManager();
        String libPackage = BuildConfig.CLOUD_ROOTFS_LIB_PACKAGE;
        ApplicationInfo info;
        try {
            info = pm.getApplicationInfo(libPackage, 0);
        } catch (PackageManager.NameNotFoundException e) {
            throw new LibMissing("cloud-lib-rootfs-termux (" + libPackage + ") is not installed — " +
                "install it from the Store's Cloud tab to enable the agent-coding shell");
        }
        //noinspection deprecation
        if (pm.checkSignatures(context.getPackageName(), libPackage) != PackageManager.SIGNATURE_MATCH) {
            throw new IOException("cloud-lib-rootfs-termux (" + libPackage + ") is signed with a different key " +
                "than this app — refusing to unpack it");
        }
        return info.sourceDir;
    }

    /** Reads and validates {@link #MANIFEST_ENTRY} out of the lib APK at {@code libSourceDir}. */
    private static JSONObject readManifest(String libSourceDir) throws IOException {
        try (ZipFile zip = new ZipFile(libSourceDir)) {
            ZipEntry entry = zip.getEntry(MANIFEST_ENTRY);
            if (entry == null)
                throw new IOException("cloud-lib-rootfs-termux carries no " + MANIFEST_ENTRY + " — reinstall it from the Store");
            String text;
            try (InputStream in = zip.getInputStream(entry)) {
                text = read(in);
            }
            JSONObject json = new JSONObject(text);
            if (!json.has("version") || !json.has("payload") || !json.has("sha256"))
                throw new IOException("cloud-lib-rootfs-termux's " + MANIFEST_ENTRY + " is missing version/payload/sha256 — reinstall it from the Store");
            return json;
        } catch (JSONException e) {
            throw new IOException("cloud-lib-rootfs-termux's " + MANIFEST_ENTRY + " is not valid JSON", e);
        }
    }

    /**
     * One manifest field, with org.json's CHECKED JSONException translated at the
     * boundary. Not a convenience: every caller here declares IOException, and a
     * bare getString would not compile.
     */
    private static String field(JSONObject manifest, String name) throws IOException {
        try {
            return manifest.getString(name);
        } catch (JSONException e) {
            throw new IOException("cloud-lib-rootfs-termux's " + MANIFEST_ENTRY
                + " has no usable " + name + " — reinstall it from the Store", e);
        }
    }

    /** {@link #isStaged} only ever reads this — never {@link #extractPayload}'s ~400 MB entry. */
    private static JSONObject libManifest(Context context) throws IOException {
        return readManifest(trustedLibSourceDir(context));
    }

    /**
     * #618's guard, moved: fetch became extract, but the property is the same —
     * REFUSE the payload unless it hashes to {@code want}. A mismatch throws, so
     * the old digest stays on disk, enter.sh keeps using the tree it already
     * unpacked, and the next start tries again.
     *
     * A tarball already on disk that hashes correctly is kept: an interrupted
     * update resumes without extracting another 400 MB.
     */
    private static void extractPayload(String libSourceDir, String payload, String want, File dir) throws IOException {
        File tarball = new File(dir, TARBALL);
        if (tarball.isFile() && want.equals(sha256(tarball))) {
            Logger.logInfo(LOG_TAG, "The extracted rootfs " + want + " is already here");
            return;
        }

        File part = new File(dir, TARBALL + ".part");
        //noinspection ResultOfMethodCallIgnored
        part.delete();
        Logger.logInfo(LOG_TAG, "Extracting the rootfs " + want + " from cloud-lib-rootfs-termux");

        try (ZipFile zip = new ZipFile(libSourceDir)) {
            ZipEntry entry = zip.getEntry("assets/" + payload);
            if (entry == null)
                throw new IOException("cloud-lib-rootfs-termux carries no assets/" + payload + " — reinstall it from the Store");
            MessageDigest digest = sha256();
            try (InputStream in = new DigestInputStream(zip.getInputStream(entry), digest);
                 OutputStream out = new FileOutputStream(part)) {
                byte[] buffer = new byte[1 << 16];
                int n;
                while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            }
            String got = hex(digest.digest());
            if (!want.equals(got)) {
                //noinspection ResultOfMethodCallIgnored
                part.delete();
                throw new IOException("the rootfs extracted from cloud-lib-rootfs-termux is sha256 " + got
                    + ", not the " + want + " this build was signed off against");
            }
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
