// SPDX-License-Identifier: MIT
// Copyright (c) 2026 mzbgq
package io.astrbot.android

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.astrbot.android.core.*
import io.astrbot.android.runtime.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

@Composable internal fun StudioOptionFields(vm: StudioModel, enabled: Boolean) {
    StudioSwitch("私聊也需要唤醒", "开启后，私聊使用 / 前缀触发", vm.options.friendNeedsWake, enabled) { vm.options = vm.options.copy(friendNeedsWake = it) }
    StudioSwitch("群聊仅 @ 回应", "关闭后也能通过唤醒词触发", vm.options.groupMentionOnly, enabled) { vm.options = vm.options.copy(groupMentionOnly = it) }
    StudioSwitch("引用原消息", "回复时附上对应的原消息", vm.options.replyWithQuote, enabled) { vm.options = vm.options.copy(replyWithQuote = it) }
    StudioSwitch("群回复时 @ 对方", "让对方更容易看到回复", vm.options.replyWithMention, enabled) { vm.options = vm.options.copy(replyWithMention = it) }
    StudioSwitch("图片理解", "需要你选择的云端模型支持视觉", vm.options.vision, enabled) { vm.options = vm.options.copy(vision = it) }
    Text("对话长度", fontWeight = FontWeight.Medium)
    BigFishResourceMode.entries.forEach { mode ->
        LiquidChoice(vm.options.mode == mode, { vm.options = vm.options.copy(mode = mode) }, { Text("${mode.title} · ${mode.turns} 轮上下文") }, enabled = enabled)
    }
    Text("语音识别和语音合成保持关闭。", style = MaterialTheme.typography.bodySmall)
}

@Composable internal fun StudioChat(vm: StudioModel) {
    val scroll = androidx.compose.foundation.lazy.rememberLazyListState()
    var previousSize by remember { mutableIntStateOf(0) }
    LaunchedEffect(vm.messages.size, vm.messages.lastOrNull()?.text?.length) {
        val nearBottom = (scroll.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= (previousSize - 2).coerceAtLeast(0)
        val sent = vm.messages.size != previousSize && vm.messages.lastOrNull()?.mine == true
        previousSize = vm.messages.size
        if (vm.messages.isNotEmpty() && (nearBottom || sent)) scroll.scrollToItem(vm.messages.lastIndex)
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("只在这里聊，也可以。", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { vm.session = ""; vm.messages.clear() }, enabled = !vm.chatting && !vm.stoppingChat) { Text("新对话") }
        }
        if (vm.messages.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
                StudioMascot(Modifier.size(128.dp))
                Text("想聊什么？", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text("回复来自你设置的模型和人设。", style = MaterialTheme.typography.bodySmall)
            }
        } else LazyColumn(Modifier.weight(1f), state = scroll, contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            itemsIndexed(vm.messages) { _, item ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (item.mine) Arrangement.End else Arrangement.Start) {
                    Surface(Modifier.widthIn(max = 420.dp).fillMaxWidth(if (item.mine) .88f else .95f),
                        shape = RoundedCornerShape(24.dp, 24.dp, if (item.mine) 6.dp else 24.dp, if (item.mine) 24.dp else 6.dp),
                        color = if (item.mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface) {
                        SelectionContainer { Text(item.text.ifBlank { "正在想…" }, Modifier.padding(18.dp), lineHeight = 25.sp) }
                    }
                }
            }
        }
        Surface(shape = RoundedCornerShape(26.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 10.dp)) {
            Column(Modifier.padding(12.dp)) {
                StudioField(vm.chatDraft, { vm.chatDraft = it }, "和鲸鱼说点什么", enabled = !vm.chatting && !vm.stoppingChat)
                LiquidButton(onClick = { if (vm.chatting) vm.stopChat() else vm.send() }, Modifier.fillMaxWidth(), enabled = !vm.stoppingChat && (vm.chatting || vm.chatDraft.isNotBlank())) {
                    Text(if (vm.stoppingChat) "正在停止…" else if (vm.chatting) "停止回复" else "发送")
                }
            }
        }
    }
}

