package com.diegonmarcos.cloudlib.web;

import android.util.Log;
import android.view.ViewGroup;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.webkit.WebViewAssetLoader;

/**
 * The client every fleet web view extends, never a bare WebViewClient (#1371). Java on purpose: an app's Kotlin
 * subclass may override with nullable or non-null parameters (platform types), as it could on WebViewClient.
 *
 * It serves bundled assets through {@code assets} and survives the renderer dying: Android kills the shared renderer
 * under memory pressure and a GPU driver crash takes it down too; without an override the app process dies with it.
 * Recovery: the dead view is destroyed and replaced in its parent by a fresh one of the same profile, with this client
 * and the old chrome client, reloading the last URL. A host that holds a reference to the view or installs JS
 * interfaces overrides {@link #onRecovered} to swap the reference and re-add them; a host with its own fallback UI
 * overrides {@link #onRenderProcessGone} itself. A WebView subclass is not rebuilt (its class is the host's): it stays
 * in place, blank, and {@link #onRecovered} gets a null fresh view.
 */
public class CloudWebClient extends WebViewClient {
    private static final String TAG = "CloudWeb";
    public final WebProfile profile;
    private final WebViewAssetLoader assets;

    public CloudWebClient(WebProfile profile) { this(profile, null); }

    public CloudWebClient(WebProfile profile, WebViewAssetLoader assets) {
        this.profile = profile;
        this.assets = assets;
    }

    @Override
    public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
        return assets == null || request == null ? null : assets.shouldInterceptRequest(request.getUrl());
    }

    @Override
    public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
        Log.w(TAG, "renderer gone (crash=" + (detail != null && detail.didCrash()) + ") at " + view.getUrl());
        if (view.getClass() != WebView.class) {
            // a host subclass (Mail's BodyWebView) cannot be rebuilt here: the dead view stays in place, the app lives,
            // and the host decides (onRecovered with a null fresh view)
            onRecovered(view, null);
            return true;
        }
        String url = view.getUrl();
        WebChromeClient chrome = view.getWebChromeClient();
        ViewGroup parent = view.getParent() instanceof ViewGroup ? (ViewGroup) view.getParent() : null;
        int index = parent == null ? -1 : parent.indexOfChild(view);
        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (parent != null) parent.removeView(view);
        view.destroy();
        if (parent == null || index < 0) {
            onRecovered(view, null);
            return true;
        }
        WebView fresh = CloudWeb.create(view.getContext(), profile);
        fresh.setId(view.getId());
        fresh.setWebViewClient(this);
        if (chrome != null) fresh.setWebChromeClient(chrome);
        parent.addView(fresh, index, params);
        onRecovered(view, fresh);
        if (url != null && !url.isEmpty() && !"about:blank".equals(url)) fresh.loadUrl(url);
        return true;
    }

    /** After {@link #onRenderProcessGone}: {@code fresh} replaced {@code dead}, or null (no parent, or a subclass). */
    public void onRecovered(WebView dead, WebView fresh) {}
}
