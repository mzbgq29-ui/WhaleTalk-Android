// SPDX-License-Identifier: MIT
// Copyright (c) 2026 mzbgq
package io.astrbot.android

import android.app.Application
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.astrbot.android.core.*
import io.astrbot.android.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

internal enum class StudioPage(val title: String, val section: String = "鲸鱼工作室") {
    HOME("鲸鱼"), CHAT("聊天"), TOOLS("工具"), ME("我的"),
    SETUP("安个家", "开始使用"), MODEL("云端模型", "思考方式"), PROVIDERS("模型库", "更多云端能力"), QQ("连接 QQ", "陪伴从这里开始"),
    ACCESS("回应方式", "聊天偏好"), PERSONAS("人设", "她的另一面"), PERSONA_LIBRARY("人设仓库", "角色与灵感"), ENGINE("引擎设置", "AstrBot 原生控制"), CAPABILITIES("工具与技能", "扩展模型能力"), HISTORY("聊天记录", "留住每次对话"),
    SESSIONS("会话", "独立的聊天空间"), PLUGINS("插件", "更多可能"), PLUGIN_MARKET("发现插件", "社区的灵感"), KNOWLEDGE("知识库", "自己的资料"),
    SCHEDULE("定时任务", "按时出现"), STATS("用量", "每次思考的记录"), LOGS("运行日志", "发生了什么"),
    BACKUP("备份与恢复", "留一份安心"), STORAGE("存储空间", "手机里的小窝"), BACKGROUND("后台运行", "保持连接"),
    APPEARANCE("外观", "你的风格"), ABOUT("关于鲸语", "由 mzbgq 制作"), LICENSES("开源许可", "感谢每一份贡献"),
    SERVICES("运行服务", "本机控制"), ACCOUNT("本机管理账号", "私有连接"), GUIDE("使用指南", "一起慢慢来"),
    COMPANIONS("陪伴增强", "记忆与拟人聊天"), DIAGNOSTICS("快速排障", "设备与连接建议"), TERMINAL("终端", "高级工具"), EXTENSIONS("扩展服务", "按需启用")
}

internal data class StudioMessage(val mine: Boolean, val text: String)

