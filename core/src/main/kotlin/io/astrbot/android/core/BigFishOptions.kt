// SPDX-License-Identifier: MIT
package io.astrbot.android.core

enum class BigFishResourceMode(val title: String, val turns: Int, val replyTokens: Int, val toolSteps: Int, val repliesPerMinute: Int) {
    LIGHT("省资源", 8, 768, 4, 6),
    BALANCED("均衡", 16, 1536, 8, 12),
    LONG("长对话", 24, 3072, 12, 20)
}

data class BigFishOptions(
    val mode: BigFishResourceMode = BigFishResourceMode.BALANCED,
    val wakeWord: String = "大肥鱼",
    val friendNeedsWake: Boolean = false,
    val groupMentionOnly: Boolean = false,
    val replyWithQuote: Boolean = false,
    val replyWithMention: Boolean = false,
    val vision: Boolean = false,
) {
    init {
        require(wakeWord.matches(Regex("[\\p{L}\\p{N}_-]{1,16}"))) { "唤醒词需为 1–16 个文字、数字、下划线或短横线" }
    }
}

data class BigFishModelPreset(val title: String, val endpoint: String, val model: String)
object BigFishModelPresets {
    val all = listOf(
        BigFishModelPreset("DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat"),
        BigFishModelPreset("百炼", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus"),
        BigFishModelPreset("硅基流动", "https://api.siliconflow.cn/v1", "deepseek-ai/DeepSeek-V3.2"),
        BigFishModelPreset("智谱", "https://open.bigmodel.cn/api/paas/v4", "glm-4-flash"),
        BigFishModelPreset("OpenAI", "https://api.openai.com/v1", "gpt-4.1-mini"),
    )
    fun completionTokenLimit(model: String) = model.substringAfterLast('/').matches(
        Regex("(?i)(o[1-9](?:[.-].*)?|gpt-(?:[5-9]|[1-9][0-9])(?:[.-].*)?)"))
}
