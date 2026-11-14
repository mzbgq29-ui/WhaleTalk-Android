// SPDX-License-Identifier: MIT
// Copyright (c) 2026 mzbgq
package io.astrbot.android

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.text.format.Formatter
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.astrbot.android.core.*
import io.astrbot.android.runtime.*
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

@Composable internal fun StudioMaintenance(vm: StudioModel, state: ManagerState, command: (String, ServiceId?) -> Unit) {
    when (vm.page) {
        StudioPage.COMPANIONS -> StudioCompanions(vm, state)
        StudioPage.DIAGNOSTICS -> StudioDiagnostics(vm, state)
        StudioPage.LOGS -> StudioLogs(vm)
        StudioPage.BACKUP -> StudioBackups(vm, state)
        StudioPage.STORAGE -> StudioStorage(vm)
        StudioPage.BACKGROUND -> StudioBackground(vm)
        StudioPage.APPEARANCE -> StudioAppearance()
        StudioPage.ACCOUNT -> StudioAccount(vm)
        StudioPage.LICENSES -> StudioLicenses(vm)
        StudioPage.TERMINAL -> StudioTerminal(vm, state, command)
        StudioPage.EXTENSIONS -> StudioExtensions(vm, state, command)
        StudioPage.ABOUT -> {
            StudioCard(accent = true) {
                StudioMascot(Modifier.size(100.dp)); Text("鲸语", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                StudioStatus("作者", "mzbgq"); StudioStatus("版本", BuildConfig.VERSION_NAME)
                Text("给手机上的 QQ 伙伴，一个自己的小世界。", style = MaterialTheme.typography.bodyMedium)
            }
            StudioAppUpdateCard()
            if (BuildConfig.APPLICATION_ID.endsWith(".qqfix")) StudioCard("与旧版并存") {
                Text("这是独立安装的修复版，数据与旧版分别保存。启动本版服务前，请先在旧版停止服务，避免端口占用。")
                StudioTile("迁移已有设置", "先在旧版导出备份，再到本版的备份与恢复中导入", { vm.go(StudioPage.BACKUP) })
            }
            StudioCard("关于这个应用") {
                Text("独立设计的安卓操作界面。运行环境内置于完整安装包，机器人和 QQ 连接在本机运行，模型使用你配置的云端 API。")
                Text("此私用版本使用 SnowLuma 2.4.2 连接 QQ，运行层还包含 PRoot、AstrBot 等组件。各组件的原作者与许可声明均保留。", style = MaterialTheme.typography.bodySmall)
                StudioTile("开源许可与原作者", "查看随包分发的完整声明", { vm.go(StudioPage.LICENSES) })
            }
            StudioArtwork(vm)
        }
        else -> {
            StudioCard("第一次和她说话") {
                Text("先准备环境、保存模型，然后登录机器人 QQ 小号。用你的主人 QQ 私聊小号，即可开始聊天。")
                StudioTile("开始设置", "环境、模型和 QQ 登录", { vm.go(StudioPage.SETUP) })
            }
            StudioCard("在群聊里") { Text("先在「回应方式」添加群号，再 @机器人或使用唤醒词。开启「群聊仅 @回应」后，需要 @机器人。") }
            StudioCard("QQ 快捷指令", "点复制后粘贴到 QQ 自行发送，应用不会替你发送。") {
                val commandClipboard = LocalClipboardManager.current
                listOf("/help" to "查看已启用命令", "/new" to "开始新会话", "/reset" to "重置当前上下文", "/sid" to "查看当前会话标识").forEach { (command, description) ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(command, fontFamily = FontFamily.Monospace)
                            Text(description, style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { commandClipboard.setText(AnnotatedString(command)); vm.notice = "已复制 $command，请在 QQ 中自行发送" }) { Text("复制") }
                    }
                }
                Text("手机聊天页的会话与 QQ 会话分别保存。", style = MaterialTheme.typography.bodySmall)
            }
            StudioCard("让她留在后台") { StudioTile("后台运行", "电池豁免、通知和重启恢复", { vm.go(StudioPage.BACKGROUND) }) }
        }
    }
}

