// SPDX-License-Identifier: MIT
package io.astrbot.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.platform.LocalContext
import io.astrbot.android.runtime.ModelRecents
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.astrbot.android.runtime.BigFishModelCatalog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable internal fun StudioModelHelp() {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }) { Text("模型 API 填写教程") }
    if (open) AlertDialog(onDismissRequest = { open = false }, title = { Text("连接云端模型 · 4 步") }, text = {
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("1. 获取密钥\n在所选服务商的官方网站注册，进入控制台的「API Key / 密钥管理」创建密钥。按服务商要求开通模型、额度或余额。网页聊天会员不一定包含 API 额度。")
            Text("2. 填 API 地址\n优先选择上方服务商预设；自定义服务按官方文档填写 OpenAI 兼容的 Base URL，例如 https://api.deepseek.com/v1。无需追加 /chat/completions 或 /models，密钥单独填写。")
            Text("3. 选模型\n粘贴 API Key，点击「获取模型列表」，搜索并选中模型。列表来自服务商，可能含嵌入、重排或你无调用权限的模型；主聊天请选择支持 Chat Completions 的模型。服务商不支持列表时，可照文档手动输入完整模型 ID。")
            Text("4. 验证并保存\n填写日常使用的主人 QQ 大号，点击「验证连接并保存」。验证会发送一次短请求，可能产生少量费用；获取列表本身不发送聊天内容。成功后到「连接 QQ」登录机器人小号。")
            Text("常见问题\n401 / 403：检查密钥、模型权限。\n402：检查余额。\n404：检查 API 地址和模型 ID。\n429：检查额度与限流。\n修改 API 地址后请重新确认密钥属于该服务商。已保存地址的密钥可留空复用；密钥不要发给别人。")
        }
    }, confirmButton = { TextButton(onClick = { open = false }) { Text("知道了") } })
}

@Composable internal fun StudioModelPicker(endpoint: String, enabled: Boolean, keyReader: suspend () -> String, choose: (String) -> Unit,
    fetch: suspend (String, String) -> List<String> = { base, key -> BigFishModelCatalog().fetch(base, key) }) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val recentsStore = remember { ModelRecents(context) }
    var recents by remember(endpoint) { mutableStateOf(recentsStore.read(endpoint)) }
    LaunchedEffect(endpoint, enabled) { if (enabled) recents = recentsStore.read(endpoint) }
    fun select(name: String) { recentsStore.remember(endpoint, name); recents = recentsStore.read(endpoint); choose(name) }
    if (recents.isNotEmpty()) {
        Text("最近选择 · 当前服务商", style = MaterialTheme.typography.labelMedium)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            recents.forEach { name -> TextButton(onClick = { select(name) }, enabled = enabled) { Text(name) } }
            TextButton(onClick = { recentsStore.clear(endpoint); recents = emptyList() }, enabled = enabled) { Text("清空记录") }
        }
    }
    var loading by remember { mutableStateOf(false) }
    var models by remember { mutableStateOf<List<String>?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var generation by remember { mutableIntStateOf(0) }
    var job by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    DisposableEffect(endpoint) { onDispose { generation++; job?.cancel(); job = null; loading = false; models = null; failure = null } }
    LiquidOutlinedButton(onClick = {
        if (loading) { job?.cancel(); return@LiquidOutlinedButton }
        loading = true; failure = null; query = ""
        val current = ++generation
        job = scope.launch {
            try { val result = fetch(endpoint, keyReader()); if (current == generation) models = result }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) { if (current == generation) failure = e.message ?: "获取失败，可手动填写模型名称" }
            finally { if (current == generation) { loading = false; job = null } }
        }
    }, Modifier.fillMaxWidth(), enabled = enabled || loading) { Text(if (loading) "正在获取… 点击取消" else "获取模型列表") }
    failure?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    models?.let { all ->
        val filtered = all.filter { it.contains(query, ignoreCase = true) }
        AlertDialog(onDismissRequest = { models = null }, title = { Text("选择模型（${all.size}）") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StudioField(query, { query = it }, "搜索模型名称")
                Text("请选择支持当前用途的模型；实际调用权限以服务商为准。", style = MaterialTheme.typography.bodySmall)
                if (filtered.isEmpty()) Text("没有匹配模型，可以返回手动填写。")
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                    items(filtered, key = { it }) { name -> TextButton(onClick = { select(name); models = null }, modifier = Modifier.fillMaxWidth()) { Text(name) } }
                }
            }
        }, confirmButton = { TextButton(onClick = { models = null }) { Text("关闭") } })
    }
}
