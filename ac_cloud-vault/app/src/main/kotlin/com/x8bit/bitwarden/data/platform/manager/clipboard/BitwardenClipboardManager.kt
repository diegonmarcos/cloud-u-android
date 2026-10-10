package com.x8bit.bitwarden.data.platform.manager.clipboard

import androidx.compose.ui.text.AnnotatedString
import com.bitwarden.ui.util.Text

/**
 * Wrapper class for using the clipboard.
 */
interface BitwardenClipboardManager {

    /**
     * Places the given [text] into the device's clipboard. Setting the data to [isSensitive] will
     * obfuscate the displayed data on the default popup (true by default). A toast will be
     * displayed on devices that do not have a default popup (pre-API 32) and will not be displayed
     * on newer APIs. If a toast is displayed, it will be formatted as "[text] copied" or if a
     * [toastDescriptorOverride] is provided, it will be formatted as
     * "[toastDescriptorOverride] copied".
     */
    fun setText(
        text: AnnotatedString,
        isSensitive: Boolean = true,
        toastDescriptorOverride: String? = null,
    )

    /**
     * See [setText] for more details.
     */
    fun setText(
        text: String,
        isSensitive: Boolean = true,
        toastDescriptorOverride: String? = null,
    )

    /**
     * See [setText] for more details.
     */
    fun setText(
        text: String,
        isSensitive: Boolean = true,
        toastDescriptorOverride: Text,
    )

    /**
     * See [setText] for more details.
     */
    fun setText(
        text: Text,
        isSensitive: Boolean = true,
        toastDescriptorOverride: String? = null,
    )

    /**
     * Cloud Vault: make sure the clipboard is cleared within [seconds], even when the user's own
     * "clear clipboard" setting is never (or longer). Used after copying a short-lived secret
     * such as a TOTP code during autofill. The default does nothing (test fakes).
     */
    fun clearWithin(seconds: Int) = Unit
}
