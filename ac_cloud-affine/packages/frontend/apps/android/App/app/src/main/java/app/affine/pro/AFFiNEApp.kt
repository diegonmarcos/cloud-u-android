package app.affine.pro

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.os.SystemClock
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewOutcomeReceiver
import androidx.webkit.WebViewStartUpConfig
import androidx.webkit.WebViewStartUpResult
import androidx.webkit.WebViewStartupException
import app.affine.pro.utils.logger.AffineDebugTree
import app.affine.pro.utils.logger.FileTree
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber
import java.util.concurrent.Executors

@HiltAndroidApp
class AFFiNEApp : Application() {

    override fun onCreate() {
        super.onCreate()
        startWebView()
        _context = applicationContext
        // DE-CLOUDED (#469): Firebase/Crashlytics removed — this is a local-first
        // note app; there is no cloud backend and no telemetry. Logs stay on
        // device only.
        if (BuildConfig.DEBUG) {
            Timber.plant(AffineDebugTree())
        } else {
            Timber.plant(FileTree(applicationContext))
        }
        Timber.i("Application started.")
        // init capacitor config
        CapacitorConfig.init(baseContext)
    }

    private fun startWebView() {
        val startedAt = SystemClock.elapsedRealtime()
        val executor = Executors.newSingleThreadExecutor()
        val config = WebViewStartUpConfig.Builder(executor).build()
        WebViewCompat.startUpWebView(
            this,
            config,
            object : WebViewOutcomeReceiver<WebViewStartUpResult, WebViewStartupException> {
                override fun onResult(result: WebViewStartUpResult) {
                    executor.shutdown()
                    Timber.i(
                        "WebView startup completed asynchronously in %d ms.",
                        SystemClock.elapsedRealtime() - startedAt,
                    )
                }

                override fun onError(error: WebViewStartupException) {
                    executor.shutdown()
                    Timber.w(error, "WebView asynchronous startup failed.")
                }
            },
        )
    }

    override fun onTerminate() {
        _context = null
        super.onTerminate()
    }

    companion object {
        @SuppressLint("StaticFieldLeak")
        private var _context: Context? = null

        fun context() = requireNotNull(_context)
    }
}
