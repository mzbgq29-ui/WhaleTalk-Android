package io.astrbot.android.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
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

    @Test fun repeatedUpstreamRestartRequestsStopAtTheLimit() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val launches = AtomicInteger()
        val supervisor = ProcessSupervisor(ServiceId.ASTRBOT, scope, {
            launches.incrementAndGet()
            object : Process() {
                override fun getInputStream() = ByteArrayInputStream(byteArrayOf())
                override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
                override fun getOutputStream() = ByteArrayOutputStream()
                override fun waitFor() = 75
                override fun exitValue() = 75
                override fun destroy() = Unit
            }
        }, onLog = {}, restartPolicy = RestartPolicy(listOf(10L, 10L, 10L)), expectedRestart = { it == 75 })
        try {
            supervisor.start()
            withTimeout(5_000) { supervisor.state.first { it.detail.contains("已暂停自动重启") } }
            assertEquals(4, launches.get())
            assertEquals(ProcessState.CRASHED, supervisor.state.value.process)
        } finally {
            supervisor.close()
            scope.cancel()
        }
    }

    @Test fun failedLogWritesDoNotInterruptProcessMonitoring() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val supervisor = ProcessSupervisor(ServiceId.ASTRBOT, scope, {
            object : Process() {
                override fun getInputStream() = "started\n".byteInputStream()
                override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
                override fun getOutputStream() = ByteArrayOutputStream()
                override fun waitFor() = 1
                override fun exitValue() = 1
                override fun destroy() = Unit
            }
        }, onLog = { throw IOException("disk full") }, autoRestart = { false })
        try {
            withTimeout(5_000) {
                supervisor.start()
                supervisor.state.first { it.process == ProcessState.CRASHED }
                supervisor.stop()
            }
            assertEquals(ProcessState.STOPPED, supervisor.state.value.process)
        } finally {
            scope.cancel()
        }
    }
}
