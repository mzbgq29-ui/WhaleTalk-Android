package io.astrbot.android.core

import java.io.File
import java.nio.file.Files

/** Data-only import of earlier releases. Run with QQ stopped under the operation lock. */
object SnowStateMigration {
    fun migrate(persistent: File, move: (File, File) -> Unit = { from, to -> check(from.renameTo(to)) { "无法迁移 QQ 配置" } }): File {
        val oldParent = File(persistent, "napcat") // Legacy on-disk identifier; never executed.
        val target = File(persistent, "snowluma")
        val manager = File(persistent, "manager")
        val candidates = listOf(File(oldParent, "config/snowluma"), File(oldParent, "snowluma"))
        for (path in listOf(oldParent, File(oldParent, "config"), target, manager) + candidates)
            check(!Files.isSymbolicLink(path.toPath()) && (!path.exists() || path.isDirectory)) { "QQ 配置路径异常" }
        check(manager.isDirectory || manager.mkdirs())
        fun archive(prefix: String): File {
            var value = File(manager, prefix); var suffix = 1
            while (value.exists() || Files.isSymbolicLink(value.toPath())) value = File(manager, "$prefix-${suffix++}")
            return value
        }
        for (legacy in candidates) if (legacy.isDirectory && legacy.list().orEmpty().isNotEmpty()) {
            if (target.isDirectory && target.list().orEmpty().isEmpty()) check(target.delete())
            move(legacy, if (!target.exists()) target else archive("qq-state-before-migration"))
        }
        check(target.isDirectory || target.mkdirs()) { "无法创建 QQ 配置目录" }
        // Preserve historical settings/data without an active legacy namespace.
        if (oldParent.exists()) {
            if (oldParent.list().orEmpty().isEmpty()) check(oldParent.delete())
            else move(oldParent, archive("qq-legacy-settings"))
        }
        return target
    }
}
