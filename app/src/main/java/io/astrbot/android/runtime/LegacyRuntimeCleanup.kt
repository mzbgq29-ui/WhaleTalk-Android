package io.astrbot.android.runtime

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.nio.file.Files

/** Only imports historic data identifiers; no legacy backend can be installed or launched. */
internal class LegacyRuntimeCleanup(private val context: Context) {
    private val root = File(context.filesDir, "runtime-data/ubuntu")
    fun prepareMetadata() {
        val bootstrap=File(root,".astrbot-bootstrap.json")
        if (!DurableFiles.exists(bootstrap)) return
        requireNotNull(DurableFiles.read(bootstrap)) { "运行记录无法读取，原数据已保留" }
        val marker = File(root, "var/lib/bigfish/runtime-components.json")
        if (!marker.isFile) {
            val base = context.assets.open("bootstrap/arm64-v8a/manifest.json").bufferedReader().use { JSONObject(it.readText()) }
            DurableFiles.write(marker, base.getJSONObject("components"))
            val identity = context.assets.open("bigfish/legacy-backup-identity.json").bufferedReader().use { JSONObject(it.readText()) }
            DurableFiles.write(File(root, "var/lib/bigfish/legacy-backup-identity.json"), identity)
        }
    }
    /** Called only with QQ stopped, after the managed replacement QQ was verified. */
    fun prepare() {
        prepareMetadata()
        val managed = File(context.filesDir, "manager/extensions/desktop/qq/opt/QQ/qq")
        check(managed.isFile && !Files.isSymbolicLink(managed.toPath())) { "先完成内置 QQ 准备，再清理旧运行文件" }
        val obsolete = File(root, "opt/qq")
        if (obsolete.exists()) {
            check(!Files.isSymbolicLink(obsolete.toPath()) && File(obsolete, ".astrbot-components.json").isFile) { "旧 QQ 目录包含未识别内容，数据已保留" }
            DurableFiles.delete(obsolete, writableGuestDirectories = true)
        }
        val cache = File(context.filesDir, "manager/extensions/snowluma")
        for (name in listOf("qq", "qq.previous")) {
            val path = File(cache, name)
            if (path.exists() && File(path, ".bigfish-qq-ready").isFile && !Files.isSymbolicLink(path.toPath()))
                DurableFiles.delete(path, writableGuestDirectories = true)
        }
        for (name in listOf("usr/local/bin/napcat-start", "usr/local/bin/start-napcat")) {
            val path = File(root, name)
            if (Files.exists(path.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) Files.delete(path.toPath())
        }
        val original = DurableFiles.read(File(root, ".astrbot-bootstrap.json"))
        if (original != null) {
            original.put("id", "ubuntu24.04.3-astrbot4.29.3-snowluma2.4.2")
            original.put("components", JSONObject(File(root, "var/lib/bigfish/runtime-components.json").readText()))
            original.put("legacyBackendRemoved", true)
            DurableFiles.write(File(root, ".astrbot-bootstrap.json"), original)
        }
    }
    companion object {
        fun importPreferences(context: Context) {
            val preferences = context.getSharedPreferences("manager", Context.MODE_PRIVATE)
            val changes = preferences.all.filterKeys { "NAPCAT" in it }
            if (changes.isEmpty()) return
            val editor = preferences.edit()
            for ((old, value) in changes) {
                val current = old.replace("NAPCAT", "SNOWLUMA")
                if (!preferences.contains(current)) when (value) {
                    is Boolean -> editor.putBoolean(current, value)
                    is String -> editor.putString(current, value)
                    is Int -> editor.putInt(current, value)
                    is Long -> editor.putLong(current, value)
                }
                editor.remove(old)
            }
            check(editor.commit()) { "无法迁移运行偏好" }
        }
    }
}
