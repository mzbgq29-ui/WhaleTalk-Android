package io.astrbot.android.runtime

import io.astrbot.android.checkLocalized
import io.astrbot.android.requireLocalized
import io.astrbot.android.core.SharedBackupPolicy
import android.system.Os
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class SharedBackupInfo(
    val id: String, val createdAt: Long, val bytes: Long, val count: Int,
    val versions: Map<String, String>, val automatic: Boolean,
)

/** Zip archive of QQ login (minus caches), AstrBot data/plugins and SnowLuma config. */
internal class SharedBackupArchive {
    private data class Entry(val name: String, val kind: String, val bytes: Long = 0, val sha256: String = "",
                             val executable: Boolean = false, val target: String = "") {
        fun json() = JSONObject().put("name", name).put("kind", kind).put("bytes", bytes).put("sha256", sha256)
            .put("executable", executable).put("target", target)
    }

    fun peekFormat(archive: File): String? = runCatching {
        java.util.zip.ZipFile(archive).use { zip ->
            val entry = zip.getEntry("manifest.json") ?: return null
            require(entry.size in 1..MAX_MANIFEST)
            JSONObject(zip.getInputStream(entry).bufferedReader().readText()).optString("format")
        }
    }.getOrNull()

    suspend fun write(source: File, destination: File, versions: RuntimeVersions, automatic: Boolean): SharedBackupInfo {
        val entries = scan(source)
        val manifest = JSONObject().put("schema", 1).put("format", FORMAT).put("id", UUID.randomUUID().toString())
            .put("createdAt", System.currentTimeMillis()).put("versions", versions.json())
            .put("scopes", JSONArray(SharedBackupPolicy.SCOPES)).put("qqSessionsIncluded", true)
            .put("cachesExcluded", true).put("automatic", automatic)
            .put("entries", JSONArray(entries.map { it.json() }))
        val bytes = manifest.toString().toByteArray(Charsets.UTF_8)
        requireLocalized(bytes.size <= MAX_MANIFEST) { "共享备份清单过大" }
        requireLocalized(destination.parentFile!!.usableSpace >= entries.sumOf { it.bytes }.coerceAtMost(MAX_ARCHIVE) + RESERVE) { "共享备份暂存空间不足" }
        val coroutine = currentCoroutineContext()
        var written = 0L
        try {
            FileOutputStream(destination).use { raw ->
                ZipOutputStream(BufferedOutputStream(raw)).use { zip ->
                    zip.putNextEntry(ZipEntry("manifest.json")); zip.write(bytes); zip.closeEntry()
                    entries.filter { it.kind == "file" }.forEach { entry ->
                        coroutine.ensureActive()
                        zip.putNextEntry(ZipEntry("data/${entry.name}"))
                        File(source, entry.name).inputStream().use { input ->
                            checkLocalized(copy(input, zip, entry.bytes) == entry.sha256) { "备份过程中文件已变化，未创建备份" }
                        }
                        zip.closeEntry()
                    }
                    val complete = JSONObject().put("manifestSha256", digest(bytes)).put("entries", entries.size).toString().toByteArray()
                    zip.putNextEntry(ZipEntry("complete.json")); zip.write(complete); zip.closeEntry()
                    zip.finish(); zip.flush(); raw.fd.sync()
                    written = destination.length()
                }
            }
            checkLocalized(scan(source) == entries) { "备份过程中文件已变化，未创建备份" }
            return info(manifest).copy(bytes = written)
        } catch (failure: Throwable) { destination.delete(); throw failure }
    }

    suspend fun read(archive: File, destination: File, versions: RuntimeVersions): SharedBackupInfo {
        requireLocalized(archive.length() in 1..MAX_ARCHIVE) { "无效的共享备份，或大小超过限制" }
        checkLocalized(!destination.exists()) { "共享备份解压目录已存在" }
        try {
            ZipInputStream(BufferedInputStream(archive.inputStream())).use { zip ->
                requireLocalized(zip.nextEntry?.name == "manifest.json") { "这不是鲸语共享备份" }
                val bytes = limited(zip, MAX_MANIFEST)
                val manifest = JSONObject(bytes.toString(Charsets.UTF_8))
                val entries = parse(manifest, versions)
                check(destination.mkdirs())
                entries.filter { it.kind == "directory" }.sortedBy { it.name.count { c -> c == '/' } }.forEach {
                    check(File(destination, it.name).mkdir() || File(destination, it.name).isDirectory)
                    Os.chmod(File(destination, it.name).path, 448)
                }
                entries.filter { it.kind == "file" }.forEach { entry ->
                    currentCoroutineContext().ensureActive()
                    requireLocalized(zip.nextEntry?.name == "data/${entry.name}") { "共享备份文件缺失或顺序不正确" }
                    val file = File(destination, entry.name)
                    FileOutputStream(file).use { output ->
                        requireLocalized(copy(zip, output, entry.bytes) == entry.sha256) { "共享备份文件校验失败，未改动当前数据" }
                        output.fd.sync()
                    }
                    Os.chmod(file.path, if (entry.executable) 448 else 384)
                }
                requireLocalized(zip.nextEntry?.name == "complete.json") { "共享备份不完整" }
                val complete = JSONObject(limited(zip, 1024).toString(Charsets.UTF_8))
                requireLocalized(complete.getString("manifestSha256") == digest(bytes) && complete.getInt("entries") == entries.size) { "共享备份完成记录不匹配" }
                entries.filter { it.kind == "symlink" }.forEach { entry ->
                    Os.symlink(entry.target, File(destination, entry.name).path)
                }
                val info = verify(destination, manifest, versions)
                DurableFiles.write(File(destination.parentFile, "verified.json"), manifest)
                return info
            }
        } catch (failure: Throwable) {
            DurableFiles.delete(destination, writableGuestDirectories = true)
            throw failure
        }
    }

