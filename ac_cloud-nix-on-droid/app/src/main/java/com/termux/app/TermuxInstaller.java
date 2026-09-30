package com.termux.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;
import android.system.Os;
import android.util.Pair;
import android.view.WindowManager;

import com.termux.BuildConfig;
import com.termux.R;
import com.termux.shared.file.FileUtils;
import com.termux.shared.termux.crash.TermuxCrashUtils;
import com.termux.shared.termux.file.TermuxFileUtils;
import com.termux.shared.interact.MessageDialogUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.markdown.MarkdownUtils;
import com.termux.shared.errors.Error;
import com.termux.shared.android.PackageUtils;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.TermuxUtils;
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import org.json.JSONObject;

import static com.termux.shared.termux.TermuxConstants.TERMUX_FILES_DIR_PATH;
import static com.termux.shared.termux.TermuxConstants.TERMUX_PREFIX_DIR;
import static com.termux.shared.termux.TermuxConstants.TERMUX_PREFIX_DIR_PATH;
import static com.termux.shared.termux.TermuxConstants.TERMUX_STAGING_PREFIX_DIR;
import static com.termux.shared.termux.TermuxConstants.TERMUX_STAGING_PREFIX_DIR_PATH;

/**
 * Install the Termux bootstrap packages if necessary by following the below steps:
 * <p/>
 * (1) If $PREFIX already exist, assume that it is correct and be done. Note that this relies on that we do not create a
 * broken $PREFIX directory below.
 * <p/>
 * (2) A progress dialog is shown with "Installing..." message and a spinner.
 * <p/>
 * (3) A staging directory, $STAGING_PREFIX, is cleared if left over from broken installation below.
 * <p/>
 * (4) The architecture is determined and an appropriate bootstrap zip url is determined in {@link #determineZipUrl()}.
 * <p/>
 * (5) The zip, containing entries relative to the $PREFIX, is is downloaded and extracted by a zip input stream
 * continuously encountering zip file entries:
 * <p/>
 * (5.1) If the zip entry encountered is SYMLINKS.txt, go through it and remember all symlinks to setup.
 * <p/>
 * (5.2) For every other zip entry, extract it into $STAGING_PREFIX and set execute permissions if necessary.
 */
final class TermuxInstaller {

    private static final String LOG_TAG = "TermuxInstaller";

    /**
     * #628 -- the proot + Nix rootfs no longer travels with this APK at all,
     * baked in or fetched over HTTP. It is a real, installed, signed sibling
     * package (rootfs-lib/, applicationId {@link BuildConfig#CLOUD_ROOTFS_LIB_PACKAGE},
     * asset cloud-lib-rootfs-nixdroid) the Store's Cloud tab installs and
     * updates like any other fleet library. This class only READS it: resolves
     * the package, checks it is signed with THIS app's key, reads its
     * {@link #LIB_MANIFEST_ENTRY} manifest, and extracts {@link #LIB_PAYLOAD_ENTRY_PREFIX}
     * lazily, verified against the manifest's sha256 before anything is marked
     * executable -- the same #348 guarantee {@code fetchBootstrap} gave a
     * downloaded zip, now given to bytes read out of another app's APK.
     */
    static final String LIB_MANIFEST_ENTRY = "assets/rootfs-lib.json";

    /** Prefix of the payload entry inside the lib APK; the exact name is {@code manifest.payload}. */
    static final String LIB_PAYLOAD_ENTRY_PREFIX = "assets/";

    /** Where the payload, once verified, is extracted to be read as a plain {@link File}. */
    static final File EXTRACTED_BOOTSTRAP_FILE = new File(TERMUX_FILES_DIR_PATH, ".bootstrap.zip");

    /**
     * #605 -- the version this class last successfully extracted FROM the lib,
     * recorded outside $PREFIX (which the next update may wipe and re-extract)
     * so a future launch can tell a stale, already-extracted rootfs apart from
     * a newer one the lib was updated to carry. {@link #setupBootstrapIfNeeded}
     * compares this against {@code rootfs-lib.json}'s {@code version} field.
     */
    static final File INSTALLED_BOOTSTRAP_VERSION_FILE = new File(TERMUX_FILES_DIR_PATH, ".bootstrap_version");

    /** Thrown when {@link BuildConfig#CLOUD_ROOTFS_LIB_PACKAGE} is not installed. A distinct type
     *  so callers can offer the Store deep link instead of just failing. */
    static final class LibMissing extends Exception {
        LibMissing(String message) { super(message); }
    }

