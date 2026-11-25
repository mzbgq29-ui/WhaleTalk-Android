package io.astrbot.android.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OneBotDropTrackerTest {
    @Test fun confirmedDropSurvivesAnUnknownSampleAndBusyRuntime() {
        val tracker = OneBotDropTracker()
        assertFalse(tracker.observe(OneBotState.CONNECTED, 1, 2, canRecover = false))
        assertFalse(tracker.observe(OneBotState.UNKNOWN, 1, 2, canRecover = false))
        assertFalse(tracker.observe(OneBotState.DISCONNECTED, 1, 2, canRecover = false))
        assertTrue(tracker.observe(OneBotState.DISCONNECTED, 1, 2, canRecover = true))
        assertFalse(tracker.observe(OneBotState.DISCONNECTED, 1, 2, canRecover = true))
    }

    @Test fun changingEitherProcessOrDisablingTheChannelClearsPreviousConnection() {
        val tracker = OneBotDropTracker()
        tracker.observe(OneBotState.CONNECTED, 1, 2, canRecover = false)
        assertFalse(tracker.observe(OneBotState.DISCONNECTED, 1, 3, canRecover = true))
        tracker.observe(OneBotState.CONNECTED, 1, 3, canRecover = false)
        assertFalse(tracker.observe(OneBotState.DISCONNECTED, 2, 3, canRecover = true))
        tracker.observe(OneBotState.CONNECTED, 2, 3, canRecover = false)
        tracker.observe(OneBotState.UNCONFIGURED, 2, 3, canRecover = false)
        assertFalse(tracker.observe(OneBotState.DISCONNECTED, 2, 3, canRecover = true))
    }
}
