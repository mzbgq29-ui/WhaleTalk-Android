package io.astrbot.android.core

import org.junit.Assert.*
import org.junit.Test

class PrepareProgressReadingTest {
    @Test fun readsWrittenAndPackedMegabytes() {
        assertEquals(20L * 1024 * 1024 to 140L * 1024 * 1024,
            PrepareProgressReading.bytesFromMessage("正在解压 node（已写 20 / 140 MB）"))
        assertEquals(12L * 1024 * 1024 to 143L * 1024 * 1024,
            PrepareProgressReading.bytesFromMessage("正在校验内置包 12/143 MB"))
        assertNull(PrepareProgressReading.bytesFromMessage("正在切换运行层目录"))
    }

    @Test fun estimatesRemainingTimeAfterEnoughProgress() {
        val remaining = PrepareProgressReading.remainingMillis(0, 40_000, 0.50f)
        assertEquals(40_000L, remaining)
        assertNull(PrepareProgressReading.remainingMillis(0, 1_000, 0.50f))
        assertNull(PrepareProgressReading.remainingMillis(0, 40_000, 0.01f))
        assertTrue(PrepareProgressReading.formatEta(90_000).contains("1 分"))
    }
}
