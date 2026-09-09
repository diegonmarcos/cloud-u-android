package app.sterna.ui.compose

import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.PlatformTextInputSession

/**
 * Ask the keyboard not to learn from a message being written (#120).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun IncognitoKeyboard(content: @Composable () -> Unit) {
    InterceptPlatformTextInput(INCOGNITO_INTERCEPTOR, content)
}

/**
 * The interceptor itself, a top-level value rather than something built inside the composition: it
 */
@OptIn(ExperimentalComposeUiApi::class)
internal val INCOGNITO_INTERCEPTOR = object : PlatformTextInputInterceptor {
    override suspend fun interceptStartInputMethod(
        request: PlatformTextInputMethodRequest,
        nextHandler: PlatformTextInputSession,
    ): Nothing {
        val incognito = object : PlatformTextInputMethodRequest {
            override fun createInputConnection(outAttributes: EditorInfo): InputConnection {
                // The delegate WRITES outAttributes (imeOptions, inputType, the action of the
                // field that asked). So it runs first and our bit is added after — reversing the
                // two lines would silently drop the flag.
                val connection = request.createInputConnection(outAttributes)
                outAttributes.imeOptions = incognitoImeOptions(outAttributes.imeOptions)
                return connection
            }
        }
        return nextHandler.startInputMethod(incognito)
    }
}

/**
 * Add the no-personalized-learning bit to whatever the field already asked for, out here so a JVM
 */
internal fun incognitoImeOptions(imeOptions: Int): Int =
    imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
