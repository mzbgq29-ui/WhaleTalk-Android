// SPDX-License-Identifier: MIT
// Copyright (c) 2026 mzbgq
package io.astrbot.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.net.Uri
import android.provider.Settings
import android.text.format.Formatter
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
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
    @OptIn(ExperimentalMaterial3Api::class)
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
        LaunchedEffect(Unit) {
            withContext(Dispatchers.IO) { runCatching { AppUpdateChecker(context).checkIfDue() } }
        }
        LaunchedEffect(state.completedOperation, state.busy) {
            if (!state.busy) vm.onConfigOperationCompleted(state.completedOperation, state.error == null)
        }
        vm.configPreview?.let { preview ->
            val running = state.services.getValue(ServiceId.ASTRBOT).desiredRunning
            AlertDialog(
                onDismissRequest = vm::closeConfigPreview,
                title = { Text("应用设置") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("改动：" + preview.changes.joinToString("、"))
                        Text(when {
                            preview.firstSetup -> "首次配置会启动 AstrBot。QQ 消息通道需要更新时，正在运行的 QQ 服务也会重启。"
                            running -> "现在应用会重启 AstrBot，QQ 继续运行。"
                            else -> "现在应用会保存设置，服务保持停止。"
                        })
                        if (!preview.firstSetup) {
                            TextButton(onClick = { vm.confirmConfig(deferred = true) }, enabled = !state.busy) {
                                Text("下次启动时应用")
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { vm.confirmConfig(deferred = false) }, enabled = !state.busy) { Text("现在应用") }
                },
                dismissButton = { TextButton(onClick = vm::closeConfigPreview) { Text("取消") } },
            )
        }
        val dockVisible = root && !keyboard
        val dockInset = if (dockVisible) 92.dp else 0.dp
        val qq = state.services.getValue(ServiceId.SNOWLUMA)
        val bot = state.services.getValue(ServiceId.ASTRBOT)
        val headerOnline = qq.qq == QqState.ONLINE && qq.oneBot == OneBotState.CONNECTED && qq.ready && bot.ready
        Box(Modifier.fillMaxSize()) {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                topBar = {
                    Column {
                        TopAppBar(
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.background,
                                scrolledContainerColor = MaterialTheme.colorScheme.background,
                            ),
                            title = {
                                Text(
                                    if (vm.page == StudioPage.HOME) "鲸语" else vm.page.title,
                                    fontWeight = FontWeight.Bold,
                                )
                            },
                            navigationIcon = {
                                if (!root) {
                                    IconButton(onClick = { if (!vm.back()) vm.go(StudioPage.HOME, true) }) {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_back),
                                            contentDescription = "返回",
                                        )
                                    }
                                }
                            },
                            actions = {
                                if (vm.page == StudioPage.HOME) {
                                    val pillText = when {
                                        headerOnline -> "在线"
                                        state.busy || vm.busy -> "处理中"
                                        state.services.values.any { it.desiredRunning } -> "连接中"
                                        else -> "未启动"
                                    }
                                    val pillTone = when {
                                        headerOnline -> StudioBadgeTone.OK
                                        state.busy || vm.busy -> StudioBadgeTone.BUSY
                                        else -> StudioBadgeTone.NEUTRAL
                                    }
                                    Box(Modifier.padding(end = 12.dp)) {
                                        StudioBadge(pillText, pillTone)
                                    }
                                }
                            },
                        )
                        if (vm.busy || state.busy) {
                            Surface(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                                shape = MaterialTheme.shapes.medium,
                                color = MaterialTheme.colorScheme.surface,
                                border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.outlineVariant),
                            ) {
                                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    PrepareProgressBar(state, fallback = if (vm.busy) vm.task else state.phase, saving = vm.busy)
                                    TextButton(onClick = { if (vm.busy) vm.cancel() else command("stop", null) }) {
                                        Text(if (vm.busy) "取消当前操作" else "取消任务并停止服务")
                                    }
                                }
                            }
                        }
                    }
                },
            ) { padding ->
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .clipToBounds()
                        .imePadding(),
                ) {
                    (vm.error ?: state.error)?.let { text ->
                        Surface(
                            modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 12.dp),
                            shape = RoundedCornerShape(18.dp),
                            color = MaterialTheme.colorScheme.errorContainer,
                            border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.25f)),
                        ) {
                            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                                TextButton(onClick = { vm.go(StudioPage.LOGS) }) { Text("日志") }
                                TextButton(onClick = { diagnostics.launch("WhaleTalk-diagnostics.zip") }) { Text("导出诊断包") }
                            }
                        }
                    }
                    vm.notice?.let {
                        Surface(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                        ) {
                            Text(
                                it,
                                Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    if (vm.page == StudioPage.CHAT) StudioChat(vm, dockInset)
                    else if (vm.page == StudioPage.PERSONA_LIBRARY) StudioPersonaLibrary(vm, dockInset)
                    else key(vm.page) {
                        Column(
                            Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 16.dp)
                                .padding(top = 4.dp, bottom = dockInset + 24.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
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
            AnimatedVisibility(
                visible = dockVisible,
                enter = fadeIn() + slideInVertically { it / 2 },
                exit = fadeOut() + slideOutVertically { it / 2 },
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                LiquidGlassBottomBar(
                    items = roots,
                    selected = vm.page,
                    onSelect = { vm.go(it, true) },
                    label = { it.title },
                    icon = { page, focused ->
                        StudioGlyph(page.title, color = LocalContentColor.current, selected = focused)
                    },
                )
            }
        }
    }
}

@Composable private fun StudioHome(vm: StudioModel, state: ManagerState, command: (String, ServiceId?) -> Unit) {
    val qq = state.services.getValue(ServiceId.SNOWLUMA)
    val bot = state.services.getValue(ServiceId.ASTRBOT)
    val online = qq.qq == QqState.ONLINE && qq.oneBot == OneBotState.CONNECTED && qq.ready && bot.ready
    val reconnect by AutoReconnectStore.state.collectAsStateWithLifecycle()
    val running = state.services.values.any { it.desiredRunning }
    val needsSetup = !state.componentsReady || !vm.configured
    val enabled = !state.busy && !vm.busy && vm.loaded
    val overview = when {
        online -> "机器人已连接"
        !vm.loaded -> "正在读取状态"
        !state.componentsReady -> "请先准备运行环境"
        !vm.configured -> "请先配置云端模型"
        !running -> "服务未启动"
        reconnect.exhausted -> "自动重连已暂停"
        qq.qq == QqState.VERIFICATION_REQUIRED -> "QQ 需要验证"
        qq.qq == QqState.LOGGED_OUT -> "QQ 需要登录"
        else -> "正在等待连接"
    }
    val subStatus = when {
        online -> "QQ 与消息通道正常，正在守护对话"
        needsSetup -> "完成环境与模型配置后即可开始对话"
        reconnect.delayMillis != null -> "将在稍后自动尝试第 ${reconnect.attempt} 次重连"
        running -> "本地服务正在启动或等待确认"
        else -> "点击下方按钮启动本地机器人服务"
    }
    val badgeTone = when {
        online -> StudioBadgeTone.OK
        reconnect.delayMillis != null || running -> StudioBadgeTone.BUSY
        reconnect.exhausted || qq.qq == QqState.VERIFICATION_REQUIRED -> StudioBadgeTone.WARN
        else -> StudioBadgeTone.NEUTRAL
    }
    val badgeText = when {
        online -> "运行正常"
        needsSetup -> "待设置"
        running -> "等待中"
        else -> "已停止"
    }
    val colors = MaterialTheme.colorScheme
    Surface(
        color = colors.surface,
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(0.75.dp, colors.outlineVariant.copy(alpha = if (LocalBigFishDark.current) 0.75f else 0.90f)),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(
                    Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .border(1.5.dp, colors.primary.copy(alpha = 0.25f), RoundedCornerShape(18.dp)),
                ) {
                    StudioMascot(Modifier.fillMaxSize())
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(overview, style = MaterialTheme.typography.titleLarge)
                    Text(subStatus, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
                StudioBadge(badgeText, badgeTone)
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LiquidButton(
                    onClick = {
                        if (needsSetup && !running) vm.go(StudioPage.SETUP)
                        else command(if (running) "stop" else "start", null)
                    },
                    modifier = Modifier.weight(1f),
                    enabled = enabled,
                ) {
                    Icon(
                        painter = painterResource(if (running) R.drawable.ic_stop else R.drawable.ic_play),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (running) "停止服务" else if (needsSetup) "开始设置" else "启动服务")
                }
                LiquidOutlinedButton(onClick = { vm.go(StudioPage.DIAGNOSTICS) }) {
                    Text("为什么没回复？")
                }
            }
        }
    }
    StudioPendingConfig(vm, enabled)
    StudioSectionHeader("连接概览")
    StudioCard {
        StudioTile("QQ 登录", qqAccountLabel(state, qq), { vm.go(StudioPage.QQ) }, iconRes = R.drawable.ic_bot)
        StudioInsetDivider(46.dp)
        val channel = if (reconnect.delayMillis != null) "等待第 ${reconnect.attempt} 次重连" else channelLabel(state, qq)
        StudioTile("消息通道", channel, { vm.go(StudioPage.DIAGNOSTICS) }, iconRes = R.drawable.ic_link)
        StudioInsetDivider(46.dp)
        StudioTile("云端模型", if (vm.configured) vm.model else "尚未配置", { vm.go(StudioPage.MODEL) }, iconRes = R.drawable.ic_ai)
    }
    StudioSectionHeader("常用管理")
    StudioCard {
        StudioTile("运行详情", "组件状态、资源用量和启停", { vm.go(StudioPage.SERVICES) }, iconRes = R.drawable.ic_settings)
        StudioInsetDivider(46.dp)
        StudioTile("回复规则", "允许名单和唤醒方式", { vm.go(StudioPage.ACCESS) }, iconRes = R.drawable.ic_persona)
    }
    StudioHomeUpdateBanner()
}

@Composable private fun StudioPendingConfig(vm: StudioModel, enabled: Boolean) {
    if (!vm.pendingConfig) return
    StudioCard("有待应用的设置", accent = true) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(vm::applyPendingConfig, enabled = enabled) { Text("现在应用") }
            TextButton(vm::discardPendingConfig, enabled = enabled) { Text("丢弃草稿") }
        }
    }
}

