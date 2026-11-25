// SPDX-License-Identifier: MIT
package io.astrbot.android

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.astrbot.android.core.*

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun BigFishOptionFields(options: BigFishOptions, wakeWord: String, enabled: Boolean,
    onOptions: (BigFishOptions) -> Unit, onWakeWord: (String) -> Unit) {
    Text("资源与回复", fontWeight = FontWeight.Bold)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BigFishResourceMode.entries.forEach { mode ->
            LiquidChoice(selected = options.mode == mode, onClick = { onOptions(options.copy(mode = mode)) }, enabled = enabled,
                label = { Text(mode.title) })
        }
    }
    Text("${options.mode.turns} 轮上下文 · 最长 ${options.mode.replyTokens} 输出 tokens · 工具最多 ${options.mode.toolSteps} 步 · 每分钟最多 ${options.mode.repliesPerMinute} 次回复",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text("均衡模式兼顾对话长度与调用预算，省资源模式限制上下文和任务轮数，不会启动本地大模型。对话历史仍在 AstrBot 中保存，裁剪上下文不等于删除历史。", style = MaterialTheme.typography.bodySmall)
    OutlinedTextField(wakeWord, onValueChange = { onWakeWord(it.take(16)) }, label = { Text("文字唤醒词") }, singleLine = true,
        modifier = Modifier.fillMaxWidth(), enabled = enabled)
    BigFishSwitch("私聊也需要唤醒前缀", "减少非指令消息触发；关闭文字唤醒时使用 / 前缀", options.friendNeedsWake, enabled) { onOptions(options.copy(friendNeedsWake = it)) }
    BigFishSwitch("关闭文字唤醒", "群聊使用 @机器人或 / 命令；不对 @全体成员作响应", options.groupMentionOnly, enabled) { onOptions(options.copy(groupMentionOnly = it)) }
    BigFishSwitch("引用原消息回复", "在 QQ 回复中附上原消息引用", options.replyWithQuote, enabled) { onOptions(options.copy(replyWithQuote = it)) }
    BigFishSwitch("回复时 @对方", "由 QQ 消息平台附加提及", options.replyWithMention, enabled) { onOptions(options.copy(replyWithMention = it)) }
    BigFishSwitch("云端识图", "需填写支持图片的模型；仅验证文本连通性，图片会发送给该服务商。默认关闭", options.vision, enabled) { onOptions(options.copy(vision = it)) }
    Text("语音识别、语音合成保持关闭。这里的设置点击「验证模型并继续」后才会应用。", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable internal fun BigFishSwitch(title: String, description: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChange, enabled = enabled)
    }
}