@Composable private fun StudioLogs(vm: StudioModel) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var category by rememberSaveable { mutableStateOf(LogCategory.RUNTIME) }
    var paused by rememberSaveable { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    var issuesOnly by rememberSaveable { mutableStateOf(false) }
    val issuePattern = remember { Regex("\\b([A-Za-z_]*error|[A-Za-z_]*exception|warn(?:ing)?|fatal|failed|failure|panic)\\b|错误|警告|失败|异常", RegexOption.IGNORE_CASE) }
    val visible = remember(text, query, issuesOnly) {
        text.lineSequence().filter { line ->
            (query.isBlank() || line.contains(query.trim(), ignoreCase = true)) &&
                (!issuesOnly || issuePattern.containsMatchIn(line))
        }.joinToString("\n")
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) vm.run("导出运行日志") { withContext(Dispatchers.IO) {
            context.contentResolver.openOutputStream(uri, "w")!!.bufferedWriter().use { RuntimeStore.log(context, category).exportTo(it) }
        }; vm.notice = "日志已保存" }
    }
    val diagnostics = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) RuntimeService.command(context, "export-diagnostics", sourceUri = uri.toString())
    }
    LaunchedEffect(category, paused) {
        text = withContext(Dispatchers.IO) { RuntimeStore.log(context, category).tail(200) }
        if (!paused) while (isActive) {
            delay(2000)
            text = withContext(Dispatchers.IO) { RuntimeStore.log(context, category).tail(200) }
        }
    }
    val recent=remember { runCatching { io.astrbot.android.runtime.DurableFiles.read(java.io.File(context.filesDir,"manager/last-preparation-error.json")) }.getOrNull() }
    recent?.let { item -> StudioCard("最近一次操作失败", java.text.DateFormat.getDateTimeInstance().format(java.util.Date(item.optLong("at")))) {
        SelectionContainer { Text(item.optString("details"),style=MaterialTheme.typography.bodySmall) }
    } }
    StudioCard {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LogCategory.entries.forEach { value ->
                LiquidChoice(category == value, { category = value }, { Text(if (value == LogCategory.MANAGER) "应用" else value.label) })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LiquidOutlinedButton(onClick = { paused = !paused }, Modifier.weight(1f)) { Text(if (paused) "继续刷新" else "暂停刷新") }
            LiquidOutlinedButton(onClick = { export.launch("WhaleTalk-${category.name.lowercase()}.log") }, Modifier.weight(1f)) { Text("导出日志") }
        }
        StudioField(query, { query = it.take(120) }, "搜索日志", hint = "输入关键词，仅筛选最近 200 行")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LiquidChoice(issuesOnly, { issuesOnly = !issuesOnly }, { Text("只看警告与错误") })
            TextButton(onClick = { query = ""; issuesOnly = false }, enabled = query.isNotEmpty() || issuesOnly) { Text("清除筛选") }
        }
        LiquidOutlinedButton(onClick = {
            clipboard.setText(AnnotatedString(visible)); vm.notice = "已复制当前筛选结果"
        }, Modifier.fillMaxWidth(), enabled = visible.isNotBlank()) { Text("复制当前结果") }
    }
    StudioCard("最近 200 行", "日志由运行层脱敏后展示。暂停刷新后可长按选取文字。") {
        SelectionContainer { Text(visible.ifBlank { if (text.isBlank()) "还没有日志" else "没有匹配的日志，可清除筛选查看上下文" }, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
        if (issuesOnly) Text("筛选仅显示匹配行。查看完整报错上下文请关闭筛选；导出日志仍保留完整内容。", style = MaterialTheme.typography.bodySmall)
    }
    LiquidOutlinedButton(onClick = { diagnostics.launch("WhaleTalk-diagnostics.zip") }, Modifier.fillMaxWidth()) { Text("导出诊断包") }
}

@Composable private fun StudioBackups(vm: StudioModel, state: ManagerState) {
    val context = LocalContext.current
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) RuntimeService.command(context, "export-backup", sourceUri = uri.toString())
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            RuntimeService.command(context, "prepare-backup", sourceUri = uri.toString())
        }
    }
    StudioCard("留一份安心", "备份可能包含 QQ 会话和 API 密钥，请保存在自己信任的位置。") {
        LiquidButton(onClick = { RuntimeService.command(context, "backup") }, Modifier.fillMaxWidth(), enabled = !state.busy && state.componentsReady) { Text("创建本机快照") }
        LiquidButton(onClick = { RuntimeService.command(context, "shared-backup") }, Modifier.fillMaxWidth(), enabled = !state.busy && state.componentsReady) { Text("立即备份到下载目录") }
        LiquidOutlinedButton(onClick = { export.launch("WhaleTalk-backup.zip") }, Modifier.fillMaxWidth(), enabled = !state.busy && state.componentsReady) { Text("导出便携备份（不含 QQ 登录）") }
        LiquidOutlinedButton(onClick = { import.launch(arrayOf("application/zip", "application/octet-stream")) }, Modifier.fillMaxWidth(), enabled = !state.busy) { Text("选择备份文件恢复") }
    }
    StudioCard("自动备份", "默认每天把登录状态、AstrBot 配置和插件备份到 Download/WhaleTalk/backups，最多保留 5 份，不会打包缓存。恢复前会先停止运行层并校验文件。") {
        listOf(SharedBackupInterval.OFF to "关闭", SharedBackupInterval.DAILY to "每天", SharedBackupInterval.WEEKLY to "每周").forEach { (id, label) ->
            LiquidChoice(state.sharedBackupInterval == id, {
                runCatching { SharedBackupStore(context).setInterval(id); RuntimeStore.state.value = RuntimeStore.state.value.copy(sharedBackupInterval = id) }
                    .onFailure { vm.error = it.message ?: "自动备份设置未能保存" }
            }, { Text(label) }, enabled = !state.busy)
        }
    }
    if (state.sharedBackups.isNotEmpty()) StudioCard("下载目录中的备份") {
        state.sharedBackups.forEach { item ->
            StudioStatus(item.name, Formatter.formatFileSize(context, item.bytes))
            TextButton(onClick = { confirm = "使用这份下载目录备份恢复？当前数据会被替换，恢复前会自动保存一份快照，运行层会先停止。" to {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(item.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                RuntimeService.command(context, "prepare-backup", sourceUri = item.uri.toString())
            } }, enabled = !state.busy) { Text("恢复此备份") }
        }
    }
    if (state.portableImport != null) StudioCard("备份已准备好", "恢复会替换当前机器人数据，运行层会先保存恢复前快照。") {
        LiquidButton(onClick = { confirm = "使用选中的备份替换当前数据？" to { RuntimeService.command(context, "apply-backup") } }, Modifier.fillMaxWidth(), enabled = !state.busy) { Text("恢复这份备份") }
        TextButton(onClick = { RuntimeService.command(context, "discard-backup") }) { Text("取消导入") }
    }
    if (state.backups.isEmpty()) StudioEmpty("还没有快照", "创建后，可在这里恢复或删除。")
    state.backups.forEach { backup ->
        StudioCard(java.text.DateFormat.getDateTimeInstance().format(java.util.Date(backup.createdAt))) {
            StudioStatus("占用空间", Formatter.formatFileSize(context, backup.bytes))
            Row {
                TextButton(onClick = { confirm = "恢复此快照？当前数据会被替换，恢复前会自动保存一份快照。" to {
                    RuntimeService.command(context, "restore", backupId = backup.id)
                } }, enabled = !state.busy) { Text("恢复") }
                TextButton(onClick = { confirm = "删除此快照？" to { RuntimeService.command(context, "delete-backup", backupId = backup.id) } },
                    enabled = !state.busy && backup.id !in state.protectedBackupIds) { Text("删除") }
            }
        }
    }
    confirm?.let { (message, action) -> AlertDialog(onDismissRequest = { confirm = null }, title = { Text("确认恢复与备份操作") }, text = { Text(message) },
        confirmButton = { TextButton(onClick = { confirm = null; action() }) { Text("确认") } }, dismissButton = { TextButton(onClick = { confirm = null }) { Text("取消") } }) }
}

