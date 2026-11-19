package io.astrbot.android.runtime

import android.content.Context

import io.astrbot.android.core.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

data class AutoReconnectStatus(
    val layer: String? = null,
    val attempt: Int = 0,
    val delayMillis: Long? = null,
    val exhausted: Boolean = false,
)

object AutoReconnectStore {
    val state = MutableStateFlow(AutoReconnectStatus())
}

/** Schedules bounded restarts after a confirmed OneBot drop. Process crashes are handled by ProcessSupervisor. */
internal class AutoReconnectCoordinator(
    private val context: Context,
    private val scope: CoroutineScope,
    private val restart: (ServiceId) -> Unit,
    private val desired: (ServiceId) -> Boolean,
    private val maintenance: () -> MaintenanceState,
    private val busy: () -> Boolean,
) {
    private val policy = RestartPolicy()
    private var job: Job? = null
    fun onChannel(
        previous: OneBotState,
        next: OneBotState,
        process: ProcessState,
        id: ServiceId,
        stillDisconnected: () -> Boolean,
    ) {
        if (next == OneBotState.CONNECTED) {
            policy.reset()
            job?.cancel()
            job = null
            AutoReconnectStore.state.value = AutoReconnectStatus()
            return
        }
        if (!AutoReconnectSettings(context).enabled()) return
        if (previous != OneBotState.CONNECTED || next != OneBotState.DISCONNECTED) return
        if (process != ProcessState.RUNNING || !desired(id) || busy() || maintenance() != MaintenanceState.IDLE) return
        if (job?.isActive == true) return
        val decision = policy.onUnexpectedExit(android.os.SystemClock.elapsedRealtime(), true, maintenance())
        when (decision) {
            RestartDecision.Suppressed -> Unit
            RestartDecision.Exhausted -> {
                AutoReconnectStore.state.value = AutoReconnectStatus(layer = "消息通道", attempt = policy.attemptCount, exhausted = true)
                ConnectionAlerts(context).notifyGiveUp("消息通道")
            }
            is RestartDecision.Retry -> {
                val pending = AutoReconnectStatus(layer = "消息通道", attempt = policy.attemptCount, delayMillis = decision.delayMillis)
                AutoReconnectStore.state.value = pending
                job = scope.launch {
                    try {
                        delay(decision.delayMillis)
                        if (!AutoReconnectSettings(context).enabled() || !desired(id) || busy()) return@launch
                        if (maintenance() != MaintenanceState.IDLE || !stillDisconnected()) return@launch
                        restart(id)
                    } finally {
                        AutoReconnectStore.state.compareAndSet(pending, AutoReconnectStatus())
                    }
                }
            }
        }
    }

    fun onProcess(id: ServiceId, previous: ServiceStatus, next: ServiceStatus) {
        if (previous.process in setOf(ProcessState.RUNNING, ProcessState.STARTING) && next.process == ProcessState.CRASHED) {
            ConnectionAlerts(context).notifyProcessDropout(id.label)
            if (next.detail.contains("已暂停自动重启")) {
                AutoReconnectStore.state.value = AutoReconnectStatus(layer = id.label, exhausted = true)
                ConnectionAlerts(context).notifyGiveUp(id.label)
            }
        }
        if (previous.process == ProcessState.CRASHED && next.process == ProcessState.RUNNING) {
            ConnectionAlerts(context).notifyProcessRecovered(id.label)
            if (AutoReconnectStore.state.value.layer == id.label) AutoReconnectStore.state.value = AutoReconnectStatus()
        }
    }
}
