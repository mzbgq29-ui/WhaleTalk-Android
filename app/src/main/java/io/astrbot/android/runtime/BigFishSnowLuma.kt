// SPDX-License-Identifier: MIT
// Copyright (c) 2026 mzbgq
package io.astrbot.android.runtime

import android.content.Context
import android.os.StatFs
import android.system.Os
import io.astrbot.android.core.ServiceId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.GZIPInputStream

/** Private platform distribution. Vendor JavaScript, authentication and agreements are preserved. */
class BigFishSnowLuma(private val context: Context) {
    private val runtime = BootstrapRuntime(context)
    private val root = File(context.filesDir, "manager/extensions/snowluma")
    private val state = File(context.filesDir, "persistent/snowluma")
    private val asset = "snowluma/arm64-v8a"
    private val commandPreferences get() = context.getSharedPreferences("bigfish_qq_commands", Context.MODE_PRIVATE)
    fun statusCommandEnabled(): Boolean = commandPreferences.getBoolean("status_enabled", true)
    fun setStatusCommandEnabled(enabled: Boolean) {
        check(commandPreferences.edit().putBoolean("status_enabled", enabled).commit()) { "状态指令设置未能保存" }
    }
    fun fullDesktopEnabled(): Boolean = commandPreferences.getBoolean("full_desktop", false)
    fun setFullDesktopEnabled(enabled: Boolean) {
        check(commandPreferences.edit().putBoolean("full_desktop", enabled).commit()) { "画面设置未能保存" }
    }
    fun versionLockEnabled(): Boolean = QqHotUpdateGuard.lockEnabled(context)
    fun setVersionLockEnabled(enabled: Boolean) = QqHotUpdateGuard.setLockEnabled(context, enabled)
    private fun shaFile(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun log(message: String) {
        RuntimeStore.log(context, LogCategory.MANAGER).append("${java.time.Instant.now()} $message")
    }

    suspend fun prepare(progress: (String, Float?) -> Unit) = withContext(Dispatchers.IO) {
        fun report(message: String, fraction: Float? = null) {
            log(message)
            progress(message, fraction)
        }
        try {
            withTimeout(PREPARE_TIMEOUT_MS) { prepareInner(::report) }
        } catch (failure: TimeoutCancellationException) {
            throw IllegalStateException("准备 SnowLuma 运行层超时（超过 15 分钟）。请检查可用存储后重试；未完成的解压文件已保留，下次会继续。")
        }
    }

    private suspend fun prepareInner(report: (String, Float?) -> Unit) {
        check(runtime.abi == "arm64-v8a") { "此 SnowLuma 私用版本需要 ARM64 手机" }
        val desktop = DesktopRepository(context)
        if (!desktop.configuration().installed) desktop.install { report(it, 0.02f) }
        desktop.prepareAccess()
        if (!desktop.configuration().qqInstalled) {
            report("正在离线更新 SnowLuma 使用的 QQ", 0.04f)
            desktop.installQq { report(it, 0.04f) }
        }
        val loginPage = context.assets.open("runtime/bigfish-login.html").use { it.readBytes() }
        val pageTarget = File(runtime.rootfs, "usr/share/novnc/bigfish-login.html")
        if (!pageTarget.isFile || !pageTarget.readBytes().contentEquals(loginPage)) {
            pageTarget.writeBytes(loginPage); Os.chmod(pageTarget.path, 384)
        }
        report("正在准备 SnowLuma ARM64 运行层", 0.06f)
        root.mkdirs(); Os.chmod(root.path, 448)
        val manifest = context.assets.open("$asset/manifest.json").bufferedReader().use { JSONObject(it.readText()) }
        val unpacked = manifest.getLong("unpackedBytes")
        val needed = unpacked * 2 + 64L * 1024 * 1024
        val available = StatFs(context.filesDir.path).availableBytes
        check(available >= needed) {
            "准备 SnowLuma 运行层需要约 ${needed / 1024 / 1024} MB 可用空间，当前仅 ${available / 1024 / 1024} MB"
        }
        val target = File(root, "runtime")
        val installed = DurableFiles.read(File(target, ".bigfish-runtime.json"))
        if (installed?.optString("sha256") != manifest.getString("sha256") ||
            listOf("node", "index.mjs", "native/snowluma-linux-arm64.so", "native/snowluma-linux-arm64.node").any { !File(target, it).isFile }) {
            val digest = MessageDigest.getInstance("SHA-256")
            val packedBytes = runCatching { context.assets.openFd("$asset/runtime.tar.bin").use { it.length } }.getOrDefault(-1L)
            var read = 0L
            var lastReport = 0L
            context.assets.open("$asset/runtime.tar.bin").use { input ->
                val buffer = ByteArray(131072)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buffer); if (n < 0) break
                    digest.update(buffer, 0, n); read += n
                    if (packedBytes > 0 && read - lastReport >= 2L * 1024 * 1024) {
                        lastReport = read
                        val fraction = 0.06f + 0.14f * (read.toFloat() / packedBytes.toFloat())
                        report("正在校验内置包 ${read / 1024 / 1024}/${packedBytes / 1024 / 1024} MB", fraction)
                    }
                }
            }
            check(digest.digest().joinToString("") { "%02x".format(it) } == manifest.getString("sha256")) { "SnowLuma 内置资源校验失败" }
            val staging = File(root, "runtime.installing")
            if (!staging.isDirectory) {
                DurableFiles.delete(staging) { currentCoroutineContext().ensureActive() }
                check(staging.mkdirs())
            }
            val entries = manifest.getJSONObject("files")
            val expected = entries.keys().asSequence().toSet()
            val seen = mutableSetOf<String>()
            var total = 0L
            try {
                context.assets.open("$asset/runtime.tar.bin").use { raw -> GZIPInputStream(raw).use { gzip -> TarArchiveInputStream(gzip).use { tar ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val entry = tar.nextTarEntry ?: break
                        val name = entry.name
                        check(entry.isFile && !entry.isSymbolicLink && !entry.isLink && name in expected && seen.add(name)) { "SnowLuma 资源目录不符合预期" }
                        val out = File(staging, name)
                        check(out.canonicalPath.startsWith(staging.canonicalPath + "/"))
                        val spec = entries.getJSONObject(name)
                        check(entry.size == spec.getLong("bytes") && entry.size <= 160L * 1024 * 1024)
                        out.parentFile!!.mkdirs()
                        val alreadyValid = out.isFile && out.length() == entry.size && shaFile(out) == spec.getString("sha256")
                        if (alreadyValid) {
                            val buffer = ByteArray(65536)
                            var skipped = 0L
                            while (skipped < entry.size) {
                                currentCoroutineContext().ensureActive()
                                val n = tar.read(buffer, 0, minOf(buffer.size.toLong(), entry.size - skipped).toInt())
                                if (n < 0) break
                                skipped += n
                            }
                            check(skipped == entry.size)
                            total += entry.size
                        } else {
                            val hash = MessageDigest.getInstance("SHA-256")
                            out.outputStream().use { stream ->
                                val buffer = ByteArray(65536); var written = 0L
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val n = tar.read(buffer); if (n < 0) break
                                    written += n; total += n
                                    check(written <= entry.size && total <= unpacked)
                                    stream.write(buffer, 0, n); hash.update(buffer, 0, n)
                                }
                                check(written == entry.size)
                            }
                            check(hash.digest().joinToString("") { "%02x".format(it) } == spec.getString("sha256"))
                            Os.chmod(out.path, if (name in listOf("node", "launcher.sh")) 448 else 384)
                        }
                        val fraction = 0.20f + 0.70f * (total.toFloat() / unpacked.toFloat().coerceAtLeast(1f))
                        report("正在解压 $name（已写 ${total / 1024 / 1024} / ${unpacked / 1024 / 1024} MB）", fraction)
                    }
                } } }
                check(seen.size == entries.length() && total == unpacked)
                DurableFiles.write(File(staging, ".bigfish-runtime.json"), manifest)
                report("正在切换运行层目录", 0.92f)
                val previous = File(root, "runtime.previous")
                DurableFiles.delete(previous) { currentCoroutineContext().ensureActive() }
                if (target.exists()) DurableFiles.move(target, previous)
                DurableFiles.move(staging, target)
                DurableFiles.delete(previous) { currentCoroutineContext().ensureActive() }
            } catch (failure: CancellationException) {
                throw failure
            }
        }
        report("正在清理旧文件并同步消息通道", 0.95f)
        LegacyRuntimeCleanup(context).prepare()
        io.astrbot.android.core.SnowStateMigration.migrate(File(context.filesDir, "persistent"), DurableFiles::move)
        LegacySnowPaths.migrate(File(context.filesDir, "persistent"))
        Os.chmod(state.path, 448)
        val secret = File(state, "bootstrap-password")
        if (!secret.exists()) {
            val password = "Az7!" + ByteArray(32).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
            val atomic = android.util.AtomicFile(secret); val stream = atomic.startWrite()
            try { Os.fchmod(stream.fd, 384); stream.write(password.toByteArray()); atomic.finishWrite(stream) }
            catch (failure: Throwable) { atomic.failWrite(stream); throw failure }
        }
        val endpoint = requireNotNull(RuntimeConfiguration(context).read(ServiceId.SNOWLUMA).endpoint)
        DurableFiles.write(File(state, "manager.json"), JSONObject().put("port", endpoint.port)
            .put("desktopGeometry", if (fullDesktopEnabled()) "1280x800" else "720x960"))
        Os.chmod(File(state, "manager.json").path, 384)
        syncOneBot()
        val script = context.assets.open("runtime/bigfish_snowluma.py").use { it.readBytes() }
        val launcher = File(root, "launch.py")
        if (!launcher.isFile || !launcher.readBytes().contentEquals(script)) { launcher.writeBytes(script); Os.chmod(launcher.path, 448) }
        val guard = QqHotUpdateGuard.applyIfLocked(context)
        guard?.message?.let { report(it, 0.98f) }
        report("SnowLuma 运行层已准备完成", 1f)
    }

    private fun syncOneBot() {
        val bot = JSONObject(File(context.filesDir, "persistent/astrbot/data/cmd_config.json").readText())
        val platforms = bot.optJSONArray("platform") ?: JSONArray()
        val platform = (0 until platforms.length()).mapNotNull { platforms.optJSONObject(it) }
            .firstOrNull { QqPlatform.matches(it.optString("id")) && it.optString("type") == "aiocqhttp" }
            ?: error("请先完成机器人设置，创建本机 QQ 消息通道")
        val port = platform.optInt("ws_reverse_port", 6199)
        check(port in 1024..65535)
        val client = JSONObject().put("name", "WhaleTalk-AstrBot").put("enabled", platform.optBoolean("enable", true))
            .put("url", "ws://127.0.0.1:$port/ws").put("role", "Universal")
            .put("accessToken", platform.optString("ws_reverse_token")).put("messageFormat", "array")
            .put("reportSelfMessage", false).put("reconnectIntervalMs", 5000)
        val configDir = File(state, "config").apply { mkdirs() }
        val files = (listOf(File(configDir, "onebot.json")) + configDir.listFiles().orEmpty()
            .filter { it.name.matches(Regex("onebot_[0-9]{5,20}\\.json")) }).distinctBy { it.path }
        val inheritedStatus = DurableFiles.read(File(configDir, "onebot.json"))?.optJSONObject("statusCommand")
        for (file in files) {
            val document = if (file.isFile) JSONObject(file.readText()) else JSONObject()
            val networks = document.optJSONObject("networks") ?: JSONObject()
                .put("httpServers", JSONArray()).put("httpClients", JSONArray()).put("wsServers", JSONArray())
            val clients = networks.optJSONArray("wsClients") ?: JSONArray()
            val retained = JSONArray()
            for (i in 0 until clients.length()) { val value = clients.getJSONObject(i); if (value.optString("name") !in setOf("BigFish-AstrBot", "WhaleTalk-AstrBot")) retained.put(value) }
            retained.put(JSONObject(client.toString()))
            networks.put("wsClients", retained); document.put("networks", networks)
            document.put("statusCommand", SnowStatusCommand.configuration(document.optJSONObject("statusCommand"), statusCommandEnabled(),
                if (document.optString("mode") == "snapshot") null else inheritedStatus))
            DurableFiles.write(file, document); Os.chmod(file.path, 384)
        }
    }

    fun password(): String = File(state, "bootstrap-password").readText().trim()
    companion object {
        const val COMMAND = "exec /usr/bin/python3 /manager/extensions/snowluma/launch.py"
        const val PREPARE_TIMEOUT_MS = 15 * 60 * 1000L
    }
}
