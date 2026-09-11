/*#######################################################
 *
 *   Maintained 2017-2025 by Gregor Santner <gsantner AT mailbox DOT org>
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package com.diegonmarcos.cloudnotes.web;

import android.app.Activity;
import android.content.Context;
import android.net.Uri;
import android.webkit.WebView;

import com.diegonmarcos.cloudnotes.activity.DocumentActivity;
import com.diegonmarcos.cloudnotes.model.AppSettings;
import com.diegonmarcos.cloudnotes.util.MarkorContextUtils;
import com.diegonmarcos.cloudnotes.opoc.web.GsWebViewClient;

public class MarkorWebViewClient extends GsWebViewClient {
    protected final Activity _activity;

    public MarkorWebViewClient(final WebView webView, final Activity activity) {
        super(webView);
        _activity = activity;
    }

    @Override
    public boolean shouldOverrideUrlLoading(WebView view, String url) {
        try {
            Context context = view.getContext();

            if (url.equals("about:blank")) {
                view.reload();
                return true;
            }
            if (url.startsWith("file:///android_asset/")) {
                return false;
            } else if (url.startsWith("file://")) {
                DocumentActivity.launch(_activity, Uri.parse(url));
            } else {
                MarkorContextUtils su = new MarkorContextUtils(_activity);
                AppSettings settings = AppSettings.get(_activity);
                if (!settings.isOpenLinksWithChromeCustomTabs() || (settings.isOpenLinksWithChromeCustomTabs() && !su.openWebpageInChromeCustomTab(context, url))) {
                    su.openWebpageInExternalBrowser(context, url);
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return true;
    }
}