    suspend fun verify(tree: File, manifest: JSONObject, versions: RuntimeVersions): SharedBackupInfo {
        val entries = parse(manifest, versions)
        checkLocalized(scan(tree) == entries) { "共享备份内容与清单不一致" }
        return info(manifest)
    }

    fun info(manifest: JSONObject): SharedBackupInfo {
        val entries = manifest.getJSONArray("entries")
        val versions = manifest.getJSONObject("versions")
        return SharedBackupInfo(manifest.getString("id"), manifest.getLong("createdAt"),
            (0 until entries.length()).sumOf { entries.getJSONObject(it).optLong("bytes") }, entries.length(),
            versions.keys().asSequence().filter { it != "compatibilityKey" }.associateWith(versions::getString),
            manifest.optBoolean("automatic"))
    }

    private fun parse(manifest: JSONObject, versions: RuntimeVersions): List<Entry> {
        requireLocalized(manifest.getInt("schema") == 1 && manifest.getString("format") == FORMAT) { "共享备份格式不受支持" }
        requireLocalized(versions.acceptsBackupKey(manifest.getJSONObject("versions").getString("compatibilityKey"))) {
            "备份与当前安装的程序版本不匹配"
        }
        val array = manifest.getJSONArray("entries")
        requireLocalized(array.length() in 1..MAX_ENTRIES) { "共享备份条目数量异常" }
        return (0 until array.length()).map { index ->
            val value = array.getJSONObject(index)
            Entry(value.getString("name"), value.getString("kind"), value.optLong("bytes"),
                value.optString("sha256"), value.optBoolean("executable"), value.optString("target"))
        }
    }

    private suspend fun scan(root: File): List<Entry> {
        requireLocalized(root.isDirectory && !Files.isSymbolicLink(root.toPath())) { "数据目录无效" }
        val coroutine = currentCoroutineContext()
        val entries = mutableListOf<Entry>()
        val base = root.toPath()
        var total = 0L
        SharedBackupPolicy.SCOPES.forEach { scope ->
            val path = File(root, scope).toPath()
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                if (SharedBackupPolicy.retain(scope)) entries += Entry(scope, "directory")
                return@forEach
            }
            requireLocalized(Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) { "备份范围必须是真实目录" }
            Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    coroutine.ensureActive()
                    val name = base.relativize(dir).toString()
                    if (SharedBackupPolicy.excluded(name)) return FileVisitResult.SKIP_SUBTREE
                    if (SharedBackupPolicy.included(name)) entries += Entry(name, "directory")
                    return FileVisitResult.CONTINUE
                }
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    coroutine.ensureActive()
                    val name = base.relativize(file).toString()
                    if (!SharedBackupPolicy.retain(name)) return FileVisitResult.CONTINUE
                    when {
                        attrs.isRegularFile -> {
                            requireLocalized(attrs.size() <= MAX_FILE) { "共享备份中的单个文件过大" }
                            total += attrs.size()
                            requireLocalized(total <= MAX_UNPACKED) { "共享备份解开后超过限制" }
                            val hash = file.toFile().inputStream().use { copyBlocking(it, null, attrs.size(), coroutine) }
                            entries += Entry(name, "file", attrs.size(), hash, Os.lstat(file.toString()).st_mode and 64 != 0)
                        }
                        attrs.isSymbolicLink -> {
                            val target = Files.readSymbolicLink(file).toString()
                            if (!target.startsWith('/') && target.none { it == '\\' || it.code < 32 }) {
                                entries += Entry(name, "symlink", target = target)
                            }
                        }
                        else -> error("数据中包含无法备份的特殊文件")
                    }
                    return FileVisitResult.CONTINUE
                }
            })
        }
        return entries.distinctBy { it.name }.sortedBy { it.name }
    }

    private suspend fun copy(input: InputStream, output: OutputStream?, expected: Long): String =
        copyBlocking(input, output, expected, currentCoroutineContext())

    private fun copyBlocking(input: InputStream, output: OutputStream?, expected: Long, coroutine: kotlin.coroutines.CoroutineContext): String {
        val hash = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(128 * 1024)
        var size = 0L
        while (true) {
            coroutine.ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            size += count
            requireLocalized(size <= expected) { "共享备份文件大小不匹配" }
            output?.write(buffer, 0, count); hash.update(buffer, 0, count)
        }
        requireLocalized(size == expected) { "共享备份文件不完整" }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }

    private suspend fun limited(input: InputStream, max: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            requireLocalized(output.size() + count <= max) { "共享备份清单超出限制" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    companion object {
        const val FORMAT = "whaletalk-shared-v1"
        const val MAX_ARCHIVE = 2L * 1024 * 1024 * 1024
        private const val MAX_UNPACKED = 8L * 1024 * 1024 * 1024
        private const val MAX_FILE = 2L * 1024 * 1024 * 1024
        private const val MAX_MANIFEST = 16 * 1024 * 1024
        private const val MAX_ENTRIES = 50_000
        private const val RESERVE = 64L * 1024 * 1024
    }
}
