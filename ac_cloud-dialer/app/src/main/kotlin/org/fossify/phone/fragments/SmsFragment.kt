package org.fossify.phone.fragments

import android.content.Context
import android.util.AttributeSet
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import com.diegonmarcos.superapp.uikit.CloudKitTheme
import com.diegonmarcos.superapp.uikit.KitPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.fossify.commons.extensions.getProperBackgroundColor
import org.fossify.phone.R
import org.fossify.phone.extensions.dialerKitPalette
import org.fossify.phone.sms.SmsListController
import org.fossify.phone.sms.SmsListScreen

/**
 * The SMS page of the main pager (bottom-nav section "sms"): a View page hosting the Compose
 * conversation list. The main search bar drives its query, like the other pages.
 */
class SmsFragment(context: Context, attributeSet: AttributeSet) : MyViewPagerFragment<MyViewPagerFragment.SmsInnerBinding>(context, attributeSet) {
    private var controller: SmsListController? = null
    private var query by mutableStateOf("")
    private var palette by mutableStateOf<KitPalette?>(null)
    private var background by mutableStateOf(Color.Black)

    override fun onFinishInflate() {
        super.onFinishInflate()
        innerBinding = SmsInnerBinding
    }

    override fun setupFragment() {
        val host = activity ?: return
        val c = SmsListController(host)
        controller = c
        findViewById<ComposeView>(R.id.sms_compose).setContent {
            val kit = palette ?: host.dialerKitPalette()
            CloudKitTheme(kit) {
                val bodyHits by produceState(emptySet<Long>(), query) {
                    value = if (query.trim().length < 2) emptySet() else withContext(Dispatchers.IO) { c.searchBodies(query) }
                }
                SmsListScreen(
                    conversations = c.visible(query, bodyHits),
                    loaded = c.loaded,
                    isDefault = c.isDefault,
                    canRead = c.canRead,
                    showArchived = c.showArchived,
                    photoOf = c::photoOf,
                    onOpen = c::openConversation,
                    onTogglePin = c::togglePin,
                    onToggleArchive = c::toggleArchive,
                    onDelete = c::delete,
                    onNewMessage = c::newMessage,
                    onToggleArchiveView = { c.showArchived = !c.showArchived },
                    onGrant = c::requestPermissions,
                    onMakeDefault = c::requestDefault,
                    modifier = Modifier.fillMaxSize().background(background),
                )
            }
        }
        c.start()
    }

    override fun setupColors(textColor: Int, primaryColor: Int, properPrimaryColor: Int) {
        val host = activity ?: return
        palette = host.dialerKitPalette()
        background = Color(host.getProperBackgroundColor())
    }

    override fun onSearchClosed() {
        query = ""
    }

    override fun onSearchQueryChanged(text: String) {
        query = text
    }

    /** The page came back on screen: the default-app state may have changed in system settings. */
    fun refreshItems() {
        controller?.refreshAccess()
    }

    override fun onDetachedFromWindow() {
        controller?.dispose()
        controller = null
        super.onDetachedFromWindow()
    }
}
