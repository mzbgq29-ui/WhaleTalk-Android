// SPDX-License-Identifier: MIT
// Copyright (c) 2026 mzbgq
package io.astrbot.android.runtime

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class QqKnownAccount(val uin: String, val label: String)

/**
 * Conservative, read-only scan of local Linux QQ config trees.
 * SnowLuma 2.4.2 cannot start a quick-login for these accounts; the list is informational.
 */
internal object QqKnownAccounts {
    private val uinPattern = Regex("^[1-9]\\d{4,11}$")
    private val jsonName = Regex("(?i).+\\.(json|jsn)$")

    fun list(context: Context): List<QqKnownAccount> = list(File(context.filesDir, "persistent/home"))

    fun list(home: File): List<QqKnownAccount> {
        if (!home.isDirectory) return emptyList()
        val root = runCatching { home.canonicalFile }.getOrNull() ?: return emptyList()
        val found = linkedSetOf<String>()
        listOf(
            File(root, ".config/QQ"),
            File(root, ".local/share/QQ"),
            File(root, "Documents/Tencent Files"),
        ).forEach { start ->
            if (start.isDirectory) walk(root, start, 0, found)
        }
        return found.sorted().map { QqKnownAccount(it, it) }
    }

    private fun walk(home: File, file: File, depth: Int, found: LinkedHashSet<String>) {
        if (depth > 3 || found.size >= 32) return
        val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return
        if (!canonical.path.startsWith(home.path)) return
        if (canonical.isDirectory) {
            if (uinPattern.matches(canonical.name)) found += canonical.name
            val children = canonical.listFiles() ?: return
            children.take(64).forEach { child -> walk(home, child, depth + 1, found) }
            return
        }
        if (!canonical.isFile || !jsonName.matches(canonical.name) || canonical.length() !in 1..65_536L) return
        val text = runCatching { canonical.readText() }.getOrNull() ?: return
        extractUins(text).forEach { found += it }
    }

    internal fun extractUins(text: String): List<String> {
        val values = linkedSetOf<String>()
        runCatching {
            collect(JSONObject(text), values)
        }.recoverCatching {
            collect(JSONArray(text), values)
        }
        return values.toList()
    }

    private fun collect(value: Any?, values: LinkedHashSet<String>, depth: Int = 0) {
        if (depth > 4 || values.size >= 32 || value == null) return
        when (value) {
            is JSONObject -> {
                val keys = value.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val child = value.opt(key)
                    if (key.lowercase() in setOf("uin", "account", "user_id", "userid", "qq") && asUin(child) != null) {
                        values += asUin(child)!!
                    }
                    collect(child, values, depth + 1)
                }
            }
            is JSONArray -> {
                for (i in 0 until value.length()) collect(value.opt(i), values, depth + 1)
            }
        }
    }

    private fun asUin(value: Any?): String? = when (value) {
        is Number -> value.toLong().toString().takeIf { uinPattern.matches(it) }
        is String -> value.trim().takeIf { uinPattern.matches(it) }
        else -> null
    }
}
