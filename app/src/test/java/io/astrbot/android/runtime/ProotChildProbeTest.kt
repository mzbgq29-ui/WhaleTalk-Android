package io.astrbot.android.runtime

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProotChildProbeTest {
    @Test fun passesImmediatelyWhenInitialProbeSucceeds() = runBlocking {
        val attempts = mutableListOf<Boolean>()
        val result = probeProotChildProcess(initialDisableSeccomp = false) { disableSeccomp ->
            attempts += disableSeccomp
            CommandResult(0, "child-ok\n")
        }
        assertEquals(listOf(false), attempts)
        assertEquals(ProotChildProbeResult.Passed(disableSeccomp = false), result)
    }

    @Test fun retriesWithoutSeccompAndSucceeds() = runBlocking {
        val attempts = mutableListOf<Boolean>()
        val result = probeProotChildProcess(initialDisableSeccomp = false) { disableSeccomp ->
            attempts += disableSeccomp
            if (!disableSeccomp) CommandResult(-1, "Timed out after 15s")
            else CommandResult(0, "child-ok\n")
        }
        assertEquals(listOf(false, true), attempts)
        assertEquals(ProotChildProbeResult.Passed(disableSeccomp = true), result)
    }

    @Test fun doesNotRetryTwiceWhenSeccompAlreadyDisabled() = runBlocking {
        val attempts = mutableListOf<Boolean>()
        val result = probeProotChildProcess(initialDisableSeccomp = true) { disableSeccomp ->
            attempts += disableSeccomp
            CommandResult(-1, "Timed out after 15s")
        }
        assertEquals(listOf(true), attempts)
        val failed = result as ProotChildProbeResult.Failed
        assertFalse(failed.retriedWithoutSeccomp)
        assertEquals("Timed out after 15s", failed.detail)
    }

    @Test fun failsWhenBothSeccompAndNoSeccompFail() = runBlocking {
        val attempts = mutableListOf<Boolean>()
        val result = probeProotChildProcess(initialDisableSeccomp = false) { disableSeccomp ->
            attempts += disableSeccomp
            if (!disableSeccomp) CommandResult(-1, "first timeout")
            else CommandResult(-1, "no-seccomp timeout")
        }
        assertEquals(listOf(false, true), attempts)
        val failed = result as ProotChildProbeResult.Failed
        assertTrue(failed.retriedWithoutSeccomp)
        assertEquals("no-seccomp timeout", failed.detail)
    }
}
