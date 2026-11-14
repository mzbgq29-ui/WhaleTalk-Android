package io.astrbot.android.core

import org.junit.Assert.*
import org.junit.Test

class RestartPolicyTest {
    @Test fun usesFiveFifteenAndSixtySecondBackoffThenStops() {
        val policy = RestartPolicy()
        assertEquals(RestartDecision.Retry(5_000), policy.onUnexpectedExit(1_000, true, MaintenanceState.IDLE))
        assertEquals(RestartDecision.Retry(15_000), policy.onUnexpectedExit(6_000, true, MaintenanceState.IDLE))
        assertEquals(RestartDecision.Retry(60_000), policy.onUnexpectedExit(21_000, true, MaintenanceState.IDLE))
        assertEquals(RestartDecision.Exhausted, policy.onUnexpectedExit(30_000, true, MaintenanceState.IDLE))
    }

    @Test fun doesNotFightUserStopOrMaintenance() {
        val policy = RestartPolicy()
        assertEquals(RestartDecision.Suppressed, policy.onUnexpectedExit(1_000, false, MaintenanceState.IDLE))
        assertEquals(RestartDecision.Suppressed, policy.onUnexpectedExit(1_000, true, MaintenanceState.BACKING_UP))
        assertEquals(0, policy.attemptCount)
    }

    @Test fun resetAllowsAnotherSeries() {
        val policy = RestartPolicy()
        repeat(3) { policy.onUnexpectedExit(1_000L + it, true, MaintenanceState.IDLE) }
        policy.reset()
        assertEquals(RestartDecision.Retry(5_000), policy.onUnexpectedExit(10_000, true, MaintenanceState.IDLE))
    }
}
