// SPDX-License-Identifier: MIT
// Copyright (c) 2026 mzbgq
package io.astrbot.android

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

@Composable internal fun StudioPluginMarket(vm: StudioModel) {
    var all by remember { mutableStateOf(emptyList<JSONObject>()) }
    var query by rememberSaveable { mutableStateOf("") }
    var page by rememberSaveable { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var failure by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var install by remember { mutableStateOf<JSONObject?>(null) }
    LaunchedEffect(refresh) {
        loading = true
        try {
            val value = vm.api.bot("/api/plugin/market_list")
            all = when (value) {
                is JSONArray -> jsonRows(value)
                is JSONObject -> value.keys().asSequence().filter { it != "\$meta" }.mapNotNull { key ->
                    value.optJSONObject(key)?.let { item -> JSONObject(item.toString()).apply { if (!has("name")) put("name", key) } }
                }.toList()
                else -> emptyList()
            }
            failure = null
        } catch (e: Exception) { if (e is CancellationException) throw e; failure = e.message }
        finally { loading = false }
    }
    val items = all.filter { query.isBlank() || listOf("name", "display_name", "author", "desc", "description").joinToString { key -> it.optString(key) }.contains(query.trim(), true) }
    val pages = ((items.size + 19) / 20).coerceAtLeast(1)
    val current = page.coerceIn(0, pages - 1)
    StudioCard("来自社区", "由本机引擎连接它配置的插件市场。语音能力保持关闭。") {
        StudioField(query, { query = it; page = 0 }, "搜索插件、作者或功能")
        Text(if (loading) "正在读取市场…" else "找到 ${items.size} 个插件", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { refresh++ }, enabled = !loading) { Text("重新读取") }
    }
    failure?.let { StudioEmpty("暂时无法连接市场", it) }
    items.drop(current * 20).take(20).forEach { item ->
        val title = item.optString("display_name", item.optString("name"))
        StudioCard(title, item.optString("desc", item.optString("description"))) {
            StudioStatus("作者", item.optString("author", "社区作者"))
            val repo = item.optString("repo", item.optString("url"))
            LiquidOutlinedButton(onClick = { install = item }, Modifier.fillMaxWidth(), enabled = !vm.busy && repo.startsWith("https://")) { Text("安装此插件") }
        }
    }
    if (pages > 1) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        LiquidOutlinedButton(onClick = { page = current - 1 }, Modifier.weight(1f), enabled = current > 0) { Text("上一页") }
        Text("${current + 1} / $pages", Modifier.padding(top = 16.dp))
        LiquidOutlinedButton(onClick = { page = current + 1 }, Modifier.weight(1f), enabled = current + 1 < pages) { Text("下一页") }
    }
    install?.let { item -> AlertDialog(onDismissRequest = { install = null }, title = { Text("安装社区插件？") },
        text = { Text("${item.optString("display_name", item.optString("name"))}\n\n插件在本机引擎中运行，会按它自身的配置扩展功能。安装后请在插件页确认状态。") },
        confirmButton = { TextButton(onClick = { install = null; vm.run("安装社区插件") {
            vm.api.bot("/api/plugin/install", "POST", JSONObject().put("url", item.optString("repo", item.optString("url"))))
            vm.go(StudioPage.PLUGINS); vm.notice = "插件安装完成"
        } }) { Text("安装") } }, dismissButton = { TextButton(onClick = { install = null }) { Text("取消") } }) }
}
