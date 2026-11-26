package io.astrbot.android.runtime

import android.content.Context

import io.astrbot.android.core.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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
    private val astrBot: StateFlow<ServiceStatus>,
    private val snowLuma: StateFlow<ServiceStatus>,
    private val restart: suspend (() -> Boolean) -> Long?,
    private val desired: () -> Boolean,
    private val maintenance: () -> MaintenanceState,
    private val busy: () -> Boolean,
) {
    private var job: Job? = null
    private val observations = combine(snowLuma, astrBot, ::Pair)
    private val channelDrop = OneBotDropTracker()

    @Synchronized fun cancel() {
        job?.cancel()
        AutoReconnectStore.state.value = AutoReconnectStatus()
    }

    @Synchronized fun onChannel(next: OneBotState, snowGeneration: Long, astrGeneration: Long) {
        val canRecover = next == OneBotState.DISCONNECTED && job?.isActive != true &&
            !busy() && maintenance() == MaintenanceState.IDLE &&
            eligible(snowLuma.value, astrBot.value, snowGeneration, astrGeneration)
        if (channelDrop.observe(next, snowGeneration, astrGeneration, canRecover)) {
            job = scope.launch { recover(snowGeneration, astrGeneration) }
        }
        if (next == OneBotState.CONNECTED) {
            val status = AutoReconnectStore.state.value
            if (status.layer == CHANNEL) AutoReconnectStore.state.compareAndSet(status, AutoReconnectStatus())
        }
    }

    private fun eligible(snow: ServiceStatus, bot: ServiceStatus, snowGeneration: Long, astrGeneration: Long): Boolean =
        snow.process == ProcessState.RUNNING && snow.processGeneration == snowGeneration &&
            bot.process == ProcessState.RUNNING && bot.processGeneration == astrGeneration &&
            snow.qq !in setOf(QqState.LOGGED_OUT, QqState.VERIFICATION_REQUIRED) &&
            AutoReconnectSettings(context).enabled() && desired()

    private suspend fun recover(initialGeneration: Long, astrGeneration: Long) {
        val coroutine = currentCoroutineContext()
        var generation = initialGeneration
        var observationTimedOut = false
        for ((index, wait) in RestartPolicy.DEFAULT_DELAYS.withIndex()) {
            val pending = AutoReconnectStatus(layer = CHANNEL, attempt = index + 1, delayMillis = wait)
            synchronized(this) {
                coroutine.ensureActive()
                AutoReconnectStore.state.value = pending
            }
            try {
                val interrupted = withTimeoutOrNull(wait) {
                    observations.first { (snow, bot) ->
                        !eligible(snow, bot, generation, astrGeneration) || snow.oneBot == OneBotState.CONNECTED
                    }
                }
                if (interrupted != null) return
                generation = restart {
                    val snow = snowLuma.value
                    val canRetryChannel = snow.oneBot == OneBotState.DISCONNECTED ||
                        (observationTimedOut && snow.oneBot == OneBotState.UNKNOWN)
                    eligible(snow, astrBot.value, generation, astrGeneration) &&
                        canRetryChannel && !busy() && maintenance() == MaintenanceState.IDLE
                } ?: return
            } finally {
                AutoReconnectStore.state.compareAndSet(pending, AutoReconnectStatus())
            }
            // A new process starts with an unknown channel. Wait for fresh evidence before the next attempt.
            val result = withTimeoutOrNull(75_000L) {
                observations.first { (snow, bot) ->
                    !eligible(snow, bot, generation, astrGeneration) ||
                        snow.oneBot in setOf(OneBotState.CONNECTED, OneBotState.DISCONNECTED, OneBotState.UNCONFIGURED)
                }
            }
            observationTimedOut = result == null
            val (snow, bot) = result ?: (snowLuma.value to astrBot.value)
            if (!eligible(snow, bot, generation, astrGeneration)) return
            if (observationTimedOut && snow.oneBot == OneBotState.UNKNOWN) continue
            if (snow.oneBot != OneBotState.DISCONNECTED) return
        }
        synchronized(this) {
            coroutine.ensureActive()
            AutoReconnectStore.state.value = AutoReconnectStatus(layer = CHANNEL, attempt = RestartPolicy.DEFAULT_DELAYS.size, exhausted = true)
        }
        ConnectionAlerts(context).notifyGiveUp(CHANNEL)
    }

    fun onProcess(id: ServiceId, previous: ServiceStatus, next: ServiceStatus) {
        // Manual restarts transition the supervisors directly and may never call cancel().
        if (next.process == ProcessState.STOPPED || next.process == ProcessState.STARTING) {
            val status = AutoReconnectStore.state.value
            if (status.layer == CHANNEL || status.layer == id.label) {
                AutoReconnectStore.state.compareAndSet(status, AutoReconnectStatus())
            }
        }
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

    private companion object { const val CHANNEL = "消息通道" }
}
