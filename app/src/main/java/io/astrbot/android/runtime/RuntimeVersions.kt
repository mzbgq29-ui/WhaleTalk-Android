package io.astrbot.android.runtime

import android.content.Context
import io.astrbot.android.BuildConfig
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

data class RuntimeVersions(val components: Map<String, String>, val compatibilityKey: String, val environmentKey: String = "",
                           val legacyCompatibilityKeys: Set<String> = emptySet()) {
    fun json() = JSONObject(components).put("compatibilityKey", compatibilityKey)
    fun acceptsBackupKey(value: String) = value == compatibilityKey || value in legacyCompatibilityKeys
}

object InstalledVersions {
    fun read(context: Context): RuntimeVersions = read(File(context.filesDir, "runtime-data/ubuntu"))
    fun digest(parts: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        parts.forEach { digest.update(it.toByteArray(Charsets.UTF_8)); digest.update(0.toByte()) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    fun read(root: File): RuntimeVersions {
        fun text(path: String): String {
            val file = File(root, path);check(file.isFile && file.length() <= 32L*1024*1024) { "运行版本记录缺失，请先完成准备" }
            return file.readText()
        }
        val bootstrap = JSONObject(text(".astrbot-bootstrap.json"))
        val source = text("opt/astrbot/astrbot/__init__.py")
        val python = text("opt/astrbot-venv/pyvenv.cfg")
        val marker = text("opt/astrbot/.astrbot-source.json")
        val dependencies = text("var/lib/astrbot-manager/astrbot-python.lock.txt")
        val declared = JSONObject(text("var/lib/bigfish/runtime-components.json"))
        val sdk = digest(listOf(source, python, marker, dependencies))
        val legacy = runCatching { JSONObject(text("var/lib/bigfish/legacy-backup-identity.json")) }.getOrNull()
        val aliases = if (legacy?.optString("sdkFingerprint") == sdk) legacy.optJSONArray("legacyCompatibilityKeys")?.let { a ->
            (0 until a.length()).map { a.getString(it) }.toSet()
        }.orEmpty() else emptySet()
        val components = linkedMapOf("App" to BuildConfig.VERSION_NAME, "运行环境" to "Ubuntu 24.04 · SnowLuma",
            "架构" to bootstrap.getString("abi"),
            "Python" to (Regex("(?m)^version\\s*=\\s*(.+)$").find(python)?.groupValues?.get(1)?.trim() ?: "未知"),
            "AstrBot" to (Regex("__version__\\s*=\\s*[\"']([^\"']+)").find(source)?.groupValues?.get(1) ?: "未知"),
            "SnowLuma" to declared.getJSONObject("snowluma").getString("version"),
            "Linux QQ" to declared.getJSONObject("qq").getString("version"))
        return RuntimeVersions(components, digest(listOf(sdk, declared.getJSONObject("snowluma").toString(), components.getValue("Linux QQ"), components.getValue("架构"))),
            digest(listOf(components.getValue("架构"), python, dependencies)), aliases)
    }
}
