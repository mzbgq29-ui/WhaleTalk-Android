// SPDX-License-Identifier: MIT
package io.astrbot.android.runtime

import android.content.Context
import android.system.Os
import io.astrbot.android.core.BigFishInput
import io.astrbot.android.core.ServiceId
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom

/** Mutations run under RuntimeStore.operationLock with both bot processes stopped. */
internal class BigFishSetupRepository(private val context: Context) {
    private val root = File(context.filesDir, "persistent")
    private val request = File(root, "manager/bigfish-request.json")
    private val journal = File(context.filesDir, "operations/bigfish-setup.json")
    private val summary = File(root, "manager/bigfish-setup.json")
    private val configuration = File(root, "astrbot/data/cmd_config.json")
    val pending get() = DurableFiles.exists(request)
    val needsRecovery get() = DurableFiles.exists(journal)

    fun summary(): JSONObject? = safeRead(summary)
    fun savedKey(): String {
        val providers = safeRead(configuration)?.optJSONArray("provider_sources") ?: return ""
        for (i in 0 until providers.length()) {
            val provider = providers.optJSONObject(i) ?: continue
            if (provider.optString("id") == BigFishConfig.SOURCE) return provider.optJSONArray("key")?.optString(0).orEmpty()
        }
        return ""
    }
    fun savedKeyFor(endpoint: String): String {
        val saved = summary()?.optString("endpoint") ?: return ""
        val target = BigFishInput.normalizeEndpoint(endpoint)
        if (target != BigFishInput.normalizeEndpoint(saved)) return ""
        val providers = safeRead(configuration)?.optJSONArray("provider_sources") ?: return ""
        for (i in 0 until providers.length()) {
            val provider = providers.optJSONObject(i) ?: continue
            if (provider.optString("id") == BigFishConfig.SOURCE) {
                val actual = runCatching { BigFishInput.normalizeEndpoint(provider.optString("api_base")) }.getOrNull()
                return if (actual == target) provider.optJSONArray("key")?.optString(0).orEmpty() else ""
            }
        }
        return ""
    }
    fun request(input: BigFishInput, verifiedAt: Long) {
        check(!needsRecovery) { "上次配置需要先恢复，请点击重试" }
        write(request, JSONObject().put("schema", 1).put("owner", input.owner).put("endpoint", input.endpoint)
            .put("model", input.model).put("key", input.key).put("friends", input.friends.joinToString(","))
            .put("groups", input.groups.joinToString(",")).put("options", BigFishConfig.optionsJson(input.options))
            .put("verifiedAt", verifiedAt).put("requestId", java.util.UUID.randomUUID().toString()))
    }
    fun prepare(wanted: Set<ServiceId>) {
        check(!needsRecovery) { "上次配置尚未恢复" }
        val values = requireNotNull(safeRead(request)) { "请先填写模型信息" }
        val input = BigFishInput(values.getString("owner"), values.getString("endpoint"), values.getString("model"),
            values.getString("key"), values.optString("friends"), values.optString("groups"), BigFishConfig.readOptions(values.optJSONObject("options")))
        val current = requireNotNull(safeRead(configuration)) { "运行环境尚未准备好" }
        val platforms = current.optJSONArray("platform") ?: JSONArray()
        val existingToken = (0 until platforms.length()).mapNotNull { platforms.optJSONObject(it) }
            .firstOrNull { QqPlatform.matches(it.optString("id")) }?.optString("ws_reverse_token").orEmpty()
        val token = existingToken.takeIf { it.matches(Regex("[a-f0-9]{64}")) } ?: ByteArray(32).also(SecureRandom()::nextBytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val updates = linkedMapOf(configuration to BigFishConfig.astrbot(current, input, token))
        val previous = JSONArray()
        (updates.keys + summary).forEach { file -> previous.put(JSONObject().put("path", relative(file))
            .put("existed", DurableFiles.exists(file)).put("value", safeRead(file) ?: JSONObject.NULL)) }
        write(journal, JSONObject().put("schema", 1).put("files", previous).put("wanted", JSONArray(wanted.map { it.name })))
        updates.forEach { (file, value) -> write(file, value) }
    }
    fun commit() {
        val values = requireNotNull(safeRead(request))
        // No key or bridge token is copied into the presentation record.
        val result = JSONObject(values.toString()).apply { remove("key"); put("configuredAt", System.currentTimeMillis()) }
        write(summary, result)
        DurableFiles.removeRecord(journal)
        DurableFiles.removeRecord(request)
    }
    fun recover(): Set<ServiceId> {
        val record = safeRead(journal) ?: return emptySet()
        check(record.getInt("schema") == 1)
        val files = record.getJSONArray("files")
        for (i in 0 until files.length()) {
            val item = files.getJSONObject(i)
            val file = resolve(item.getString("path"))
            if (item.getBoolean("existed")) write(file, item.getJSONObject("value"))
            else if (DurableFiles.exists(file)) DurableFiles.removeRecord(file)
        }
        DurableFiles.removeRecord(journal)
        val wanted = record.optJSONArray("wanted") ?: JSONArray()
        return (0 until wanted.length()).mapNotNull { i -> ServiceId.fromStored(wanted.optString(i)) }.toSet()
    }
    fun cleanCommittedRequest() {
        if (needsRecovery || !pending) return
        val completed = summary()?.optString("requestId").orEmpty()
        if (completed.isNotEmpty() && completed == safeRead(request)?.optString("requestId")) DurableFiles.removeRecord(request)
    }
    private fun relative(file: File): String {
        check(file.canonicalFile.toPath().startsWith(root.canonicalFile.toPath()))
        check(!java.nio.file.Files.isSymbolicLink(file.toPath()))
        return file.relativeTo(root).invariantSeparatorsPath
    }
    private fun resolve(path: String): File = File(root, path).also { file ->
        check(relative(file) == path && path in setOf("astrbot/data/cmd_config.json", "napcat/config/onebot11.json", "manager/bigfish-setup.json") ||
            relative(file) == path && path.matches(Regex("napcat/config/onebot11_[0-9]{5,20}\\.json")))
    }
    private fun safeRead(file: File): JSONObject? {
        if (!DurableFiles.exists(file)) return null
        check(!java.nio.file.Files.isSymbolicLink(file.toPath()) && file.length() <= 8 * 1024 * 1024) { "配置文件不符合预期，原文件已保留" }
        return try { DurableFiles.read(file) } catch (_: Exception) { error("无法读取配置，原文件已保留，请打开高级管理检查") }
    }
    private fun write(file: File, value: JSONObject) {
        DurableFiles.write(file, value)
        Os.chmod(file.path, 384)
    }
}