private fun collectionEndpoint(page: StudioPage) = when (page) {
    StudioPage.PERSONAS -> "/api/persona/list"
    StudioPage.HISTORY -> "/api/conversation/list?page_size=50"
    StudioPage.SESSIONS -> "/api/session/list-all-with-status?page_size=50"
    StudioPage.PLUGINS -> "/api/plugin/get"
    StudioPage.KNOWLEDGE -> "/api/kb/list?page_size=50"
    StudioPage.SCHEDULE -> "/api/cron/jobs"
    else -> "/api/stat/get"
}

private fun itemTitle(item: JSONObject): String = listOf("display_name", "title", "kb_name", "name", "persona_id", "umo", "user_id", "session_id", "cid", "id")
    .firstNotNullOfOrNull { item.optString(it).takeIf { value -> value.isNotBlank() && value != "null" } } ?: "未命名"
private fun itemText(item: JSONObject): String = listOf("description", "desc", "note", "system_prompt", "alias", "platform", "updated_at", "created_at")
    .mapNotNull { item.optString(it).takeIf { value -> value.isNotBlank() && value != "null" } }.joinToString("\n").take(200)

@Composable internal fun StudioCollection(vm: StudioModel, state: ManagerState) {
    val page = vm.page
    if (page == StudioPage.PERSONAS) StudioCard {
        StudioTile("人设仓库", "浏览、收藏并导入公开人设，支持离线使用", { vm.go(StudioPage.PERSONA_LIBRARY) })
    }
    if (page == StudioPage.PLUGINS) StudioCard {
        StudioTile("内置陪伴增强", "长期记忆、拟人分段和群聊增强；不需要新增 API Key", { vm.go(StudioPage.COMPANIONS) })
    }
    var data by remember(page) { mutableStateOf<Any?>(null) }
    var loading by remember(page) { mutableStateOf(false) }
    var failure by remember(page) { mutableStateOf<String?>(null) }
    var refresh by remember(page) { mutableIntStateOf(0) }
    var query by rememberSaveable(page) { mutableStateOf("") }
    var selected by remember(page) { mutableStateOf<JSONObject?>(null) }
    var editor by remember(page) { mutableStateOf<JSONObject?>(null) }
    var newEditor by remember(page) { mutableStateOf(false) }
    var confirm by remember(page) { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    var sessionConfig by remember { mutableStateOf<String?>(null) }
    var pluginConfig by remember { mutableStateOf<String?>(null) }
    var documents by remember { mutableStateOf<JSONObject?>(null) }
    var exportPersona by remember { mutableStateOf<JSONObject?>(null) }
    val context=androidx.compose.ui.platform.LocalContext.current
    val installZip=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri!=null)vm.run("安装本地插件"){vm.api.uploadPlugin(uri);refresh++;vm.notice="插件安装完成，请查看加载状态"}
    }
    val importPersona=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri!=null)vm.run("导入角色卡"){
            val raw=withContext(Dispatchers.IO){context.contentResolver.openInputStream(uri)!!.use { TavernCharacterCard.decode(TavernCharacterCard.readLimited(it)) }}
            newEditor=true;editor=PersonaTemplates.importCard(raw)
            if(!raw.has("persona_id"))withContext(Dispatchers.IO){PersonaWarehouseRepository(context).importLocal(raw.toString().toByteArray())}

        }
    }
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")){uri ->
        val value=exportPersona;exportPersona=null
        if(uri!=null&&value!=null)vm.run("导出人设"){withContext(Dispatchers.IO){context.contentResolver.openOutputStream(uri,"wt")!!.use{it.write(value.toString(2).toByteArray())}};vm.notice="人设已导出"}
    }
    var uploadTo by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val upload = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.run("导入知识文档") { vm.api.uploadKnowledge(uploadTo, uri); refresh++; vm.notice = "文档已上传，后端正在处理" }
    }
    LaunchedEffect(page, refresh, state.services.getValue(ServiceId.ASTRBOT).ready) {
        loading = true; failure = null
        try { data = vm.api.bot(collectionEndpoint(page)) }
        catch (e: Exception) { if (e is CancellationException) throw e; failure = e.message ?: "暂时无法读取内容" }
        finally { loading = false }
    }
    StudioCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(if (loading) "正在读取…" else "来自本机的真实数据", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { refresh++ }, enabled = !loading) { Text("刷新") }
        }
        if (page != StudioPage.STATS) StudioField(query, { query = it }, "搜索已加载的内容")
        if (page in listOf(StudioPage.PERSONAS, StudioPage.KNOWLEDGE, StudioPage.SCHEDULE, StudioPage.PLUGINS))
            LiquidButton(onClick = { newEditor = true; editor = JSONObject() }, Modifier.fillMaxWidth(), enabled = !vm.busy) {
                Text(when (page) { StudioPage.PERSONAS -> "写一个新人设"; StudioPage.KNOWLEDGE -> "创建知识库"; StudioPage.SCHEDULE -> "创建定时任务"; else -> "从 GitHub 安装插件" })
            }
        if(page==StudioPage.PLUGINS) LiquidOutlinedButton(onClick={installZip.launch(arrayOf("application/zip","application/octet-stream"))},Modifier.fillMaxWidth(),enabled=!vm.busy){Text("从手机 ZIP 安装插件")}
        if(page==StudioPage.PERSONAS) LiquidOutlinedButton(onClick={importPersona.launch(arrayOf("application/json","image/png","text/plain","application/octet-stream"))},Modifier.fillMaxWidth(),enabled=!vm.busy){Text("导入角色卡 PNG / JSON")}
        if (page == StudioPage.PLUGINS) StudioTile("发现社区插件", "搜索插件市场，查看作者与简介", { vm.go(StudioPage.PLUGIN_MARKET) })
        if (page == StudioPage.KNOWLEDGE) StudioTile("配置嵌入模型", "在模型库添加云端嵌入服务", { vm.go(StudioPage.PROVIDERS) })
    }
    if (failure != null) StudioCard("暂时连接不上", failure) {
        StudioTile("启动思考引擎", "在运行服务中启动 AstrBot，再回来刷新", { vm.go(StudioPage.SERVICES) })
        StudioTile("本机管理账号", "如果修改过后台密码，在这里重新登录", { vm.go(StudioPage.ACCOUNT) })
    }
    if (page == StudioPage.STATS && data is JSONObject) {
        val stats = data as JSONObject
        StudioCard("陪伴一览", "统计来自本机引擎，不代表云端账户余额。") {
            StudioStatus("消息总数", stats.optLong("message_count").toString())
            StudioStatus("连接平台", stats.optInt("platform_count").toString())
            StudioStatus("已加载插件", stats.optInt("plugin_count").toString())
            stats.optJSONObject("memory")?.let { StudioStatus("引擎内存", "${it.optInt("process")} MB") }
            stats.optJSONObject("running")?.let { StudioStatus("运行时长", "${it.optInt("hours")} 时 ${it.optInt("minutes")} 分") }
        }
    } else {
        val all = jsonRows(data, "conversations", "sessions", "plugins", "knowledge_bases", "jobs")
        val items = all.filter { query.isBlank() || (itemTitle(it) + itemText(it)).contains(query.trim(), true) }
        if (!loading && failure == null && items.isEmpty()) StudioEmpty("这里还空着", if (query.isBlank()) "使用一段时间后，内容会出现在这里。" else "没有找到匹配内容，试试其他关键词。")
        items.forEach { item ->
            StudioCard(QqPlatform.display(itemTitle(item)), QqPlatform.display(itemText(item)).ifBlank { null }) {
                when (page) {
                    StudioPage.PERSONAS -> {
                        TextButton(onClick = { selected = item }) { Text("阅读人设") }
                        Row {
                            TextButton(onClick={vm.run("应用人设"){vm.api.applyPersona(item.getString("persona_id"));vm.notice="默认人设已应用；新会话会使用该人设"}},enabled=!vm.busy){Text("设为默认")}
                            TextButton(onClick={exportPersona=JSONObject(item.toString());export.launch("WhaleTalk-persona.json")}){Text("导出 JSON")}
                        }
                        Row { TextButton(onClick = { newEditor = false; editor = JSONObject(item.toString()) }) { Text("编辑") }
                            TextButton(onClick = { confirm = "删除这个人设？现有对话不会被一并删除。" to {
                                vm.run("删除人设") { vm.api.bot("/api/persona/delete", "POST", JSONObject().put("persona_id", item.getString("persona_id"))); refresh++ }
                            } }) { Text("删除") } }
                    }
                    StudioPage.PLUGINS -> {
                        TextButton(onClick={pluginConfig=item.getString("name")}){Text("插件配置")}
                        StudioStatus("作者", item.optString("author", "未知")); StudioStatus("版本", item.optString("version", "未知"))
                        StudioSwitch("启用插件", "开关会立即应用到机器人", item.optBoolean("activated"), !vm.busy && !item.optBoolean("reserved")) { value ->
                            vm.run("调整插件") { vm.api.bot("/api/plugin/" + if (value) "on" else "off", "POST", JSONObject().put("name", item.getString("name"))); refresh++ }
                        }
                        if (!item.optBoolean("reserved")) TextButton(onClick = { confirm = "卸载 ${itemTitle(item)}？插件数据是否保留由该插件决定。" to {
                            vm.run("卸载插件") { vm.api.bot("/api/plugin/uninstall", "POST", JSONObject().put("name", item.getString("name"))); refresh++ }
                        } }) { Text("卸载") }
                    }
                    StudioPage.SCHEDULE -> {
                        StudioStatus("时间规则", item.optString("cron_expression", "单次任务"))
                        StudioStatus("下次执行", item.optString("next_run_time").replace("T", " ").take(19))
                        StudioSwitch("启用任务", "启用后按计划执行，可能向对应会话发送消息", item.optBoolean("enabled"), !vm.busy) { value ->
                            vm.run("调整任务") { vm.api.bot("/api/cron/jobs/${urlValue(item.getString("job_id"))}", "PATCH", JSONObject().put("enabled", value)); refresh++ }
                        }
                        Row { TextButton(onClick = { newEditor = false; editor = JSONObject(item.toString()) }) { Text("编辑") }
                            TextButton(onClick = { confirm = "删除这个定时任务？" to {
                                vm.run("删除任务") { vm.api.bot("/api/cron/jobs/${urlValue(item.getString("job_id"))}", "DELETE"); refresh++ }
                            } }) { Text("删除") } }
                    }
                    StudioPage.KNOWLEDGE -> {
                        TextButton(onClick = { uploadTo = item.getString("kb_id"); upload.launch(arrayOf("text/*", "application/pdf", "application/epub+zip", "application/vnd.openxmlformats-officedocument.wordprocessingml.document")) }) { Text("导入文档") }
                        TextButton(onClick = { newEditor = false; editor = JSONObject(item.toString()) }) { Text("编辑资料库") }
                        TextButton(onClick={documents=item}){Text("文档与片段")}
                        TextButton(onClick={vm.run("关联知识库"){
                            val data=vm.api.bot("/api/v1/config-profiles/default") as JSONObject
                            val config=JSONObject(data.getJSONObject("config").toString())
                            val names=config.optJSONArray("kb_names")?:JSONArray()
                            val name=item.getString("kb_name")
                            if((0 until names.length()).none{names.optString(it)==name})names.put(name)
                            config.put("kb_names",names);vm.api.bot("/api/v1/config-profiles/default","PUT",config)
                            vm.notice="知识库已关联到默认聊天配置"
                        }},enabled=!vm.busy){Text("用于默认聊天")}
                        TextButton(onClick={confirm="删除这个知识库及其中的索引？" to {vm.run("删除知识库"){vm.api.bot("/api/kb/delete","POST",JSONObject().put("kb_id",item.getString("kb_id")));refresh++}}}){Text("删除知识库")}
                    }
                    StudioPage.HISTORY -> TextButton(onClick = {
                        vm.run("读取聊天记录") {
                            val detail = vm.api.bot("/api/conversation/detail", "POST", JSONObject().put("user_id", item.optString("user_id")).put("cid", item.optString("cid", item.optString("conversation_id")))) as JSONObject
                            selected = detail
                        }
                    }) { Text("阅读对话") }
                    StudioPage.SESSIONS -> {
                        TextButton(onClick={sessionConfig=item.getString("umo")},enabled=!vm.busy){Text("专属聊天配置")}
                        val active = item.optBoolean("session_enabled", true)
                        StudioSwitch("回应此会话", "只调整这一段会话的响应", active, !vm.busy) { value ->
                            vm.run("调整会话") { vm.api.bot("/api/session/batch-update-service", "POST", JSONObject().put("umos", JSONArray().put(item.getString("umo"))).put("session_enabled", value)); refresh++ }
                        }
                    }
                    else -> Unit
                }
            }
        }
        if (all.size >= 50 && page in listOf(StudioPage.HISTORY, StudioPage.SESSIONS, StudioPage.KNOWLEDGE))
            Text("当前显示最近 50 条。", style = MaterialTheme.typography.bodySmall)
    }
    if (page == StudioPage.PERSONAS) StudioCard("内置的鲸鱼") {
        Text("需要恢复默认鲸语人设时，可重新应用内置预设。原有其他人设会保留。", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = {
            vm.run("应用内置人设") {
                val context = vm.getApplication<android.app.Application>()
                val preset = withContext(Dispatchers.IO) { PersonaLibrary.read(context).firstOrNull { it.id == "blue-whale" } } ?: error("未找到内置人设")
                RuntimeService.command(context, "persona-apply", updateId = preset.id)
            }
        }) { Text("应用鲸语人设") }
    }
    sessionConfig?.let { origin -> StudioSessionConfiguration(vm,origin){sessionConfig=null} }
    pluginConfig?.let { name -> StudioPluginConfiguration(vm,name){pluginConfig=null} }
    documents?.let { kb -> StudioKnowledgeDocuments(vm,kb){documents=null} }
    selected?.let { item ->
        AlertDialog(onDismissRequest = { selected = null }, title = { Text(if (page == StudioPage.PERSONAS) itemTitle(item) else "对话记录") },
            text = { SelectionContainer { Column(Modifier.heightIn(max = 430.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (page == StudioPage.PERSONAS) Text(item.optString("system_prompt", "暂无人设内容"))
                else {
                    val history = when (val h = item.opt("history")) { is JSONArray -> h; is String -> runCatching { JSONArray(h) }.getOrDefault(JSONArray()); else -> JSONArray() }
                    if (history.length() == 0) Text("暂无可显示的文字记录")
                    for (i in 0 until history.length()) {
                        val msg = history.optJSONObject(i) ?: continue
                        Text(if (msg.optString("role") == "user") "你" else "鲸鱼", fontWeight = FontWeight.Bold)
                        Text(readMessageText(msg.opt("content")))
                    }
                }
            } } }, confirmButton = { TextButton(onClick = { selected = null }) { Text("关闭") } })
    }
    editor?.let { initial ->
        StudioEditor(page, initial, newEditor, onDismiss = { editor = null }, onSave = { values ->
            vm.run("保存${page.title}") {
                when (page) {
                    StudioPage.PERSONAS -> vm.api.bot("/api/persona/" + if (!newEditor) "update" else "create", "POST", values)
                    StudioPage.PLUGINS -> vm.api.bot("/api/plugin/install", "POST", values)
                    StudioPage.KNOWLEDGE -> vm.api.bot("/api/kb/" + if (initial.has("kb_id")) "update" else "create", "POST", values)
                    StudioPage.SCHEDULE -> vm.api.bot("/api/cron/jobs" + if (initial.has("job_id")) "/${urlValue(initial.getString("job_id"))}" else "", if (initial.has("job_id")) "PATCH" else "POST", values)
                    else -> Unit
                }
                editor = null; refresh++; vm.notice = "已保存"
            }
        })
    }
    confirm?.let { (text, action) -> AlertDialog(onDismissRequest = { confirm = null }, title = { Text("确认操作") }, text = { Text(text) },
        confirmButton = { TextButton(onClick = { confirm = null; action() }) { Text("确认") } }, dismissButton = { TextButton(onClick = { confirm = null }) { Text("取消") } }) }
}

