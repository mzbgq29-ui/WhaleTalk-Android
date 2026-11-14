package io.astrbot.android.core

import java.util.Locale

/** Parses prepare-phase text and estimates remaining time from a monotonic clock. */
object PrepareProgressReading {
    private val bytes = Regex("""(\d+)\s*/\s*(\d+)\s*MB""", RegexOption.IGNORE_CASE)

    fun bytesFromMessage(message: String): Pair<Long, Long>? {
        val match = bytes.find(message) ?: return null
        val processedMb = match.groupValues[1].toLongOrNull() ?: return null
        val totalMb = match.groupValues[2].toLongOrNull() ?: return null
        if (processedMb < 0 || totalMb <= 0 || processedMb > totalMb * 4) return null
        return processedMb * 1024L * 1024L to totalMb * 1024L * 1024L
    }

    fun remainingMillis(startedAtElapsed: Long, nowElapsed: Long, fraction: Float): Long? {
        if (!fraction.isFinite() || fraction < 0.02f || fraction > 0.99f) return null
        val elapsed = nowElapsed - startedAtElapsed
        if (elapsed < 3_000L) return null
        val remaining = (elapsed * (1.0 - fraction) / fraction).toLong()
        return remaining.takeIf { it in 0..12 * 60 * 60 * 1000L }
    }

    fun formatBytes(bytes: Long, locale: Locale = Locale.CHINA): String {
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 10) String.format(locale, "%.0f MB", mb) else String.format(locale, "%.1f MB", mb)
    }

    fun formatEta(remainingMillis: Long, locale: Locale = Locale.CHINA): String {
        val totalSeconds = (remainingMillis / 1000).coerceAtLeast(1)
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return when {
            hours > 0 -> String.format(locale, "大约剩余 %d 小时 %d 分钟", hours, minutes)
            minutes > 0 -> String.format(locale, "大约剩余 %d 分 %d 秒", minutes, seconds)
            else -> String.format(locale, "大约剩余 %d 秒", seconds)
        }
    }
}