@Composable private fun StudioStorage(vm: StudioModel) {
    val context = LocalContext.current
    var report by remember { mutableStateOf<StorageUsageReport?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var reading by remember { mutableStateOf(false) }
    LaunchedEffect(refresh) { reading = true
        try { report = StorageUsageRepository(context).scan() } catch (_: Exception) { vm.error = "读取存储占用失败" }
        finally { reading = false }
    }
    StudioCard("手机里的小窝") {
        StudioStatus("剩余空间", Formatter.formatFileSize(context, context.filesDir.usableSpace))
        report?.app?.let { StudioStatus("应用安装", Formatter.formatFileSize(context, it.app)); StudioStatus("应用数据", Formatter.formatFileSize(context, it.data)) }
        TextButton(onClick = { refresh++ }, enabled = !reading) { Text(if (reading) "正在统计…" else "重新统计") }
    }
    report?.groups?.forEach { group -> StudioCard(group.title, group.scope) {
        StudioStatus("占用", Formatter.formatFileSize(context, group.bytes)); StudioStatus("文件数", group.files.toString())
        if (!group.complete) Text("统计未遍历全部内容，实际占用可能更多。", style = MaterialTheme.typography.bodySmall)
    } }
    StudioCard { StudioTile("管理快照", "在备份页删除不再需要的快照", { vm.go(StudioPage.BACKUP) })
        Text("系统的「清除数据」会删除 QQ 登录、模型配置和机器人数据。", style = MaterialTheme.typography.bodySmall) }
}

@Composable private fun StudioBackground(vm: StudioModel) {
    val context = LocalContext.current
    var recovery by remember { mutableStateOf(BackgroundRecovery.enabled(context)) }
    fun open(intent: Intent) { runCatching { context.startActivity(intent) }.onFailure { vm.error = "系统没有提供此设置入口，请在应用信息中手动设置" } }
    val colorOs = listOf(android.os.Build.MANUFACTURER, android.os.Build.BRAND).any { it.contains("oneplus", true) || it.contains("oppo", true) || it.contains("realme", true) }
    StudioCard("让陪伴持续") {
        val exempt = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
        StudioStatus("电池优化", if (exempt) "已豁免" else "尚未豁免", exempt)
        LiquidOutlinedButton(onClick = { open(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))) }, Modifier.fillMaxWidth()) { Text("申请电池优化豁免") }
        LiquidOutlinedButton(onClick = { open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }, Modifier.fillMaxWidth()) { Text("应用信息与后台权限") }
        LiquidOutlinedButton(onClick = { open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) }, Modifier.fillMaxWidth()) { Text("运行通知设置") }
        if (colorOs) Text("ColorOS 还需要在「应用启动管理」中允许鲸语自启动和关联启动，并在最近任务中锁定应用，否则后台仍可能被系统结束。", style = MaterialTheme.typography.bodySmall)
        else Text("部分手机还需要在系统中允许自启动，并在最近任务中锁定应用。强行停止应用后，需要你手动再打开。", style = MaterialTheme.typography.bodySmall)
    }
    StudioCard("掉线自动重连") {
        val reconnect = remember { AutoReconnectSettings(context) }
        var autoReconnect by remember { mutableStateOf(reconnect.enabled()) }
        StudioSwitch("QQ 或消息通道掉线后自动重连", "默认开启。按 5 秒、15 秒、60 秒退避重试，次数用尽后通知你并停止。你手动暂停服务时不会再拉起。", autoReconnect, !vm.busy) { value ->
            runCatching { reconnect.setEnabled(value); autoReconnect = value }
                .onFailure { vm.error = "自动重连设置未能保存，请重试" }
        }
    }
    StudioCard("重启后恢复") {
        StudioSwitch("恢复先前运行的服务", "手机重启并解锁后尝试恢复；不会自动执行终端命令", recovery) { value ->
            if (BackgroundRecovery.setEnabled(context, value)) recovery = value else vm.error = "设置未能保存，请重试"
        }
    }
}