    /** The small pointer the lib APK carries: its content-address version, the payload entry's
     *  name and the sha256 that gates extracting it. */
    private static final class LibBootstrapManifest {
        final String version;
        final String payload;
        final String sha256;
        LibBootstrapManifest(String version, String payload, String sha256) {
            this.version = version; this.payload = payload; this.sha256 = sha256;
        }
    }

    /**
     * THE trust boundary, and the only place this app learns where the rootfs
     * library's APK is. Resolves cloud-lib-rootfs-nixdroid and refuses it unless
     * it carries this app's own signature.
     *
     * #348's guarantee, given to another app's APK: extracting ~400 MB of
     * executables out of a package we did not verify is exactly the "marking
     * unread bytes executable" hazard this whole file exists to avoid. A
     * signature match is what makes the sibling trustworthy — so every read of
     * that APK goes through here, and there is no path that reaches its bytes
     * without having passed this check.
     */
    private static String trustedLibSourceDir(Activity activity) throws LibMissing, IOException {
        String libPackage = BuildConfig.CLOUD_ROOTFS_LIB_PACKAGE;
        String sourceDir;
        try {
            sourceDir = activity.getPackageManager().getApplicationInfo(libPackage, 0).sourceDir;
        } catch (PackageManager.NameNotFoundException e) {
            throw new LibMissing("cloud-lib-rootfs-nixdroid (" + libPackage + ") is not installed. "
                + "Install it from the Store's Cloud tab to get a terminal here.");
        }
        @SuppressWarnings("deprecation")
        boolean sameSignature = activity.getPackageManager()
            .checkSignatures(activity.getPackageName(), libPackage) == PackageManager.SIGNATURE_MATCH;
        if (!sameSignature)
            throw new IOException(libPackage + " is installed but signed with a different key than "
                + activity.getPackageName() + " -- refusing to extract executables from an untrusted APK");
        return sourceDir;
    }