@Composable private fun StudioSetup(vm: StudioModel, state: ManagerState, command: (String, ServiceId?) -> Unit) {
    val context = LocalContext.current
    val arm = Build.SUPPORTED_ABIS.contains("arm64-v8a")
    val space = context.filesDir.usableSpace / (1024.0 * 1024 * 1024)
    StudioCard("准备运行环境", "首次解压时请保持应用在前台。") {
        StudioStatus("运行环境", if (state.componentsReady) "已准备" else "待准备", state.componentsReady)
        StudioStatus("手机架构", if (arm) "ARM64" else "当前架构不支持", arm)
        StudioStatus("剩余空间", String.format(java.util.Locale.ROOT, "%.1f GB", space), space >= 3)
        Text("建议预留 6 GB。环境已经包含在完整安装包中。", style = MaterialTheme.typography.bodySmall)
        LiquidButton(onClick = { command("initialize", null) }, Modifier.fillMaxWidth(), enabled = !state.busy && arm && BuildConfig.BUNDLED_RUNTIME) {
            Text(if (state.componentsReady) "重新检查环境" else "准备环境")
        }
    }
    StudioCard("配置云端模型") {
        StudioTile("主人和模型", "填写主人 QQ、服务商与 API Key", { vm.go(StudioPage.MODEL) }, if (vm.configured) "已配置" else null)
        if (vm.configured) StudioTile("本机管理账号", "填入初始密码或恢复 AstrBot 管理登录", { vm.go(StudioPage.ACCOUNT) })
    }
    StudioCard("登录机器人 QQ") {
        StudioTile("登录 QQ", "登录画面直接显示在应用中", { vm.go(StudioPage.QQ) })
    }
    LiquidOutlinedButton(onClick = { vm.go(StudioPage.HOME, true) }, Modifier.fillMaxWidth()) { Text("返回运行页") }
}

