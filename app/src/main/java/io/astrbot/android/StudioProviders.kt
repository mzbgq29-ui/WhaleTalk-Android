// SPDX-License-Identifier: MIT
// Copyright (c) 2026 mzbgq
package io.astrbot.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.astrbot.android.runtime.*
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

@Composable internal fun StudioProviders(vm: StudioModel) {
    var data by remember { mutableStateOf(emptyList<JSONObject>()) }
    var refresh by remember { mutableIntStateOf(0) }
    var failure by remember { mutableStateOf<String?>(null) }
    var editor by remember { mutableStateOf<JSONObject?>(null) }
    var remove by remember { mutableStateOf<JSONObject?>(null) }
    LaunchedEffect(refresh) {
        try { data = jsonRows(vm.api.bot("/api/config/provider/list?provider_type=chat_completion,embedding,rerank"), "providers"); failure = null }
        catch (e: Exception) { if (e is CancellationException) throw e; failure = e.message }
    }
    StudioCard("让能力各有所长", "聊天、知识库嵌入与重排模型分别配置；全部使用云端服务。") {
        LiquidButton(onClick = { editor = JSONObject() }, Modifier.fillMaxWidth()) { Text("添加云端模型") }
        StudioTile("主聊天模型", "主人、默认模型和 API Key", { vm.go(StudioPage.MODEL) })
        TextButton(onClick = { refresh++ }) { Text("刷新模型库") }
    }
    failure?.let { StudioCard("暂时无法读取", it) { StudioTile("运行服务", "先启动思考引擎", { vm.go(StudioPage.SERVICES) })
        StudioTile("本机管理账号", "修改过管理密码时，在这里登录", { vm.go(StudioPage.ACCOUNT) }) } }
    data.forEach { item ->
        StudioCard(item.optString("id"), when (item.optString("provider_type")) { "embedding" -> "知识库嵌入"; "rerank" -> "检索重排"; else -> "聊天模型" }) {
            StudioStatus("模型", item.optString("model", item.optString("embedding_model", item.optString("rerank_model"))))
            StudioStatus("状态", if (item.optBoolean("enable")) "已启用" else "已停用")
            Row {
                TextButton(onClick = { if (item.optString("id") == BigFishConfig.MODEL) vm.go(StudioPage.MODEL) else vm.run("读取模型配置") {
                    val detail = vm.api.bot("/api/v1/providers/by-id?provider_id=${urlValue(item.getString("id"))}") as? JSONObject
                    val raw = detail?.optJSONObject("provider") ?: error("无法读取原始模型配置")
                    editor = JSONObject(item.toString()).put("_studio_raw_config", raw)
                } }) { Text("编辑") }
                TextButton(onClick = { vm.run("测试云端模型") {
                    val result = vm.api.bot("/api/config/provider/check_one?id=${urlValue(item.getString("id"))}")
                    if (result is JSONObject && result.optString("status") == "available") vm.notice = "模型连接可用"
                    else vm.error = (result as? JSONObject)?.optString("error")?.takeIf { it.isNotBlank() && it != "null" } ?: "模型暂时不可用，请检查服务商和配置"
                } }) { Text("测试连接") }
                if (item.optString("id") != BigFishConfig.MODEL) TextButton(onClick = { remove = item }) { Text("删除") }
            }
        }
    }
    if (data.isEmpty() && failure == null) StudioEmpty("没有额外模型", "主模型配置后会显示在这里。知识库需要另加一个嵌入模型。")
    editor?.let { initial -> StudioProviderEditor(initial, { editor = null }) { config ->
        vm.run("保存云端模型") {
            if (initial.has("id")) vm.api.bot("/api/config/provider/update", "POST", JSONObject().put("id", initial.getString("id")).put("config", config))
            else vm.api.bot("/api/config/provider/new", "POST", config)
            editor = null; refresh++; vm.notice = "模型配置已保存"
        }
    } }
    remove?.let { item -> AlertDialog(onDismissRequest = { remove = null }, title = { Text("删除模型？") },
        text = { Text("删除 ${item.optString("id")} 后，使用它的聊天或知识库需要重新选择模型。") },
        confirmButton = { TextButton(onClick = { remove = null; vm.run("删除模型") {
            vm.api.bot("/api/config/provider/delete", "POST", JSONObject().put("id", item.getString("id"))); refresh++
        } }) { Text("删除") } }, dismissButton = { TextButton(onClick = { remove = null }) { Text("取消") } }) }
}

