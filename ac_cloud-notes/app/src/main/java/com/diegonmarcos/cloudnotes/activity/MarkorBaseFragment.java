package com.diegonmarcos.cloudnotes.activity;

import android.content.Context;

import com.diegonmarcos.cloudnotes.model.AppSettings;
import com.diegonmarcos.cloudnotes.util.MarkorContextUtils;
import com.diegonmarcos.cloudnotes.opoc.frontend.base.GsFragmentBase;

public abstract class MarkorBaseFragment extends GsFragmentBase<AppSettings, MarkorContextUtils> {
    @Override
    public AppSettings createAppSettingsInstance(Context context) {
        return AppSettings.get(context);
    }

    @Override
    public MarkorContextUtils createContextUtilsInstance(Context context) {
        return new MarkorContextUtils(context);
    }
}
