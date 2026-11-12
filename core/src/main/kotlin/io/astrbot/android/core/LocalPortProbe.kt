package io.astrbot.android.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/** A bounded TCP check of a local listener, without sending application data. */
object LocalPortProbe {
    enum class Status { OPEN, CLOSED, TIMEOUT, UNAVAILABLE }
    data class Result(val status: Status, val elapsedMillis: Long)

    suspend fun check(port: Int, host: String = "127.0.0.1", timeoutMillis: Int = 1000): Result {
        require(port in 1..65535 && timeoutMillis in 1..5000)
        require(host in setOf("127.0.0.1", "::1", "localhost")) { "Only local service checks are supported" }
        return withContext(Dispatchers.IO) {
            currentCoroutineContext().ensureActive()
            val started = System.nanoTime()
            val status = try {
                val address = InetAddress.getByName(host)
                require(address.isLoopbackAddress)
                Socket().use { it.connect(InetSocketAddress(address, port), timeoutMillis) }
                Status.OPEN
            } catch (_: SocketTimeoutException) {
                Status.TIMEOUT
            } catch (_: ConnectException) {
                Status.CLOSED
            } catch (_: IOException) {
                Status.UNAVAILABLE
            }
            currentCoroutineContext().ensureActive()
            Result(status, (System.nanoTime() - started) / 1_000_000)
        }
    }
}
