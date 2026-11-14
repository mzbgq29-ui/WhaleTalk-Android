// SPDX-License-Identifier: MIT
// Copyright (c) 2026 mzbgq
package io.astrbot.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.astrbot.android.core.*
import io.astrbot.android.runtime.*
import kotlinx.coroutines.*
import org.json.JSONObject

/** Product UI. Runtime code is a dependency; upstream Android screens are never launched. */
open class BigFishStudioActivity : ComponentActivity() {
    private val model by viewModels<StudioModel>()
    private var firstCreation = false
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private fun command(operation: String, id: ServiceId? = null) {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        runCatching { RuntimeService.command(this, operation, id) }.onFailure { model.error = "任务未能启动，请检查后台运行权限" }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); firstCreation = savedInstanceState == null; enableEdgeToEdge()
        setContent { BigFishTheme { StudioApp(model, ::command) { if (isTaskRoot) moveTaskToBack(true) else finish() } } }
    }
    @Composable private fun StudioApp(vm: StudioModel, command: (String, ServiceId?) -> Unit, exit: () -> Unit) {
        val state by RuntimeStore.state.collectAsStateWithLifecycle()
        val keyboard = WindowInsets.ime.getBottom(LocalDensity.current) > 0
        val roots = listOf(StudioPage.HOME, StudioPage.CHAT, StudioPage.TOOLS, StudioPage.ME)
        val root = vm.page in roots
        val context = LocalContext.current
        val diagnostics = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            if (uri != null) RuntimeService.command(context, "export-diagnostics", sourceUri = uri.toString())
        }
        BackHandler { if (!vm.back()) { if (!root && vm.page != StudioPage.SETUP) vm.go(StudioPage.HOME, true) else exit() } }
        LaunchedEffect(vm.loaded) {
            if (vm.loaded && firstCreation) {
                val requested = intent.getStringExtra("studioPage")
                val old = intent.getStringExtra("openSettingsRoute")
                val route = StudioPage.entries.firstOrNull { it.name == requested } ?: when {
                    intent.hasExtra("openLogService") -> StudioPage.LOGS
                    old == "BACKUPS" -> StudioPage.BACKUP
                    old == "BACKGROUND" -> StudioPage.BACKGROUND
                    old == "DIAGNOSTICS" -> StudioPage.DIAGNOSTICS
                    intent.getStringExtra("openManagerPage") == "PERSONAS" -> StudioPage.PERSONAS
                    intent.getBooleanExtra("editModel", false) -> StudioPage.MODEL
                    else -> null
                }
                if (route != null) vm.go(route)
            }
        }
        LaunchedEffect(state.completedOperation) {
            if (state.completedOperation != null && state.completedOperation == vm.configRequest && !state.busy && state.error == null) {
                vm.configRequest = null; vm.reload(); vm.go(StudioPage.QQ); vm.notice = "配置已保存，现在可以登录 QQ"
            }
        }
        Scaffold(containerColor = Color.Transparent, topBar = {
            Column(Modifier.statusBarsPadding().padding(horizontal = 18.dp).padding(top = 8.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 58.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (!root) TextButton(onClick = { if (!vm.back()) vm.go(StudioPage.HOME, true) }, Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) { Text("‹", fontSize = 32.sp) }
                    Column(Modifier.weight(1f)) {
                        Text(if (root) "WHALETALK  /  mzbgq" else vm.page.section, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        Text(vm.page.title, fontSize = if (root) 30.sp else 25.sp, fontWeight = FontWeight.Bold)
                    }
                    if (root) StudioMascot(Modifier.size(48.dp).clickable { vm.go(StudioPage.ABOUT) })
                }
                if (vm.busy || state.busy) StudioCard {
                    Text(if (vm.busy) vm.task else state.phase, style = MaterialTheme.typography.bodySmall)
                    val fraction = state.progressFraction
                    if (!vm.busy && fraction != null) {
                        LinearProgressIndicator(progress = { fraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                        Text("${(fraction.coerceIn(0f, 1f) * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
                    } else LinearProgressIndicator(Modifier.fillMaxWidth())
                    TextButton(onClick = { if (vm.busy) vm.cancel() else command("stop", null) }) { Text(if (vm.busy) "取消当前操作" else "取消任务并停止服务") }
                }
            }
        }, bottomBar = {
            if (root && !keyboard) LiquidPanel(Modifier.navigationBarsPadding().padding(horizontal = 20.dp, vertical = 10.dp), RoundedCornerShape(32.dp)) {
                Row(Modifier.fillMaxWidth().padding(7.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    roots.forEach { page ->
                        val selected = vm.page == page
                        Column(Modifier.weight(1f).clip(RoundedCornerShape(26.dp))
                            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                            .clickable { vm.go(page, true) }.padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            StudioGlyph(page.title, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(page.title, style = MaterialTheme.typography.labelSmall, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                }
            }
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).clipToBounds().imePadding()) {
                (vm.error ?: state.error)?.let { text ->
                    Surface(Modifier.padding(horizontal = 20.dp).padding(bottom = 12.dp), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.errorContainer) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { vm.go(StudioPage.LOGS) }) { Text("日志") }
                            TextButton(onClick = { diagnostics.launch("WhaleTalk-diagnostics.zip") }) { Text("导出诊断包") }
                        }
                    }
                }
                vm.notice?.let { Text(it, Modifier.padding(horizontal = 24.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
                if (vm.page == StudioPage.CHAT) StudioChat(vm)
                else if (vm.page == StudioPage.PERSONA_LIBRARY) StudioPersonaLibrary(vm)
                else key(vm.page) {
                  Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    when (vm.page) {
                        StudioPage.HOME -> StudioHome(vm, state, command)
                        StudioPage.SETUP -> StudioSetup(vm, state, command)
                        StudioPage.MODEL -> StudioModelPage(vm, state)
                        StudioPage.PROVIDERS -> StudioProviders(vm)
                        StudioPage.ENGINE -> StudioEngineSettings(vm)
                        StudioPage.CAPABILITIES -> StudioCapabilities(vm)
                        StudioPage.PLUGIN_MARKET -> StudioPluginMarket(vm)
                        StudioPage.QQ -> StudioQQ(vm, state, command)
                        StudioPage.ACCESS -> StudioAccess(vm, state)
                        StudioPage.TOOLS -> StudioTools(vm)
                        StudioPage.ME -> StudioMe(vm)
                        StudioPage.SERVICES -> StudioServices(vm, state, command)
                        StudioPage.PERSONAS, StudioPage.HISTORY, StudioPage.SESSIONS, StudioPage.PLUGINS,
                        StudioPage.KNOWLEDGE, StudioPage.SCHEDULE, StudioPage.STATS -> StudioCollection(vm, state)
                        else -> StudioMaintenance(vm, state, command)
                    }
                  }
                }
            }
        }
    }
}

@Composable private fun StudioHome(vm: StudioModel, state: ManagerState, command: (String, ServiceId?) -> Unit) {
    val qq = state.services.getValue(ServiceId.SNOWLUMA)
    val bot = state.services.getValue(ServiceId.ASTRBOT)
    val online = qq.qq == QqState.ONLINE && qq.oneBot == OneBotState.CONNECTED && qq.ready && bot.ready
    StudioCard(accent = true) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (online) "鲸鱼在，\n随时聊聊。" else "今天也，\n有我在。", fontSize = 26.sp, lineHeight = 35.sp, fontWeight = FontWeight.Bold)
                Text(if (online) "QQ 已连接，去打个招呼吧。" else "让她上线，或者先在这里聊聊。", style = MaterialTheme.typography.bodySmall)
            }
            StudioMascot(Modifier.size(if (LocalBigFishCompact.current) 80.dp else 112.dp))
        }
        LiquidButton(onClick = { command(if (state.services.values.any { it.desiredRunning }) "stop" else "start", null) },
            Modifier.fillMaxWidth(), enabled = state.componentsReady && !state.busy && !vm.busy) {
            Text(if (state.services.values.any { it.desiredRunning }) "暂停陪伴" else "让鲸鱼上线")
        }
    }
    if (!state.componentsReady || !vm.configured) StudioCard {
        StudioTile("完成初次设置", "准备环境、连接模型，再登录 QQ。", { vm.go(StudioPage.SETUP) })
    }
    if (state.busy || qq.desiredRunning) StartupChecklist(state, qq, vm.configured)
    StudioCard("连接一览") {
        StudioStatus("QQ 账号", qqAccountLabel(state, qq), qq.qq == QqState.ONLINE)
        StudioStatus("消息通道", channelLabel(state, qq), qq.oneBot == OneBotState.CONNECTED)
        StudioStatus("云端模型", if (vm.configured) vm.model else "尚未配置", vm.configured)
        StudioTile("QQ 登录与连接", "打开登录画面，处理验证或重新登录", { vm.go(StudioPage.QQ) })
    }
    StudioCard("常用") {
        StudioTile("和她聊聊", "在手机上直接开始一段对话", { vm.go(StudioPage.CHAT, true) })
        StudioTile("回应方式", "谁能聊天、群聊怎么唤醒", { vm.go(StudioPage.ACCESS) })
        StudioTile("人设与性格", "鲸语，或你亲手写下的性格", { vm.go(StudioPage.PERSONAS) })
    }
    Text("本机运行 · 云端思考 · mzbgq", Modifier.fillMaxWidth(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable private fun StudioSetup(vm: StudioModel, state: ManagerState, command: (String, ServiceId?) -> Unit) {
    val context = LocalContext.current
    val arm = Build.SUPPORTED_ABIS.contains("arm64-v8a")
    val space = context.filesDir.usableSpace / (1024.0 * 1024 * 1024)
    StudioCard(accent = true) {
        StudioMascot(Modifier.size(84.dp))
        Text("给鲸鱼安个家。", fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Text("只需三步，其余交给鲸语。")
    }
    StudioCard("01  准备小窝", "首次解压需要一些时间，请保持应用在前台。") {
        StudioStatus("运行环境", if (state.componentsReady) "已准备" else "待准备", state.componentsReady)
        StudioStatus("手机架构", if (arm) "ARM64" else "当前架构不支持", arm)
        StudioStatus("剩余空间", String.format(java.util.Locale.ROOT, "%.1f GB", space), space >= 3)
        Text("建议预留 6 GB。环境已经包含在完整安装包中。", style = MaterialTheme.typography.bodySmall)
        LiquidButton(onClick = { command("initialize", null) }, Modifier.fillMaxWidth(), enabled = !state.busy && arm && BuildConfig.BUNDLED_RUNTIME) {
            Text(if (state.componentsReady) "重新检查环境" else "准备环境")
        }
    }
    StudioCard("02  连接思考", "使用你的云端模型 API，不在手机上加载大模型。") {
        StudioTile("主人和模型", "填写主人 QQ、服务商与 API Key", { vm.go(StudioPage.MODEL) }, if (vm.configured) "已配置" else null)
    }
    StudioCard("03  认识 QQ", "登录机器人的 QQ 小号，再用主人 QQ 与她聊天。") {
        StudioTile("登录 QQ", "登录画面直接显示在应用中", { vm.go(StudioPage.QQ) })
    }
    LiquidOutlinedButton(onClick = { vm.go(StudioPage.HOME, true) }, Modifier.fillMaxWidth()) { Text("进入鲸鱼首页") }
}

@Composable private fun StudioModelPage(vm: StudioModel, state: ManagerState) {
    val enabled = !vm.busy && !state.busy
    StudioCard("主人", "这里填你日常使用的 QQ 大号，机器人小号稍后登录。") {
        StudioField(vm.owner, { vm.owner = it.take(20) }, "主人 QQ 号", numeric = true, enabled = enabled)
    }
    StudioCard("云端模型", "兼容 Chat Completions 的服务均可手动配置。") {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BigFishModelPresets.all.forEach { preset ->
                LiquidChoice(vm.endpoint == preset.endpoint, {
                    if (vm.endpoint != preset.endpoint) vm.key = ""
                    vm.endpoint = preset.endpoint; vm.model = preset.model
                }, { Text(preset.title) }, enabled = enabled)
            }
        }
        StudioModelHelp()
        StudioField(vm.endpoint, { if (vm.endpoint != it) vm.key = ""; vm.endpoint = it }, "API 地址", enabled = enabled)
        StudioField(vm.model, { vm.model = it }, "模型名称", enabled = enabled)
        StudioField(vm.key, { vm.key = it }, "API Key", secret = true, enabled = enabled,
            hint = if (vm.configured) "留空可继续使用此地址已保存的密钥" else "仅用于连接你选择的服务商")
        val context = androidx.compose.ui.platform.LocalContext.current
        val discoveryEndpoint = vm.endpoint
        val discoveryKey = vm.key
        StudioModelPicker(discoveryEndpoint, enabled, keyReader = {
            discoveryKey.ifBlank { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                BigFishSetupRepository(context).savedKeyFor(io.astrbot.android.core.BigFishInput.normalizeEndpoint(discoveryEndpoint))
            } }
        }, choose = { vm.model = it })
    }
    LiquidOutlinedButton(vm::testModel, Modifier.fillMaxWidth(), enabled = enabled) { Text("测试当前模型（不保存）") }
    Text("测试会发送一次短请求并显示耗时，可能产生少量费用。", style = MaterialTheme.typography.bodySmall)
    LiquidButton(vm::saveConfig, Modifier.fillMaxWidth(), enabled = enabled && state.componentsReady && vm.loaded) { Text("验证连接并保存") }
    if (!state.componentsReady) StudioEmpty("先准备运行环境", "返回初次设置，完成「准备小窝」后再保存模型。")
    Text("云端调用由服务商计费。密钥不会出现在页面状态、日志导出或分享内容中。", style = MaterialTheme.typography.bodySmall)
}

@Composable private fun StudioAccess(vm: StudioModel, state: ManagerState) {
    val enabled = !vm.busy && !state.busy
    var presetUndo by remember { mutableStateOf<Triple<String, String, BigFishOptions>?>(null) }
    var selectedPreset by remember { mutableStateOf("") }
    fun preset(name: String, apply: () -> Unit) {
        presetUndo = Triple(vm.friends, vm.groups, vm.options)
        apply(); selectedPreset = name
    }
    StudioCard("懒人回应预设", "选择后会填好下面的选项，点击底部「保存并应用偏好」才生效。好友和群仍按允许名单回应。") {
        LiquidOutlinedButton(onClick = {
            preset("只和主人聊") {
                vm.friends = ""; vm.groups = ""
                vm.options = vm.options.copy(friendNeedsWake = false, groupMentionOnly = true)
            }
        }, Modifier.fillMaxWidth(), enabled = enabled) { Text("只和主人聊 · 暂时清空好友与群名单") }
        LiquidOutlinedButton(onClick = {
            preset("好友自然聊") {
                vm.groups = ""
                vm.options = vm.options.copy(friendNeedsWake = false, replyWithQuote = false)
            }
        }, Modifier.fillMaxWidth(), enabled = enabled) { Text("好友自然聊 · 私聊无需唤醒，关闭群聊") }
        LiquidOutlinedButton(onClick = {
            preset("群聊仅 @") {
                vm.options = vm.options.copy(groupMentionOnly = true, replyWithQuote = true, replyWithMention = false)
            }
        }, Modifier.fillMaxWidth(), enabled = enabled) { Text("群聊仅 @ · 保留名单，引用原消息") }
        if (selectedPreset.isNotBlank()) Text("当前草稿预设：$selectedPreset", style = MaterialTheme.typography.bodySmall)
        presetUndo?.let { previous ->
            TextButton(onClick = {
                vm.friends = previous.first; vm.groups = previous.second; vm.options = previous.third
                presetUndo = null; selectedPreset = ""
            }, enabled = enabled) { Text("撤销上次预设") }
        }
    }
    StudioCard("允许谁聊天", "用逗号或换行分隔 QQ 号、群号。主人始终被允许。") {
        StudioField(vm.friends, { vm.friends = it }, "好友 QQ 号", multiline = true, enabled = enabled)
        StudioField(vm.groups, { vm.groups = it }, "允许的群号", multiline = true, enabled = enabled)
    }
    StudioCard("怎么叫醒她") {
        StudioField(vm.wakeWord, { vm.wakeWord = it }, "唤醒词", enabled = enabled)
        StudioOptionFields(vm, enabled)
    }
    LiquidButton(vm::saveConfig, Modifier.fillMaxWidth(), enabled = enabled && state.componentsReady) { Text("保存并应用偏好") }
}

@Composable private fun StudioQQ(vm: StudioModel, state: ManagerState, command: (String, ServiceId?) -> Unit) {
    val service = state.services.getValue(ServiceId.SNOWLUMA)
    val context = LocalContext.current
    var fullScreen by remember { mutableStateOf(false) }
    var offeredLogin by remember { mutableStateOf(false) }
    var automaticLogin by remember { mutableStateOf(false) }
    val qqVersion = remember(context) { io.astrbot.android.runtime.DesktopRepository(context).qqVersion }
    val snowLuma = remember(context) { BigFishSnowLuma(context) }
    var statusCommand by remember { mutableStateOf(snowLuma.statusCommandEnabled()) }
    var fullDesktop by remember { mutableStateOf(snowLuma.fullDesktopEnabled()) }
    LaunchedEffect(service.canOpenWebUi, service.qq) {
        if (service.canOpenWebUi && service.qq != QqState.ONLINE && !offeredLogin) {
            offeredLogin = true; automaticLogin = true; fullScreen = true
        }
    }
    LaunchedEffect(service.qq, automaticLogin) {
        if (service.qq == QqState.ONLINE && automaticLogin) {
            delay(1500); fullScreen = false; automaticLogin = false
        }
    }
    StudioCard("QQ 登录", "SnowLuma · QQ $qqVersion · ARM64") {
        Text(if (service.qq == QqState.ONLINE) "QQ 已登录。登录画面按需打开，收起后停止传输画面，QQ 保持在线。" else "服务就绪后自动打开大屏登录。扫码后在手机 QQ 中确认；安全验证也在登录画面处理。")
        if (!service.canOpenWebUi) {
            LiquidButton(onClick = { command("start", ServiceId.SNOWLUMA) }, Modifier.fillMaxWidth(), enabled = state.componentsReady && !state.busy) { Text("启动 QQ 服务") }
        } else {
            LiquidButton(onClick = { automaticLogin = false; fullScreen = true }, Modifier.fillMaxWidth()) { Text("打开大屏登录画面") }
        }
        LiquidOutlinedButton(onClick = {
            runCatching { context.startActivity(context.packageManager.getLaunchIntentForPackage("com.tencent.mobileqq") ?: error("未安装 QQ")) }
                .onFailure { vm.error = "这台手机尚未安装 QQ，可在另一台设备扫码" }
        }, Modifier.fillMaxWidth()) { Text("打开手机 QQ") }
    }
    if (service.canOpenWebUi && !fullScreen) {
        LiquidOutlinedButton(onClick = { command("restart", ServiceId.SNOWLUMA) }, Modifier.fillMaxWidth(), enabled = !state.busy) { Text("重新启动 QQ 服务") }
    }
    if (fullScreen) androidx.compose.ui.window.Dialog(onDismissRequest = { fullScreen = false; automaticLogin = false },
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().systemBarsPadding().padding(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("QQ 登录", style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = { fullScreen = false; automaticLogin = false }) { Text("收起画面") }
                }
                StudioQqDesktop(Modifier.weight(1f), initiallyEnlarged = true)
            }
        }
    }
    StudioCard("连接状态") {
        StudioStatus("QQ 在线", if (service.qq == QqState.ONLINE) "已确认" else "待确认", service.qq == QqState.ONLINE)
        StudioStatus("消息通道", if (service.oneBot == OneBotState.CONNECTED) "已连通" else "等待连接", service.oneBot == OneBotState.CONNECTED)
        StudioTile("运行服务", "启动机器人引擎、重连和停止服务", { vm.go(StudioPage.SERVICES) })
    }
    StudioCard("状态查询") {
        StudioSwitch("启用 SnowLuma 状态指令", "默认指令 #sl，直接返回版本和运行时间，不调用模型。修改开关会重新启动正在运行的 QQ 服务。", statusCommand, !state.busy && !vm.busy) { value ->
            runCatching {
                snowLuma.setStatusCommandEnabled(value)
                statusCommand = value
                if (service.desiredRunning) command("restart", ServiceId.SNOWLUMA)
            }.onFailure { vm.error = "状态指令设置未能保存，请重试" }
        }
        Text("在 QQ 中单独发送纯文字 #sl，不要同时 @、引用或带图片。默认两次查询至少间隔 5 秒；已有的自定义触发词和冷却时间会保留。", style = MaterialTheme.typography.bodySmall)
    }
    StudioCard("QQ 版本锁定") {
        var versionLock by remember { mutableStateOf(snowLuma.versionLockEnabled()) }
        val pinned = remember(context) { QqHotUpdateGuard.pinnedVersion(context) }
        StudioSwitch("锁定 Linux QQ 版本", "默认开启。发现热更新模块时，会把当前版本改回 $pinned，并把新版本记入错误列表。不会删除账号数据。", versionLock, !state.busy && !vm.busy) { value ->
            runCatching {
                snowLuma.setVersionLockEnabled(value)
                versionLock = value
            }.onFailure { vm.error = "QQ 版本锁定设置未能保存，请重试" }
        }
    }
    StudioCard("掉线通知") {
        val alerts = remember(context) { ConnectionAlerts(context) }
        var disconnectAlerts by remember { mutableStateOf(alerts.enabled()) }
        StudioSwitch("QQ 或消息通道掉线时通知我", "只在已经成功连接之后断开时提醒，启动过程中的「未知」状态不会通知。", disconnectAlerts, !vm.busy) { value ->
            runCatching { alerts.setEnabled(value); disconnectAlerts = value }
                .onFailure { vm.error = "掉线通知设置未能保存，请重试" }
        }
    }
    StudioCard("画面与占用") {
        StudioSwitch("完整电脑桌面", "默认使用 720×960 手机竖屏。若验证窗口放不下，可切换为 1280×800 完整桌面；修改会重启 QQ 服务。", fullDesktop, !state.busy && !vm.busy) { value ->
            runCatching {
                snowLuma.setFullDesktopEnabled(value); fullDesktop = value
                if (service.desiredRunning) command("restart", ServiceId.SNOWLUMA)
            }.onFailure { vm.error = "画面设置未能保存，请重试" }
        }
        Text("离开登录画面会断开画面连接，保持机器人消息服务在线。", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable private fun StudioTools(vm: StudioModel) {
    var query by rememberSaveable { mutableStateOf("") }
    StudioField(query, { query = it }, "搜索工具")
    listOf("陪伴" to listOf(StudioPage.PERSONAS, StudioPage.PERSONA_LIBRARY, StudioPage.HISTORY, StudioPage.SESSIONS),
        "能力" to listOf(StudioPage.COMPANIONS, StudioPage.CAPABILITIES, StudioPage.ENGINE, StudioPage.MODEL, StudioPage.PROVIDERS, StudioPage.PLUGINS, StudioPage.KNOWLEDGE, StudioPage.SCHEDULE),
        "照料" to listOf(StudioPage.DIAGNOSTICS, StudioPage.STATS, StudioPage.SERVICES, StudioPage.BACKUP, StudioPage.LOGS, StudioPage.STORAGE),
        "进阶" to listOf(StudioPage.TERMINAL, StudioPage.EXTENSIONS)).forEach { (title, pages) ->
        val visible = pages.filter { query.isBlank() || (it.title + it.section).contains(query.trim(), true) }
        if (visible.isNotEmpty()) StudioCard(title) { visible.forEach { page -> StudioTile(page.title, page.section, { vm.go(page) }) } }
    }
}

@Composable private fun StudioMe(vm: StudioModel) {
    StudioCard(accent = true) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            StudioMascot(Modifier.size(76.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("鲸语", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("有自己的小世界。", style = MaterialTheme.typography.bodySmall)
                Text("作者  mzbgq", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
    StudioCard("偏好") {
        listOf(StudioPage.APPEARANCE, StudioPage.ACCESS, StudioPage.BACKGROUND, StudioPage.ACCOUNT).forEach { p ->
            StudioTile(p.title, if (p == StudioPage.APPEARANCE) "浅色、深色或跟随系统" else p.section, { vm.go(p) })
        }
    }
    StudioCard("更多") {
        listOf(StudioPage.DIAGNOSTICS, StudioPage.GUIDE, StudioPage.ABOUT, StudioPage.LICENSES).forEach { p -> StudioTile(p.title, p.section, { vm.go(p) }) }
    }
}

@Composable private fun StudioServices(vm: StudioModel, state: ManagerState, command: (String, ServiceId?) -> Unit) {
    ServiceId.entries.forEach { id ->
        val status = state.services.getValue(id)
        StudioCard(if (id == ServiceId.ASTRBOT) "思考引擎" else "QQ 连接", if (id == ServiceId.ASTRBOT) "AstrBot" else "SnowLuma 2.4.2 · Linux QQ") {
            StudioStatus("当前状态", if (status.ready) "运行中" else if (status.desiredRunning) "正在启动" else "已停止", status.ready)
            state.versions[id.label]?.let { StudioStatus("组件版本", it) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LiquidButton(onClick = { command(if (status.desiredRunning) "stop" else "start", id) }, Modifier.weight(1f), enabled = !state.busy && state.componentsReady) { Text(if (status.desiredRunning) "停止" else "启动") }
                LiquidOutlinedButton(onClick = { command("restart", id) }, Modifier.weight(1f), enabled = !state.busy && state.componentsReady) { Text("重启") }
            }
        }
    }
    StudioCard {
        StudioTile("查看日志", "启动失败、掉线与配置问题", { vm.go(StudioPage.LOGS) })
        StudioTile("初次设置", "准备环境和连接模型", { vm.go(StudioPage.SETUP) })
        LiquidOutlinedButton(onClick = { command("reconcile", null) }, Modifier.fillMaxWidth(), enabled = !state.busy) { Text("恢复中断的任务") }
    }
}

private fun preparingRuntime(state: ManagerState): Boolean {
    val phase = state.phase
    return state.busy && listOf("准备 SnowLuma", "校验内置包", "解压", "切换运行层", "清理旧文件").any { it in phase }
}

private fun qqAccountLabel(state: ManagerState, qq: ServiceStatus): String = when {
    preparingRuntime(state) -> "正在准备运行层"
    qq.qq == QqState.ONLINE -> "已登录"
    qq.qq == QqState.OFFLINE -> "已掉线"
    qq.qq == QqState.VERIFICATION_REQUIRED -> "等待验证"
    qq.desiredRunning && qq.process == ProcessState.RUNNING -> "已启动，等待登录"
    qq.desiredRunning || state.busy -> "正在启动"
    else -> "尚未连接"
}

private fun channelLabel(state: ManagerState, qq: ServiceStatus): String = when {
    qq.oneBot == OneBotState.CONNECTED -> "已连通"
    preparingRuntime(state) -> "等待运行层准备完成"
    qq.desiredRunning && qq.process == ProcessState.RUNNING && qq.qq == QqState.ONLINE -> "已登录，等待消息通道"
    qq.desiredRunning || state.busy -> "等待连接"
    else -> "等待连接"
}

@Composable private fun StartupChecklist(state: ManagerState, qq: ServiceStatus, configured: Boolean) {
    val prepared = state.componentsReady && !preparingRuntime(state)
    val started = qq.process == ProcessState.RUNNING || qq.ready
    val loggedIn = qq.qq == QqState.ONLINE
    val connected = qq.oneBot == OneBotState.CONNECTED
    StudioCard("启动进度") {
        StudioStatus("环境就绪", if (state.componentsReady) "完成" else "进行中", state.componentsReady)
        StudioStatus("准备运行层", when {
            preparingRuntime(state) -> state.phase
            prepared -> "完成"
            else -> "等待开始"
        }, prepared)
        StudioStatus("启动服务", if (started) "完成" else if (prepared && qq.desiredRunning) "进行中" else "等待开始", started)
        StudioStatus("扫码登录", if (loggedIn) "完成" else if (started) "等待扫码或确认" else "等待开始", loggedIn)
        StudioStatus("通道已连接", if (connected) "完成" else if (loggedIn) "进行中" else "等待开始", connected)
        if (!configured) Text("还可以先去配置云端模型。", style = MaterialTheme.typography.bodySmall)
    }
}
