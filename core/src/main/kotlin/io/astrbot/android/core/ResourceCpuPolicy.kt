package io.astrbot.android.core

/** CPU ticks come from /proc/<pid>/stat utime+stime. Child processes are counted separately. */
object ResourceCpuPolicy {
    fun cpuTicks(pid: Int, stat: String): Long {
        val fields = ResourceMemoryPolicy.statFields(pid, stat)
        val user = fields[11].toLong().also { require(it >= 0) }
        val system = fields[12].toLong().also { require(it >= 0) }
        return Math.addExact(user, system)
    }

    fun percent(deltaTicks: Long, deltaWallNanos: Long, hz: Long, cpus: Int): Double? {
        if (deltaTicks < 0 || deltaWallNanos <= 0 || hz <= 0 || cpus <= 0) return null
        val percent = deltaTicks.toDouble() * 1_000_000_000.0 * 100.0 / (hz.toDouble() * deltaWallNanos * cpus)
        return percent.takeIf { it.isFinite() && it in 0.0..100.0 * cpus && it <= 400.0 }
    }
}