    /**
     * Resolves the installed rootfs-nixdroid lib, checks it is signed with the
     * SAME key as this app, and reads its manifest. Cheap: a {@link ZipFile}
     * central-directory read plus one small JSON entry, never the ~400 MB
     * payload -- safe to call from the UI thread to decide whether a
     * re-extraction is needed before showing any progress dialog.
     */
    private static LibBootstrapManifest readLibManifest(Activity activity) throws LibMissing, IOException {
        String libPackage = BuildConfig.CLOUD_ROOTFS_LIB_PACKAGE;
        String sourceDir = trustedLibSourceDir(activity);

        try (ZipFile lib = new ZipFile(sourceDir)) {
            ZipEntry entry = lib.getEntry(LIB_MANIFEST_ENTRY);
            if (entry == null)
                throw new IOException(libPackage + " carries no " + LIB_MANIFEST_ENTRY + " -- not a valid rootfs-nixdroid lib");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = lib.getInputStream(entry)) {
                byte[] buffer = new byte[1 << 12];
                int read;
                while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            }
            JSONObject json = new JSONObject(out.toString(StandardCharsets.UTF_8.name()));
            String version = json.optString("version", null);
            String payload = json.optString("payload", null);
            String sha256 = json.optString("sha256", null);
            if (version == null || payload == null || sha256 == null)
                throw new IOException(libPackage + "'s " + LIB_MANIFEST_ENTRY
                    + " is missing version/payload/sha256 -- refusing to extract an unverifiable bootstrap");
            return new LibBootstrapManifest(version, payload, sha256);
        } catch (org.json.JSONException e) {
            throw new IOException(libPackage + "'s " + LIB_MANIFEST_ENTRY + " does not parse: " + e);
        }
    }

    /**
     * Lazily extracts the manifest's payload entry out of the lib APK, verified
     * against its sha256 before it is trusted. A previously-extracted copy that
     * already hashes correctly is reused rather than re-extracted, exactly as
     * {@code fetchBootstrap} used to reuse a cached download.
     */
    private static File openBootstrapFromLib(Activity activity, LibBootstrapManifest manifest) throws LibMissing, IOException {
        // want -- the digest the lib's OWN manifest declares. Named the same as
        // #618's fetchBootstrap named its baked digest, so the comparison below
        // reads as the same guarantee moved to a different source: the bytes
        // are refused unless they hash to a value the network (here: the other
        // app's APK) cannot influence.
        final String want = manifest.sha256;
        if (EXTRACTED_BOOTSTRAP_FILE.isFile() && want.equals(sha256OfQuiet(EXTRACTED_BOOTSTRAP_FILE))) {
            Logger.logInfo(LOG_TAG, "The bootstrap " + manifest.version + " is already extracted");
            return EXTRACTED_BOOTSTRAP_FILE;
        }

        String libPackage = BuildConfig.CLOUD_ROOTFS_LIB_PACKAGE;
        // Through the SAME trust boundary, not a second resolution of its own.
        // This used to re-read getApplicationInfo() here with no signature check,
        // relying on readLibManifest having run first — a door that is closed
        // only by call order, which is the kind of guarantee that survives
        // exactly until somebody adds a caller.
        String sourceDir = trustedLibSourceDir(activity);

        File part = new File(EXTRACTED_BOOTSTRAP_FILE.getAbsolutePath() + ".part");
        //noinspection ResultOfMethodCallIgnored
        part.delete();
        Logger.logInfo(LOG_TAG, "Extracting the bootstrap " + manifest.version + " from " + libPackage);

        try (ZipFile lib = new ZipFile(sourceDir)) {
            String entryName = LIB_PAYLOAD_ENTRY_PREFIX + manifest.payload;
            ZipEntry entry = lib.getEntry(entryName);
            if (entry == null)
                throw new IOException(libPackage + " carries no " + entryName + " -- the lib's manifest names a payload it does not have");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new DigestInputStream(lib.getInputStream(entry), digest);
                 FileOutputStream out = new FileOutputStream(part)) {
                byte[] buffer = new byte[1 << 16];
                int read;
                while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            }
            String got = hex(digest.digest());
            if (!want.equals(got)) {
                //noinspection ResultOfMethodCallIgnored
                part.delete();
                throw new IOException(libPackage + "'s " + entryName + " is sha256 " + got
                    + ", not the " + want + " its own manifest declares");
            }
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
        //noinspection ResultOfMethodCallIgnored
        EXTRACTED_BOOTSTRAP_FILE.delete();
        if (!part.renameTo(EXTRACTED_BOOTSTRAP_FILE))
            throw new IOException("cannot move " + part + " to " + EXTRACTED_BOOTSTRAP_FILE);
        return EXTRACTED_BOOTSTRAP_FILE;
    }

    /** Same digest as {@link #sha256Of(File)} but never throws -- a reuse check must not fail
     *  the whole install just because the previously-extracted copy is unreadable. */
    private static String sha256OfQuiet(File file) {
        try {
            return sha256Of(file);
        } catch (Exception e) {
            return null;
        }
    }

    private static String sha256Of(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
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

    /** Reads the marker left by the last successful extraction, or null if there was none. */
    private static String readInstalledBootstrapVersion() {
        if (!INSTALLED_BOOTSTRAP_VERSION_FILE.isFile()) return null;
        try (InputStream in = new FileInputStream(INSTALLED_BOOTSTRAP_VERSION_FILE)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[256];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            return new String(out.toByteArray(), StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * #698 -- one line for the terminal screen when the rootfs about to run is
     * not the one this APK was built against, else null. The Store updates this
     * app and cloud-lib-rootfs-nixdroid independently, each by its own sha256,
     * so a new terminal over an older rootfs is an ordinary state -- and it used
     * to run that rootfs's login silently, whatever that login did. Both sides
     * carry the same content address ({@link BuildConfig#CLOUD_ROOTFS_LIB_VERSION}
     * and the lib's manifest version), so equality is the whole check.
     */
    static String rootfsVersionNotice() {
        String installed = readInstalledBootstrapVersion();
        if (BuildConfig.CLOUD_ROOTFS_LIB_VERSION.equals(installed)) return null;
        return "⚠ rootfs " + (installed == null ? "(unversioned)" : installed)
            + " is installed, this terminal was built for " + BuildConfig.CLOUD_ROOTFS_LIB_VERSION
            + " -- update cloud-lib-rootfs-nixdroid and this app from the Store's Cloud tab.";
    }

    /** Performs bootstrap setup if necessary. */
    static void setupBootstrapIfNeeded(final Activity activity, final Runnable whenDone) {
        String bootstrapErrorMessage;
        Error filesDirectoryAccessibleError;

        // This will also call Context.getFilesDir(), which should ensure that termux files directory
        // is created if it does not already exist
        filesDirectoryAccessibleError = TermuxFileUtils.isTermuxFilesDirectoryAccessible(activity, true, true);
        boolean isFilesDirectoryAccessible = filesDirectoryAccessibleError == null;

        // Termux can only be run as the primary user (device owner) since only that
        // account has the expected file system paths. Verify that:
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !PackageUtils.isCurrentUserThePrimaryUser(activity)) {
            bootstrapErrorMessage = activity.getString(R.string.bootstrap_error_not_primary_user_message,
                MarkdownUtils.getMarkdownCodeForString(TERMUX_PREFIX_DIR_PATH, false));
            Logger.logError(LOG_TAG, "isFilesDirectoryAccessible: " + isFilesDirectoryAccessible);
            Logger.logError(LOG_TAG, bootstrapErrorMessage);
            sendBootstrapCrashReportNotification(activity, bootstrapErrorMessage);
            MessageDialogUtils.exitAppWithErrorMessage(activity,
                activity.getString(R.string.bootstrap_error_title),
                bootstrapErrorMessage);
            return;
        }

        if (!isFilesDirectoryAccessible) {
            bootstrapErrorMessage = Error.getMinimalErrorString(filesDirectoryAccessibleError);
            //noinspection SdCardPath
            if (PackageUtils.isAppInstalledOnExternalStorage(activity) &&
                !TermuxConstants.TERMUX_FILES_DIR_PATH.equals(activity.getFilesDir().getAbsolutePath().replaceAll("^/data/user/0/", "/data/data/"))) {
                bootstrapErrorMessage += "\n\n" + activity.getString(R.string.bootstrap_error_installed_on_portable_sd,
                    MarkdownUtils.getMarkdownCodeForString(TERMUX_PREFIX_DIR_PATH, false));
            }

            Logger.logError(LOG_TAG, bootstrapErrorMessage);
            sendBootstrapCrashReportNotification(activity, bootstrapErrorMessage);
            MessageDialogUtils.showMessage(activity,
                activity.getString(R.string.bootstrap_error_title),
                bootstrapErrorMessage, null);
            return;
        }

        // #628 -- the bootstrap lives in the rootfs-nixdroid companion lib now,
        // not this APK's own assets/. A missing lib or a signature mismatch is
        // surfaced here, before any progress dialog, as a dialog naming the lib
        // and offering the Store's Cloud tab -- not a failure buried deeper in
        // the install with no way for the user to act on it.
        LibBootstrapManifest libManifest;
        try {
            libManifest = readLibManifest(activity);
        } catch (LibMissing e) {
            showLibMissingDialog(activity, whenDone, e.getMessage());
            return;
        } catch (IOException e) {
            showBootstrapErrorDialog(activity, whenDone, e.getMessage());
            return;
        }

        // #605 -- the lib can be updated to carry a FIXED bootstrap, but that
        // fix never reaches a phone whose $PREFIX was already extracted from an
        // older, possibly-broken one, unless the version actually installed is
        // compared against the version the lib currently carries.
        boolean bootstrapUpToDate = libManifest.version.equals(readInstalledBootstrapVersion());

        // If prefix directory exists, even if its a symlink to a valid directory and symlink is not broken/dangling
        if (FileUtils.directoryFileExists(TERMUX_PREFIX_DIR_PATH, true)) {
            if (TermuxFileUtils.isTermuxPrefixDirectoryEmpty()) {
                Logger.logInfo(LOG_TAG, "The termux prefix directory \"" + TERMUX_PREFIX_DIR_PATH + "\" exists but is empty or only contains specific unimportant files.");
            } else if (bootstrapUpToDate) {
                whenDone.run();
                return;
            } else {
                Logger.logInfo(LOG_TAG, "The termux prefix directory \"" + TERMUX_PREFIX_DIR_PATH + "\" was extracted from an older bootstrap (installed=" + readInstalledBootstrapVersion() + ", lib=" + libManifest.version + "); re-extracting.");
            }
        } else if (FileUtils.fileExists(TERMUX_PREFIX_DIR_PATH, false)) {
            Logger.logInfo(LOG_TAG, "The termux prefix directory \"" + TERMUX_PREFIX_DIR_PATH + "\" does not exist but another file exists at its destination.");
        }

        // The bootstrap is read out of the installed lib, so there is nothing
        // to ask the user and no network involved: install it.
        restOfSetupIfNeeded(activity, whenDone, libManifest);
    }

    static void restOfSetupIfNeeded(final Activity activity, final Runnable whenDone, final LibBootstrapManifest libManifest) {

        final ProgressDialog progress = ProgressDialog.show(activity, null, activity.getString(R.string.bootstrap_installer_body), true, false);
        new Thread() {
            @Override
            public void run() {
                try {
                    Logger.logInfo(LOG_TAG, "Installing " + TermuxConstants.TERMUX_APP_NAME + " bootstrap packages.");

                    Error error;

                    // Delete prefix staging directory or any file at its destination
                    error = FileUtils.deleteFile("termux prefix staging directory", TERMUX_STAGING_PREFIX_DIR_PATH, true);
                    if (error != null) {
                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                        return;
                    }

                    // Delete prefix directory or any file at its destination
                    error = FileUtils.deleteFile("termux prefix directory", TERMUX_PREFIX_DIR_PATH, true);
                    if (error != null) {
                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                        return;
                    }

                    // Create prefix staging directory if it does not already exist and set required permissions
                    error = TermuxFileUtils.isTermuxPrefixStagingDirectoryAccessible(true, true);
                    if (error != null) {
                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                        return;
                    }

                    // Create prefix directory if it does not already exist and set required permissions
                    error = TermuxFileUtils.isTermuxPrefixDirectoryAccessible(true, true);
                    if (error != null) {
                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                        return;
                    }

                    // #628 -- the zip is extracted (once) out of the installed
                    // rootfs-nixdroid lib instead of fetched over HTTP, and
                    // refused unless it hashes to the sha256 the lib's own
                    // manifest declares. Everything after this line is
                    // unchanged: what is extracted is the same verified
                    // archive as before, just read from a different place.
                    final File bootstrapZip = openBootstrapFromLib(activity, libManifest);

                    Logger.logInfo(LOG_TAG, "Extracting bootstrap zip to prefix staging directory \"" + TERMUX_STAGING_PREFIX_DIR_PATH + "\".");

                    final byte[] buffer = new byte[8096];
                    final List<Pair<String, String>> symlinks = new ArrayList<>(50);
                    final List<String> executables = new ArrayList<>(128);

                    try (ZipInputStream zipInput = new ZipInputStream(new FileInputStream(bootstrapZip))) {
                        ZipEntry zipEntry;
                        while ((zipEntry = zipInput.getNextEntry()) != null) {
                            if (zipEntry.getName().equals("SYMLINKS.txt")) {
                                BufferedReader symlinksReader = new BufferedReader(new InputStreamReader(zipInput));
                                String line;
                                while ((line = symlinksReader.readLine()) != null) {
                                    String[] parts = line.split("←");
                                    if (parts.length != 2)
                                        throw new RuntimeException("Malformed symlink line: " + line);
                                    String oldPath = parts[0];
                                    String newPath = TERMUX_STAGING_PREFIX_DIR_PATH + "/" + parts[1];
                                    symlinks.add(Pair.create(oldPath, newPath));

                                    error = ensureDirectoryExists(new File(newPath).getParentFile());
                                    if (error != null) {
                                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                                        return;
                                    }
                                }
                            } else if (zipEntry.getName().equals("EXECUTABLES.txt")) {
                                BufferedReader executablesReader = new BufferedReader(new InputStreamReader(zipInput));
                                String line;
                                while ((line = executablesReader.readLine()) != null) {
                                    executables.add(line);
                                }
                            } else {
                                String zipEntryName = zipEntry.getName();
                                File targetFile = new File(TERMUX_STAGING_PREFIX_DIR_PATH, zipEntryName);
                                boolean isDirectory = zipEntry.isDirectory();

                                error = ensureDirectoryExists(isDirectory ? targetFile : targetFile.getParentFile());
                                if (error != null) {
                                    showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                                    return;
                                }

                                if (!isDirectory) {
                                    try (FileOutputStream outStream = new FileOutputStream(targetFile)) {
                                        int readBytes;
                                        while ((readBytes = zipInput.read(buffer)) != -1)
                                            outStream.write(buffer, 0, readBytes);
                                    }
                                    if (zipEntryName.startsWith("bin/") || zipEntryName.startsWith("libexec") ||
                                        zipEntryName.startsWith("lib/apt/apt-helper") || zipEntryName.startsWith("lib/apt/methods")) {
                                        //noinspection OctalInteger
                                        Os.chmod(targetFile.getAbsolutePath(), 0700);
                                    }
                                }
                            }
                        }
                    }

                    if (!executables.isEmpty()) {
                        for (String executable : executables) {
                            //noinspection OctalInteger
                            try {
                                Os.chmod(TERMUX_STAGING_PREFIX_DIR + "/" + executable, 0700);
                            } catch (Throwable t) {
                                Logger.logError(LOG_TAG, "EXECUTABLES error: " + TERMUX_STAGING_PREFIX_DIR + "/" + executable + t);
                            }
                        }
                    } else {
                        throw new RuntimeException("Installer: no EXECUTABLES.txt found while extracting environment archive.");
                    }

                    if (symlinks.isEmpty())
                        throw new RuntimeException("No SYMLINKS.txt encountered");
                    for (Pair<String, String> symlink : symlinks) {
                        Os.symlink(symlink.first, symlink.second);
                    }

                    Logger.logInfo(LOG_TAG, "Moving termux prefix staging to prefix directory.");

                    if (!TERMUX_STAGING_PREFIX_DIR.renameTo(TERMUX_PREFIX_DIR)) {
                        throw new RuntimeException("Moving termux prefix staging to prefix directory failed");
                    }

                    Logger.logInfo(LOG_TAG, "Bootstrap packages installed successfully.");

                    // #605 -- record what was actually extracted, outside $PREFIX
                    // (which the next update may wipe and re-extract), so a future
                    // launch can tell this bootstrap apart from a newer, fixed one.
                    try (FileOutputStream versionOut = new FileOutputStream(INSTALLED_BOOTSTRAP_VERSION_FILE)) {
                        versionOut.write(libManifest.version.getBytes(StandardCharsets.UTF_8));
                    } catch (Exception e) {
                        Logger.logWarn(LOG_TAG, "Could not record installed bootstrap version: " + e);
                    }

                    // #628 -- the extracted-once cache has done its job. Keeping it
                    // would double this app's footprint for bytes only a rootfs
                    // change needs again, and a change re-extracts by its new
                    // digest the next time the lib is opened.
                    //noinspection ResultOfMethodCallIgnored
                    EXTRACTED_BOOTSTRAP_FILE.delete();

                    // Recreate env file since termux prefix was wiped earlier
                    TermuxShellEnvironment.writeEnvironmentToFile(activity);

                    activity.runOnUiThread(whenDone);

                } catch (final LibMissing e) {
                    showLibMissingDialog(activity, whenDone, e.getMessage());

                } catch (final Exception e) {
                    showBootstrapErrorDialog(activity, whenDone, Logger.getStackTracesMarkdownString(null, Logger.getStackTracesStringArray(e)));

                } finally {
                    activity.runOnUiThread(() -> {
                        try {
                            progress.dismiss();
                        } catch (RuntimeException e) {
                            // Activity already dismissed - ignore.
                        }
                    });
                }
            }
        }.start();
    }

    public static void showBootstrapErrorDialog(Activity activity, Runnable whenDone, String message) {
        Logger.logErrorExtended(LOG_TAG, "Bootstrap Error:\n" + message);

        // Send a notification with the exception so that the user knows why bootstrap setup failed
        sendBootstrapCrashReportNotification(activity, message);

        activity.runOnUiThread(() -> {
            try {
                new AlertDialog.Builder(activity).setTitle(R.string.bootstrap_error_title).setMessage(R.string.bootstrap_error_body)
                    .setNegativeButton(R.string.bootstrap_error_abort, (dialog, which) -> {
                        dialog.dismiss();
                        activity.finish();
                    })
                    .setPositiveButton(R.string.bootstrap_error_try_again, (dialog, which) -> {
                        dialog.dismiss();
                        FileUtils.deleteFile("termux prefix directory", TERMUX_PREFIX_DIR_PATH, true);
                        TermuxInstaller.setupBootstrapIfNeeded(activity, whenDone);
                    }).show();
            } catch (WindowManager.BadTokenException e1) {
                // Activity already dismissed - ignore.
            }
        });
    }

    /**
     * #628 -- {@link LibMissing} is a DIFFERENT failure than a broken
     * extraction: nothing here is corrupt, the rootfs-nixdroid companion lib
     * this app reads its terminal environment out of is simply not installed
     * (or not yet, or was uninstalled). "Try again" would just fail the same
     * way again, so this dialog offers the one thing that actually helps:
     * a deep link into the SuperApp's Store, Cloud tab, where that lib lives.
     */
    public static void showLibMissingDialog(Activity activity, Runnable whenDone, String message) {
        Logger.logErrorExtended(LOG_TAG, "Rootfs lib missing:\n" + message);
        sendBootstrapCrashReportNotification(activity, message);

        activity.runOnUiThread(() -> {
            try {
                new AlertDialog.Builder(activity).setTitle(R.string.rootfs_lib_missing_title).setMessage(R.string.rootfs_lib_missing_body)
                    .setNegativeButton(R.string.bootstrap_error_abort, (dialog, which) -> {
                        dialog.dismiss();
                        activity.finish();
                    })
                    .setPositiveButton(R.string.rootfs_lib_missing_install, (dialog, which) -> {
                        dialog.dismiss();
                        try {
                            Intent i = new Intent(Intent.ACTION_MAIN)
                                .setClassName("com.diegonmarcos.superapp", "com.diegonmarcos.superapp.HomeActivity")
                                .putExtra("shortcut_action", "page:config/store-cloud")
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            activity.startActivity(i);
                        } catch (ActivityNotFoundException e) {
                            Logger.logWarn(LOG_TAG, "SuperApp Store is not installed, cannot deep link to it: " + e);
                        }
                        activity.finish();
                    }).show();
            } catch (WindowManager.BadTokenException e1) {
                // Activity already dismissed - ignore.
            }
        });
    }

    private static void sendBootstrapCrashReportNotification(Activity activity, String message) {
        final String title = TermuxConstants.TERMUX_APP_NAME + " Bootstrap Error";

        // Add info of all install Termux plugin apps as well since their target sdk or installation
        // on external/portable sd card can affect Termux app files directory access or exec.
        TermuxCrashUtils.sendCrashReportNotification(activity, LOG_TAG,
            title, null, "## " + title + "\n\n" + message + "\n\n" +
                TermuxUtils.getTermuxDebugMarkdownString(activity),
            true, false, TermuxUtils.AppInfoMode.TERMUX_AND_PLUGIN_PACKAGES, true);
    }

    static void setupStorageSymlinks(final Context context) {
        final String LOG_TAG = "termux-storage";
        final String title = TermuxConstants.TERMUX_APP_NAME + " Setup Storage Error";

        Logger.logInfo(LOG_TAG, "Setting up storage symlinks.");

        new Thread() {
            public void run() {
                try {
                    Error error;
                    File storageDir = TermuxConstants.TERMUX_STORAGE_HOME_DIR;

                    error = FileUtils.clearDirectory("~/storage", storageDir.getAbsolutePath());
                    if (error != null) {
                        Logger.logErrorAndShowToast(context, LOG_TAG, error.getMessage());
                        Logger.logErrorExtended(LOG_TAG, "Setup Storage Error\n" + error.toString());
                        TermuxCrashUtils.sendCrashReportNotification(context, LOG_TAG, title, null,
                            "## " + title + "\n\n" + Error.getErrorMarkdownString(error),
                            true, false, TermuxUtils.AppInfoMode.TERMUX_PACKAGE, true);
                        return;
                    }

                    Logger.logInfo(LOG_TAG, "Setting up storage symlinks at ~/storage/shared, ~/storage/downloads, ~/storage/dcim, ~/storage/pictures, ~/storage/music and ~/storage/movies for directories in \"" + Environment.getExternalStorageDirectory().getAbsolutePath() + "\".");

                    // Get primary storage root "/storage/emulated/0" symlink
                    File sharedDir = Environment.getExternalStorageDirectory();
                    Os.symlink(sharedDir.getAbsolutePath(), new File(storageDir, "shared").getAbsolutePath());

                    File documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
                    Os.symlink(documentsDir.getAbsolutePath(), new File(storageDir, "documents").getAbsolutePath());

                    File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    Os.symlink(downloadsDir.getAbsolutePath(), new File(storageDir, "downloads").getAbsolutePath());

                    File dcimDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM);
                    Os.symlink(dcimDir.getAbsolutePath(), new File(storageDir, "dcim").getAbsolutePath());

                    File picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
                    Os.symlink(picturesDir.getAbsolutePath(), new File(storageDir, "pictures").getAbsolutePath());

                    File musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC);
                    Os.symlink(musicDir.getAbsolutePath(), new File(storageDir, "music").getAbsolutePath());

                    File moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES);
                    Os.symlink(moviesDir.getAbsolutePath(), new File(storageDir, "movies").getAbsolutePath());

                    File podcastsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PODCASTS);
                    Os.symlink(podcastsDir.getAbsolutePath(), new File(storageDir, "podcasts").getAbsolutePath());

                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        File audiobooksDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_AUDIOBOOKS);
                        Os.symlink(audiobooksDir.getAbsolutePath(), new File(storageDir, "audiobooks").getAbsolutePath());
                    }

                    // Dir 0 should ideally be for primary storage
                    // https://cs.android.com/android/platform/superproject/+/android-12.0.0_r32:frameworks/base/core/java/android/app/ContextImpl.java;l=818
                    // https://cs.android.com/android/platform/superproject/+/android-12.0.0_r32:frameworks/base/core/java/android/os/Environment.java;l=219
                    // https://cs.android.com/android/platform/superproject/+/android-12.0.0_r32:frameworks/base/core/java/android/os/Environment.java;l=181
                    // https://cs.android.com/android/platform/superproject/+/android-12.0.0_r32:frameworks/base/services/core/java/com/android/server/StorageManagerService.java;l=3796
                    // https://cs.android.com/android/platform/superproject/+/android-7.0.0_r36:frameworks/base/services/core/java/com/android/server/MountService.java;l=3053

                    // Create "Android/data/com.termux" symlinks
                    File[] dirs = context.getExternalFilesDirs(null);
                    if (dirs != null && dirs.length > 0) {
                        for (int i = 0; i < dirs.length; i++) {
                            File dir = dirs[i];
                            if (dir == null) continue;
                            String symlinkName = "external-" + i;
                            Logger.logInfo(LOG_TAG, "Setting up storage symlinks at ~/storage/" + symlinkName + " for \"" + dir.getAbsolutePath() + "\".");
                            Os.symlink(dir.getAbsolutePath(), new File(storageDir, symlinkName).getAbsolutePath());
                        }
                    }

                    // Create "Android/media/com.termux" symlinks
                    dirs = context.getExternalMediaDirs();
                    if (dirs != null && dirs.length > 0) {
                        for (int i = 0; i < dirs.length; i++) {
                            File dir = dirs[i];
                            if (dir == null) continue;
                            String symlinkName = "media-" + i;
                            Logger.logInfo(LOG_TAG, "Setting up storage symlinks at ~/storage/" + symlinkName + " for \"" + dir.getAbsolutePath() + "\".");
                            Os.symlink(dir.getAbsolutePath(), new File(storageDir, symlinkName).getAbsolutePath());
                        }
                    }

                    Logger.logInfo(LOG_TAG, "Storage symlinks created successfully.");
                } catch (Exception e) {
                    Logger.logErrorAndShowToast(context, LOG_TAG, e.getMessage());
                    Logger.logStackTraceWithMessage(LOG_TAG, "Setup Storage Error: Error setting up link", e);
                    TermuxCrashUtils.sendCrashReportNotification(context, LOG_TAG, title, null,
                        "## " + title + "\n\n" + Logger.getStackTracesMarkdownString(null, Logger.getStackTracesStringArray(e)),
                        true, false, TermuxUtils.AppInfoMode.TERMUX_PACKAGE, true);
                }
            }
        }.start();
    }

    private static Error ensureDirectoryExists(File directory) {
        return FileUtils.createDirectoryFile(directory.getAbsolutePath());
    }

    private static String determineTermuxArchName() {
        // Note that we cannot use System.getProperty("os.arch") since that may give e.g. "aarch64"
        // while a 64-bit runtime may not be installed (like on the Samsung Galaxy S5 Neo).
        // Instead we search through the supported abi:s on the device, see:
        // http://developer.android.com/ndk/guides/abis.html
        // Note that we search for abi:s in preferred order (the ordering of the
        // Build.SUPPORTED_ABIS list) to avoid e.g. installing arm on an x86 system where arm
        // emulation is available.
        for (String androidArch : Build.SUPPORTED_ABIS) {
            switch (androidArch) {
                case "arm64-v8a": return "aarch64";
                case "armeabi-v7a": return "arm";
                case "x86_64": return "x86_64";
                case "x86": return "i686";
            }
        }
        throw new RuntimeException("Unable to determine arch from Build.SUPPORTED_ABIS =  " +
            Arrays.toString(Build.SUPPORTED_ABIS));
    }

}
