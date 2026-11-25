package io.astrbot.android.core

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

class AuditRegressionTest {
    @Test fun phonePortraitZoomAlwaysEnlargesAndKeepsCenter() {
        val fitted = RfbViewport(720, 960, 1080, 1800, true)
        val zoomed = fitted.copy(fit = false)
        assertEquals(fitted.scale * 2f, zoomed.scale, .001f)
        assertEquals(360 to 480, fitted.remote(540f, 900f))
        assertEquals(360 to 480, zoomed.remote(540f, 900f))
        assertNotNull(zoomed.copy(panX = 99999f, panY = -99999f).remote(1080f, 1800f, true))
    }
    @Test fun viewportLetterboxEdgesAndZoomStayWithinFramebuffer() {
        val fitted = RfbViewport(320, 200, 320, 400, true)
        assertNull(fitted.remote(0f, 99f))
        assertEquals(0 to 0, fitted.remote(0f, 100f))
        assertEquals(319 to 199, fitted.remote(319.9f, 299.9f))
        assertEquals(319 to 199, fitted.remote(999f, 999f, true))
        val zoomed = RfbViewport(1280, 800, 320, 400, false)
        assertEquals(480 to 200, zoomed.remote(0f, 0f))
        val panned = zoomed.copy(panX = -999f, panY = 99999f)
        assertEquals(0 to 400, panned.remote(0f, 0f))
    }
    @Test fun legacyStateMovesIntoPortableBackupScopeAndIsIdempotent() {
        val root = Files.createTempDirectory("snow-migration").toFile()
        try {
            val old = root.resolve("napcat/snowluma").apply { mkdirs() }
            old.resolve("bootstrap-password").writeText("fixture-password")
            old.resolve("config").mkdir(); old.resolve("config/webui.json").writeText("fixture-hash")
            val target = SnowStateMigration.migrate(root)
            assertEquals(root.resolve("snowluma"), target)
            assertEquals("fixture-password", target.resolve("bootstrap-password").readText())
            assertEquals("fixture-hash", target.resolve("config/webui.json").readText())
            assertEquals(target, SnowStateMigration.migrate(root))
            assertFalse(root.resolve("napcat").exists())
            // Imported backup wins; previous local account is preserved separately.
            old.mkdirs(); old.resolve("config.json").writeText("old-account")
            SnowStateMigration.migrate(root)
            assertEquals("old-account", root.resolve("manager/qq-state-before-migration/config.json").readText())
            assertEquals("fixture-password", target.resolve("bootstrap-password").readText())
        } finally { root.deleteRecursively() }
    }
    @Test fun migrationRejectsSymlinkedState() {
        val root = Files.createTempDirectory("snow-link").toFile()
        try {
            root.resolve("napcat").mkdir()
            Files.createSymbolicLink(root.resolve("napcat/config").toPath(), root.toPath())
            assertTrue(runCatching { SnowStateMigration.migrate(root) }.isFailure)
        } finally { root.deleteRecursively() }
    }
    @Test fun cancellingStartupUnblocksSupervisorAndAllowsAnotherStart() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val entered = CompletableDeferred<Unit>(); val count = AtomicInteger()
        val supervisor = ProcessSupervisor(ServiceId.ASTRBOT, scope, {
            count.incrementAndGet(); entered.complete(Unit); awaitCancellation()
        }, {})
        try {
            val request = launch { supervisor.start() }
            withTimeout(2000) { entered.await() }
            withTimeout(2000) { request.cancelAndJoin(); supervisor.stop() }
            assertEquals(ProcessState.STOPPED, supervisor.state.value.process)
            assertFalse(supervisor.state.value.desiredRunning)
            assertEquals(1, count.get())
        } finally { supervisor.close(); scope.cancel() }
    }
}