@Composable private fun StudioModelPage(vm: StudioModel, state: ManagerState) {
    val enabled = !vm.busy && !state.busy
    StudioPendingConfig(vm, enabled)
    StudioCard("账号") {
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
    LiquidButton(vm::saveConfig, Modifier.fillMaxWidth(), enabled = enabled && state.componentsReady && vm.loaded) { Text("查看改动") }
    if (!state.componentsReady) StudioEmpty("先准备运行环境", "在初次设置中完成环境准备后即可保存。")
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
    StudioPendingConfig(vm, enabled)
    StudioCard("回复预设", "选择预设后，可在保存前检查并修改草稿。") {
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
    StudioCard("唤醒方式") {
        StudioField(vm.wakeWord, { vm.wakeWord = it }, "唤醒词", enabled = enabled)
        StudioOptionFields(vm, enabled)
    }
    StudioReplyPreview(vm)
    LiquidButton(vm::saveConfig, Modifier.fillMaxWidth(), enabled = enabled && state.componentsReady) { Text("查看改动") }
}

@Composable private fun StudioReplyPreview(vm: StudioModel) {
    var group by rememberSaveable { mutableStateOf(true) }
    var recipient by rememberSaveable { mutableStateOf("") }
    var mentioned by rememberSaveable { mutableStateOf(false) }
    var sample by rememberSaveable { mutableStateOf("") }
    StudioCard("回复规则试算", "使用当前草稿检查允许名单与唤醒方式。") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LiquidChoice(group, { group = true; recipient = "" }, { Text("群聊") })
            LiquidChoice(!group, { group = false; recipient = "" }, { Text("私聊") })
        }
        StudioField(recipient, { recipient = it.take(20) }, if (group) "群号" else "好友 QQ 号", numeric = true)
        StudioField(sample, { sample = it.take(160) }, "示例消息", hint = "可在开头输入唤醒词或 / 命令")
        if (group) StudioSwitch("@机器人", "", mentioned) { mentioned = it }
        if (recipient.isNotBlank()) {
            val result = remember(group, recipient, mentioned, sample, vm.owner, vm.friends, vm.groups, vm.options, vm.wakeWord) {
                runCatching {
                    ReplyRulePreview.evaluate(vm.owner, vm.friends, vm.groups, vm.options.copy(wakeWord = vm.wakeWord.trim()),
                        recipient.trim(), group, group && mentioned, sample)
                }.getOrNull()
            }
            Text(when (result) {
                ReplyRulePreview.Result.ALLOWED -> "允许回复"
                ReplyRulePreview.Result.NOT_ALLOWED -> "未加入允许名单"
                ReplyRulePreview.Result.NOT_AWAKENED -> "未满足唤醒条件"
                null -> "请检查号码、允许名单与唤醒词的格式"
            }, style = MaterialTheme.typography.bodyMedium)
        }
    }
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
        Text(if (service.qq == QqState.ONLINE) "QQ 已登录。登录画面按需打开，收起后停止传输画面，QQ 保持在线。" else "服务就绪后自动打开大屏登录。若画面里出现二维码，会放大显示在上方，便于扫码。SnowLuma 2.4.2 没有稳定的二维码或一键登录接口，识别失败时请直接使用登录画面。安全验证也在画面中处理。")
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
    StudioCard("掉线通知与重连") {
        val alerts = remember(context) { ConnectionAlerts(context) }
        var disconnectAlerts by remember { mutableStateOf(alerts.enabled()) }
        StudioSwitch("QQ 或消息通道掉线时通知我", "只在已经成功连接之后断开时提醒，恢复成功也会通知。启动过程中的「未知」状态不会通知。", disconnectAlerts, !vm.busy) { value ->
            runCatching { alerts.setEnabled(value); disconnectAlerts = value }
                .onFailure { vm.error = "掉线通知设置未能保存，请重试" }
        }
        val reconnect = remember(context) { AutoReconnectSettings(context) }
        var autoReconnect by remember { mutableStateOf(reconnect.enabled()) }
        StudioSwitch("掉线后自动重连", "默认开启。进程退出或消息通道断开后按 5 秒、15 秒、60 秒重试，次数用尽后停止并通知。手动暂停不会被拉起。", autoReconnect, !vm.busy) { value ->
            runCatching { reconnect.setEnabled(value); autoReconnect = value }
                .onFailure { vm.error = "自动重连设置未能保存，请重试" }
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

private fun studioPageIcon(page: StudioPage): Int = when (page) {
    StudioPage.MODEL, StudioPage.PROVIDERS -> R.drawable.ic_ai
    StudioPage.ACCESS, StudioPage.PERSONAS, StudioPage.PERSONA_LIBRARY, StudioPage.COMPANIONS -> R.drawable.ic_persona
    StudioPage.HISTORY, StudioPage.SESSIONS, StudioPage.QQ -> R.drawable.ic_bot
    StudioPage.STATS, StudioPage.LOGS, StudioPage.DIAGNOSTICS -> R.drawable.ic_logs
    StudioPage.PLUGINS, StudioPage.PLUGIN_MARKET, StudioPage.CAPABILITIES, StudioPage.EXTENSIONS -> R.drawable.ic_link
    StudioPage.KNOWLEDGE, StudioPage.GUIDE, StudioPage.LICENSES -> R.drawable.ic_document
    StudioPage.SCHEDULE -> R.drawable.ic_update
    StudioPage.BACKUP -> R.drawable.ic_backup
    StudioPage.STORAGE, StudioPage.SERVICES, StudioPage.ENGINE, StudioPage.APPEARANCE -> R.drawable.ic_settings
    StudioPage.TERMINAL -> R.drawable.ic_terminal
    StudioPage.BACKGROUND -> R.drawable.ic_battery
    StudioPage.ACCOUNT -> R.drawable.ic_info
    StudioPage.ABOUT -> R.drawable.ic_info
    else -> R.drawable.ic_more
}

@Composable private fun StudioTools(vm: StudioModel) {
    var query by rememberSaveable { mutableStateOf("") }
    StudioField(query, { query = it }, "搜索工具")
    listOf(
        "模型与回复" to listOf(StudioPage.MODEL, StudioPage.ACCESS, StudioPage.PROVIDERS, StudioPage.HISTORY, StudioPage.SESSIONS, StudioPage.STATS),
        "人设与插件" to listOf(StudioPage.PERSONAS, StudioPage.PERSONA_LIBRARY, StudioPage.COMPANIONS, StudioPage.PLUGINS, StudioPage.CAPABILITIES, StudioPage.KNOWLEDGE, StudioPage.SCHEDULE),
        "日志与备份" to listOf(StudioPage.DIAGNOSTICS, StudioPage.SERVICES, StudioPage.LOGS, StudioPage.BACKUP, StudioPage.STORAGE),
        "进阶" to listOf(StudioPage.ENGINE, StudioPage.TERMINAL, StudioPage.EXTENSIONS),
    ).forEach { (title, pages) ->
        val visible = pages.filter { query.isBlank() || (it.title + it.section).contains(query.trim(), true) }
        if (visible.isNotEmpty()) {
            StudioSectionHeader(title)
            StudioCard {
                Column {
                    visible.forEachIndexed { index, page ->
                        if (index > 0) StudioInsetDivider(46.dp)
                        StudioTile(page.title, page.section, { vm.go(page) }, iconRes = studioPageIcon(page))
                    }
                }
            }
        }
    }
}

@Composable private fun StudioMe(vm: StudioModel) {
    StudioSectionHeader("偏好")
    StudioCard {
        val prefPages = listOf(StudioPage.APPEARANCE, StudioPage.BACKGROUND, StudioPage.ACCOUNT)
        prefPages.forEachIndexed { index, p ->
            if (index > 0) StudioInsetDivider(46.dp)
            StudioTile(p.title, p.section, { vm.go(p) }, iconRes = studioPageIcon(p))
        }
    }
    StudioSectionHeader("更多")
    StudioCard {
        val morePages = listOf(StudioPage.DIAGNOSTICS, StudioPage.GUIDE, StudioPage.ABOUT, StudioPage.LICENSES)
        morePages.forEachIndexed { index, p ->
            if (index > 0) StudioInsetDivider(46.dp)
            StudioTile(p.title, p.section, { vm.go(p) }, iconRes = studioPageIcon(p))
        }
    }
}

@Composable private fun StudioServices(vm: StudioModel, state: ManagerState, command: (String, ServiceId?) -> Unit) {
    val snow = state.services.getValue(ServiceId.SNOWLUMA)
    val bot = state.services.getValue(ServiceId.ASTRBOT)
    if (state.busy || snow.desiredRunning) StartupChecklist(state, snow, vm.configured)
    ServiceId.entries.forEach { id ->
        val status = state.services.getValue(id)
        StudioCard(if (id == ServiceId.ASTRBOT) "思考引擎" else "QQ 连接", if (id == ServiceId.ASTRBOT) "AstrBot" else "SnowLuma 2.4.2 · Linux QQ") {
            StudioStatus("当前状态", layerLabel(status), status.ready)
            state.versions[id.label]?.let { StudioStatus("组件版本", it) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LiquidButton(onClick = { command(if (status.desiredRunning) "stop" else "start", id) }, Modifier.weight(1f), enabled = !state.busy && state.componentsReady) { Text(if (status.desiredRunning) "停止" else "启动") }
                LiquidOutlinedButton(onClick = { command("restart", id) }, Modifier.weight(1f), enabled = !state.busy && state.componentsReady) { Text("重启") }
            }
        }
    }
    StudioRuntimeDashboard(state, snow, bot)
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

@Composable private fun StudioHomeUpdateBanner() {
    val update by AppUpdateStore.state.collectAsStateWithLifecycle()
    val candidate = update?.candidate ?: return
    val uriHandler = LocalUriHandler.current
    StudioCard("发现新版本 ${candidate.versionName}", "可到 GitHub Releases 下载安装包。覆盖安装即可从 1.4.0 升级，不会自动下载或安装。") {
        LiquidButton(onClick = { uriHandler.openUri(candidate.pageUrl) }, Modifier.fillMaxWidth()) { Text("打开下载页") }
    }
}

@Composable private fun StudioRuntimeDashboard(state: ManagerState, qq: ServiceStatus, bot: ServiceStatus) {
    val context = LocalContext.current
    var resources by remember { mutableStateOf(ResourceSnapshot()) }
    LaunchedEffect(Unit) {
        val monitor = ResourceMonitor(context)
        while (isActive) {
            resources = runCatching { monitor.sample(400) }.getOrDefault(resources)
            delay(2600)
        }
    }
    val reconnect by AutoReconnectStore.state.collectAsStateWithLifecycle()
    val exempt = remember { context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName) }
    val colorOs = listOf(Build.MANUFACTURER, Build.BRAND, Build.FINGERPRINT).any { it.contains("oneplus", true) || it.contains("oppo", true) || it.contains("realme", true) }
    StudioCard("资源用量") {
        val memoryBytes = (resources.memory.totalKiB ?: resources.memory.partialKiB)?.times(1024)
        StudioStatus("CPU", resources.cpuPercent?.let { String.format(java.util.Locale.CHINA, "约 %.1f%%", it) } ?: "测量中", resources.cpuPercent != null)
        StudioStatus("内存", memoryBytes?.let { Formatter.formatShortFileSize(context, it) } ?: "测量中", memoryBytes != null)
        val uptime = state.runtimeStartedAtElapsed?.let { SystemClock.elapsedRealtime() - it }?.takeIf { it >= 0 }
        StudioStatus("运行时长", uptime?.let { formatUptime(it) } ?: "未运行", uptime != null)
        StudioStatus("Ubuntu 环境", if (state.componentsReady) "已就绪" else if (state.environmentInstalled) "基础已安装" else "未准备", state.componentsReady)
        val retryDelay = reconnect.delayMillis
        if (reconnect.exhausted) Text("自动重连已暂停：${reconnect.layer ?: "服务"}多次恢复失败，请手动检查。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        else if (retryDelay != null) Text("正在自动重连${reconnect.layer ?: ""}（第 ${reconnect.attempt} 次，约 ${retryDelay / 1000} 秒后重试）。", style = MaterialTheme.typography.bodySmall)
    }
    if (!exempt && (qq.desiredRunning || bot.desiredRunning)) StudioCard("后台容易被系统结束") {
        Text(if (colorOs) "ColorOS 还需要在「应用启动管理」中允许鲸语自启动和关联启动，并在最近任务中锁定应用。" else "建议先关闭电池优化，再在系统设置里允许后台运行。")
        LiquidOutlinedButton(onClick = {
            runCatching { context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))) }
        }, Modifier.fillMaxWidth()) { Text("申请电池优化豁免") }
    }
}

private fun layerLabel(status: ServiceStatus): String = when (status.process) {
    ProcessState.RUNNING -> if (status.ready) "运行中" else "已启动，等待就绪"
    ProcessState.STARTING -> "正在启动"
    ProcessState.STOPPING -> "正在停止"
    ProcessState.CRASHED -> status.detail.ifBlank { "异常退出" }
    ProcessState.STOPPED -> "已停止"
}

private fun formatUptime(ms: Long): String {
    val total = ms / 1000
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    return when {
        hours > 0 -> "${hours} 小时 ${minutes} 分"
        minutes > 0 -> "${minutes} 分 ${seconds} 秒"
        else -> "${seconds} 秒"
    }
}
