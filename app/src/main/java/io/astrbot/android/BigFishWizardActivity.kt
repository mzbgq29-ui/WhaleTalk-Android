// SPDX-License-Identifier: MIT
package io.astrbot.android

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.astrbot.android.core.*
import io.astrbot.android.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File

/** Retains unsaved input across rotation without putting API keys in saved-state bundles. */
class BigFishForm : ViewModel() {
    var owner by mutableStateOf("")
    var key by mutableStateOf("")
    var endpoint by mutableStateOf("https://api.deepseek.com/v1")
    var model by mutableStateOf("deepseek-chat")
    var friends by mutableStateOf("")
    var groups by mutableStateOf("")
    var saving by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var task by mutableStateOf("")
    var options by mutableStateOf(BigFishOptions())
    var wakeWord by mutableStateOf("鲸语")
    override fun onCleared() { key = "" }
}

/** Guided entry; service supervision, PRoot, login WebUI, personas and recovery are reused upstream. */
class BigFishWizardActivity : ComponentActivity() {
    private val form by viewModels<BigFishForm>()
    private val notify = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private fun command(operation: String, id: ServiceId? = null): String {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            notify.launch(Manifest.permission.POST_NOTIFICATIONS)
        return RuntimeService.command(this, operation, id)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (isTaskRoot) moveTaskToBack(true) else finish() }
        })
        setContent {
            BigFishTheme { dark ->
                Wizard(dark)
            }
        }
    }
    private fun advanced() = startActivity(Intent(this, MainActivity::class.java))
    private fun features() = startActivity(Intent(this, BigFishFeaturesActivity::class.java))
    private fun settings(route: String) = startActivity(Intent(this, MainActivity::class.java).putExtra("openSettingsRoute", route))
    private fun backend(id: ServiceId) = startActivity(Intent(this, WebUiActivity::class.java).putExtra("serviceId", id.name))

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable private fun Wizard(dark: Boolean) {
        val state by RuntimeStore.state.collectAsStateWithLifecycle()
        val scope = rememberCoroutineScope()
        val keyboard = LocalSoftwareKeyboardController.current
        val keyFocus = remember { FocusRequester() }
        val compact = LocalBigFishCompact.current
        val settings = remember { getSharedPreferences("bigfish-ui", MODE_PRIVATE) }
        val repository = remember { BigFishSetupRepository(this) }
        var record by remember { mutableStateOf<JSONObject?>(null) }
        var loaded by remember { mutableStateOf(false) }
        var completed by remember { mutableStateOf(settings.getBoolean("completed", false)) }
        var step by remember { mutableIntStateOf(settings.getInt("step", 0).coerceIn(0, 2)) }
        var requested by rememberSaveable { mutableStateOf<String?>(null) }
        var pendingLogin by rememberSaveable { mutableStateOf(false) }
        var hasPending by remember { mutableStateOf(false) }
        var verificationOnly by remember { mutableStateOf(false) }
        val working = state.busy || form.saving
        val qqService = state.services.getValue(ServiceId.SNOWLUMA)
        val astrbot = state.services.getValue(ServiceId.ASTRBOT)
        val online = qqService.qq == QqState.ONLINE && qqService.oneBot == OneBotState.CONNECTED && astrbot.ready && qqService.ready
        val compatible = Build.SUPPORTED_ABIS.contains("arm64-v8a")
        val availableGiB = filesDir.usableSpace.toDouble() / (1024 * 1024 * 1024)
        var advancedFields by remember { mutableStateOf(false) }
        var presetMenu by remember { mutableStateOf(false) }
        var showKey by remember { mutableStateOf(false) }
        var elapsed by remember { mutableLongStateOf(0) }
        var verificationJob by remember { mutableStateOf<Job?>(null) }
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        DisposableEffect(lifecycle) {
            val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) showKey = false }
            lifecycle.addObserver(observer)
            onDispose { lifecycle.removeObserver(observer) }
        }

        suspend fun refreshRecord() {
            val result = withContext(Dispatchers.IO) { repository.summary() to repository.pending }
            record = result.first; hasPending = result.second
        }
        fun move(value: Int) { step = value; settings.edit().putInt("step", value).apply(); form.error = null }
        fun runCommand(operation: String, id: ServiceId? = null) {
            if (working) return
            form.error = null
            runCatching { requested = command(operation, id) }.onFailure { form.error = "任务未能启动，请检查后台权限后重试" }
        }
        LaunchedEffect(Unit) {
            try {
                withContext(Dispatchers.IO) { RuntimeStore.operationLock.withLock { RuntimeStore.refreshInstallation(this@BigFishWizardActivity) } }
                refreshRecord()
                record?.let { saved ->
                    if (form.owner.isEmpty()) {
                        form.owner = saved.optString("owner"); form.endpoint = saved.optString("endpoint", form.endpoint)
                        form.model = saved.optString("model", form.model); form.friends = saved.optString("friends"); form.groups = saved.optString("groups")
                        form.options = BigFishConfig.readOptions(saved.optJSONObject("options")); form.wakeWord = form.options.wakeWord
                    }
                }
                verificationOnly = !BuildConfig.BUNDLED_RUNTIME
                if (record == null) { completed = false; settings.edit().putBoolean("completed", false).apply() }
                if (!completed) step = when { record != null -> 2; RuntimeStore.state.value.componentsReady -> 1; else -> 0 }
                if (intent.getBooleanExtra("editModel", false) && RuntimeStore.state.value.componentsReady) { completed = false; step = 1 }
                val desired = getSharedPreferences("manager", MODE_PRIVATE)
                if (RuntimeStore.owner == null && (repository.needsRecovery || ServiceId.entries.any { desired.getBoolean("desired_${it.name}", false) }))
                    command("reconcile")
            } catch (_: Exception) { form.error = "读取运行状态失败，请重试或打开高级管理检查" }
            loaded = true
        }
        LaunchedEffect(state.busy) {
            if (state.busy) {
                val start = SystemClock.elapsedRealtime()
                while (isActive) { elapsed = (SystemClock.elapsedRealtime() - start) / 1000; delay(1000) }
            } else elapsed = 0
        }
        LaunchedEffect(state.completedOperation, state.busy) {
            if (loaded && !state.busy) {
                refreshRecord()
                if (requested != null && state.completedOperation == requested) {
                    requested = null
                    if (state.error == null && !completed) {
                        when { record != null -> move(2); state.componentsReady && step == 0 -> move(1) }
                    }
                }
            }
        }
        LaunchedEffect(qqService.canOpenWebUi, pendingLogin) {
            if (pendingLogin && qqService.canOpenWebUi) { pendingLogin = false; backend(ServiceId.SNOWLUMA) }
        }

        Scaffold(containerColor = Color.Transparent,
            topBar = {
                LiquidPanel(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 14.dp, vertical = 8.dp)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Image(painterResource(R.drawable.whale_girl), "鲸语", Modifier.size(42.dp).clip(RoundedCornerShape(15.dp)))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("鲸语", fontSize = 21.sp, fontWeight = FontWeight.Bold)
                        if (!compact) Text(if (completed) "你的 QQ 伙伴" else "跟着引导，给鲸鱼安个家", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    LiquidOutlinedButton(onClick = ::features) { Text("功能中心") }
                }
                }
            }, bottomBar = {
                LiquidPanel(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), shape = RoundedCornerShape(30.dp)) {
                    Column(Modifier.navigationBarsPadding().imePadding().padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (working) {
                            LinearProgressIndicator(Modifier.fillMaxWidth().semantics { contentDescription = "任务正在进行" })
                            Text(if (form.saving) form.task else state.phase + if (elapsed > 0) " · ${elapsed}s" else "", style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = {
                                if (form.saving && !RuntimeStore.state.value.busy) verificationJob?.cancel()
                                else runCatching { requested = command("stop") }.onFailure { form.error = "停止请求未能启动，请在高级管理中停止服务" }
                            }, modifier = Modifier.align(Alignment.End)) { Text(if (form.saving && !state.busy) "取消验证" else "取消任务并停止服务") }
                        }
                        if (!completed) when (step) {
                            0 -> LiquidButton(onClick = { runCommand("initialize") }, enabled = loaded && !working && compatible && BuildConfig.BUNDLED_RUNTIME && availableGiB >= 3.0,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)) { Text(if (working) "正在准备环境…" else if (state.componentsReady) "继续配置模型" else "一键准备环境", fontSize = 16.sp) }
                            1 -> LiquidButton(onClick = {
                                if (form.saving || state.busy) return@LiquidButton
                                form.saving = true; form.error = null; form.task = "正在验证模型连接…"
                                verificationJob = scope.launch {
                                    try {
                                        val input = withContext(Dispatchers.IO) { BigFishInput(form.owner, form.endpoint, form.model,
                                            form.key.ifBlank { repository.savedKeyFor(form.endpoint) }, form.friends, form.groups, form.options.copy(wakeWord = form.wakeWord.trim())) }
                                        BigFishModelVerifier().verify(input)
                                        form.task = "验证通过，正在保存配置…"
                                        withContext(Dispatchers.IO) { RuntimeStore.operationLock.withLock { repository.request(input, System.currentTimeMillis()) } }
                                        form.key = ""; requested = command("bigfish-configure")
                                    } catch (failure: CancellationException) { throw failure }
                                    catch (failure: Exception) { form.error = failure.message?.takeIf { it.any { c -> c in '\u4e00'..'\u9fff' } } ?: "配置未完成，请检查输入后重试" }
                                    finally { form.saving = false; verificationJob = null }
                                }
                            }, enabled = loaded && !working && state.componentsReady, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)) {
                                Text(if (working) "正在应用配置…" else "验证模型并继续", fontSize = 16.sp)
                            }
                            2 -> LiquidButton(onClick = {
                                if (online) { completed = true; settings.edit().putBoolean("completed", true).apply() }
                                else if (!working) {
                                    if (qqService.canOpenWebUi) backend(ServiceId.SNOWLUMA)
                                    else { pendingLogin = true; runCommand("start", ServiceId.SNOWLUMA) }
                                }
                            }, enabled = loaded && !working, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)) {
                                Text(if (online) "完成，进入首页" else if (working) "正在启动 QQ…" else "打开 QQ 登录", fontSize = 16.sp)
                            }
                        } else Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            val active = state.services.values.any { it.desiredRunning }
                            LiquidButton(onClick = { runCommand(if (active) "stop" else "start") }, enabled = !working,
                                modifier = Modifier.weight(1f).heightIn(min = 54.dp)) { Text(if (active) "暂停机器人" else "启动机器人") }
                            LiquidOutlinedButton(onClick = { completed = false; move(1) }, enabled = !working, modifier = Modifier.weight(1f).heightIn(min = 54.dp)) { Text("修改配置") }
                        }
                    }
                }
            }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 22.dp)
                .padding(bottom = 22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                if (!completed) StepStrip(step)
                val error = form.error ?: state.error
                if (error != null) Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.errorContainer) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("这一步还没完成", fontWeight = FontWeight.Bold)
                        Text(error, style = MaterialTheme.typography.bodyMedium)
                        Row { TextButton(onClick = { form.error = null; runCommand("reconcile") }, enabled = !working) { Text("重新检查") }
                            TextButton(onClick = ::advanced) { Text("查看诊断") } }
                    }
                }
                if (completed) {
                    Hero(if (online) "鲸语，\n在线啦。" else "鲸鱼\n就在这里。", if (online) "现在可以用主人 QQ 给小号发消息了。" else "连接状态会在检查后更新。", dark)
                    Section("运行状态", "只有 QQ 在线且消息通道连接，才显示已连接") {
                        CheckRow("QQ 登录", when (qqService.qq) { QqState.ONLINE -> "已登录"; QqState.VERIFICATION_REQUIRED -> "需要安全验证"; QqState.OFFLINE -> "已掉线"; else -> "等待确认" }, qqService.qq == QqState.ONLINE)
                        CheckRow("消息通道", if (qqService.oneBot == OneBotState.CONNECTED) "已连接" else "尚未连接", qqService.oneBot == OneBotState.CONNECTED)
                        CheckRow("机器人服务", if (astrbot.ready) "运行中" else "未就绪", astrbot.ready)
                        CheckRow("上次验证模型", record?.optString("model").orEmpty().ifBlank { "尚未配置" }, record != null)
                    }
                    Section("接下来，和她聊聊", "内置鲸语人设，对话记录由 AstrBot 保存在这台手机") {
                        Text("用主人 QQ 私聊机器人小号，发一句「在吗」。群聊需先在配置里添加群号，再 @机器人或喊「鲸语」。")
                        LiquidOutlinedButton(onClick = { backend(ServiceId.SNOWLUMA) }, enabled = qqService.canOpenWebUi, modifier = Modifier.fillMaxWidth()) { Text("QQ 登录与连接") }
                        LiquidOutlinedButton(onClick = { backend(ServiceId.ASTRBOT) }, enabled = astrbot.canOpenWebUi, modifier = Modifier.fillMaxWidth()) { Text("对话、插件与更多模型") }
                        LiquidButton(onClick = ::features, modifier = Modifier.fillMaxWidth()) { Text("打开功能中心") }
                    }
                    Section("让她安心待在后台", "按你的系统开启后台运行和电池豁免") {
                        Text("系统强行停止、内存不足或 QQ 风控仍可能让服务断开。后台权限和恢复功能可在高级管理中设置。", style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { settings("BACKGROUND") }) { Text("打开后台运行设置  →") }
                        TextButton(onClick = { settings("BACKUPS") }) { Text("备份与恢复  →") }
                    }
                } else when (step) {
                    0 -> {
                        Hero("把鲸语\n养进 QQ。", "一个安装包。环境自动准备，剩下跟着引导。", dark)
                        Section("准备好这些，就能开始", "不需要电脑，也不用手动输入命令") {
                            CheckRow("手机架构", if (compatible) "ARM64 · 支持" else "当前架构暂不支持 Linux QQ", compatible)
                            CheckRow("可用空间", String.format(java.util.Locale.ROOT, "%.1f GB", availableGiB), availableGiB >= 3.0)
                            Text("建议预留至少 6 GB。会校验并解压 APK 内的环境，首次需要一些时间。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            CheckRow("QQ 小号", "稍后扫码或按提示确认", true)
                            CheckRow("模型 API Key", "稍后填写你的云端密钥", true)
                        }
                        if (verificationOnly) Notice("这是界面验证构建，不含手机运行环境。用户安装应使用完整 ARM64 APK。")
                        if (availableGiB < 3.0) Notice("剩余空间不足以安全解压环境，请先释放空间后重新打开。")
                        if (!compatible) Notice("Linux QQ 需要 ARM64 系统。这不是权限问题，32 位系统和 x86 设备不能使用这个完整包。")
                        Section("安装包里有什么？", "复用已发布、校验过的运行环境") {
                            Text("PRoot · Ubuntu · Python · AstrBot · SnowLuma · Linux QQ", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text("组件全部在本机运行，模型推理使用你配置的云端 API。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    1 -> {
                        Heading("认识一下你的鲸鱼", "填主人 QQ 和模型密钥，其余连接会自动配好。")
                        Section("主人是谁？", "填写主人的 QQ 大号，稍后再登录机器人小号") {
                            OutlinedTextField(form.owner, { form.owner = it.take(20) }, Modifier.fillMaxWidth(), label = { Text("主人 QQ 号") }, singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                                keyboardActions = KeyboardActions(onNext = { keyFocus.requestFocus() }), enabled = !working)
                        }
                        Section("她用什么模型思考？", "默认 DeepSeek，也可以填写兼容 Chat Completions 的服务") {
                            Box {
                                LiquidOutlinedButton(onClick = { presetMenu = true }, enabled = !working, modifier = Modifier.fillMaxWidth()) {
                                    Text((BigFishModelPresets.all.firstOrNull { it.endpoint == form.endpoint }?.title ?: "自定义服务商") + "  ▾")
                                }
                                DropdownMenu(expanded = presetMenu, onDismissRequest = { presetMenu = false }) {
                                    BigFishModelPresets.all.forEach { preset -> DropdownMenuItem(text = { Text(preset.title) }, onClick = {
                                        if (form.endpoint != preset.endpoint) form.key = ""
                                        form.endpoint = preset.endpoint; form.model = preset.model; presetMenu = false
                                    }) }
                                    DropdownMenuItem(text = { Text("自定义兼容接口") }, onClick = { presetMenu = false; advancedFields = true })
                                }
                            }
                            Text("型号是可修改的示例，以服务商当前支持范围为准。切换地址后请填写对应密钥，避免向其他服务商发送旧密钥。", style = MaterialTheme.typography.bodySmall)
                            OutlinedTextField(form.key, { form.key = it.take(8192) }, Modifier.fillMaxWidth().focusRequester(keyFocus), label = { Text("API Key") }, singleLine = true, enabled = !working,
                                placeholder = { Text(if (record == null || record?.optString("endpoint") != form.endpoint) "粘贴当前服务商的密钥" else "已保存；留空保留原密钥") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
                                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                                trailingIcon = { TextButton(onClick = { showKey = !showKey }, enabled = !working) { Text(if (showKey) "隐藏" else "显示") } })
                            Text("验证会向你的模型发送一次简短请求，按服务商规则计费。密钥用于本机配置，不会写入界面状态备份。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton(onClick = { advancedFields = !advancedFields }, enabled = !working) { Text(if (advancedFields) "收起更多配置" else "模型地址、群聊和好友设置  ↓") }
                            if (advancedFields) {
                                OutlinedTextField(form.endpoint, { if (form.endpoint != it) form.key = ""; form.endpoint = it }, Modifier.fillMaxWidth(), label = { Text("API 基础地址（HTTPS）") }, singleLine = true, enabled = !working)
                                OutlinedTextField(form.model, { form.model = it.take(160) }, Modifier.fillMaxWidth(), label = { Text("模型名称") }, singleLine = true, enabled = !working)
                                OutlinedTextField(form.friends, { form.friends = it.take(4096) }, Modifier.fillMaxWidth(), label = { Text("允许私聊的 QQ 号（可选）") }, supportingText = { Text("逗号分隔；主人始终加入") }, enabled = !working)
                                OutlinedTextField(form.groups, { form.groups = it.take(4096) }, Modifier.fillMaxWidth(), label = { Text("允许回复的群号（可选）") }, supportingText = { Text("逗号分隔；群内 @或喊鲸语唤醒") }, enabled = !working)
                            }
                        }
                        Section("对话与触发方式", "可直接应用的个性化设置") {
                            BigFishOptionFields(form.options, form.wakeWord, !working, { form.options = it }, { form.wakeWord = it })
                        }
                        if (hasPending && !working) Section("上次配置已保存", "可以继续应用，不需要重新填写密钥") {
                            LiquidButton(onClick = { runCommand("bigfish-configure") }, modifier = Modifier.fillMaxWidth()) { Text("继续上次配置") }
                        }
                        Notice("应用配置会短暂重启机器人服务。语音保持关闭；知识库、长期记忆和插件可在功能中心按需配置。")
                    }
                    2 -> {
                        Heading("最后，让小号上线", "在内置登录页完成 QQ 验证，回来即可查看连接结果。")
                        Hero(if (online) "连接成功，\n她准备好啦。" else "扫码登录，\n让鲸鱼开口。", if (online) "用你的主人 QQ 给机器人发一句「在吗」。" else "登录状态由 QQ 确认，不会代填或保存 QQ 密码。", dark)
                        Section("连接检查", "每一项都根据实际运行状态更新") {
                            CheckRow("模型配置", if (record != null) "已验证并应用" else "尚未完成", record != null)
                            CheckRow("机器人服务", if (astrbot.ready) "已就绪" else "等待启动", astrbot.ready)
                            CheckRow("QQ 登录", when (qqService.qq) { QqState.ONLINE -> "已登录"; QqState.VERIFICATION_REQUIRED -> "需要安全验证"; QqState.OFFLINE -> "需要重新登录"; else -> "等待登录" }, qqService.qq == QqState.ONLINE)
                            CheckRow("消息通道", if (qqService.oneBot == OneBotState.CONNECTED) "已连接" else "等待连接", qqService.oneBot == OneBotState.CONNECTED)
                        }
                        Notice("同一手机操作时，可保存登录二维码后用手机 QQ 识别；也可用另一台设备扫码。遇到身份验证，按 QQ 提示完成。")
                        TextButton(onClick = { runCommand("start") }, enabled = !working) { Text("启动并重新检查连接") }
                        TextButton(onClick = { move(1) }, enabled = !working) { Text("返回模型配置") }
                    }
                }
                Text("基于 AstrBot for Android · AstrBot · SnowLuma", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            }
        }
    }

    @Composable private fun StepStrip(active: Int) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("准备环境", "配置模型", "登录 QQ").forEachIndexed { index, label ->
                val selected = index == active
                LiquidPanel(Modifier.weight(1f).semantics { contentDescription = "第 ${index + 1} 步，$label，${if (selected) "当前步骤" else if (index < active) "已完成" else "未开始"}" },
                    shape = RoundedCornerShape(20.dp), accent = selected) {
                    Row(Modifier.padding(horizontal = 9.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(if (index < active) "✓" else "${index + 1}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(label, modifier = Modifier.weight(1f), fontSize = 12.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
        }
    }
    @Composable private fun Hero(title: String, subtitle: String, dark: Boolean) {
        LiquidPanel(Modifier.fillMaxWidth(), accent = true) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("鲸鱼娘 · 海屿", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                        Text(title, fontSize = 28.sp, lineHeight = 37.sp, fontWeight = FontWeight.Bold)
                    }
                    Image(painterResource(R.drawable.whale_girl), null, Modifier.size(88.dp).clip(RoundedCornerShape(26.dp)))
                }
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, lineHeight = 22.sp, modifier = Modifier.fillMaxWidth())
            }
        }
    }
    @Composable private fun Heading(title: String, description: String) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontSize = 25.sp, fontWeight = FontWeight.Bold)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    @Composable private fun Section(title: String, description: String, content: @Composable ColumnScope.() -> Unit) {
        LiquidPanel(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                content()
            }
        }
    }
    @Composable private fun CheckRow(label: String, value: String, okay: Boolean) {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(Modifier.size(24.dp), shape = CircleShape, color = if (okay) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant) {
                Box(contentAlignment = Alignment.Center) { Text(if (okay) "✓" else "·", fontSize = 13.sp, fontWeight = FontWeight.Bold) }
            }
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.widthIn(max = 190.dp))
        }
    }
    @Composable private fun Notice(text: String) {
        Text(text, style = MaterialTheme.typography.bodySmall, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 3.dp))
    }
}
