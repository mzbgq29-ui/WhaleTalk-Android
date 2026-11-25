package io.astrbot.android.core

import org.junit.Assert.*
import org.junit.Test

class ResourceCpuPolicyTest {
    @Test fun readsUserAndSystemTicksAndPercent() {
        val stat = "10 (proot) S 1 10 10 0 0 0 0 0 0 0 100 50 0 0 20 0 1 0 999 0"
        assertEquals(150L, ResourceCpuPolicy.cpuTicks(10, stat))
        val percent = ResourceCpuPolicy.percent(100, 1_000_000_000L, 100, 8)
        assertNotNull(percent)
        assertEquals(12.5, percent!!, 0.01)
        assertNull(ResourceCpuPolicy.percent(-1, 1_000_000_000L, 100, 8))
    }
}
