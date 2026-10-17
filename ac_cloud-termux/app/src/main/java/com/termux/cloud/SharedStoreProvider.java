package com.termux.cloud;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.BuildConfig;
import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * cloud-shared-store's door for every other fleet app: content://cld.termux.sharedstore/tree/<path>,
 * rooted at $HOME/cloud-shared-store (build.json::shared_store). Guarded in the manifest by the
 * signature-level com.diegonmarcos.cloud.permission.SHARED_STORE, so only an APK signed with the
 * fleet key reaches it; call() is NOT permission-checked by the framework, so it re-checks here.
 *
 * openFile hands back a ParcelFileDescriptor on the real file: one binder call per open, then the
 * caller reads and writes at native speed. Every path goes through SharedStoreFiles.resolve, which
 * refuses ".." and a symlink that leaves the store.
 */
public class SharedStoreProvider extends ContentProvider {

    public static final String PERMISSION = "com.diegonmarcos.cloud.permission.SHARED_STORE";
    public static final String[] COLUMNS = {"name", "path", "is_dir", "size", "last_modified", "can_exec"};

    private File root() {
        File r = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, BuildConfig.CLOUD_SHARED_STORE_DIR);
        //noinspection ResultOfMethodCallIgnored
        r.mkdirs();
        return r;
    }

    private static String rel(Uri uri) {
        String p = uri.getPath() == null ? "" : uri.getPath();
        if (p.startsWith("/tree")) p = p.substring(5);
        return p;
    }

    private File file(Uri uri) throws FileNotFoundException {
        try {
            File f = SharedStoreFiles.resolve(root(), rel(uri));
            if (f == null) throw new FileNotFoundException("outside the store: " + uri);
            return f;
        } catch (IOException e) {
            throw new FileNotFoundException(e.getMessage());
        }
    }

    @Override public boolean onCreate() { return true; }

    @Nullable @Override
    public Cursor query(@NonNull Uri uri, @Nullable String[] projection, @Nullable String selection, @Nullable String[] selectionArgs, @Nullable String sortOrder) {
        MatrixCursor c = new MatrixCursor(COLUMNS);
        File f;
        try { f = file(uri); } catch (FileNotFoundException e) { return c; }
        File base = root();
        File[] rows = f.isDirectory() ? f.listFiles() : (f.exists() ? new File[]{f} : new File[0]);
        if (rows == null) return c;
        String bp = base.getAbsolutePath();
        for (File x : rows) {
            String p = x.getAbsolutePath();
            String r = p.length() > bp.length() ? p.substring(bp.length() + 1) : "";
            c.addRow(new Object[]{x.getName(), r, x.isDirectory() ? 1 : 0, x.length(), x.lastModified(), x.canExecute() ? 1 : 0});
        }
        return c;
    }

    @Nullable @Override
    public ParcelFileDescriptor openFile(@NonNull Uri uri, @NonNull String mode) throws FileNotFoundException {
        File f = file(uri);
        if (!"r".equals(mode)) {
            File parent = f.getParentFile();
            //noinspection ResultOfMethodCallIgnored
            if (parent != null) parent.mkdirs();
        }
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.parseMode(mode));
    }

    @Override
    public int delete(@NonNull Uri uri, @Nullable String selection, @Nullable String[] selectionArgs) {
        try {
            File f = file(uri);
            if (f.equals(root())) return 0;
            return f.delete() ? 1 : 0; // a file, or an EMPTY directory: never recursive
        } catch (FileNotFoundException e) {
            return 0;
        }
    }

    /** call(mkdir|rename|export_mirror, arg, extras{path,to}). */
    @Nullable @Override
    public Bundle call(@NonNull String method, @Nullable String arg, @Nullable Bundle extras) {
        if (getContext() == null || getContext().checkCallingOrSelfPermission(PERMISSION) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            throw new SecurityException("requires " + PERMISSION);
        Bundle out = new Bundle();
        try {
            switch (method) {
                case "mkdir": {
                    File f = SharedStoreFiles.resolve(root(), arg);
                    out.putBoolean("ok", f != null && (f.isDirectory() || f.mkdirs()));
                    break;
                }
                case "rename": {
                    File a = SharedStoreFiles.resolve(root(), arg);
                    File b = SharedStoreFiles.resolve(root(), extras == null ? null : extras.getString("to"));
                    out.putBoolean("ok", a != null && b != null && a.renameTo(b));
                    break;
                }
                case "export_mirror": {
                    // build.json::shared_store.export_mirror: OFF unless "on"; enter.sh reads the flag at login.
                    File flag = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, BuildConfig.CLOUD_SHARED_STORE_EXPORT_FLAG);
                    if (arg != null) {
                        //noinspection ResultOfMethodCallIgnored
                        flag.getParentFile().mkdirs();
                        try (FileOutputStream o = new FileOutputStream(flag)) { o.write(("on".equals(arg) ? "on" : "off").getBytes(StandardCharsets.UTF_8)); }
                    }
                    out.putString("state", flag.isFile() && new String(java.nio.file.Files.readAllBytes(flag.toPath()), StandardCharsets.UTF_8).trim().equals("on") ? "on" : "off");
                    out.putBoolean("ok", true);
                    break;
                }
                case "root":
                    out.putString("path", root().getAbsolutePath());
                    out.putBoolean("ok", true);
                    break;
                default:
                    out.putBoolean("ok", false);
                    out.putString("error", "unknown method " + method);
            }
        } catch (IOException e) {
            out.putBoolean("ok", false);
            out.putString("error", e.getMessage());
        }
        return out;
    }

    @Nullable @Override public String getType(@NonNull Uri uri) { return null; }
    @Nullable @Override public Uri insert(@NonNull Uri uri, @Nullable ContentValues values) { return null; }
    @Override public int update(@NonNull Uri uri, @Nullable ContentValues values, @Nullable String selection, @Nullable String[] selectionArgs) { return 0; }
}