internal fun readMessageText(value: Any?): String = when (value) {
    is String -> value
    is JSONArray -> (0 until value.length()).map { value.opt(it) }.joinToString("\n") { part ->
        if (part is JSONObject) part.optString("text", part.optString("content", "[附件]")) else part.toString()
    }
    else -> value?.toString().orEmpty()
}

@Composable private fun StudioEditor(page: StudioPage, initial: JSONObject, isNew: Boolean, onDismiss: () -> Unit, onSave: (JSONObject) -> Unit) {
    val fields = when (page) {
        StudioPage.PERSONAS -> listOf("persona_id" to "人设名称", "system_prompt" to "性格与行为设定")
        StudioPage.PLUGINS -> listOf("url" to "GitHub 仓库 HTTPS 地址")
        StudioPage.KNOWLEDGE -> listOf("kb_name" to "资料库名称", "description" to "说明", "embedding_provider_id" to "嵌入模型 ID", "rerank_provider_id" to "重排模型 ID（可留空）")
        else -> listOf("name" to "任务名称") +
            (if (initial.optBoolean("run_once")) listOf("run_at" to "执行时间（例如 2026-12-01T09:00:00）") else listOf("cron_expression" to "时间规则（Cron）")) +
            listOf("session" to "目标会话标识", "note" to "届时让鲸鱼做什么", "timezone" to "时区")
    }
    val values = remember(initial, page) { mutableStateMapOf<String, String>().apply {
        fields.forEach { (key, _) ->
            val fallback = if (key == "timezone") java.util.TimeZone.getDefault().id else ""
            put(key, if (initial.isNull(key)) fallback else initial.optString(key, fallback))
        }
        if (page == StudioPage.SCHEDULE) initial.optJSONObject("payload")?.let { payload ->
            listOf("session", "note").forEach { key -> if (get(key).isNullOrBlank()) put(key, payload.optString(key)) }
        }
    } }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (initial.length() == 0) "新建${page.title}" else "编辑${page.title}") }, text = {
        Column(Modifier.heightIn(max = 470.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            fields.forEach { (key, label) -> StudioField(values[key].orEmpty(), { values[key] = it }, label,
                multiline = key in listOf("system_prompt", "note", "description"), enabled = !(key == "persona_id" && !isNew)) }
            if (page == StudioPage.SCHEDULE && !initial.optBoolean("run_once")) Text("例如：0 9 * * * 为每天 09:00。新任务默认暂停，保存后可手动启用。\n目标会话标识可在 QQ 中用 /sid 查看。", style = MaterialTheme.typography.bodySmall)
            if (page == StudioPage.KNOWLEDGE) Text("需要先配置一个云端嵌入模型，ID 与服务配置中的模型 ID 一致。", style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = {
        val first = fields.first().first
        if (values[first].isNullOrBlank()) { error = "请填写${fields.first().second}"; return@TextButton }
        if (page == StudioPage.PLUGINS && !values["url"].orEmpty().matches(Regex("https://github\\.com/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+/?"))) {
            error = "请填写完整的 GitHub HTTPS 仓库地址"; return@TextButton
        }
        if (page == StudioPage.KNOWLEDGE && values["embedding_provider_id"].isNullOrBlank()) {
            error = "请先选择或填写云端嵌入模型 ID"; return@TextButton
        }
        if (page == StudioPage.SCHEDULE && listOf("session", "note", if (initial.optBoolean("run_once")) "run_at" else "cron_expression").any { values[it].isNullOrBlank() }) {
            error = "请填写执行时间、目标会话和任务内容"; return@TextButton
        }
        val result = JSONObject(initial.toString()); fields.forEach { (key, _) -> result.put(key, values[key].orEmpty().trim()) }
        if (page == StudioPage.KNOWLEDGE && values["rerank_provider_id"].isNullOrBlank()) result.put("rerank_provider_id", JSONObject.NULL)
        if (page == StudioPage.SCHEDULE && !initial.has("job_id")) result.put("enabled", false)
        onSave(result)
    }) { Text("保存") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
