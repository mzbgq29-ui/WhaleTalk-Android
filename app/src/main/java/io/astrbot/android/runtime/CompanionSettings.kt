package io.astrbot.android.runtime

import org.json.JSONArray
import org.json.JSONObject

internal data class CompanionSettings(val memory: Boolean = true, val summaries: Boolean = false,
    val naturalReplies: Boolean = true, val groupChat: Boolean = false, val summaryEvents: Int = 20) {
    init { require(summaryEvents in 8..100) { "记忆总结间隔应在 8–100 条消息之间" } }
    fun json() = JSONObject().put("memory", memory).put("summaries", summaries).put("naturalReplies", naturalReplies)
        .put("groupChat", groupChat).put("summaryEvents", summaryEvents)
    companion object {
        fun read(v: JSONObject?) = if (v == null) CompanionSettings() else CompanionSettings(v.optBoolean("memory", true),
            v.optBoolean("summaries"), v.optBoolean("naturalReplies", true), v.optBoolean("groupChat"), v.optInt("summaryEvents", 20))
    }
}

/** Controlled defaults only. Does not create providers, credentials or extra cloud service URLs. */
internal object CompanionConfiguration {
    const val MEMORY = "astrbot_plugin_memory_companion"
    const val GROUP = "astrbot_plugin_group_chat_plus"
    private fun section(v: JSONObject, key: String) = v.optJSONObject(key) ?: JSONObject().also { v.put(key, it) }
    fun memory(original: JSONObject, options: CompanionSettings): JSONObject = JSONObject(original.toString()).apply {
        section(this, "memory_capture").put("enabled", options.memory).put("extract_stable_facts", true)
        section(this, "scope_control").apply {
            for (key in listOf("private_capture_enabled", "group_capture_enabled", "private_recall_enabled", "group_recall_enabled")) put(key, options.memory)
        }
        section(this, "memory_injection").put("enabled", options.memory).put("max_chars", 2400).put("top_k", 6)
        section(this, "retrieval").put("embedding_enabled", false).put("embedding_provider_id", "").put("mode", "basic").put("rerank_provider_id", "")
        section(this, "source_semantic").put("enabled", false).put("provider_id", "")
        section(this, "memory_humanization").put("enabled", options.memory)
        section(this, "memory_summary").put("enabled", options.memory && options.summaries).put("provider_id", BigFishConfig.MODEL)
            .put("fallback_provider_id", "").put("private_provider_id", "").put("group_provider_id", "")
            .put("private_fallback_provider_id", "").put("group_fallback_provider_id", "")
            .put("min_events", options.summaryEvents).put("trigger_event_count", options.summaryEvents)
            .put("trigger_interval_minutes", 0).put("auto_drain_enabled", false).put("max_calls_per_session_hour", 4)
            .put("request_max_retries", 0).put("max_retries", 2).put("provider_timeout_seconds", 60)
        section(this, "memory_tools").apply {
            for (key in listOf("enable_recall_tool", "enable_remember_tool", "enable_person_weight_tool", "enable_note_tools", "enable_reconstruction_tool")) put(key, options.memory)
            put("allow_group_remember", false)
        }
        section(this, "private_companion_bridge").put("cross_window_emotional_continuity_enabled", false)
        section(this, "identity").put("merge_platform_accounts", false)
    }
    fun groups(config: JSONObject): List<String> {
        val values = config.optJSONObject("platform_settings")?.optJSONArray("id_whitelist") ?: JSONArray()
        return (0 until values.length()).map { values.optString(it) }.filter { it.matches(Regex("[1-9][0-9]{4,19}")) }.distinct()
    }
    fun groupChat(original: JSONObject, bot: JSONObject, options: CompanionSettings): JSONObject = JSONObject(original.toString()).apply {
        val groups = groups(bot)
        // Empty enabled_groups means ALL groups upstream. Never enable that interpretation accidentally.
        val wake = bot.optJSONArray("wake_prefix")
        val mentionsOnly = wake == null || (0 until wake.length()).none { wake.optString(it).isNotBlank() && wake.optString(it) != "/" }
        put("enable_group_chat", options.groupChat && groups.isNotEmpty() && !mentionsOnly)
        put("enabled_groups", JSONArray(groups)).put("enable_private_chat", false)
        put("decision_ai_provider_id", BigFishConfig.MODEL).put("decision_ai_include_persona", true)
        put("enable_web_panel", false).put("web_panel_host", "127.0.0.1")
        put("enable_image_processing", false).put("enable_proactive_chat", false).put("enable_proactive_ai_judge", false)
        put("enable_frequency_adjuster", false).put("enable_memory_injection", false)
        put("enable_humanize_mode", true).put("enable_typing_simulator", true).put("typing_speed", 22.0).put("typing_max_delay", 2.0)
        put("enable_typo_generator", false).put("enable_poke_after_reply", false).put("poke_reverse_on_poke_probability", 0.0)
        put("initial_probability", 0.02).put("after_reply_probability", 0.35).put("enable_debug_log", false)
    }
    fun bot(original: JSONObject, options: CompanionSettings): JSONObject = JSONObject(original.toString()).apply {
        val platform = section(this, "platform_settings")
        section(platform, "segmented_reply").put("enable", options.naturalReplies).put("only_llm_result", true)
            .put("interval_method", "random").put("interval", "0.4,1.2").put("words_count_threshold", 180)
        if (options.groupChat) {
            platform.put("empty_mention_waiting", false)
            val ltm = section(this, "provider_ltm_settings")
            ltm.put("group_icl_enable", true)
            section(ltm, "active_reply").put("enable", false)
        }
    }
}