internal class StudioModel(application: Application) : AndroidViewModel(application) {
    val api = StudioApi(application)
    val history = mutableStateListOf<StudioPage>()
    var page by mutableStateOf(StudioPage.HOME)
    var loaded by mutableStateOf(false)
    var owner by mutableStateOf("")
    var endpoint by mutableStateOf("https://api.deepseek.com/v1")
    var model by mutableStateOf("deepseek-chat")
    var key by mutableStateOf("")
    var friends by mutableStateOf("")
    var groups by mutableStateOf("")
    var options by mutableStateOf(BigFishOptions())
    var wakeWord by mutableStateOf("鲸语")
    var configured by mutableStateOf(false)
    var configRequest by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
    var task by mutableStateOf("")
    private var displayedError by mutableStateOf<String?>(null)
    var error: String?
        get() = displayedError
        set(value) { displayedError = value?.let(io.astrbot.android.core.SecretRedactor::redact)?.take(240) }
    var notice by mutableStateOf<String?>(null)
    var session by mutableStateOf("")
    var chatDraft by mutableStateOf("")
    val messages = mutableStateListOf<StudioMessage>()
    var chatting by mutableStateOf(false)
    var stoppingChat by mutableStateOf(false)
    private var chatJob: Job? = null
    private var work: Job? = null
    private val context get() = getApplication<Application>()
    init {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { RuntimeStore.operationLock.withLock { RuntimeStore.refreshInstallation(context) } }
                reload()
                if (!RuntimeStore.state.value.componentsReady || !configured) page = StudioPage.SETUP
                val repository = BigFishSetupRepository(context)
                if (RuntimeStore.owner == null && repository.pending) configRequest = RuntimeService.command(context, "bigfish-configure")
                else if (RuntimeStore.owner == null && (repository.needsRecovery || BackgroundRecovery.hasDesiredServices(context)))
                    RuntimeService.command(context, "reconcile")
            } catch (_: Exception) { error = "暂时无法读取运行状态，可在运行日志中查看详情" }
            loaded = true
        }
    }
    suspend fun reload() {
        val record = withContext(Dispatchers.IO) { BigFishSetupRepository(context).summary() }
        configured = record != null
        record?.let {
            owner = it.optString("owner"); endpoint = it.optString("endpoint", endpoint); model = it.optString("model", model)
            friends = it.optString("friends"); groups = it.optString("groups"); options = BigFishConfig.readOptions(it.optJSONObject("options")); wakeWord = options.wakeWord
        }
    }
    fun go(value: StudioPage, root: Boolean = false) {
        if (value == page) return
        if (root) history.clear() else history.add(page)
        page = value; error = null; notice = null
    }
    fun back(): Boolean {
        if (history.isEmpty()) return false
        page = history.removeAt(history.lastIndex); error = null; return true
    }
    fun run(label: String, block: suspend () -> Unit) {
        if (busy) return
        busy = true; task = label; error = null; notice = null
        work = viewModelScope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) { error = e.message?.let(io.astrbot.android.core.SecretRedactor::redact)?.take(240) ?: "操作未完成，请稍后重试" }
            finally { busy = false; task = ""; work = null }
        }
    }
    fun cancel() { work?.cancel() }
    fun saveConfig() = run("验证模型并保存配置") {
        check(!RuntimeStore.state.value.busy) { "请等待当前任务完成" }
        val repository = BigFishSetupRepository(context)
        val preferencesOnly = page == StudioPage.ACCESS
        val input = withContext(Dispatchers.IO) {
            val saved = repository.summary()
            if (preferencesOnly) {
                check(saved != null) { "请先在云端模型页面保存主人和模型配置" }
                val savedEndpoint = saved.getString("endpoint")
                BigFishInput(saved.getString("owner"), savedEndpoint, saved.getString("model"), repository.savedKeyFor(savedEndpoint),
                    friends, groups, options.copy(wakeWord = wakeWord.trim()))
            } else BigFishInput(owner, endpoint, model, key.ifBlank { repository.savedKeyFor(endpoint) },
                saved?.optString("friends").orEmpty(), saved?.optString("groups").orEmpty(),
                saved?.let { BigFishConfig.readOptions(it.optJSONObject("options")) } ?: BigFishOptions())
        }
        if (!preferencesOnly) BigFishModelVerifier().verify(input)
        withContext(Dispatchers.IO) { RuntimeStore.operationLock.withLock {
            check(!RuntimeStore.state.value.busy) { "当前服务正在处理任务，请稍后重试" }
            repository.request(input, System.currentTimeMillis())
        } }
        if (!preferencesOnly) ModelRecents(context).remember(input.endpoint, input.model)
        configRequest = RuntimeService.command(context, "bigfish-configure"); key = ""; notice = if (preferencesOnly) "正在应用回应设置" else "已验证模型，正在应用配置"
    }
    fun testModel() = run("测试当前模型连接") {
        val base = endpoint; val selectedModel = model; val suppliedKey = key
        val credentials = withContext(Dispatchers.IO) {
            suppliedKey.ifBlank { BigFishSetupRepository(context).savedKeyFor(base) }
        }
        val input = BigFishInput("100001", base, selectedModel, credentials)
        val begin = android.os.SystemClock.elapsedRealtime()
        BigFishModelVerifier().verify(input)
        val elapsed = android.os.SystemClock.elapsedRealtime() - begin
        ModelRecents(context).remember(input.endpoint, input.model)
        notice = "连接测试通过 · ${elapsed} 毫秒 · 配置未保存"
    }
    fun send() {
        if (chatting || stoppingChat || chatDraft.isBlank()) return
        val text = chatDraft.trim(); chatDraft = ""; messages.add(StudioMessage(true, text)); chatting = true; error = null
        chatJob = viewModelScope.launch {
            try {
                if (session.isBlank()) session = (api.bot("/api/chat/new_session") as JSONObject).getString("session_id")
                messages.add(StudioMessage(false, "")); val index = messages.lastIndex
                api.sendChat(session, text) { event ->
                    withContext(Dispatchers.Main) {
                        if (event.optString("type") == "error") kotlin.error(event.optString("data", "对话失败"))
                        if (event.optString("type") == "plain" && event.optString("chain_type") !in setOf("reasoning", "think")) {
                            val chunk = event.optString("data")
                            messages[index] = messages[index].copy(text = if (event.optBoolean("streaming")) messages[index].text + chunk else chunk)
                        }
                    }
                }
                if (messages[index].text.isBlank()) messages[index] = messages[index].copy(text = "这次没有收到文字回复，请检查模型或插件设置。")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) { error = e.message?.let(io.astrbot.android.core.SecretRedactor::redact)?.take(220) ?: "没有收到回复，请稍后重试" }
            finally { chatting = false; chatJob = null }
        }
    }
    fun stopChat() {
        if (stoppingChat) return
        val current = session
        val pending = chatJob
        stoppingChat = true
        viewModelScope.launch {
            try {
                pending?.cancelAndJoin()
                if (current.isNotBlank()) api.bot("/api/chat/stop", "POST", JSONObject().put("session_id", current))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                // Do not reuse a session whose remote stop was not acknowledged.
                session = ""
                error = "未确认远端已停止，下次发送会建立新对话"
            } finally { stoppingChat = false }
        }
    }
    override fun onCleared() { key = ""; api.close(); super.onCleared() }
}