@Composable private fun StudioProviderEditor(initial: JSONObject, dismiss: () -> Unit, save: (JSONObject) -> Unit) {
    var id by remember { mutableStateOf(initial.optString("id")) }
    var role by remember { mutableStateOf(initial.optString("provider_type", "embedding")) }
    var endpoint by remember { mutableStateOf(initial.optString("api_base", initial.optString("embedding_api_base", initial.optString("rerank_api_base", "https://api.siliconflow.cn/v1")))) }
    var model by remember { mutableStateOf(initial.optString("model", initial.optString("embedding_model", initial.optString("rerank_model")))) }
    var secret by remember { mutableStateOf("") }
    var failure by remember { mutableStateOf<String?>(null) }
    var enabled by remember { mutableStateOf(initial.optBoolean("enable", true)) }
    AlertDialog(onDismissRequest = dismiss, title = { Text(if (initial.has("id")) "编辑模型" else "添加模型") }, text = {
        Column(Modifier.heightIn(max = 470.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            StudioField(id, { id = it }, "模型 ID", enabled = !initial.has("id"), hint = "给知识库或会话选择时使用，例如 my-embedding")
            if (!initial.has("id")) listOf("chat_completion" to "聊天", "embedding" to "知识嵌入", "rerank" to "检索重排").forEach { (value, title) ->
                LiquidChoice(role == value, { role = value }, { Text(title) })
            }
            StudioModelHelp()
            StudioField(endpoint, { endpoint = it; secret = "" }, "API 地址")
            StudioField(model, { model = it }, "服务商模型名称")
            StudioField(secret, { secret = it }, "API Key", secret = true, hint = if (initial.has("id")) "留空可保留已保存密钥" else null)
            val discoveryEndpoint = endpoint
            val discoveryKey = secret
            StudioModelPicker(discoveryEndpoint, true, keyReader = {
                discoveryKey.ifBlank {
                    check(discoveryEndpoint == initial.optString("api_base", initial.optString("embedding_api_base", initial.optString("rerank_api_base")))) { "更换服务商地址后，请填写对应 API Key" }
                    when (role) {
                        "embedding" -> initial.optString("embedding_api_key")
                        "rerank" -> initial.optString("rerank_api_key")
                        else -> initial.optJSONArray("key")?.optString(0).orEmpty()
                    }
                }
            }, choose = { model = it })
            StudioSwitch("启用模型", "允许机器人调用这个云端服务", enabled) { enabled = it }
            Text("聊天和嵌入使用 OpenAI 兼容接口；重排使用兼容 vLLM 的 /rerank 接口。", style = MaterialTheme.typography.bodySmall)
            failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = {
        val normalized = runCatching { io.astrbot.android.core.BigFishInput.normalizeEndpoint(endpoint) }.getOrNull()
        if (id.trim().isEmpty() || id.length > 160 || id.any(Char::isISOControl) || model.isBlank() || model.length > 160 || model.any(Char::isISOControl) || normalized == null) {
            failure = "请填写模型 ID、模型名称与不含密码、参数的 HTTPS API 地址"; return@TextButton
        }
        val oldEndpoint = initial.optString("api_base", initial.optString("embedding_api_base", initial.optString("rerank_api_base")))
        val changedEndpoint = initial.has("id") && normalized != runCatching { io.astrbot.android.core.BigFishInput.normalizeEndpoint(oldEndpoint) }.getOrNull()
        if ((!initial.has("id") || changedEndpoint) && secret.isBlank()) { failure = "新增模型或更换服务商地址时，请填写对应 API Key"; return@TextButton }
        if (secret.isNotBlank() && (secret.trim().length !in 8..8192 || secret.trim().any { it.isWhitespace() || it.isISOControl() })) {
            failure = "请填写完整的 API Key"; return@TextButton
        }
        val raw = initial.optJSONObject("_studio_raw_config")
        val result = JSONObject((raw ?: initial).toString()).put("id", id.trim()).put("enable", enabled).put("provider_type", role)
        result.remove("_studio_raw_config")
        if (!initial.has("id")) result.put("timeout", 90)
        when (role) {
            "embedding" -> result.put("type", "openai_embedding").put("provider", "openai").put("embedding_api_base", normalized)
                .put("embedding_model", model.trim()).put("embedding_dimensions_mode", "auto").put("embedding_dimensions", initial.optInt("embedding_dimensions", 1024))
                .also { if (secret.isNotBlank()) it.put("embedding_api_key", secret.trim()) }
            "rerank" -> result.put("type", "vllm_rerank").put("provider", "vllm").put("rerank_api_base", normalized)
                .put("rerank_model", model.trim()).put("rerank_api_suffix", "/rerank").also { if (secret.isNotBlank()) it.put("rerank_api_key", secret.trim()) }
            else -> result.put("type", "openai_chat_completion").put("provider", "openai").put("api_base", normalized).put("model", model.trim())
                .also { if (secret.isNotBlank()) it.put("key", JSONArray().put(secret.trim())) }
        }
        if (raw != null && raw.optString("provider_source_id").isNotBlank()) {
            val fields = when (role) {
                "embedding" -> listOf("type", "provider", "embedding_api_base", "embedding_api_key", "embedding_dimensions_mode", "embedding_dimensions")
                "rerank" -> listOf("type", "provider", "rerank_api_base", "rerank_api_key", "rerank_api_suffix")
                else -> listOf("type", "provider", "api_base", "key")
            }
            for (field in fields) if (!raw.has(field) && result.opt(field)?.toString() == initial.opt(field)?.toString()) result.remove(field)
        }
        if (initial.has("id")) {
            // Editing an existing model must preserve its provider implementation.
            for (field in listOf("type", "provider")) {
                if (raw?.has(field) == true) result.put(field, raw.get(field))
                else if (raw != null) result.remove(field)
                else if (initial.has(field)) result.put(field, initial.get(field))
            }
        }
        save(result); secret = ""
    }) { Text("保存") } }, dismissButton = { TextButton(onClick = dismiss) { Text("取消") } })
}
