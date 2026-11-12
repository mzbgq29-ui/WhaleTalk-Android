// SPDX-License-Identifier: MIT
package io.astrbot.android

/** Fixed routes from the bundled AstrBot 4.29.3 dashboard, never arbitrary URLs. */
internal enum class BigFishWebPage(val title: String, val path: String) {
    CHAT("本机对话", "/#/chat"), MODELS("模型管理", "/#/providers"),
    PLUGINS("插件市场", "/#/extension/plugins/market"), KNOWLEDGE("知识库", "/#/knowledge-base"),
    CRON("定时任务", "/#/cron"), SESSIONS("会话管理", "/#/session-management"),
    HISTORY("聊天记录", "/#/data/conversations"), STATS("调用统计", "/#/data/statistics"),
    CONFIG("机器人配置", "/#/config"), LOGS("机器人日志", "/#/data/logs")
}
