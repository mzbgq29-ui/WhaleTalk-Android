package io.astrbot.android.runtime

import android.content.Context
import android.system.Os
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** Offline deployment, called before AstrBot starts under the manager operation lock. */
internal class BundledCompanionPlugins(private val context: Context) {
    private val data = File(context.filesDir, "persistent/astrbot/data")
    private val settings = File(data, "bigfish-companion-settings.json")
    fun readSettings() = CompanionSettings.read(DurableFiles.read(settings))
    fun saveSettings(options: CompanionSettings) = DurableFiles.write(settings, options.json())
    fun installed(id: String) = File(data, "plugins/$id/metadata.yaml").isFile
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    suspend fun prepare(progress: (String) -> Unit) = withContext(Dispatchers.IO) {
        val manifest = context.assets.open("bigfish/bundled-plugins.json").bufferedReader().use { JSONObject(it.readText()) }
        val specs = manifest.getJSONObject("files")
        val names = specs.keys().asSequence().toList()
        val components = names.map { it.split('/').take(2).joinToString("/") }.distinct()
        fun destination(component: String): File = File(data, component.replaceFirst("python/", "site-packages/"))
        for (dir in listOf(data, File(data, "plugins"), File(data, "site-packages"))) {
            check(!Files.isSymbolicLink(dir.toPath()) && (!dir.exists() || dir.isDirectory)) { "内置插件安装路径异常" }
            check(dir.isDirectory || dir.mkdirs()) { "无法创建内置插件目录" }
        }
        val missing = components.filter { !destination(it).exists() }
        for (component in components) check(!Files.isSymbolicLink(destination(component).toPath())) { "已有插件路径为链接，已保留原文件，请先检查" }
        if (missing.isNotEmpty()) {
            progress("正在离线准备记忆与拟人插件")
            check(data.usableSpace >= manifest.getLong("unpackedBytes") + 64L * 1024 * 1024) { "准备内置插件需要更多可用存储" }
            val bytes = context.assets.open("bigfish/bundled-plugins.zip").use { it.readBytes() }
            check(bytes.size.toLong() == manifest.getLong("bytes") && hash(bytes) == manifest.getString("sha256")) { "内置插件资源校验失败" }
            val staging = File(context.filesDir, "manager/bundled-plugins.installing")
            DurableFiles.delete(staging); check(staging.mkdirs())
            val seen = mutableSetOf<String>()
            try {
                ZipInputStream(bytes.inputStream()).use { zip ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val entry = zip.nextEntry ?: break
                        check(!entry.isDirectory && entry.name in names && seen.add(entry.name)) { "内置插件资源目录异常" }
                        val target = File(staging, entry.name)
                        check(target.canonicalPath.startsWith(staging.canonicalPath + "/"))
                        val spec = specs.getJSONObject(entry.name)
                        check(spec.getLong("bytes") in 0..16L * 1024 * 1024)
                        val content = zip.readBytesBounded(spec.getInt("bytes"))
                        check(hash(content) == spec.getString("sha256")) { "内置插件文件校验失败" }
                        target.parentFile!!.mkdirs(); target.writeBytes(content); Os.chmod(target.path, 384)
                    }
                }
                check(seen.size == names.size)
                for (component in missing) {
                    val source = File(staging, component)
                    if (component.startsWith("plugins/")) DurableFiles.write(File(source, ".bigfish-bundled.json"),
                        JSONObject().put("bundleSHA256", manifest.getString("sha256")))
                    DurableFiles.move(source, destination(component))
                }
            } finally { DurableFiles.delete(staging) }
        }
        // A same-name plugin already installed by the user is preserved and not configured by this installer.
        val options = readSettings()
        val botFile = File(data, "cmd_config.json")
        val bot = DurableFiles.read(botFile) ?: JSONObject()
        for (id in listOf(CompanionConfiguration.MEMORY, CompanionConfiguration.GROUP)) {
            if (!File(data, "plugins/$id/.bigfish-bundled.json").isFile) continue
            val file = File(data, "config/${id}_config.json")
            val previous = DurableFiles.read(file) ?: JSONObject()
            val next = if (id == CompanionConfiguration.MEMORY) CompanionConfiguration.memory(previous, options)
                else CompanionConfiguration.groupChat(previous, bot, options)
            DurableFiles.write(file, next)
        }
        if (botFile.isFile) DurableFiles.write(botFile, CompanionConfiguration.bot(bot, options))
    }
    private fun ZipInputStream.readBytesBounded(expected: Int): ByteArray {
        val bytes = ByteArray(expected)
        var offset = 0
        while (offset < expected) { val count = read(bytes, offset, expected - offset); check(count > 0); offset += count }
        check(read() == -1)
        return bytes
    }
}
