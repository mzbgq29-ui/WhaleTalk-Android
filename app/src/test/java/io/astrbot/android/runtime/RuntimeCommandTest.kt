package io.astrbot.android.runtime

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.InterruptedIOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RuntimeCommandTest {
    @Test fun timeoutSurvivesAndroidClosingTheOutputPipe() = runBlocking<Unit> {
        val step = "Loading FAISS\n"
        val waitingForOutput = CountDownLatch(1)
        val pipeClosed = CountDownLatch(1)
        val output = object : InputStream() {
            private var sentStep = false

            override fun read(): Int = error("Expected a buffered read")

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (sentStep) {
                    waitingForOutput.countDown()
                    check(pipeClosed.await(5, TimeUnit.SECONDS))
                    throw InterruptedIOException("read interrupted by close() on another thread")
                }
                sentStep = true
                val bytes = step.toByteArray()
                bytes.copyInto(buffer, offset)
                return bytes.size
            }
        }
        val process = object : Process() {
            override fun getInputStream() = output
            override fun getErrorStream() = byteArrayOf().inputStream()
            override fun getOutputStream() = ByteArrayOutputStream()
            override fun isAlive() = pipeClosed.count != 0L
            override fun exitValue() = if (isAlive) throw IllegalThreadStateException() else 143
            override fun waitFor() = error("Expected a timed wait")
            override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
                check(waitingForOutput.await(5, TimeUnit.SECONDS))
                return !isAlive
            }
            override fun destroy() = pipeClosed.countDown()
        }

        val timeout = IllegalStateException("Command timed out")
        val failure = runCatching {
            runRuntimeCommand(1, { process }) { captured ->
                assertEquals(step, captured)
                timeout
            }
        }.exceptionOrNull()
        assertEquals(timeout.javaClass, failure?.javaClass)
        assertEquals(timeout.message, failure?.message)
        assertFalse(process.isAlive)
    }
}
