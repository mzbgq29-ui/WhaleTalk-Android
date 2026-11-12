package io.astrbot.android

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.astrbot.android.core.ServiceId
import io.astrbot.android.runtime.*
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import kotlinx.coroutines.sync.withLock

@Composable internal fun StudioCompanions(vm: StudioModel, state: ManagerState) {
    val context = LocalContext.current
    val repository = remember { BundledCompanionPlugins(context) }
    var options by remember { mutableStateOf(CompanionSettings()) }
    var loaded by remember { mutableStateOf(false) }
    var memoryInstalled by remember { mutableStateOf(false) }
    var chatInstalled by remember { mutableStateOf(false) }
    LaunchedEffect(state.completedOperation) {
        try {
            withContext(Dispatchers.IO) { Triple(repository.readSettings(), repository.installed(CompanionConfiguration.MEMORY), repository.installed(CompanionConfiguration.GROUP)) }
                .let { (saved, memory, chat) -> options = saved; memoryInstalled = memory; chatInstalled = chat }
            loaded = true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { vm.error = "增强设置暂时无法读取，请查看日志" }
    }
    val enabled = loaded && !state.busy && !vm.busy
    StudioCard("内置陪伴增强", "源码和 ARM64 依赖已经在 APK 里；首次启动思考引擎时离线部署。已有同名插件会保留，不覆盖你的安装。") {
        StudioStatus("Memory Companion 2.6.6", if (memoryInstalled) "已部署，加载状态见插件页" else "随包，等待首次启动")
        StudioStatus("Chat Plus 1.2.3.hotfix.2", if (chatInstalled) "已部署，加载状态见插件页" else "随包，等待首次启动")
        StudioTile("插件加载状态", "查看实际启用情况或错误，不以文件存在代替运行成功", { vm.go(StudioPage.PLUGINS) })
    }
    StudioCard("她会记得你", "本地数据库保存偏好与对话线索，按会话和人格隔离。已关闭嵌入、重排和其他服务 API。") {
        StudioSwitch("长期记忆", "启用记忆采集与自动召回；关闭会保留已有数据。", options.memory, enabled) { options = options.copy(memory = it) }
        StudioSwitch("自动整理长对话", "可选：复用主聊天模型做阶段总结，会增加现有 API 用量；默认关闭。", options.summaries, enabled && options.memory) { options = options.copy(summaries = it) }
        if (options.summaries) {
            Text("每 ${options.summaryEvents} 条对话事件尝试整理（用户消息和机器人回复分别计数）")
            Slider(options.summaryEvents.toFloat(), { options = options.copy(summaryEvents = it.toInt()) }, valueRange = 8f..100f, steps = 91, enabled = enabled)
            Text("只按消息数量触发，不在空闲时批量调用；每会话每小时最多 4 次自动总结调用。", style = MaterialTheme.typography.bodySmall)
        }
        Text("QQ 中可用 /mcomp status 查看记忆状态，/mcomp search 关键词 搜索记忆。", style = MaterialTheme.typography.bodySmall)
        Text("例如告诉她「我喜欢……」或让她记住约定。工具调用、记忆注入会使用当前聊天模型的额度；关闭自动总结后也不需要额外服务密钥。", style = MaterialTheme.typography.bodySmall)
    }
    StudioCard("更自然地聊天") {
        StudioSwitch("拟人分段回复", "将较短文字回复分段，间隔约 0.4–1.2 秒；不额外调用模型。", options.naturalReplies, enabled) { options = options.copy(naturalReplies = it) }
        StudioSwitch("拟人群聊增强", "Chat Plus 只在已允许的群参与；读空气判断复用主模型，会增加 API 用量，默认关闭。", options.groupChat, enabled) { options = options.copy(groupChat = it) }
        Text("群聊增强需要先在「回应方式」添加群号，并关闭「群聊仅 @ 回应」。空群名单不会被解释成全部群。主动空闲发消息、额外图片接口、语音及独立网页面板均关闭。", style = MaterialTheme.typography.bodySmall)
        StudioTile("回应方式", "调整群号、唤醒方式与允许名单", { vm.go(StudioPage.ACCESS) })
    }
    val running = state.services.getValue(ServiceId.ASTRBOT).desiredRunning
    LiquidButton(onClick = { vm.run("保存陪伴增强设置") {
        val selected = options
        withContext(Dispatchers.IO) { RuntimeStore.operationLock.withLock {
            check(!RuntimeStore.state.value.busy) { "请等待当前任务完成" }
            if (selected.groupChat) {
                val bot = requireNotNull(DurableFiles.read(File(context.filesDir, "persistent/astrbot/data/cmd_config.json"))) { "请先保存模型和允许的群号" }
                check(CompanionConfiguration.groupChat(JSONObject(), bot, selected).getBoolean("enable_group_chat")) {
                    "先在回应方式添加群号，并关闭群聊仅 @ 回应，再启用群聊增强"
                }
            }
            repository.saveSettings(selected)
        } }
        if (running) RuntimeService.command(context, "restart", ServiceId.ASTRBOT)
        vm.notice = if (running) "增强设置已保存，正在重新启动思考引擎" else "已保存，下次启动思考引擎时应用"
    } }, Modifier.fillMaxWidth(), enabled = enabled && state.componentsReady) {
        Text(if (running) "保存并重启思考引擎" else "保存增强设置")
    }
    if (!state.componentsReady) Text("先在初次设置中准备运行环境，再保存设置。", style = MaterialTheme.typography.bodySmall)
    StudioCard("来源与许可") {
        Text("Memory Companion · menglimi · MIT\nChat Plus · Him666233 · AGPL-3.0\n原始源码、固定提交、原许可与移动配置改动说明均随包提供。", style = MaterialTheme.typography.bodySmall)
        StudioTile("开源许可", "查看原作者与完整许可声明", { vm.go(StudioPage.LICENSES) })
    }
}
