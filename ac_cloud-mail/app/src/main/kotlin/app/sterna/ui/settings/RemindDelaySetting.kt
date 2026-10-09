package app.sterna.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.sterna.R
import app.sterna.push.NotificationReminders

/** Configs > Notifications > Remind: how long the Remind button waits before the notification comes back. */
@Composable
internal fun RemindDelayChoices() {
    val context = LocalContext.current.applicationContext
    var minutes by remember { mutableIntStateOf(NotificationReminders.delayMinutes(context)) }
    Column {
        NotificationReminders.DELAY_CHOICES.forEach { choice ->
            DeliveryModeOption(
                title = if (choice % 60 == 0) {
                    stringResource(R.string.settings_remind_hours, choice / 60)
                } else {
                    stringResource(R.string.settings_remind_minutes, choice)
                },
                subtitle = "",
                selected = minutes == choice,
                onClick = {
                    minutes = choice
                    NotificationReminders.setDelayMinutes(context, choice)
                },
            )
        }
    }
}