@Composable private fun StudioAppearance() {
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("bigfish-ui", 0) }
    var theme by remember { mutableStateOf(preferences.getString("theme", "system")) }
    var motion by remember { mutableStateOf(preferences.getBoolean("ocean_motion", false)) }
    var dynamicColor by remember { mutableStateOf(preferences.getBoolean("dynamic_color", true)) }
    StudioCard("光与颜色", "玻璃只折射装饰背景，文字和弹窗使用独立清晰层。鲸鱼蓝绿作为主色保留。") {
        listOf("system" to "跟随系统", "light" to "浅色海面", "dark" to "深色海底").forEach { (id, label) ->
            LiquidChoice(theme == id, { theme = id; preferences.edit().putString("theme", id).apply() }, { Text(label) }, Modifier.fillMaxWidth())
        }
        Text("Android 13 及以上显示边缘折射；Android 12 使用模糊，较早版本使用稳定的玻璃配色。", style = MaterialTheme.typography.bodySmall)
        if (android.os.Build.VERSION.SDK_INT >= 31) StudioSwitch("跟随壁纸颜色", "仅用于背景和表面。按钮和强调色仍使用鲸语的蓝与青绿，保持品牌识别。", dynamicColor) { value ->
            dynamicColor = value; preferences.edit().putBoolean("dynamic_color", value).apply()
        }
        StudioSwitch("动态海洋背景", "默认关闭以减少持续重绘和耗电，静态背景仍保留玻璃质感。", motion) { value ->
            motion = value; preferences.edit().putBoolean("ocean_motion", value).apply()
        }
    }
}

