// SPDX-License-Identifier: MIT
// Copyright (c) 2026 mzbgq
package io.astrbot.android.runtime

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Conservatively pins Linux QQ to the bundled version without deleting account data. */
object QqHotUpdateGuard {
    const val PREFERENCE = "qq_version_lock"
    const val DEFAULT_PINNED = "3.2.35-54395"

    data class Decision(
        val action: String,
        val current: String?,
        val pinned: String,
        val isolated: Boolean,
        val message: String?,
        val updated: JSONObject? = null,
    )

    fun lockEnabled(context: Context): Boolean =
        context.getSharedPreferences("manager", Context.MODE_PRIVATE).getBoolean(PREFERENCE, true)

    fun setLockEnabled(context: Context, enabled: Boolean) {
        check(context.getSharedPreferences("manager", Context.MODE_PRIVATE).edit().putBoolean(PREFERENCE, enabled).commit()) {
            "QQ 版本锁定设置未能保存"
        }
    }

    fun pinnedVersion(context: Context): String = runCatching {
        context.assets.open("runtime/desktop/sources.json").bufferedReader().use { JSONObject(it.readText()) }
            .getJSONObject("qq").getString("version")
    }.getOrDefault(DEFAULT_PINNED)

    fun currentVersion(document: JSONObject): String? {
        val value = document.optString("curVersion").ifBlank { document.optString("currentVersion") }
        return value.takeIf { it.isNotBlank() }
    }

    fun inspect(document: JSONObject, pinned: String): Decision {
        val current = currentVersion(document)
        if (current == null) return Decision("unchanged", null, pinned, false, null, document)
        if (current == pinned) return Decision("already-pinned", current, pinned, false, null, document)
        val updated = JSONObject(document.toString())
        val errors = updated.optJSONArray("onErrorVersions") ?: JSONArray()
        val listed = (0 until errors.length()).map { errors.optString(it) }
        if (current !in listed) errors.put(current)
        updated.put("onErrorVersions", errors)
        updated.put("curVersion", pinned)
        return Decision(
            action = "isolate",
            current = current,
            pinned = pinned,
            isolated = true,
            message = "检测到 QQ 热更新模块 $current，已改回并锁定为 $pinned。账号数据未删除。",
            updated = updated,
        )
    }

    fun apply(config: File, pinned: String): Decision {
        val original = DurableFiles.read(config) ?: return Decision("missing", null, pinned, false, null)
        val decision = inspect(original, pinned)
        if (!decision.isolated || decision.updated == null) return decision
        val stamp = System.currentTimeMillis()
        val backup = File(config.parentFile, "config.json.whaletalk-backup-$stamp")
        if (!config.exists() || !config.copyTo(backup, overwrite = false).isFile) {
            return Decision("backup-failed", decision.current, pinned, false, "无法备份 QQ 版本配置，已跳过锁定，账号数据未改动")
        }
        DurableFiles.write(config, decision.updated)
        return decision
    }

    fun applyIfLocked(context: Context, filesDir: File = context.filesDir): Decision? {
        val pinned = pinnedVersion(context)
        val policy = JSONObject().put("enabled", lockEnabled(context)).put("pinned", pinned)
        val state = File(filesDir, "persistent/snowluma")
        state.mkdirs()
        DurableFiles.write(File(state, "qq-version-lock.json"), policy)
        if (!policy.getBoolean("enabled")) return Decision("disabled", null, pinned, false, null)
        val config = File(filesDir, "persistent/home/.config/QQ/versions/config.json")
        if (!config.isFile) return Decision("missing", null, pinned, false, null)
        return apply(config, pinned)
    }
}
