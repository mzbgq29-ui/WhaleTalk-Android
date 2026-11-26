package io.astrbot.android.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

data class CommandResult(val exitCode: Int, val output: String)

private const val MAX_OUTPUT_CHARS = 1_048_576

internal suspend fun runRuntimeCommand(
    timeoutSeconds: Long,
    start: () -> Process,
    timeoutFailure: (String) -> Throwable,
): CommandResult = withContext(Dispatchers.IO) {
    // Android closes the process pipes in destroy(). A concurrent read failure
    // during teardown must not replace the timeout or cancellation of the command.
    supervisorScope {
        val process = start()
        val captured = StringBuffer()
        try {
            val drain = async {
                process.inputStream.bufferedReader().use { reader ->
                    val buffer = CharArray(4096)
                    while (true) {
                        val count = reader.read(buffer)
                        if (count < 0) break
                        val remaining = MAX_OUTPUT_CHARS - captured.length
                        if (remaining > 0) captured.append(buffer, 0, count.coerceAtMost(remaining))
                    }
                }
            }
            if (!runInterruptible { process.waitFor(timeoutSeconds, TimeUnit.SECONDS) }) {
                throw timeoutFailure(captured.toString().takeLast(3000))
            }
            drain.await()
            CommandResult(process.exitValue(), captured.toString())
        } finally {
            if (process.isAlive) {
                process.destroy()
                if (!process.waitFor(8, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    process.waitFor(3, TimeUnit.SECONDS)
                }
            }
        }
    }
}
