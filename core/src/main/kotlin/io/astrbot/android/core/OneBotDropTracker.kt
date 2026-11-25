package io.astrbot.android.core

/** Tracks a confirmed connection for one pair of service processes. */
class OneBotDropTracker {
    private var generation: Pair<Long, Long>? = null
    private var armed = false

    fun observe(state: OneBotState, snowGeneration: Long, astrGeneration: Long, canRecover: Boolean): Boolean {
        val currentGeneration = snowGeneration to astrGeneration
        if (generation != currentGeneration) {
            generation = currentGeneration
            armed = false
        }
        when (state) {
            OneBotState.CONNECTED -> armed = true
            OneBotState.UNCONFIGURED -> armed = false
            OneBotState.DISCONNECTED -> if (armed && canRecover) {
                armed = false
                return true
            }
            else -> Unit
        }
        return false
    }
}