@Composable private fun StudioAccount(vm: StudioModel) {
    var username by rememberSaveable { mutableStateOf("astrbot") }
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    StudioCard("连接本机引擎", "首次使用会尝试读取应用生成的本机凭据。修改过管理账号后，可在这里重新登录。") {
        StudioField(username, { username = it }, "管理用户名")
        StudioField(password, { password = it }, "管理密码", secret = true)
        StudioField(code, { code = it }, "两步验证码（未启用可留空）", secret = true)
        LiquidButton(onClick = { vm.run("登录本机引擎") { vm.api.login(username, password, code); password = ""; code = ""; vm.notice = "本机连接已建立" } }, Modifier.fillMaxWidth(), enabled = !vm.busy) { Text("登录") }
    }
    StudioCard("更换管理密码", "仅修改本机引擎的管理账号，不影响 QQ 账号密码。") {
        Text("更换密码前，请在上方再次填写当前管理密码。", style = MaterialTheme.typography.bodySmall)
        StudioField(newPassword, { newPassword = it }, "新管理密码", secret = true, hint = "请使用至少 12 位的大小写字母、数字与符号")
        LiquidOutlinedButton(onClick = { vm.run("更新管理密码") {
            vm.api.bot("/api/auth/account/edit", "POST", JSONObject().put("password", password).put("new_password", newPassword).put("confirm_password", newPassword).put("new_username", username))
            password = ""; newPassword = ""; vm.notice = "管理密码已更新"
        } }, Modifier.fillMaxWidth(), enabled = !vm.busy && newPassword.isNotBlank() && password.isNotBlank()) { Text("更新密码") }
    }
}

@Composable private fun StudioArtwork(vm: StudioModel) {
    val context = LocalContext.current
    var text by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { text = withContext(Dispatchers.IO) { runCatching { context.assets.open("bigfish/artwork.txt").bufferedReader().use { it.readText() } }.getOrDefault("形象来源请见开源声明") } }
    StudioCard("鲸鱼娘形象") { Text(text, style = MaterialTheme.typography.bodySmall) }
}

