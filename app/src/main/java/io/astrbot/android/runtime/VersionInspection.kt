package io.astrbot.android.runtime

import io.astrbot.android.AppText
import io.astrbot.android.R

import io.astrbot.android.BuildConfig
import org.json.JSONObject
import java.io.File

data class VersionInspection(val components: Map<String, String>, val notice: String, val checkedAtMillis: Long)

/** Display-only inspection survives partial upstream replacements. Maintenance keys remain strict. */
object InstalledVersionInspection {
    val MATCHED_NOTICE get() = AppText.get(R.string.ui_component_versions_match)
    internal val paths = listOf(".astrbot-bootstrap.json", "opt/astrbot/astrbot/__init__.py",
        "opt/astrbot/astrbot/dashboard/dist/assets/version", "var/lib/bigfish/runtime-components.json",
        "opt/astrbot-venv/pyvenv.cfg", "var/lib/astrbot-manager/astrbot-python.lock.txt")
    fun read(root: File): VersionInspection = try {
        val versions = InstalledVersions.read(root)
        VersionInspection(versions.components, MATCHED_NOTICE, System.currentTimeMillis())
    } catch (_: Exception) {
        VersionInspection(mapOf("App" to BuildConfig.VERSION_NAME, "SnowLuma" to "待准备"), "运行环境尚未准备完整", System.currentTimeMillis())
    }

}

/** Cheap metadata checks every poll; periodically reread even if timestamps were preserved. */
internal class VersionInspectionWatcher(private val files: File) {
    private var signature: List<String>? = null
    private var lastRead = Long.MIN_VALUE
    fun readIfChanged(monotonicMillis: Long): VersionInspection? {
        val root = File(files, "runtime-data/ubuntu")
        val tracked = InstalledVersionInspection.paths.map { File(root, it) } + File(files, "manager/astrbot-startup.json")
        val current = tracked.map { "${it.exists()}:${it.length()}:${it.lastModified()}" }
        if (current == signature && lastRead != Long.MIN_VALUE && monotonicMillis - lastRead < 60_000) return null
        val result = InstalledVersionInspection.read(root)
        signature = current
        lastRead = monotonicMillis
        return result
    }
}
