package com.termux.cloud;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;

import com.termux.BuildConfig;
import com.termux.R;
import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;

/**
 * cloud-shared-store: the ONE-TIME notice after the sharedUserId reinstall
 * (build.json::shared_store.migration). Adding android:sharedUserId to an installed app needs
 * uninstall + install, which wipes $HOME; the owner backs it up first to
 * <shared storage>/<storage.shared_root>/<migration.backup_dir>/<package>/<migration.archive>.
 * On the first launch after, this says what happened and offers the restore (files already in
 * $HOME are kept unless the backup's copy is newer); the archive is deleted once restored, since it
 * holds the home's secrets on shared storage. Marked done in $HOME/<migration.marker>, but only
 * once shared storage was readable, so a launch before the storage grant asks again next time.
 * The SAME file (package line, R and BuildConfig aside) is in cld.termux.nix.
 */
public final class SharedStoreMigration {

    private SharedStoreMigration() {}

    public static File archive(String pkg) {
        return new File(new File(new File(Environment.getExternalStorageDirectory(), BuildConfig.CLOUD_LEGACY_SHARED_ROOT),
            BuildConfig.CLOUD_SHARED_STORE_MIGRATION_DIR + "/" + pkg), BuildConfig.CLOUD_SHARED_STORE_MIGRATION_ARCHIVE);
    }

    public static void maybeShow(final Activity activity) {
        final File home = new File(TermuxConstants.TERMUX_HOME_DIR_PATH);
        final File marker = new File(home, BuildConfig.CLOUD_SHARED_STORE_MIGRATION_MARKER);
        if (marker.exists()) return;
        final File zip = archive(activity.getPackageName());
        final boolean storageReadable = Environment.getExternalStorageDirectory().canRead();
        String body = activity.getString(R.string.shared_store_migration_body)
            + (zip.isFile() ? activity.getString(R.string.shared_store_migration_restore, zip.getAbsolutePath())
                            : activity.getString(R.string.shared_store_migration_no_backup, zip.getAbsolutePath()));
        if (storageReadable || zip.isFile()) mark(marker);
        AlertDialog.Builder b = new AlertDialog.Builder(activity)
            .setTitle(R.string.shared_store_migration_title)
            .setMessage(body);
        if (zip.isFile()) {
            b.setPositiveButton(R.string.shared_store_migration_restore_action, (d, w) -> restore(activity, zip, home))
             .setNegativeButton(R.string.shared_store_migration_skip_action, null);
        } else {
            b.setPositiveButton(R.string.shared_store_migration_ok_action, null);
        }
        b.show();
    }

    private static void restore(final Activity activity, final File zip, final File home) {
        new Thread(() -> {
            String msg;
            try (InputStream in = new FileInputStream(zip)) {
                int n = SharedStoreFiles.restoreTarGz(in, home);
                //noinspection ResultOfMethodCallIgnored
                zip.delete();
                msg = activity.getString(R.string.shared_store_migration_done, n);
            } catch (Exception e) {
                msg = activity.getString(R.string.shared_store_migration_failed, String.valueOf(e.getMessage()));
            }
            final String m = msg;
            new Handler(Looper.getMainLooper()).post(() -> {
                if (!activity.isFinishing()) new AlertDialog.Builder(activity).setMessage(m)
                    .setPositiveButton(R.string.shared_store_migration_ok_action, null).show();
            });
        }, "cloud-shared-store-restore").start();
    }

    private static void mark(File marker) {
        try {
            //noinspection ResultOfMethodCallIgnored
            marker.getParentFile().mkdirs();
            //noinspection ResultOfMethodCallIgnored
            marker.createNewFile();
        } catch (Exception ignored) {
        }
    }
}
