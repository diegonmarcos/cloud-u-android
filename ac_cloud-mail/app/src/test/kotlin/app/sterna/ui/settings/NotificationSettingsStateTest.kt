package app.sterna.ui.settings

import app.sterna.core.data.settings.DeliveryMode
import app.sterna.core.data.settings.NotificationContent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The gate of the notifications screen, EXECUTED (#57): the screen may show a value only once the
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotificationSettingsStateTest {

    @Test fun `nothing is emitted until all five stored values have answered`() = runTest {
        val deliveryMode = MutableSharedFlow<DeliveryMode>(replay = 1)
        val notificationContent = MutableSharedFlow<NotificationContent>(replay = 1)
        val quietHoursEnabled = MutableSharedFlow<Boolean>(replay = 1)
        val quietHoursStart = MutableSharedFlow<Int>(replay = 1)
        val quietHoursEnd = MutableSharedFlow<Int>(replay = 1)

        val seen = mutableListOf<NotificationSettingsState>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) {
            notificationSettingsState(
                deliveryMode = deliveryMode,
                notificationContent = notificationContent,
                quietHoursEnabled = quietHoursEnabled,
                quietHoursStart = quietHoursStart,
                quietHoursEnd = quietHoursEnd,
            ).collect { seen += it }
        }

        fun stillNothing(answered: String) {
            runCurrent()
            assertEquals(
                "the screen must have NOTHING to show while some of the five stored settings are " +
                    "still unread (answered so far: $answered). Anything emitted here is a default " +
                    "standing in for a value nobody has read yet — and the radio rows it would " +
                    "draw are tappable.",
                emptyList<NotificationSettingsState>(),
                seen.toList(),
            )
        }

        deliveryMode.emit(STORED.deliveryMode)
        stillNothing("deliveryMode")
        notificationContent.emit(STORED.notificationContent)
        stillNothing("deliveryMode, notificationContent")
        quietHoursEnabled.emit(STORED.quietHoursEnabled)
        stillNothing("deliveryMode, notificationContent, quietHoursEnabled")
        quietHoursStart.emit(STORED.quietHoursStart)
        stillNothing("all but quietHoursEnd")

        quietHoursEnd.emit(STORED.quietHoursEnd)
        runCurrent()
        assertEquals(
            "once the fifth stored value has answered, exactly one state must be emitted",
            listOf(STORED),
            seen.toList(),
        )
        collector.cancel()
    }

    @Test fun `the emitted state carries the stored values, each in its own field`() = runTest {
        val state = notificationSettingsState(
            deliveryMode = MutableStateFlow(DeliveryMode.BATTERY_SAVER),
            notificationContent = MutableStateFlow(NotificationContent.NONE),
            quietHoursEnabled = MutableStateFlow(true),
            quietHoursStart = MutableStateFlow(23 * 60 + 30),
            quietHoursEnd = MutableStateFlow(6 * 60 + 45),
        ).first()

        assertEquals(
            "deliveryMode must carry the stored Battery saver, not the INSTANT default",
            DeliveryMode.BATTERY_SAVER,
            state.deliveryMode,
        )
        assertEquals(
            "notificationContent must carry the stored NONE — the setting of #57, the one whose " +
                "SENDER_AND_SUBJECT default was being shown over it",
            NotificationContent.NONE,
            state.notificationContent,
        )
        assertEquals(
            "quietHoursEnabled must carry the stored true, not the false default",
            true,
            state.quietHoursEnabled,
        )
        assertEquals(
            "quietHoursStart must carry 23:30 = 1410 (stored), not 22:00 = 1320 (default) and not " +
                "the end time",
            23 * 60 + 30,
            state.quietHoursStart,
        )
        assertEquals(
            "quietHoursEnd must carry 06:45 = 405 (stored), not 07:00 = 420 (default) and not the " +
                "start time",
            6 * 60 + 45,
            state.quietHoursEnd,
        )
        assertEquals(
            "and the five together, in case a pair was swapped on the way in",
            STORED,
            state,
        )
    }

    @Test fun `a later change to any one of them is still published`() = runTest {
        val quietHoursEnabled = MutableStateFlow(true)
        val flow = notificationSettingsState(
            deliveryMode = MutableStateFlow(DeliveryMode.BATTERY_SAVER),
            notificationContent = MutableStateFlow(NotificationContent.NONE),
            quietHoursEnabled = quietHoursEnabled,
            quietHoursStart = MutableStateFlow(23 * 60 + 30),
            quietHoursEnd = MutableStateFlow(6 * 60 + 45),
        )
        val seen = mutableListOf<NotificationSettingsState>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) { flow.collect { seen += it } }
        runCurrent()
        quietHoursEnabled.value = false
        runCurrent()
        assertEquals(
            "gating the first frame must not freeze the screen afterwards: switching quiet hours " +
                "off has to reach it",
            listOf(STORED, STORED.copy(quietHoursEnabled = false)),
            seen.toList(),
        )
        collector.cancel()
    }

    private companion object {
        /** Five stored values, none of them a default, none of them equal to another. */
        val STORED = NotificationSettingsState(
            deliveryMode = DeliveryMode.BATTERY_SAVER,
            notificationContent = NotificationContent.NONE,
            quietHoursEnabled = true,
            quietHoursStart = 23 * 60 + 30,
            quietHoursEnd = 6 * 60 + 45,
        )
    }
}
