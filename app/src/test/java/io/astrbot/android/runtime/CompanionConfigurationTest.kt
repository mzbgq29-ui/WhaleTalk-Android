package io.astrbot.android.runtime

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CompanionConfigurationTest {
    @Test fun defaultsNeverRequireAdditionalApiOrSummarizeInBackground() {
        val result = CompanionConfiguration.memory(JSONObject().put("unrelated", "preserved"), CompanionSettings())
        assertFalse(result.getJSONObject("retrieval").getBoolean("embedding_enabled"))
        assertEquals("basic", result.getJSONObject("retrieval").getString("mode"))
        assertFalse(result.getJSONObject("source_semantic").getBoolean("enabled"))
        assertFalse(result.getJSONObject("memory_summary").getBoolean("enabled"))
        assertFalse(result.getJSONObject("memory_summary").getBoolean("auto_drain_enabled"))
        assertEquals(BigFishConfig.MODEL, result.getJSONObject("memory_summary").getString("provider_id"))
        assertEquals("preserved", result.getString("unrelated"))
        assertFalse(result.toString().contains("https://")); assertFalse(result.toString().contains("api_key"))
    }
    @Test fun summaryBudgetAndDisableAreAppliedWithoutDeletingMemory() {
        val enabled = CompanionConfiguration.memory(JSONObject(), CompanionSettings(summaries = true, summaryEvents = 40))
        assertEquals(40, enabled.getJSONObject("memory_summary").getInt("trigger_event_count"))
        assertEquals(4, enabled.getJSONObject("memory_summary").getInt("max_calls_per_session_hour"))
        val disabled = CompanionConfiguration.memory(enabled, CompanionSettings(memory = false, summaries = true))
        assertFalse(disabled.getJSONObject("memory_capture").getBoolean("enabled"))
        assertFalse(disabled.getJSONObject("memory_injection").getBoolean("enabled"))
        assertFalse(disabled.getJSONObject("memory_summary").getBoolean("enabled"))
    }
    @Test fun emptyGroupListAndMentionOnlyNeverEnableAllGroups() {
        val bot = JSONObject().put("wake_prefix", JSONArray().put("大肥鱼").put("/"))
            .put("platform_settings", JSONObject().put("id_whitelist", JSONArray().put("napcat:FriendMessage:100001")))
        val options = CompanionSettings(groupChat = true)
        assertFalse(CompanionConfiguration.groupChat(JSONObject(), bot, options).getBoolean("enable_group_chat"))
        bot.getJSONObject("platform_settings").getJSONArray("id_whitelist").put("200002")
        val group = CompanionConfiguration.groupChat(JSONObject(), bot, options)
        assertTrue(group.getBoolean("enable_group_chat"))
        assertEquals(listOf("200002"), List(group.getJSONArray("enabled_groups").length()) { group.getJSONArray("enabled_groups").getString(it) })
        assertFalse(group.getBoolean("enable_private_chat")); assertFalse(group.getBoolean("enable_proactive_chat")); assertFalse(group.getBoolean("enable_image_processing"))
        bot.put("wake_prefix", JSONArray().put("/"))
        assertFalse(CompanionConfiguration.groupChat(JSONObject(), bot, options).getBoolean("enable_group_chat"))
    }
    @Test fun nativeHumanizationKeepsUnrelatedSettingsAndPreventsDuplicateActiveReply() {
        val original = JSONObject().put("custom", "kept").put("provider_ltm_settings", JSONObject().put("active_reply", JSONObject().put("enable", true)))
        val next = CompanionConfiguration.bot(original, CompanionSettings(groupChat = true))
        assertEquals("kept", next.getString("custom"))
        assertTrue(next.getJSONObject("platform_settings").getJSONObject("segmented_reply").getBoolean("only_llm_result"))
        assertFalse(next.getJSONObject("provider_ltm_settings").getJSONObject("active_reply").getBoolean("enable"))
        assertTrue(original.getJSONObject("provider_ltm_settings").getJSONObject("active_reply").getBoolean("enable"))
    }
}
