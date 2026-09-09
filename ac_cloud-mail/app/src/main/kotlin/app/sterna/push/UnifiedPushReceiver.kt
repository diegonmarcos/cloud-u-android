package app.sterna.push

import app.sterna.container
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

/**
 * Entry point for distributor events (the connector's abstract PushService — imported
 */
class UnifiedPushReceiver : org.unifiedpush.android.connector.PushService() {

    private val manager: UnifiedPushManager get() = application.container.unifiedPushManager

    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) =
        manager.onNewEndpoint(instance, endpoint)

    override fun onMessage(message: PushMessage, instance: String) =
        manager.onMessage(instance, message)

    override fun onRegistrationFailed(reason: FailedReason, instance: String) =
        manager.onRegistrationFailed(instance, reason)

    override fun onUnregistered(instance: String) =
        manager.onUnregistered(instance)
}
