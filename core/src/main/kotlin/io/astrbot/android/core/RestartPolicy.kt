package io.astrbot.android.core

sealed interface RestartDecision {
    data object Suppressed : RestartDecision
    data object Exhausted : RestartDecision
    data class Retry(val delayMillis: Long) : RestartDecision
}

/** Uses a monotonic clock supplied by the caller, independent of wall-clock changes. */
class RestartPolicy(
    private val delaysMillis: List<Long> = DEFAULT_DELAYS,
    private val windowMillis: Long = DEFAULT_WINDOW_MS,
) {
    private val attempts = ArrayDeque<Long>()
    init {
        require(delaysMillis.isNotEmpty() && delaysMillis.size <= 30)
        require(delaysMillis.all { it > 0 })
        require(windowMillis > 0)
    }

    val attemptCount: Int
        @Synchronized get() = attempts.size

    @Synchronized
    fun onUnexpectedExit(nowMillis: Long, desiredRunning: Boolean, maintenance: MaintenanceState): RestartDecision {
        if (!desiredRunning || maintenance != MaintenanceState.IDLE) return RestartDecision.Suppressed
        while (attempts.isNotEmpty() && nowMillis - attempts.first() >= windowMillis) attempts.removeFirst()
        if (attempts.size >= delaysMillis.size) return RestartDecision.Exhausted
        val delay = delaysMillis[attempts.size]
        attempts.addLast(nowMillis)
        return RestartDecision.Retry(delay)
    }

    @Synchronized fun reset() = attempts.clear()

    companion object {
        val DEFAULT_DELAYS = listOf(5_000L, 15_000L, 60_000L)
        const val DEFAULT_WINDOW_MS = 15 * 60 * 1000L
    }
}