@Composable private fun StudioLicenses(vm: StudioModel) {
    val context = LocalContext.current
    var names by remember { mutableStateOf(emptyList<String>()) }
    var query by rememberSaveable { mutableStateOf("") }
    var content by remember { mutableStateOf<Pair<String, String>?>(null) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) vm.run("保存开源声明") { withContext(Dispatchers.IO) {
            context.contentResolver.openOutputStream(uri, "w")!!.use { output -> context.assets.open("third-party/notices.zip").use { it.copyTo(output) } }
        }; vm.notice = "开源声明已保存" }
    }
    LaunchedEffect(Unit) { runCatching { withContext(Dispatchers.IO) {
        val list = JSONObject(context.assets.open("third-party/manifest.json").bufferedReader().use { it.readText() }).getJSONArray("names")
        List(list.length()) { list.getString(it) }
    } }.onSuccess { names = it }.onFailure { vm.error = "此构建未包含完整许可材料，请使用完整发布包" } }
    StudioCard("开源贡献者", "mzbgq 维护本应用界面；底层组件与社区作品保留各自作者和许可。") {
        StudioField(query, { query = it }, "搜索项目与许可")
        LiquidOutlinedButton(onClick = { export.launch("WhaleTalk-open-source-notices.zip") }, Modifier.fillMaxWidth()) { Text("导出全部许可") }
    }
    val filtered = names.filter { query.isBlank() || it.contains(query, true) }
    filtered.take(80).forEach { name -> StudioCard {
        TextButton(onClick = { vm.run("读取开源声明") {
            val text = withContext(Dispatchers.IO) {
                ZipInputStream(context.assets.open("third-party/notices.zip")).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        if (entry.name == name) return@use zip.readBytes().toString(Charsets.UTF_8).take(120_000)
                        entry = zip.nextEntry
                    }; "未找到此材料，请导出完整声明查看"
                }
            }; content = name to text
        } }) { Text(name) }
    } }
    if (filtered.size > 80) Text("请搜索具体项目，或导出全部 ${names.size} 份材料。", style = MaterialTheme.typography.bodySmall)
    content?.let { (name, text) -> AlertDialog(onDismissRequest = { content = null }, title = { Text(name) }, text = {
        SelectionContainer { Text(text, Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall) }
    }, confirmButton = { TextButton(onClick = { content = null }) { Text("关闭") } }) }
}

@Composable private fun StudioTerminal(vm: StudioModel, state: ManagerState, command: (String, ServiceId?) -> Unit) {
    val terminal by TerminalStore.state.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    LaunchedEffect(terminal.selectedId) {
        var generation = -1L; var sequence = 0L; val bytes = ByteArrayOutputStream()
        TerminalStore.resize(72, 24)
        while (isActive) {
            val output = TerminalStore.output(generation, sequence)
            if (output.optBoolean("reset")) bytes.reset()
            generation = output.optLong("generation"); sequence = output.optLong("sequence")
            bytes.write(Base64.decode(output.optString("data"), Base64.DEFAULT))
            if (bytes.size() > 65536) { val tail = bytes.toByteArray().takeLast(32768).toByteArray(); bytes.reset(); bytes.write(tail) }
            text = bytes.toString("UTF-8").replace(Regex("\\u001B\\[[0-?]*[ -/]*[@-~]"), "").replace(Regex("\\u001B\\][^\\u0007]*\\u0007"), "").replace("\r", "")
            delay(500)
        }
    }
    StudioCard("本机终端", "命令在内置运行环境执行。只执行你理解的命令。") {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            terminal.sessions.forEachIndexed { index, session -> LiquidChoice(terminal.selectedId == session.id, { TerminalStore.select(session.id) }, { Text("终端 ${index + 1}") }) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LiquidOutlinedButton(onClick = { command("terminal-new", null) }, enabled = state.componentsReady && !state.busy) { Text("新建终端") }
            LiquidOutlinedButton(onClick = { command("terminal-stop", null) }, enabled = terminal.running) { Text("停止") }
        }
    }
    StudioCard { SelectionContainer { Text(text.ifBlank { "创建终端后，输出会显示在这里。" }, Modifier.heightIn(min = 180.dp, max = 400.dp).verticalScroll(rememberScrollState()), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) } }
    StudioCard {
        StudioField(input, { input = it }, "输入命令", enabled = terminal.running)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LiquidButton(onClick = { TerminalStore.input(input + "\r"); input = "" }, enabled = terminal.running) { Text("执行") }
            LiquidOutlinedButton(onClick = { TerminalStore.input("\u0003") }, enabled = terminal.running) { Text("Ctrl+C") }
            LiquidOutlinedButton(onClick = { TerminalStore.clearScreen() }) { Text("清屏") }
        }
    }
}

