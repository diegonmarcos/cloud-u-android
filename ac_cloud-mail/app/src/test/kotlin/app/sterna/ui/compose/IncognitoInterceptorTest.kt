package app.sterna.ui.compose

import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.PlatformTextInputSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The interceptor RUN, not read (#120).
 */
@OptIn(ExperimentalComposeUiApi::class)
class IncognitoInterceptorTest {

    /** The request a `BasicTextField` would have opened its session with. */
    private class FieldRequest : PlatformTextInputMethodRequest {
        override fun createInputConnection(outAttributes: EditorInfo): InputConnection =
            error("not called: reaching here would need a real EditorInfo")
    }

    /** Stands in for the platform: records what it is asked to start, then unwinds. */
    private class RecordingSession : PlatformTextInputSession {
        var started: PlatformTextInputMethodRequest? = null
        override val view: View get() = error("the interceptor must not need the View")
        override suspend fun startInputMethod(request: PlatformTextInputMethodRequest): Nothing {
            started = request
            throw Unwind()
        }
    }

    /** `startInputMethod` returns Nothing, so the only way back out of it is to throw. */
    private class Unwind : RuntimeException()

    private fun intercept(request: PlatformTextInputMethodRequest): PlatformTextInputMethodRequest? {
        val session = RecordingSession()
        try {
            runBlocking { INCOGNITO_INTERCEPTOR.interceptStartInputMethod(request, session) }
        } catch (_: Unwind) {
            // expected: the fake platform never returns either
        }
        return session.started
    }

    @Test fun `the input method is started, not swallowed`() {
        // The interceptor must chain: an interceptor that never calls nextHandler would leave the
        // composer with no keyboard at all.
        assertNotNull("the interceptor never started an input method", intercept(FieldRequest()))
    }

    @Test fun `what reaches the input method is the wrapper, never the field's own request`() {
        // THE assertion this file exists for. `startInputMethod(request)` passes here and passes
        // every lint; it fails only this.
        val original = FieldRequest()
        assertNotSame(
            "the interceptor handed the field's OWN request to the input method — the incognito " +
                "wrapper was built and dropped, and the keyboard is asked nothing (#120)",
            original,
            intercept(original),
        )
    }

    @Test fun `the wrapper is the interceptor's own, not something borrowed`() {
        // Two different fields must not end up sharing a request, and the wrapper must be the one
        // this file builds — the same class both times, a different instance each time.
        val first = intercept(FieldRequest())
        val second = intercept(FieldRequest())
        assertNotNull(first)
        assertNotNull(second)
        assertSame(
            "the two sessions were wrapped by different implementations",
            first!!::class.java,
            second!!::class.java,
        )
        assertNotSame("the same wrapper instance was reused across two sessions", first, second)
    }
}
