package com.diegonmarcos.cloudnotes.util;

import android.app.Activity;
import android.content.Context;

import androidx.fragment.app.FragmentManager;

import com.diegonmarcos.cloudnotes.R;
import com.diegonmarcos.cloudnotes.format.ActionButtonBase;
import com.diegonmarcos.cloudnotes.frontend.filebrowser.MarkorFileBrowserFactory;
import com.diegonmarcos.cloudnotes.model.AppSettings;
import com.diegonmarcos.cloudnotes.opoc.frontend.filebrowser.GsFileBrowserOptions;
import com.diegonmarcos.cloudnotes.opoc.util.GsBackupUtils;

import java.io.File;
import java.util.List;

public class BackupUtils extends GsBackupUtils {

    public static void showBackupSelectFromDialog(final Context context, final FragmentManager manager) {
        if (context instanceof Activity) {
            final Activity activity = (Activity) context;

            MarkorFileBrowserFactory.showFileDialog(
                    new GsFileBrowserOptions.SelectionListenerAdapter() {
                        @Override
                        public void onFsViewerConfig(GsFileBrowserOptions.Options dopt) {
                            dopt.rootFolder = AppSettings.get(context).getNotebookDirectory();
                            dopt.titleText = R.string.select;
                        }

                        @Override
                        public void onFsViewerSelected(String request, File file, final Integer lineNumber) {
                            loadBackup(context, file);
                        }
                    }, manager, activity,
                    (c, file) -> file != null && file.exists() && file.toString().trim().toLowerCase().endsWith(".json")
            );
        }
    }

    public static void showBackupWriteToDialog(final Context context, final FragmentManager manager) {
        if (context instanceof Activity) {
            final Activity activity = (Activity) context;

            MarkorFileBrowserFactory.showFolderDialog(
                    new GsFileBrowserOptions.SelectionListenerAdapter() {
                        @Override
                        public void onFsViewerConfig(GsFileBrowserOptions.Options dopt) {
                            dopt.rootFolder = AppSettings.get(context).getNotebookDirectory();
                            dopt.titleText = R.string.select_folder;
                        }

                        @Override
                        public void onFsViewerSelected(String request, File dir, final Integer lineNumber) {
                            makeBackup(context, getPrefNamesToBackup(), generateBackupFilepath(context, dir));
                        }
                    }, manager, activity
            );
        }
    }

    public static List<String> getPrefNamesToBackup() {
        List<String> prefs = GsBackupUtils.getPrefNamesToBackup();
        prefs.add(ActionButtonBase.ACTION_ORDER_PREF_NAME);
        return prefs;
    }
}