@Composable private fun StudioExtensions(vm: StudioModel, state: ManagerState, command: (String, ServiceId?) -> Unit) {
    val ssh by SshStore.state.collectAsStateWithLifecycle()
    val desktop by DesktopStore.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var port by rememberSaveable { mutableStateOf("8022") }
    var password by remember { mutableStateOf("") }
    StudioCard("SSH 连接", "默认仅允许本机连接。保存密码后，按需启动。") {
        StudioField(port, { port = it }, "本机端口", numeric = true)
        StudioField(password, { password = it }, "SSH 密码", secret = true)
        LiquidOutlinedButton(onClick = { vm.run("保存 SSH 配置") {
            val value = port.toIntOrNull(); require(value != null && value in 1024..65535) { "端口需在 1024 至 65535 之间" }
            RuntimeService.command(context, "ssh-save", sshPort = value, sshLan = false, sshPassword = password.ifBlank { null }); password = ""
        } }, enabled = !state.busy && !vm.busy) { Text("保存本机连接设置") }
        LiquidButton(onClick = { command(if (ssh.running) "ssh-stop" else "ssh-start", null) }, enabled = !state.busy && state.componentsReady) { Text(if (ssh.running) "停止 SSH" else "启动 SSH") }
        ssh.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
    StudioCard("图形桌面", "QQ 服务运行时，登录桌面由 QQ 服务统一管理。") {
        val qqOwnsDesktop = state.services.getValue(ServiceId.SNOWLUMA).desiredRunning
        if (qqOwnsDesktop) StudioTile("打开 QQ 登录画面", "在手机界面操作本机 QQ", { vm.go(StudioPage.QQ) })
        StudioStatus("桌面状态", if (desktop.running) "运行中" else "已停止", desktop.running)
        LiquidOutlinedButton(onClick = { command(if (desktop.running) "desktop-stop" else "desktop-start", null) }, enabled = !state.busy && state.componentsReady && !qqOwnsDesktop) { Text(if (desktop.running) "停止图形桌面" else "启动图形桌面") }
        if (desktop.webUiReady) LiquidOutlinedButton(onClick = {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(DesktopRepository.WEB_UI_URL))) }.onFailure { vm.error = "未找到浏览器" }
        }) { Text("在浏览器打开桌面") }
        desktop.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable private fun StudioAppUpdateCard() {
    val context = LocalContext.current
    val update by AppUpdateStore.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    StudioCard("检查更新", "对照本仓库的 GitHub Releases。不会自动下载或安装。") {
        Text("当前版本 ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall)
        update?.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        LiquidButton(onClick = {
            if (checking) return@LiquidButton
            checking = true
            scope.launch {
                try { withContext(Dispatchers.IO) { AppUpdateChecker(context).check() } }
                finally { checking = false }
            }
        }, Modifier.fillMaxWidth(), enabled = !checking) { Text(if (checking) "正在检查…" else "检查更新") }
        val candidate = update?.candidate
        if (candidate != null) {
            Text("发现 ${candidate.versionName}" + (candidate.apkBytes?.let { "，安装包约 ${it / 1024 / 1024} MiB" } ?: ""), style = MaterialTheme.typography.bodySmall)
            LiquidOutlinedButton(onClick = { uriHandler.openUri(candidate.pageUrl) }, Modifier.fillMaxWidth()) { Text("打开下载页") }
        }
    }
}

