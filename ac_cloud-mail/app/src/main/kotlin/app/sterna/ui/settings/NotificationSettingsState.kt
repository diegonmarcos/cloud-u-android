package app.sterna.ui.settings

import app.sterna.core.data.settings.DeliveryMode
import app.sterna.core.data.settings.NotificationContent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * The five stored values the notifications screen shows, carried as one value (#57).
 */
data class NotificationSettingsState(
    val deliveryMode: DeliveryMode,
    val notificationContent: NotificationContent,
    val quietHoursEnabled: Boolean,
    val quietHoursStart: Int,
    val quietHoursEnd: Int,
)

/** Combines the five stored settings into one value. Nothing is emitted until all five have
 *  answered; afterwards every change to any of them emits again. */
internal fun notificationSettingsState(
    deliveryMode: Flow<DeliveryMode>,
    notificationContent: Flow<NotificationContent>,
    quietHoursEnabled: Flow<Boolean>,
    quietHoursStart: Flow<Int>,
    quietHoursEnd: Flow<Int>,
): Flow<NotificationSettingsState> = combine(
    deliveryMode,
    notificationContent,
    quietHoursEnabled,
    quietHoursStart,
    quietHoursEnd,
) { mode, content, quietEnabled, quietStart, quietEnd ->
    NotificationSettingsState(
        deliveryMode = mode,
        notificationContent = content,
        quietHoursEnabled = quietEnabled,
        quietHoursStart = quietStart,
        quietHoursEnd = quietEnd,
    )
}
