package com.diegonmarcos.ide

import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * The cloud-myterminal home — a thin WebView launcher for the my-konsole frontend
 * (assets/frontend/, seeded by build.sh::step_bundle_frontend). In v1 the
 * frontend talked to the local my-konsole engine over ws://127.0.0.1:7333
 * (transport.js). From v2 the hub injects a `window.Transport` shim backed by
 * [TerminalBridge] / [SshBackend] so the frontend SSHes directly into the
 * selected phone env (Termux / Nix-on-Droid) — no local engine process needed.
 *
 * The shim is injected via [WebViewCompat.addDocumentStartJavaScript] before any
 * page scripts execute. If the WebView version is too old to support that API the
 * shim is not injected and the frontend falls back to its built-in transport.js.
 *
 * 2026-10-16 owner decision: all apps must be Compose. The shell is a ComposeView under
 * CloudKitTheme (Ui.composeView): the floating gear, the native browser overlay and its chrome are
 * composables; the two WebViews (terminal frontend, browser page) stay WebViews, hosted by AndroidView.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var ssh: SshBackend
    private lateinit var browserWeb: WebView

    /** The native browser overlay: shown over the terminal, with its address bar text. */
    private var browserShown by mutableStateOf(false)
    private var browserAddr by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ssh = SshBackend(this)
        webView = WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            )
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true   // CRITICAL — frontend session-restore uses localStorage
                allowFileAccess = true
                allowFileAccessFromFileURLs = true
                allowUniversalAccessFromFileURLs = true   // file:// page opening a ws:// connection
                mediaPlaybackRequiresUserGesture = false
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }
            webViewClient = WebViewClient()       // keep navigation in-app
            webChromeClient = WebChromeClient()   // console logging
        }

        // Wire the bridge — exposes AndroidTerm to JS before loadUrl.
        val bridge = TerminalBridge(this, webView, ssh) { TerminalSessions.activeBackend(this) }
        webView.addJavascriptInterface(bridge, "AndroidTerm")

        // Inject the Transport shim at document-start so it runs before the
        // bundled transport.js (which already guards against clobbering an
        // existing window.Transport — see da_my-konsole guard).
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(webView, TRANSPORT_SHIM_JS, setOf("*"))
        }

        webView.loadUrl("file:///android_asset/frontend/index.html")

        // The native browser's page. Opened via openBrowser(url) called from
        // TerminalBridge.browserOpen().
        browserWeb = WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
        }

        setContent { Shell() }
    }

    @Composable
    private fun Shell() {
        Box(Modifier.fillMaxSize()) {
            AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
            // Floating ⚙ → native Configs (terminal backend switcher, SSH connection
            // editor, key pairing, setup steps). The WebView has no chrome, so this
            // is the only entry point into ConfigsActivity.
            Text(
                "⚙", fontSize = 22.sp, color = colorResource(R.color.ide_lavender),
                modifier = Modifier.align(Alignment.BottomEnd).padding(14.dp)
                    .background(Color(0x99000000))
                    .clickable { startActivity(android.content.Intent(this@MainActivity, ConfigsActivity::class.java)) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
            // ── Native browser overlay ─────────────────────────────────────────────
            // Covers the whole frame (drawn last so it sits on top).
            if (browserShown) BrowserOverlay()
        }
    }

    /** Top chrome (back | address bar | close) over the browser page. */
    @Composable
    private fun BrowserOverlay() {
        Column(Modifier.fillMaxSize().background(colorResource(R.color.ide_browser_bg))) {
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { if (browserWeb.canGoBack()) browserWeb.goBack() }) { Text("‹") }
                OutlinedTextField(
                    value = browserAddr, onValueChange = { browserAddr = it },
                    modifier = Modifier.weight(1f), singleLine = true,
                    placeholder = { Text("https://…") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = {
                        var v = browserAddr.trim()
                        if (!Regex("^[a-z]+://.*").matches(v)) v = "https://$v"
                        browserAddr = v
                        browserWeb.loadUrl(v)
                    }),
                )
                Button(onClick = { browserShown = false }) { Text("✕") }
            }
            AndroidView(factory = { browserWeb }, modifier = Modifier.fillMaxWidth().weight(1f))
        }
    }

    /** Open the native browser overlay, navigate to [url], and show it. */
    fun openBrowser(url: String) {
        browserAddr = url
        browserWeb.loadUrl(url)
        browserShown = true
    }

    override fun onBackPressed() {
        if (browserShown) {
            if (browserWeb.canGoBack()) browserWeb.goBack()
            else browserShown = false
            return
        }
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        TerminalSessions.disconnectAll(this)
        ssh.disconnectAll()
        super.onDestroy()
    }

    companion object {
        /**
         * Injected before page scripts via WebViewCompat.addDocumentStartJavaScript.
         *
         * Sets window.Transport backed by window.AndroidTerm (the TerminalBridge
         * JavascriptInterface). If AndroidTerm is not present (very old WebView
         * missing the DOCUMENT_START_SCRIPT feature), the shim is never injected
         * so the frontend falls back to its own transport.js.
         *
         * Callback protocol (Android → JS):
         *   window.__aptyData(id, dataStr)       — streamed pty output
         *   window.__aptyExit(id)                — pty channel closed
         *   window.__afsResult(rid, ok, jsonStr) — fs Promise resolved/rejected
         *
         * subs  = { id: { out: cb, exit: cb } }  — onPty / onPtyExit listeners
         * pend  = { rid: { resolve, reject } }    — in-flight fs Promises
         * rid   = incrementing request id counter
         *
         * fs(fn, arg) is the one-arg fs helper (listDir, readFile).
         * writeFile needs 3 args so it is wired inline.
         */
        const val TRANSPORT_SHIM_JS = """(function(){
  if (!window.AndroidTerm) return;
  var subs = {}, pend = {}, rid = 0;

  window.__aptyData = function(id, d) {
    var s = subs[id]; if (s && s.out) s.out(d);
  };
  window.__aptyExit = function(id) {
    var s = subs[id]; if (s && s.exit) s.exit();
  };
  window.__afsResult = function(r, ok, j) {
    var p = pend[r]; if (!p) return; delete pend[r];
    if (ok) p.resolve(j ? JSON.parse(j) : null); else p.reject(j || 'error');
  };

  function fs(fn, arg) {
    var r = ++rid;
    return new Promise(function(res, rej) {
      pend[r] = { resolve: res, reject: rej };
      AndroidTerm[fn](r, arg);
    });
  }

  window.Transport = {
    ptyStart: function(id, c, r, cwd) {
      AndroidTerm.ptyStart(id, c, r, cwd || '');
      return Promise.resolve();
    },
    ptyWrite:  function(id, d) { AndroidTerm.ptyWrite(id, d); return Promise.resolve(); },
    ptyResize: function(id, c, r) { AndroidTerm.ptyResize(id, c, r); },
    ptyKill:   function(id) { AndroidTerm.ptyKill(id); },
    onPty: function(id, cb) {
      var s = subs[id] || {}; s.out = cb; subs[id] = s;
      return function() { s.out = null; };
    },
    onPtyExit: function(id, cb) {
      var s = subs[id] || {}; s.exit = cb; subs[id] = s;
      return function() { s.exit = null; };
    },
    listDir:  function(p) { return fs('listDir', p); },
    readFile: function(p) { return fs('readFile', p); },
    writeFile: function(p, ct) {
      var r = ++rid;
      return new Promise(function(res, rej) {
        pend[r] = { resolve: res, reject: rej };
        AndroidTerm.writeFile(r, p, ct);
      });
    }
  };
})();"""
    }
}
