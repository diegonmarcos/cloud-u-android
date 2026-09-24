package com.foxdebug.webview;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.FrameLayout;

public class WebViewActivity extends Activity {

  private WebView webView;
  private String webviewId;

  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    webviewId = getIntent().getStringExtra("webviewId");

    // The plugin registers itself in pluginInitialize(), so the singleton is
    // always available while the app is alive. Previously this read a static
    // field that was never set, which made every fullscreen WebView close
    // instantly because the instance lookup returned null.
    WebViewPlugin plugin = WebViewPlugin.getInstance();
    WebViewInstance instance = plugin != null ? plugin.getInstance(webviewId) : null;
    if (instance == null) {
      finish();
      return;
    }

    instance.createWebView(this);
    webView = instance.getWebView();
    if (webView == null) {
      finish();
      return;
    }
    instance.setHostingActivity(this);

    String title = instance.getTitle();
    if (title != null && !title.isEmpty()) {
      setTitle(title);
    }

    if (webView.getParent() != null) {
      ((ViewGroup) webView.getParent()).removeView(webView);
    }
    FrameLayout container = new FrameLayout(this);
    // Edge-to-edge is enforced on newer Android versions, so pad the content
    // out from under the status and navigation bars.
    WebViewInstance.applySystemBarInsets(container);
    container.addView(webView, new FrameLayout.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT,
      ViewGroup.LayoutParams.MATCH_PARENT
    ));
    setContentView(container);

    if (Build.VERSION.SDK_INT >= 30) {
      getWindow().setDecorFitsSystemWindows(false);
    }
  }

  @Override
  public void onBackPressed() {
    if (webView != null && webView.canGoBack()) {
      webView.goBack();
    } else {
      finish();
    }
  }

  @Override
  protected void onDestroy() {
    super.onDestroy();

    WebViewPlugin plugin = WebViewPlugin.getInstance();
    if (plugin != null && webviewId != null) {
      WebViewInstance instance = plugin.getInstance(webviewId);
      if (instance != null) {
        instance.clearHostingActivity(this);
        // Actually destroy the WebView instead of leaking it, then drop the
        // instance so later calls fail instead of touching a dead WebView.
        // destroy() is idempotent, so this is safe when the JS side already
        // called destroy() and finishing this activity is what tore us down.
        instance.destroy();
        plugin.removeInstance(webviewId);
      }
      plugin.sendEventToCordova(webviewId, "closed", null);
    }

    webView = null;
  }
}
