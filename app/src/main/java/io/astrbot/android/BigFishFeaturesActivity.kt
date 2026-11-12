// SPDX-License-Identifier: MIT
package io.astrbot.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.astrbot.android.core.ServiceId
import io.astrbot.android.runtime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class BigFishFeature(val title: String, val description: String, val category: String, val needsRuntime: Boolean = false, val action: () -> Unit)

/** Lightweight native directory for existing backend features; no additional resident service. */
class BigFishFeaturesActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        setContent { BigFishTheme { FeatureScreen() } }
    }
    private fun manager(route: String? = null, page: String? = null) = startActivity(Intent(this, MainActivity::class.java)
        .putExtra("openSettingsRoute", route).putExtra("openManagerPage", page))
    private fun web(page: BigFishWebPage) = startActivity(Intent(this, WebUiActivity::class.java)
        .putExtra("serviceId", ServiceId.ASTRBOT.name).putExtra("bigfishPage", page.name))
    private fun copy(value: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("大肥鱼", value))
        Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show()
    }
    @OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
    @Composable private fun FeatureScreen() {
        val state by RuntimeStore.state.collectAsStateWithLifecycle()
        val scope = rememberCoroutineScope()
        val keyboard = LocalSoftwareKeyboardController.current
        val compact = LocalBigFishCompact.current
        val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
        val featureScroll = rememberScrollState()
        val preferences = remember { getSharedPreferences("bigfish-ui", MODE_PRIVATE) }
        var category by rememberSaveable { mutableStateOf("全部") }
        var query by rememberSaveable { mutableStateOf("") }
        LaunchedEffect(query, category) { featureScroll.scrollTo(0) }
        var dialog by remember { mutableStateOf<Pair<String, String>?>(null) }
        var inspecting by remember { mutableStateOf(false) }
        var theme by remember { mutableStateOf(preferences.getString("theme", "system")) }
        fun inspect() {
            if (inspecting) return
            inspecting = true
            scope.launch {
                val report = withContext(Dispatchers.IO) { runCatching { BigFishDeviceReport.read(this@BigFishFeaturesActivity) } }
                dialog = "设备自检" to report.getOrElse { "读取设备状态失败，请在高级管理中查看诊断。" }
                inspecting = false
            }
        }
        val items = listOf(
            BigFishFeature("模型与回复设置", "5 个服务商预设、自定义地址、资源模式、唤醒词、引用与 @回复", "对话", true) {
                startActivity(Intent(this, BigFishWizardActivity::class.java).putExtra("editModel", true))
            },
            BigFishFeature("本机聊天", "在 AstrBot 的聊天页测试模型，不依赖 QQ 消息投递", "对话", true) { web(BigFishWebPage.CHAT) },
            BigFishFeature("人设库", "浏览、预览、自定义、切换人设，保留现有本地库和恢复入口", "对话", true) { manager(page = "PERSONAS") },
            BigFishFeature("聊天记录", "查看 AstrBot 保存的真实对话记录", "对话", true) { web(BigFishWebPage.HISTORY) },
            BigFishFeature("会话管理", "管理独立会话与上下文；删除或重置由后台确认", "对话", true) { web(BigFishWebPage.SESSIONS) },
            BigFishFeature("知识库", "使用 AstrBot 原生知识库；需配置云端嵌入服务，导入文件后才有可检索内容", "扩展", true) { web(BigFishWebPage.KNOWLEDGE) },
            BigFishFeature("定时任务", "在 AstrBot 创建提醒和计划；不会替你自动创建或发送消息", "扩展", true) { web(BigFishWebPage.CRON) },
            BigFishFeature("插件市场", "按需安装兼容插件。优先文本工具，避免本地语音、模型及浏览器任务", "扩展", true) { web(BigFishWebPage.PLUGINS) },
            BigFishFeature("更多云端模型", "在 AstrBot 添加独立模型，包括视觉模型；根据服务商能力配置", "扩展", true) { web(BigFishWebPage.MODELS) },
            BigFishFeature("调用统计", "查看后端记录的调用数据；不是服务商的账单余额", "维护", true) { web(BigFishWebPage.STATS) },
            BigFishFeature("QQ 登录与连接", "内置 SnowLuma 登录页、账号在线状态和消息连接", "维护", true) {
                startActivity(Intent(this, WebUiActivity::class.java).putExtra("serviceId", ServiceId.SNOWLUMA.name))
            },
            BigFishFeature("备份与恢复", "原生快照、导出、导入和恢复；导出的备份可能包含凭据", "维护") { manager("BACKUPS") },
            BigFishFeature("后台运行", "电池豁免、后台权限和按需恢复设置", "维护") { manager("BACKGROUND") },
            BigFishFeature("日志与诊断", "查看服务日志，使用现有诊断导出与恢复入口", "维护") { manager("DIAGNOSTICS") },
            BigFishFeature("存储管理", "查看环境、备份和数据占用，按明确范围清理", "维护") { startActivity(Intent(this, StorageUsageActivity::class.java)) },
            BigFishFeature("设备自检", "架构、WebView、可用空间、内存、网络、通知和电池优化，只读检查", "维护") { inspect() },
            BigFishFeature("高级管理", "保留底座完整管理功能；终端等高级扩展不会自动启动", "维护") { manager() },
            BigFishFeature("QQ 使用指南", "常用命令、群聊触发与省资源建议，可复制命令后自行发送", "帮助") {
                dialog = "QQ 使用指南" to "私聊机器人小号即可聊天；先把好友或群号加入允许列表。\n\n/help　查看当前已启用命令\n/new　开启新会话\n/reset　重置当前会话上下文\n/sid　查看会话标识\n\n在群内 @机器人、使用配置的唤醒词，或使用 / 命令。关闭文字唤醒后，使用 @或 /；私聊启用前缀时也使用 /。\n\n这些命令由所带 AstrBot 提供，App 不会自动替你发送。\n\n默认均衡模式，可按需要切换省资源或长对话模式。插件、知识库和定时任务按需开启；语音保持关闭。"
            },
            BigFishFeature("形象与来源", "网上社区鲸鱼娘头像的来源、作者与许可说明", "帮助") {
                scope.launch {
                    val content = withContext(Dispatchers.IO) { assets.open("bigfish/artwork.txt").bufferedReader().use { it.readText() } }
                    dialog = "鲸鱼娘形象与来源" to content
                }
            },
        )
        val filtered = items.filter { (category == "全部" || it.category == category) &&
            (query.isBlank() || "${it.title}${it.description}".contains(query.trim(), ignoreCase = true)) }
        Scaffold(containerColor = androidx.compose.ui.graphics.Color.Transparent, topBar = {
            LiquidPanel(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 14.dp, vertical = 8.dp)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    LiquidOutlinedButton(onClick = { finish() }) { Text("返回") }
                    Text("功能中心", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
            }
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!compact && !imeVisible && query.isBlank()) LiquidPanel(Modifier.fillMaxWidth(), accent = true) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Image(painterResource(R.drawable.whale_girl), "社区二创鲸鱼娘", Modifier.size(64.dp).clip(RoundedCornerShape(18.dp)))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("鲸鱼娘 · 海屿控制台", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("${items.size} 个入口 · 语音关闭 · 扩展按需启用", style = MaterialTheme.typography.bodySmall)
                    }
                }
                }
                OutlinedTextField(query, { query = it.take(80) }, Modifier.fillMaxWidth().glassMaterial(), label = { Text("搜索功能") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
                    trailingIcon = { if (query.isNotEmpty()) TextButton(onClick = { query = "" }) { Text("清空") } })
                if (!imeVisible) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("全部", "对话", "扩展", "维护", "帮助").forEach { value ->
                        LiquidChoice(selected = category == value, onClick = { category = value }, label = { Text(value) })
                    }
                }
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(featureScroll).padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (category in listOf("全部", "维护") && query.isBlank()) {
                        LiquidPanel(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text("首页与功能中心外观", fontWeight = FontWeight.Bold)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色").forEach { (key, label) ->
                                        LiquidChoice(selected = theme == key, onClick = { theme = key; preferences.edit().putString("theme", key).apply() }, label = { Text(label) })
                                    }
                                }
                            }
                        }
                    }
                    if (filtered.isEmpty()) Text("没有匹配的功能，试试其他关键词。")
                    filtered.forEach { item ->
                        LiquidPanel(modifier = Modifier.fillMaxWidth().clickable {
                                if (item.needsRuntime && !state.componentsReady) dialog = "先准备环境" to "返回首页点击「一键准备环境」，完成后再使用这个功能。"
                                else item.action()
                            }) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text(item.title, modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
                                    Text(if (item.title == "设备自检" && inspecting) "检查中" else if (item.needsRuntime && !state.componentsReady) "待准备" else "打开 →",
                                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                }
                                Text(item.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    Text("云端功能可能产生服务商费用；实际能力取决于模型和插件配置。功能入口不会自动启用扩展。\nv${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        dialog?.let { (title, message) ->
            AlertDialog(onDismissRequest = { dialog = null }, title = { Text(title) }, text = {
                SelectionContainer { Text(message, Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) }
            }, confirmButton = { TextButton(onClick = { dialog = null }) { Text("知道了") } }, dismissButton = { TextButton(onClick = { copy(message) }) { Text("复制内容") } })
        }
    }
}
