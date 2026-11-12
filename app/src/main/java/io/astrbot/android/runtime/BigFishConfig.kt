// SPDX-License-Identifier: MIT
package io.astrbot.android.runtime

import io.astrbot.android.core.BigFishInput
import io.astrbot.android.core.BigFishOptions
import io.astrbot.android.core.BigFishResourceMode
import io.astrbot.android.core.BigFishModelPresets
import org.json.JSONArray
import org.json.JSONObject

/** AstrBot 4.29.3 public configuration contract; preserves unrelated providers and settings. */
internal object BigFishConfig {
    const val SOURCE = "bigfish-source"
    const val MODEL = "bigfish-chat"
    fun astrbot(original: JSONObject, input: BigFishInput, token: String): JSONObject {
        require(token.matches(Regex("[a-f0-9]{64}")))
        val document = JSONObject(original.toString())
        document.put("config_version", 5)
        upsert(document, "provider_sources", SOURCE, JSONObject().put("id", SOURCE).put("provider", "openai")
            .put("type", "openai_chat_completion").put("provider_type", "chat_completion").put("enable", true)
            .put("key", JSONArray().put(input.key)).put("api_base", input.endpoint).put("timeout", 90)
            .put("proxy", "").put("custom_headers", JSONObject()))
        val extra = JSONObject()
        val options = input.options
        extra.put(if (BigFishModelPresets.completionTokenLimit(input.model)) "max_completion_tokens" else "max_tokens", options.mode.replyTokens)
        if (java.net.URI(input.endpoint).host in setOf("dashscope.aliyuncs.com", "dashscope-intl.aliyuncs.com", "dashscope-us.aliyuncs.com")) extra.put("enable_thinking", false)
        upsert(document, "provider", MODEL, JSONObject().put("id", MODEL).put("provider_source_id", SOURCE)
            .put("model", input.model).put("type", "openai_chat_completion").put("provider_type", "chat_completion")
            .put("enable", true).put("modalities", JSONArray().put("text").also { if (options.vision) it.put("image") }).put("custom_extra_body", extra))
        upsert(document, "platform", QqPlatform.id(original), JSONObject().put("id", QqPlatform.id(original)).put("type", "aiocqhttp").put("enable", true)
            .put("ws_reverse_host", "127.0.0.1").put("ws_reverse_port", 6199).put("ws_reverse_token", token))
        document.put("admins_id", JSONArray().put(input.owner))
        document.put("wake_prefix", JSONArray().also { if (!options.groupMentionOnly) it.put(options.wakeWord); it.put("/") })
        val settings = document.optJSONObject("platform_settings") ?: JSONObject().also { document.put("platform_settings", it) }
        val allowed = JSONArray()
        input.friends.forEach { allowed.put("${QqPlatform.id(original)}:FriendMessage:$it") }
        input.groups.forEach(allowed::put)
        settings.put("enable_id_white_list", true).put("id_whitelist", allowed)
            .put("wl_ignore_admin_on_friend", true).put("wl_ignore_admin_on_group", false)
            .put("ignore_bot_self_message", true).put("friend_message_needs_wake_prefix", options.friendNeedsWake)
            .put("ignore_at_all", true).put("reply_with_quote", options.replyWithQuote).put("reply_with_mention", options.replyWithMention)
            .put("rate_limit", JSONObject().put("time", 60).put("count", options.mode.repliesPerMinute).put("strategy", "discard"))
        val providers = document.optJSONObject("provider_settings") ?: JSONObject().also { document.put("provider_settings", it) }
        providers.put("enable", true)
        providers.put("max_quoted_fallback_images", if (options.vision) 2 else 0)
        providers.put("image_compress_options", JSONObject().put("max_size", 1024))
        // This mobile edition intentionally has no audio pipeline.
        listOf("provider_stt_settings", "provider_tts_settings").forEach { field ->
            val audio = document.optJSONObject(field) ?: JSONObject().also { document.put(field, it) }
            audio.put("enable", false)
        }
        if (!providers.has("computer_use_runtime")) providers.put("computer_use_runtime", "none")
        val runner = document.optJSONObject("agent_runner") ?: JSONObject().also { document.put("agent_runner", it) }
        runner.put("runner_type", "local")
        val config = runner.optJSONObject("config") ?: JSONObject().also { runner.put("config", it) }
        val model = config.optJSONObject("model") ?: JSONObject().also { config.put("model", it) }
        model.put("provider_id", MODEL).put("request_max_retries", 1).put("fallback_provider_ids", JSONArray())
        val compression = config.optJSONObject("compression") ?: JSONObject().also { config.put("compression", it) }
        compression.put("max_turns", options.mode.turns).put("overflow_strategy", "truncate_by_turns").put("trim_turns", 2)
        val misc = config.optJSONObject("misc") ?: JSONObject().also { config.put("misc", it) }
        misc.put("max_steps", options.mode.toolSteps)
        return document
    }
    fun optionsJson(options: BigFishOptions) = JSONObject().put("mode", options.mode.name).put("wakeWord", options.wakeWord)
        .put("friendNeedsWake", options.friendNeedsWake).put("groupMentionOnly", options.groupMentionOnly)
        .put("replyWithQuote", options.replyWithQuote).put("replyWithMention", options.replyWithMention).put("vision", options.vision)
    fun readOptions(value: JSONObject?): BigFishOptions {
        if (value == null) return BigFishOptions()
        return BigFishOptions(BigFishResourceMode.valueOf(value.optString("mode", "BALANCED")), value.optString("wakeWord", "鲸语"),
            value.optBoolean("friendNeedsWake"), value.optBoolean("groupMentionOnly"), value.optBoolean("replyWithQuote"),
            value.optBoolean("replyWithMention"), value.optBoolean("vision"))
    }
    private fun upsert(document: JSONObject, field: String, id: String, replacement: JSONObject) {
        val current = document.optJSONArray(field) ?: JSONArray()
        val result = JSONArray()
        for (i in 0 until current.length()) if (current.getJSONObject(i).optString("id") != id) result.put(current.get(i))
        result.put(replacement); document.put(field, result)
    }
}
