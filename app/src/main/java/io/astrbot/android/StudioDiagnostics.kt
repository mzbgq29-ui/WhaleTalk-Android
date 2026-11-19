package io.astrbot.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import io.astrbot.android.core.*
import io.astrbot.android.runtime.*
import kotlinx.coroutines.*

/** Read-only local guidance, with an explicit copy action and no cloud requests. */
@Composable internal fun StudioDiagnostics(vm: StudioModel, state: ManagerState) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var revision by remember { mutableIntStateOf(0) }
    var device by remember { mutableStateOf("") }
    var reading by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var probing by remember { mutableStateOf(false) }
    var portChecks by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    LaunchedEffect(revision) {
        reading = true; copied = false
        try { device = withContext(Dispatchers.IO) { BigFishDeviceReport.read(context) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { device = "部分设备信息暂时无法读取，可重试或查看运行日志。" }
        finally { reading = false }
    }
    val qq = state.services.getValue(ServiceId.SNOWLUMA)
    val bot = state.services.getValue(ServiceId.ASTRBOT)
    val suggestions = buildList<Pair<String, StudioPage>> {
        if (!state.componentsReady) add("先完成运行环境准备。首次解压时保持前台，并预留足够存储。" to StudioPage.SETUP)
        if (!vm.configured) add("填写服务商、API Key 和聊天模型，再验证连接并保存。" to StudioPage.MODEL)
        if (bot.process == ProcessState.CRASHED) add("AstrBot 意外退出，请查看运行日志。" to StudioPage.LOGS)
        if (qq.process == ProcessState.CRASHED) add("QQ 连接服务意外退出，查看日志后可在连接 QQ 页重新启动。" to StudioPage.QQ)
        if (state.componentsReady && !bot.desiredRunning && !qq.desiredRunning) add("服务已停止，可在运行页启动。" to StudioPage.HOME)
        when (qq.qq) {
            QqState.LOGGED_OUT -> add("QQ 尚未登录。" to StudioPage.QQ)
            QqState.VERIFICATION_REQUIRED -> add("QQ 需要安全验证。" to StudioPage.QQ)
            QqState.OFFLINE -> add("QQ 已掉线，请查看登录画面。" to StudioPage.QQ)
            else -> Unit
        }
        if (qq.qq == QqState.ONLINE && qq.oneBot != OneBotState.CONNECTED) add("QQ 已登录但消息通道尚未连通，先确认思考引擎正在运行，再查看连接日志。" to StudioPage.SERVICES)
        if (bot.health == HealthState.UNRESPONSIVE) add("思考引擎没有及时响应，可先查看日志，确认没有维护任务后重新启动。" to StudioPage.LOGS)
    }
    val primary = suggestions.firstOrNull() ?: if (qq.oneBot == OneBotState.CONNECTED) {
        "消息通道已连通，可以试算允许名单和唤醒规则。" to StudioPage.ACCESS
    } else {
        "尚未确认消息通道状态，请查看运行详情。" to StudioPage.SERVICES
    }
    val serviceSummary = buildString {
        appendLine("运行环境：${if (state.componentsReady) "已准备" else "待准备"}")
        appendLine("模型配置：${if (vm.configured) "已保存" else "未保存"}（未发云端测试请求）")
        appendLine("思考引擎：${bot.process} / ${bot.health}")
        appendLine("QQ 连接服务：${qq.process} / ${qq.health}")
        appendLine("QQ 在线状态：${qq.qq}；消息通道：${qq.oneBot}")
    }
    StudioCard("为什么没回复？", accent = true) {
        Text(primary.first)
        LiquidButton(onClick = { vm.go(primary.second) }) { Text(primary.second.title) }
    }
    StudioCard("状态详情") {
        Text(serviceSummary)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LiquidOutlinedButton(onClick = { revision++ }, Modifier.weight(1f), enabled = !reading) { Text(if (reading) "检查中…" else "重新检查") }
            LiquidOutlinedButton(onClick = {
                clipboard.setText(AnnotatedString(device + "\n" + serviceSummary +
                    portChecks.joinToString("\n", prefix = "\n本机连接检测：\n") { "${it.first}：${it.second}" } +
                    "\n建议：\n" + suggestions.joinToString("\n") { it.first }))
                copied = true
            }, Modifier.weight(1f), enabled = device.isNotBlank() && !reading) { Text(if (copied) "已复制" else "复制诊断") }
        }
        Text("复制内容不含 QQ 号、API Key、登录画面或聊天记录。", style = MaterialTheme.typography.bodySmall)
    }
    StudioCard("本机连接自检", "手动检查本机服务是否在监听，不发送聊天消息、不调用模型。检测结果不代表 QQ 已登录。") {
        LiquidOutlinedButton(onClick = {
            scope.launch {
                probing = true; copied = false
                try {
                    portChecks = ServiceId.entries.map { id ->
                        val connection = state.webUis.getValue(id)
                        val running = state.services.getValue(id).process == ProcessState.RUNNING
                        async {
                            val name = if (id == ServiceId.ASTRBOT) "思考引擎" else "QQ 管理服务"
                            val endpoint = connection.endpoint
                            name to if (!connection.enabled) "管理入口已关闭" else if (endpoint == null) "尚未识别本机地址" else {
                                val result = LocalPortProbe.check(endpoint.port, endpoint.host)
                                when (result.status) {
                                    LocalPortProbe.Status.OPEN -> if (running) "可以连接 · ${result.elapsedMillis} 毫秒"
                                        else "端口已有响应，本应用服务未运行；请检查旧版或其他应用是否占用"
                                    LocalPortProbe.Status.CLOSED -> "未监听，请启动对应服务后重试"
                                    LocalPortProbe.Status.TIMEOUT -> "连接超时，请查看运行日志"
                                    LocalPortProbe.Status.UNAVAILABLE -> "暂时无法检测，请稍后重试"
                                }
                            }
                        }
                    }.awaitAll()
                } finally { probing = false }
            }
        }, Modifier.fillMaxWidth(), enabled = !probing) { Text(if (probing) "正在检测…" else "检测本机连接") }
        portChecks.forEach { (name, result) -> StudioStatus(name, result) }
    }
    StudioCard("接下来可以做") {
        suggestions.drop(1).forEach { (text, page) -> StudioTile(page.title, text, { vm.go(page) }) }
        StudioTile("后台运行", "检查电池豁免、运行通知与自启动权限。", { vm.go(StudioPage.BACKGROUND) })
        StudioTile("模型连接测试", "去模型页手动测试，并查看耗时；可能产生少量云端费用。", { vm.go(StudioPage.MODEL) })
    }
    StudioCard("设备信息") { SelectionContainer { Text(device.ifBlank { "正在读取…" }, style = MaterialTheme.typography.bodySmall) } }
}
