package io.astrbot.android.runtime

import android.content.Context
import android.app.ActivityManager
import android.os.Process
import android.os.StatFs
import android.system.Os
import io.astrbot.android.core.MemoryReading
import io.astrbot.android.core.ResourceCpuPolicy
import io.astrbot.android.core.ResourceMemoryPolicy
import kotlinx.coroutines.*
import java.io.File

data class ResourceSnapshot(
    val memory: MemoryReading = MemoryReading(null, null, 0, 0, false),
    val storageAvailableBytes: Long? = null,
    val checkedAtMillis: Long? = null,
    val physicalMemoryBytes: Long? = null,
    val cpuPercent: Double? = null,
) {
    val memoryPercent: Double? get() = ResourceMemoryPolicy.percentOfPhysical(memory.totalKiB, physicalMemoryBytes)
}

/** Same-UID PSS excludes isolated WebView renderers and does not double-count shared resident pages. */
class ResourceMonitor(private val context: Context) {
    private data class Census(val processes: Map<Int, Long>, val cpuTicks: Map<Int, Long>, val complete: Boolean)

    suspend fun sample(cpuWindowMs: Long = 0): ResourceSnapshot = withContext(Dispatchers.IO) {
        val first = census()
        val cpuStarted = System.nanoTime()
        val readings = mutableMapOf<Int, Long>()
        for (pid in first.processes.keys.sorted()) {
            currentCoroutineContext().ensureActive()
            runCatching { readPss(pid) }.onSuccess { readings[pid] = it }
        }
        val after = census()
        var memory = ResourceMemoryPolicy.summarize(first.processes, after.processes, readings, Process.myPid())
        if (!first.complete || !after.complete) memory = memory.copy(totalKiB = null, complete = false)
        val physicalMemory = runCatching {
            ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }.totalMem
        }.getOrNull()?.takeIf { it > 0 }
        var cpu: Double? = null
        if (cpuWindowMs > 0) {
            val remain = cpuWindowMs - ((System.nanoTime() - cpuStarted) / 1_000_000)
            if (remain > 50) kotlinx.coroutines.delay(remain)
            val later = census()
            val elapsed = System.nanoTime() - cpuStarted
            val common = first.cpuTicks.keys.intersect(later.cpuTicks.keys)
            if (common.isNotEmpty() && elapsed > 0) {
                val delta = common.sumOf { pid -> (later.cpuTicks[pid]!! - first.cpuTicks[pid]!!).coerceAtLeast(0) }
                val hz = runCatching { Os.sysconf(android.system.OsConstants._SC_CLK_TCK) }.getOrNull()?.takeIf { it > 0 } ?: 100
                val cpus = runCatching { Os.sysconf(android.system.OsConstants._SC_NPROCESSORS_ONLN) }.getOrNull()?.takeIf { it > 0 } ?: 1
                cpu = ResourceCpuPolicy.percent(delta, elapsed, hz, cpus.toInt())
            }
        }
        ResourceSnapshot(memory, runCatching { StatFs(context.filesDir.path).availableBytes }.getOrNull(),
            System.currentTimeMillis(), physicalMemory, cpu)
    }

    private suspend fun census(): Census {
        val files = File("/proc").listFiles() ?: return Census(emptyMap(), emptyMap(), false)
        var complete = true
        val processes = mutableMapOf<Int, Long>()
        val cpuTicks = mutableMapOf<Int, Long>()
        for (file in files) {
            currentCoroutineContext().ensureActive()
            val pid = file.name.toIntOrNull() ?: continue
            val uid = runCatching { Os.stat(file.path).st_uid }.getOrNull()
            if (uid == null) { complete = false; continue }
            if (uid != Process.myUid()) continue
            val stat = runCatching { File(file, "stat").readText() }.getOrNull()
            if (stat == null) { complete = false; continue }
            runCatching { ResourceMemoryPolicy.residentStartTicks(pid, stat) }
                .onSuccess { ticks -> if (ticks != null) processes[pid] = ticks }.onFailure { complete = false }
            runCatching { ResourceCpuPolicy.cpuTicks(pid, stat) }.onSuccess { cpuTicks[pid] = it }.onFailure { complete = false }
        }
        return Census(processes, cpuTicks, complete)
    }

    private suspend fun readPss(pid: Int): Long {
        val rollup = File("/proc/$pid/smaps_rollup")
        val single = rollup.exists()
        val file = if (single) rollup else File("/proc/$pid/smaps")
        val coroutine = currentCoroutineContext()
        return file.bufferedReader().use { reader ->
            var characters = 0L
            ResourceMemoryPolicy.pssKiB(reader.lineSequence().map { line ->
                coroutine.ensureActive()
                characters += line.length
                require(characters <= 8L * 1024 * 1024) { "Memory map exceeds sampling budget" }
                line
            }, single)
        }
    }
}
