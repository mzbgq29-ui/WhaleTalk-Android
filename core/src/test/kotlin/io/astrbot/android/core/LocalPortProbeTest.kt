package io.astrbot.android.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket

class LocalPortProbeTest {
    @Test fun detectsListenerWithoutSendingApplicationData() = runBlocking {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val result = LocalPortProbe.check(server.localPort)
            assertEquals(LocalPortProbe.Status.OPEN, result.status)
            server.soTimeout = 1000
            server.accept().use { connection ->
                connection.soTimeout = 1000
                assertEquals(-1, connection.getInputStream().read())
            }
        }
    }

    @Test fun closedListenerIsReportedWithoutAnException() = runBlocking {
        val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val result = LocalPortProbe.check(port)
        assertEquals(LocalPortProbe.Status.CLOSED, result.status)
        assertTrue(result.elapsedMillis >= 0)
    }

    @Test fun rejectsRemoteHostsAndInvalidSettings() = runBlocking {
        for (settings in listOf(Triple(80, "example.com", 1000), Triple(0, "127.0.0.1", 1000), Triple(80, "127.0.0.1", 0))) {
            try {
                LocalPortProbe.check(settings.first, settings.second, settings.third)
                throw AssertionError("Invalid probe must fail before connecting")
            } catch (_: IllegalArgumentException) { }
        }
    }
}
